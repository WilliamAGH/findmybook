-- Function to refresh the search view (call after bulk updates)
drop function if exists refresh_book_search_view();
create or replace function refresh_book_search_view()
returns void
language plpgsql
as $$
begin
  -- Non-concurrent so it can run inside the initializer's transaction and within a function
  refresh materialized view book_search_view;
end;
$$;

-- Main search function combining multiple strategies with cluster deduplication
drop function if exists search_books(text, integer);
create or replace function search_books(
  search_query text,
  max_results integer default 20
)
returns table (
  book_id uuid,
  title text,
  subtitle text,
  authors text,
  isbn13 text,
  isbn10 text,
  published_date date,
  publisher text,
  relevance_score float,
  match_type text,
  edition_count integer,
  cluster_id uuid
) as $$
begin
  return query
  with
  -- Strategy 1: Exact title matches (highest priority)
  exact_matches as (
    select
      b.book_id,
      b.title,
      b.subtitle,
      b.authors,
      b.isbn13,
      b.isbn10,
      b.published_date,
      b.publisher,
      1.0::float as relevance_score,
      'exact_title'::text as match_type,
      coalesce(wc.member_count, 1) as edition_count,
      wc.id as cluster_id,
      -- Add priority for selecting primary edition
      coalesce(wcm.is_primary, false) as is_primary,
      -- Quality metrics for tie-breaking
      coalesce(
        (
          select coalesce(bil.is_high_resolution, false)
          from book_image_links bil
          where bil.book_id = b.book_id
          order by coalesce(bil.is_high_resolution, false) desc,
                   coalesce((bil.width::bigint * bil.height::bigint), 0) desc
          limit 1
        ),
        false
      ) as has_high_res_cover
    from book_search_view b
    left join work_cluster_members wcm on b.book_id = wcm.book_id
    left join work_clusters wc on wcm.cluster_id = wc.id
    where lower(b.title) = lower(search_query)
    order by
      coalesce(wcm.is_primary, false) desc,
      has_high_res_cover desc,
      b.published_date desc nulls last,
      lower(b.title),
      b.book_id
    limit 50  -- Get more results for deduplication
  ),
  -- Strategy 2: Full-text search with ranking
  fulltext_matches as (
    select
      b.book_id,
      b.title,
      b.subtitle,
      b.authors,
      b.isbn13,
      b.isbn10,
      b.published_date,
      b.publisher,
      ts_rank(b.search_vector, plainto_tsquery('english', search_query))::float as relevance_score,
      'fulltext'::text as match_type,
      coalesce(wc.member_count, 1) as edition_count,
      wc.id as cluster_id,
      coalesce(wcm.is_primary, false) as is_primary,
      coalesce(
        (
          select coalesce(bil.is_high_resolution, false)
          from book_image_links bil
          where bil.book_id = b.book_id
          order by coalesce(bil.is_high_resolution, false) desc,
                   coalesce((bil.width::bigint * bil.height::bigint), 0) desc
          limit 1
        ),
        false
      ) as has_high_res_cover
    from book_search_view b
    left join work_cluster_members wcm on b.book_id = wcm.book_id
    left join work_clusters wc on wcm.cluster_id = wc.id
    where b.search_vector @@ plainto_tsquery('english', search_query)
      and b.book_id not in (select em.book_id from exact_matches em)
    order by
      relevance_score desc,
      coalesce(wcm.is_primary, false) desc,
      has_high_res_cover desc,
      b.published_date desc nulls last,
      lower(b.title),
      b.book_id
    limit 100  -- Get more results for deduplication
  ),
  -- Strategy 3: Fuzzy matches for typo tolerance
  fuzzy_matches as (
    select
      b.book_id,
      b.title,
      b.subtitle,
      b.authors,
      b.isbn13,
      b.isbn10,
      b.published_date,
      b.publisher,
      similarity(b.searchable_text, lower(search_query))::float * 0.8 as relevance_score,
      'fuzzy'::text as match_type,
      coalesce(wc.member_count, 1) as edition_count,
      wc.id as cluster_id,
      coalesce(wcm.is_primary, false) as is_primary,
      coalesce(
        (
          select coalesce(bil.is_high_resolution, false)
          from book_image_links bil
          where bil.book_id = b.book_id
          order by coalesce(bil.is_high_resolution, false) desc,
                   coalesce((bil.width::bigint * bil.height::bigint), 0) desc
          limit 1
        ),
        false
      ) as has_high_res_cover
    from book_search_view b
    left join work_cluster_members wcm on b.book_id = wcm.book_id
    left join work_clusters wc on wcm.cluster_id = wc.id
    where b.searchable_text % lower(search_query)
      and b.book_id not in (
        select em.book_id from exact_matches em
        union select fm.book_id from fulltext_matches fm
      )
    order by
      relevance_score desc,
      coalesce(wcm.is_primary, false) desc,
      has_high_res_cover desc,
      b.published_date desc nulls last,
      lower(b.title),
      b.book_id
    limit 50  -- Get more results for deduplication
  ),
  -- Combine all results
  all_matches as (
    select * from exact_matches
    union all
    select * from fulltext_matches
    union all
    select * from fuzzy_matches
  ),
  -- Deduplicate by cluster: keep only the best book per cluster
  deduplicated as (
    select distinct on (coalesce(am.cluster_id, am.book_id))
      am.book_id,
      am.title,
      am.subtitle,
      am.authors,
      am.isbn13,
      am.isbn10,
      am.published_date,
      am.publisher,
      am.relevance_score,
      am.match_type,
      am.edition_count,
      am.cluster_id
    from all_matches am
    order by
      coalesce(am.cluster_id, am.book_id),  -- Group by cluster (or book if no cluster)
      am.relevance_score desc,               -- Prefer higher relevance
      am.is_primary desc,                    -- Prefer primary edition
      am.has_high_res_cover desc,            -- Prefer better cover quality
      am.published_date desc nulls last      -- Prefer newer editions
  )
  -- Final results ordered by relevance
  select * from deduplicated
  order by
    relevance_score desc,
    lower(title),
    coalesce(cluster_id, book_id)
  limit max_results;
