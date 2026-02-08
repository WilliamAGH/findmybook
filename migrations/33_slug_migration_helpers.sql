-- ============================================================================
-- MIGRATION HELPERS
-- ============================================================================

-- PostgreSQL function to generate slug from title and authors
-- Used during migration when Java SlugGenerator is not available
create or replace function generate_slug(title text, author_name text default null)
returns text as $$
declare
  slug text;
begin
  -- Start with title
  slug := lower(title);

  -- Basic replacements
  slug := regexp_replace(slug, '&', 'and', 'g');

  -- Remove accents/diacritics
  slug := translate(slug,
    'àáäâãåèéëêìíïîòóöôõùúüûñçğışÀÁÄÂÃÅÈÉËÊÌÍÏÎÒÓÖÔÕÙÚÜÛÑÇĞİŞ',
    'aaaaaaeeeeiiiiooooouuuuncgisAAAAAEEEEIIIIOOOOOUUUUNCGIS');

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

  -- Add author if provided
  if author_name is not null and author_name != '' then
    declare
      author_slug text;
    begin
      author_slug := lower(author_name);
      author_slug := regexp_replace(author_slug, '[^a-z0-9\s-]', '', 'g');
      author_slug := regexp_replace(author_slug, '[\s_]+', '-', 'g');
      author_slug := regexp_replace(author_slug, '-+', '-', 'g');
      author_slug := trim(both '-' from author_slug);

      -- Truncate author to 30 chars
      if length(author_slug) > 30 then
        author_slug := substring(author_slug from 1 for 30);
        author_slug := regexp_replace(author_slug, '-[^-]*$', '');
      end if;

      slug := slug || '-' || author_slug;
    end;
  end if;

  -- Final truncation to 100 chars
  if length(slug) > 100 then
    slug := substring(slug from 1 for 100);
    slug := regexp_replace(slug, '-[^-]*$', '');
  end if;

  -- Ensure it's not empty
  if slug = '' or slug is null then
    slug := 'book';
  end if;

  return slug;
end;
$$ language plpgsql;

-- Function to batch generate slugs for existing books
-- Call this after migration to populate slugs
create or replace function generate_all_book_slugs()
returns void as $$
declare
  book_record record;
  base_slug text;
  final_slug text;
  counter integer;
begin
  -- Loop through all books without slugs
  for book_record in
    select
      b.id,
      b.title,
      string_agg(a.name, ' ' order by ba.position) as first_author
    from books b
    left join book_authors_join ba on b.id = ba.book_id and ba.position = 0
    left join authors a on ba.author_id = a.id
    where b.slug is null or b.slug = ''
    group by b.id, b.title
  loop
    -- Generate base slug
    base_slug := generate_slug(book_record.title, book_record.first_author);
    final_slug := base_slug;
    counter := 1;

    -- Ensure uniqueness
    while exists(select 1 from books where slug = final_slug and id != book_record.id) loop
      final_slug := base_slug || '-' || counter;
      counter := counter + 1;
    end loop;

    -- Update the book with its slug
    update books set slug = final_slug where id = book_record.id;
  end loop;

  raise notice 'Generated slugs for all books';
end;
$$ language plpgsql;

comment on function generate_slug is 'Generate URL slug from title and optional author, used during migration';
comment on function generate_all_book_slugs is 'Batch generate slugs for all books that dont have one, used after data migration';
