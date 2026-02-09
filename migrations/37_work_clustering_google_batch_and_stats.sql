-- Cluster books by Google canonical links
drop function if exists cluster_books_by_google_canonical();
create or replace function cluster_books_by_google_canonical()
returns table (clusters_created integer, books_clustered integer) as $$
declare
  rec record;
  cluster_uuid uuid;
  clusters_count integer := 0;
  books_count integer := 0;
  google_id text;
  existing_cluster uuid;
  primary_title text;
begin
  for rec in
    select
      canonical_id,
      array_agg(book_id order by has_high_res desc, cover_area desc, published_date desc nulls last, lower(title)) as book_ids,
      count(*) as book_count
    from (
      select distinct
        coalesce(
          nullif(regexp_replace(bei.google_canonical_id, '[[:cntrl:]]', '', 'g'), ''),
          (regexp_match(bei.canonical_volume_link, '[?&]id=([^&]+)'))[1]
        ) as canonical_id,
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
          bei.google_canonical_id is not null
          or bei.canonical_volume_link ~ '[?&]id='
        )
    ) candidate
    where canonical_id is not null
      and canonical_id <> ''
      and canonical_id !~ '[[:cntrl:]]'
    group by canonical_id
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
    where google_canonical_id = rec.canonical_id;

    if cluster_uuid is null then
      insert into work_clusters (google_canonical_id, canonical_title, confidence_score, cluster_method, member_count)
      values (rec.canonical_id, primary_title, 0.85, 'GOOGLE_CANONICAL', rec.book_count)
      returning id into cluster_uuid;

      clusters_count := clusters_count + 1;
    else
      update work_clusters
      set canonical_title = coalesce(primary_title, work_clusters.canonical_title),
          member_count = rec.book_count,
          updated_at = now()
      where id = cluster_uuid;
    end if;

    for i in 1..array_length(rec.book_ids, 1) loop
      insert into work_cluster_members (cluster_id, book_id, is_primary, confidence, join_reason)
      values (cluster_uuid, rec.book_ids[i], (i = 1), 0.85, 'GOOGLE_CANONICAL')
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

-- Statistics function
drop function if exists get_clustering_stats();
create or replace function get_clustering_stats()
returns table (
  total_books bigint,
  clustered_books bigint,
  unclustered_books bigint,
  total_clusters bigint,
  isbn_clusters bigint,
  google_clusters bigint,
  avg_cluster_size numeric
) as $$
begin
  return query
  with stats as (
    select
      (select count(*) from books) as total_books,
      (select count(distinct book_id) from work_cluster_members) as clustered_books,
      (select count(*) from work_clusters) as total_clusters,
      (select count(*) from work_clusters where cluster_method = 'ISBN_PREFIX') as isbn_clusters,
      (select count(*) from work_clusters where cluster_method = 'GOOGLE_CANONICAL') as google_clusters
  )
  select
    s.total_books,
    s.clustered_books,
    s.total_books - s.clustered_books as unclustered_books,
    s.total_clusters,
    s.isbn_clusters,
    s.google_clusters,
    case
      when s.total_clusters > 0
      then round(s.clustered_books::numeric / s.total_clusters, 2)
      else 0
    end as avg_cluster_size
  from stats s;
end;
$$ language plpgsql;

comment on function extract_isbn_work_prefix is 'Extracts work identifier from ISBN-13 (first 11 digits)';
comment on function cluster_books_by_isbn is 'Groups books by ISBN prefix to find editions of the same work';
comment on function cluster_books_by_google_canonical is 'Groups books by Google canonical volume link to find editions';
comment on function get_book_editions is 'Returns all editions of a book from its work cluster';
comment on function get_clustering_stats is 'Returns statistics about work clustering';