end;
$$ language plpgsql;

-- ISBN search function for barcode scanning
drop function if exists search_by_isbn(text);
create or replace function search_by_isbn(isbn_query text)
returns table (
  book_id uuid,
  title text,
  subtitle text,
  authors text,
  isbn13 text,
  isbn10 text,
  published_date date,
  publisher text
) as $$
declare
  clean_isbn text;
begin
  -- Remove any non-digit characters from ISBN
  clean_isbn := regexp_replace(isbn_query, '[^0-9]', '', 'g');

  return query
  select
    b.id as book_id,
    b.title,
    b.subtitle,
    string_agg(a.name, ', ' order by ba.position) as authors,
    b.isbn13,
    b.isbn10,
    b.published_date,
    b.publisher
  from books b
  left join book_authors_join ba on b.id = ba.book_id
  left join authors a on ba.author_id = a.id
  where
    regexp_replace(b.isbn13, '[^0-9]', '', 'g') = clean_isbn
    or regexp_replace(b.isbn10, '[^0-9]', '', 'g') = clean_isbn
  group by b.id
  limit 1;
end;
$$ language plpgsql;

-- Author search function
drop function if exists search_authors(text, integer);
create or replace function search_authors(
  search_query text,
  max_results integer default 20
)
returns table (
  author_id text,
  author_name text,
  book_count bigint,
  relevance_score float
) as $$
begin
  return query
  with author_matches as (
    select
      a.id as author_id,
      a.name as author_name,
      count(distinct ba.book_id) as book_count,
      case
        when lower(a.name) = lower(search_query) then 1.0
        when lower(a.name) like lower(search_query) || '%' then 0.9
        when a.search_vector @@ plainto_tsquery('english', search_query) then
          ts_rank(a.search_vector, plainto_tsquery('english', search_query))::float
        else similarity(lower(a.name), lower(search_query)) * 0.7
      end as relevance_score
    from authors a
    left join book_authors_join ba on a.id = ba.author_id
    where
      lower(a.name) like '%' || lower(search_query) || '%'
      or a.search_vector @@ plainto_tsquery('english', search_query)
      or lower(a.name) % lower(search_query)
    group by a.id, a.name, a.search_vector
  )
  select * from author_matches
  order by
    relevance_score desc,
    book_count desc,
    lower(author_name),
    author_id
  limit max_results;
end;
$$ language plpgsql;

-- Comments for search components
comment on materialized view book_search_view is 'Denormalized view optimized for full-text and fuzzy search';
comment on function search_books is 'Smart search combining exact, full-text, and fuzzy matching strategies';
comment on function search_by_isbn is 'Search for books by ISBN-10 or ISBN-13, handles various formats';
comment on function search_authors is 'Search for authors with relevance ranking and book count';
comment on function refresh_book_search_view is 'Refresh the search materialized view after bulk updates';
