-- ============================================================================
-- WORK CLUSTERING FUNCTIONS
-- ============================================================================

-- Extract ISBN work prefix (publisher + title identifier)
create or replace function extract_isbn_work_prefix(isbn13 text)
returns text as $$
begin
  if isbn13 is null or length(isbn13) < 13 then
    return null;
  end if;

  -- Remove any non-digits
  isbn13 := regexp_replace(isbn13, '[^0-9]', '', 'g');

  if length(isbn13) != 13 then
    return null;
  end if;

  -- Return first 11 digits (groups editions from same publisher)
  return substring(isbn13, 1, 11);
end;
$$ language plpgsql immutable;

-- Cluster books by ISBN prefix
drop function if exists cluster_books_by_isbn();
create or replace function cluster_books_by_isbn()
returns table (clusters_created integer, books_clustered integer) as $$
declare
  rec record;
  cluster_uuid uuid;
  clusters_count integer := 0;
  books_count integer := 0;
  book_uuid uuid;
  primary_title text;
begin
  for rec in
    select
      prefix,
      array_agg(book_id order by has_high_res desc, cover_area desc, published_date desc nulls last, lower(title)) as book_ids,
      count(*) as book_count
    from (
      select
        extract_isbn_work_prefix(b.isbn13) as prefix,
        b.id as book_id,
        b.title,
        b.published_date,
        coalesce(
          (
            select coalesce(bil.is_high_resolution, false)
            from book_image_links bil
            where bil.book_id = b.id
            order by coalesce(bil.is_high_resolution, false) desc,
                     coalesce((bil.width::bigint * bil.height::bigint), 0) desc,
                     bil.created_at desc
            limit 1
          ),
          false
        ) as has_high_res,
        coalesce(
          (
            select coalesce((bil.width::bigint * bil.height::bigint), 0)
            from book_image_links bil
            where bil.book_id = b.id
            order by coalesce(bil.is_high_resolution, false) desc,
                     coalesce((bil.width::bigint * bil.height::bigint), 0) desc,
                     bil.created_at desc
            limit 1
          ),
          0
        ) as cover_area
      from books b
      where b.isbn13 is not null
    ) candidate
    where prefix is not null
    group by prefix
    having count(*) > 1
  loop
    if rec.book_ids is null or array_length(rec.book_ids, 1) = 0 then
      continue;
    end if;

    -- Get the primary title from the first (best) book with fallback for NULL/empty
    select coalesce(nullif(title, ''), 'Untitled Book') into primary_title
    from books
    where id = rec.book_ids[1];

    -- Ensure primary_title is never NULL
    if primary_title is null or primary_title = '' then
      primary_title := 'Untitled Book';
    end if;

    select id into cluster_uuid
    from work_clusters
    where isbn_prefix = rec.prefix;

    if cluster_uuid is null then
      insert into work_clusters (isbn_prefix, canonical_title, confidence_score, cluster_method, member_count)
      values (rec.prefix, primary_title, 0.9, 'ISBN_PREFIX', rec.book_count)
      returning id into cluster_uuid;
      clusters_count := clusters_count + 1;
    else
      update work_clusters
      set canonical_title = primary_title,
          member_count = rec.book_count,
          updated_at = now()
      where id = cluster_uuid;
    end if;

    for i in 1..array_length(rec.book_ids, 1) loop
      book_uuid := rec.book_ids[i];

      insert into work_cluster_members (cluster_id, book_id, is_primary, confidence, join_reason)
      values (cluster_uuid, book_uuid, (i = 1), 0.9, 'ISBN_PREFIX')
      on conflict (cluster_id, book_id) do update set
        is_primary = excluded.is_primary,
        confidence = excluded.confidence,
        join_reason = excluded.join_reason,
        joined_at = now();

      books_count := books_count + 1;
    end loop;
  end loop;

  return query select clusters_count, books_count;
end;
$$ language plpgsql;

create or replace function cluster_single_book_by_isbn(target_book_id uuid)
returns void as $$
declare
  prefix text;
  cluster_uuid uuid;
  book_ids uuid[];
  primary_title text;
  book_count integer;
  i integer;
  current_book uuid;
begin
  select extract_isbn_work_prefix(isbn13) into prefix
  from books
  where id = target_book_id;

  if prefix is null then
    return;
  end if;

  select
    array_agg(book_id order by has_high_res desc, cover_area desc, published_date desc nulls last, lower(title)) as ordered_ids,
    count(*) as total_books
  into book_ids, book_count
  from (
    select
      b.id as book_id,
      b.title,
      b.published_date,
      coalesce(
        (
          select coalesce(bil.is_high_resolution, false)
          from book_image_links bil
          where bil.book_id = b.id
          order by coalesce(bil.is_high_resolution, false) desc,
                   coalesce((bil.width::bigint * bil.height::bigint), 0) desc,
                   bil.created_at desc
          limit 1
        ),
        false
      ) as has_high_res,
      coalesce(
        (
          select coalesce((bil.width::bigint * bil.height::bigint), 0)
          from book_image_links bil
          where bil.book_id = b.id
          order by coalesce(bil.is_high_resolution, false) desc,
                   coalesce((bil.width::bigint * bil.height::bigint), 0) desc,
                   bil.created_at desc
          limit 1
        ),
        0
      ) as cover_area
    from books b
    where extract_isbn_work_prefix(b.isbn13) = prefix
  ) ranked;

  if book_count is null or book_count < 2 then
    return;
  end if;

  -- Get primary title with fallback for NULL/empty
  select coalesce(nullif(title, ''), 'Untitled Book') into primary_title
  from books
  where id = book_ids[1];

  -- Ensure primary_title is never NULL
  if primary_title is null or primary_title = '' then
    primary_title := 'Untitled Book';
  end if;

  select id into cluster_uuid
  from work_clusters
  where isbn_prefix = prefix;

  if cluster_uuid is null then
    insert into work_clusters (isbn_prefix, canonical_title, confidence_score, cluster_method, member_count)
    values (prefix, primary_title, 0.9, 'ISBN_PREFIX', book_count)
    returning id into cluster_uuid;
  else
    update work_clusters
    set canonical_title = primary_title,
        member_count = book_count,
        updated_at = now()
    where id = cluster_uuid;
  end if;

  for i in 1..array_length(book_ids, 1) loop
    current_book := book_ids[i];

    insert into work_cluster_members (cluster_id, book_id, is_primary, confidence, join_reason)
    values (cluster_uuid, current_book, (i = 1), 0.9, 'ISBN_PREFIX')
    on conflict (cluster_id, book_id) do update set
      is_primary = excluded.is_primary,
      confidence = excluded.confidence,
      join_reason = excluded.join_reason,
      joined_at = now();
  end loop;
end;
$$ language plpgsql;

