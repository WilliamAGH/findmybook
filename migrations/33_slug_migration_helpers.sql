-- ============================================================================
-- MIGRATION HELPERS
-- ============================================================================

-- Persistence slug owner for SQL maintenance paths. New books are keyed by
-- their allocated UUID, so same-title cohorts never need counter probes.
create or replace function public.generate_slug(title text, book_id uuid)
returns text as $$
declare
  slug text;
begin
  if book_id is null then
    raise exception 'book_id is required before generating a book slug';
  end if;

  -- Start with title
  slug := lower(title);

  -- Basic replacements
  slug := regexp_replace(slug, '&', 'and', 'g');

  -- Remove accents/diacritics
  slug := translate(slug,
    'àáäâãåèéëêìíïîòóöôõùúüûñçğışÀÁÄÂÃÅÈÉËÊÌÍÏÎÒÓÖÔÕÙÚÜÛÑÇĞİŞ',
    'aaaaaaeeeeiiiiooooouuuuncgisAAAAAAEEEEIIIIOOOOOUUUUNCGIS');

  -- Keep only alphanumeric and spaces/hyphens
  slug := regexp_replace(slug, '[^a-z0-9\s-]', '', 'g');

  -- Replace spaces with hyphens
  slug := regexp_replace(slug, '[\s_]+', '-', 'g');

  -- Remove multiple consecutive hyphens
  slug := regexp_replace(slug, '-+', '-', 'g');

  -- Trim hyphens from start/end
  slug := trim(both '-' from slug);

  -- Truncate title part to 60 chars at word boundary
  if length(slug) > 60 then
    slug := substring(slug from 1 for 60);
    -- Try to cut at last hyphen
    slug := regexp_replace(slug, '-[^-]*$', '');
  end if;

  -- Ensure it's not empty
  if slug = '' or slug is null then
    slug := 'book';
  end if;

  return slug || '-' || book_id::text;
end;
$$ language plpgsql;

-- Function to batch generate slugs for existing books
-- Call this after migration to populate slugs
create or replace function public.generate_all_book_slugs()
returns void as $$
declare
  book_record record;
  final_slug text;
begin
  -- Loop through all books without slugs
  for book_record in
    select
      b.id,
      b.title
    from books b
    where b.slug is null or b.slug = ''
  loop
    final_slug := public.generate_slug(book_record.title, book_record.id);

    -- Update the book with its slug
    update books set slug = final_slug where id = book_record.id;
  end loop;

  raise notice 'Generated slugs for all books';
end;
$$ language plpgsql;

comment on function public.generate_slug(text, uuid) is
  'Generate a deterministic title-and-book-identity slug for SQL maintenance paths';
comment on function public.generate_all_book_slugs() is
  'Populate missing book slugs in one pass without counter scans';
