-- ============================================================================
-- TITLE + AUTHOR CLUSTERING
-- Merge duplicate clusters for books with same title/authors but different ISBNs
-- ============================================================================

-- Function to normalize titles for matching (remove punctuation, lowercase, trim)
create or replace function normalize_title_for_clustering(title text)
returns text as $$
begin
  if title is null then
    return null;
  end if;
  
  -- Lowercase, remove punctuation, collapse whitespace
  return trim(regexp_replace(
    lower(regexp_replace(title, '[^\w\s]', '', 'g')),
    '\s+', ' ', 'g'
  ));
end;
$$ language plpgsql immutable;

-- Function to get normalized author list for a book
create or replace function get_normalized_authors(book_id_param uuid)
returns text as $$
begin
  return (
    select string_agg(
      trim(lower(regexp_replace(regexp_replace(a.name, '[^\w\s]', '', 'g'), '\s+', ' ', 'g'))),
      '|'
      order by ba.position, a.name
    )
    from book_authors_join ba
    join authors a on a.id = ba.author_id
    where ba.book_id = book_id_param
  );
end;
$$ language plpgsql stable;

comment on function normalize_title_for_clustering is 'Normalize book title for clustering: lowercase, remove punctuation, collapse whitespace';
comment on function get_normalized_authors is 'Get normalized author list for a book as pipe-separated string';
