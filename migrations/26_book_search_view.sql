-- ============================================================================
-- SEARCH FUNCTIONALITY
-- ============================================================================

-- Materialized view for optimized book search
-- Denormalizes books with authors for fast full-text and fuzzy search
create materialized view if not exists book_search_view as
select
  b.id as book_id,
  b.title,
  b.subtitle,
  b.slug,
  b.isbn13,
  b.isbn10,
  b.published_date,
  b.publisher,
  b.language,
  b.page_count,
  string_agg(a.name, ', ' order by ba.position) as authors,
  -- Combined search vector with weights:
  -- A: title, author names (highest priority)
  -- B: subtitle (high priority)
  -- C: publisher (medium priority)
  -- D: description (low priority)
  (
    setweight(to_tsvector('english', coalesce(b.title, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(string_agg(a.name, ' ' order by ba.position), '')), 'A') ||
    setweight(to_tsvector('english', coalesce(b.subtitle, '')), 'B') ||
    setweight(to_tsvector('english', coalesce(b.publisher, '')), 'C') ||
    setweight(to_tsvector('english', coalesce(b.description, '')), 'D')
  ) as search_vector,
  -- Searchable text for trigram similarity (fuzzy matching)
  lower(
    coalesce(b.title, '') || ' ' ||
    coalesce(b.subtitle, '') || ' ' ||
    coalesce(string_agg(a.name, ' ' order by ba.position), '') || ' ' ||
    coalesce(b.publisher, '')
  ) as searchable_text
from books b
left join book_authors_join ba on b.id = ba.book_id
left join authors a on ba.author_id = a.id
group by b.id;

-- Indexes for the materialized view
create index if not exists idx_book_search_view_search_vector
  on book_search_view using gin (search_vector);

create index if not exists idx_book_search_view_searchable_text
  on book_search_view using gin (searchable_text gin_trgm_ops);

-- Unique index for concurrent refresh
create unique index if not exists idx_book_search_view_book_id
  on book_search_view (book_id);
