-- ============================================================================
-- SLUG MANAGEMENT
-- ============================================================================

BEGIN;

-- Function to find book by slug or ID (supports legacy URLs)
drop function if exists find_book_by_slug_or_id(text);
create function find_book_by_slug_or_id(identifier text)
returns table (
  book_id uuid,
  title text,
  subtitle text,
  slug text,
  authors text,
  isbn13 text,
  isbn10 text,
  published_date date,
  publisher text
) as $$
begin
  -- First try to find by slug
  return query
  select
    b.id as book_id,
    b.title,
    b.subtitle,
    b.slug,
    string_agg(a.name, ', ' order by ba.position) as authors,
    b.isbn13,
    b.isbn10,
    b.published_date,
    b.publisher
  from books b
  left join book_authors_join ba on b.id = ba.book_id
  left join authors a on ba.author_id = a.id
  where b.slug = identifier
  group by b.id
  limit 1;

  -- If not found, try as UUID (legacy support)
  if not found then
    return query
    select
      b.id as book_id,
      b.title,
      b.subtitle,
      b.slug,
      string_agg(a.name, ', ' order by ba.position) as authors,
      b.isbn13,
      b.isbn10,
      b.published_date,
      b.publisher
    from books b
    left join book_authors_join ba on b.id = ba.book_id
    left join authors a on ba.author_id = a.id
    where b.id::text = identifier
    group by b.id
    limit 1;
  end if;
end;
$$ language plpgsql;

-- Function to ensure slug uniqueness by appending counter if needed
create or replace function ensure_unique_slug(base_slug text)
returns text as $$
declare
  final_slug text;
  counter integer := 1;
begin
  final_slug := base_slug;

  -- Check if slug exists
  while exists(select 1 from books where slug = final_slug) loop
    final_slug := base_slug || '-' || counter;
    counter := counter + 1;
  end loop;

  return final_slug;
end;
$$ language plpgsql;

comment on function find_book_by_slug_or_id is 'Find book by SEO slug or UUID, supports legacy URL patterns';
comment on function ensure_unique_slug is 'Generate unique slug by appending counter if base slug already exists';

-- Migration 53 retires the counter-probing slug function after every caller
-- uses UUID-owned slugs. Keep replay of this historical module idempotent: the
-- function may exist transiently only inside this transaction, so contracted
-- databases never expose it again to concurrent sessions.
DO $legacy_slug_retirement$
DECLARE
  canonical_author_contract_applied boolean := false;
BEGIN
  IF to_regprocedure('public.canonical_author_contract_is_applied()') IS NOT NULL THEN
    EXECUTE 'SELECT public.canonical_author_contract_is_applied()'
      INTO canonical_author_contract_applied;
  END IF;

  IF canonical_author_contract_applied THEN
    EXECUTE 'DROP FUNCTION IF EXISTS public.ensure_unique_slug(text)';
  END IF;
END
$legacy_slug_retirement$;

COMMIT;
