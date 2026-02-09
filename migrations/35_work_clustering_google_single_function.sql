create or replace function cluster_single_book_by_google_id(target_book_id uuid)
returns void as $$
declare
  canonical_id text;
  cluster_uuid uuid;
  book_ids uuid[];
  book_count integer;
  primary_title text;
  i integer;
  current_book uuid;
begin
  select google_canonical_id into canonical_id
  from book_external_ids
  where book_id = target_book_id
    and google_canonical_id is not null
    and source = 'GOOGLE_BOOKS'
  limit 1;

  if canonical_id is not null then
    canonical_id := nullif(regexp_replace(canonical_id, '[[:cntrl:]]', '', 'g'), '');
  end if;

  if canonical_id is null then
    select canonical_volume_link into canonical_id
    from book_external_ids
    where book_id = target_book_id
      and canonical_volume_link is not null
      and source = 'GOOGLE_BOOKS'
    limit 1;

    if canonical_id is not null then
      canonical_id := (regexp_match(canonical_id, '[?&]id=([^&]+)'))[1];
      canonical_id := nullif(regexp_replace(canonical_id, '[[:cntrl:]]', '', 'g'), '');
    end if;
  end if;

  if canonical_id is null then
    return;
  end if;

  select
    array_agg(book_id order by has_high_res desc, cover_area desc, published_date desc nulls last, lower(title)) as ordered_ids,
    count(*) as total_books
  into book_ids, book_count
  from (
    select distinct
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
    from book_external_ids bei
    join books b on b.id = bei.book_id
    where bei.source = 'GOOGLE_BOOKS'
      and (
        nullif(regexp_replace(bei.google_canonical_id, '[[:cntrl:]]', '', 'g'), '') = canonical_id
        or (
          bei.canonical_volume_link is not null
          and (regexp_match(bei.canonical_volume_link, '[?&]id=([^&]+)'))[1] = canonical_id
        )
      )
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
  where google_canonical_id = canonical_id;

  if cluster_uuid is null then
    insert into work_clusters (google_canonical_id, canonical_title, confidence_score, cluster_method, member_count)
    values (canonical_id, primary_title, 0.85, 'GOOGLE_CANONICAL', book_count)
    returning id into cluster_uuid;
  else
    update work_clusters
    set canonical_title = coalesce(primary_title, work_clusters.canonical_title),
        member_count = book_count,
        updated_at = now()
    where id = cluster_uuid;
  end if;

  for i in 1..array_length(book_ids, 1) loop
    current_book := book_ids[i];

    insert into work_cluster_members (cluster_id, book_id, is_primary, confidence, join_reason)
    values (cluster_uuid, current_book, (i = 1), 0.85, 'GOOGLE_CANONICAL')
    on conflict (cluster_id, book_id) do update set
      is_primary = excluded.is_primary,
      confidence = excluded.confidence,
      join_reason = excluded.join_reason,
      joined_at = now();
  end loop;
end;
$$ language plpgsql;
