-- ============================================================================
-- AUTHOR NORMALIZATION
-- ============================================================================

-- Comprehensive author name normalization for deduplication
create or replace function normalize_author_name(author_name text)
returns text as $$
declare
  normalized text;
begin
  -- Return NULL for NULL input
  if author_name is null or trim(author_name) = '' then
    return null;
  end if;

  normalized := author_name;

  -- Convert to lowercase
  normalized := lower(normalized);

  -- Remove accents and diacritics
  normalized := translate(normalized,
    'àáäâãåèéëêìíïîòóöôõùúüûñçğışÀÁÄÂÃÅÈÉËÊÌÍÏÎÒÓÖÔÕÙÚÜÛÑÇĞİŞ',
    'aaaaaaeeeeiiiiooooouuuuncgisAAAAAEEEEIIIIOOOOOUUUUNCGIS');

  -- Handle common suffixes and titles
  normalized := regexp_replace(normalized, '\s+jr\.?(?:\s|$)', ' jr', 'gi');
  normalized := regexp_replace(normalized, '\s+sr\.?(?:\s|$)', ' sr', 'gi');
  normalized := regexp_replace(normalized, '\s+ph\.?d\.?(?:\s|$)', ' phd', 'gi');
  normalized := regexp_replace(normalized, '\s+m\.?d\.?(?:\s|$)', ' md', 'gi');

  -- Remove possessives
  normalized := regexp_replace(normalized, '''s(?:\s|$)', '', 'g');

  -- Handle corporate suffixes
  normalized := regexp_replace(normalized, '\s+inc\.?(?:\s|$)', ' inc', 'gi');
  normalized := regexp_replace(normalized, '\s+corp\.?(?:\s|$)', ' corp', 'gi');
  normalized := regexp_replace(normalized, '\s+ltd\.?(?:\s|$)', ' ltd', 'gi');
  normalized := regexp_replace(normalized, '\s+llc\.?(?:\s|$)', ' llc', 'gi');

  -- Remove editorial annotations
  normalized := regexp_replace(normalized, '\[from old catalog\]', '', 'gi');
  normalized := regexp_replace(normalized, '\(editor\)', '', 'gi');
  normalized := regexp_replace(normalized, '\(author\)', '', 'gi');

  -- Normalize punctuation and spaces
  normalized := regexp_replace(normalized, '[^a-z0-9\s]', ' ', 'g');
  normalized := regexp_replace(normalized, '\s+', ' ', 'g');
  normalized := trim(normalized);

  return normalized;
end;
$$ language plpgsql immutable;

comment on function normalize_author_name is 'Normalizes author names for deduplication: handles accents, suffixes, corporate names';

-- Function to merge duplicate authors with same normalized name
drop function if exists merge_duplicate_authors();
create or replace function merge_duplicate_authors()
returns table (
  groups_found integer,
  authors_merged integer
) as $$
declare
  rec record;
  primary_author_id text;
  merge_count integer := 0;
  group_count integer := 0;
begin
  -- Find groups of authors with same normalized name
  for rec in
    select
      normalized_name,
      array_agg(id order by created_at, id) as author_ids
    from authors
    where normalized_name is not null
    group by normalized_name
    having count(*) > 1
  loop
    group_count := group_count + 1;
    primary_author_id := rec.author_ids[1];

    -- Remove duplicate secondary rows for books that already have the primary author.
    delete from book_authors_join baj
    where baj.author_id = any(rec.author_ids[2:])
      and exists (
        select 1
        from book_authors_join existing
        where existing.book_id = baj.book_id
          and existing.author_id = primary_author_id
      );

    -- Keep one secondary row per book before remapping to avoid transient unique collisions.
    with secondary_ranked as (
      select
        ctid,
        row_number() over (
          partition by book_id
          order by position asc, created_at asc nulls last, id asc
        ) as rn
      from book_authors_join
      where author_id = any(rec.author_ids[2:])
    )
    delete from book_authors_join baj
    using secondary_ranked ranked
    where baj.ctid = ranked.ctid
      and ranked.rn > 1;

    -- Update remaining book references to point to primary author.
    update book_authors_join
    set author_id = primary_author_id
    where author_id = any(rec.author_ids[2:]);

    -- Merge author external IDs
    update author_external_ids
    set author_id = primary_author_id
    where author_id = any(rec.author_ids[2:])
      and not exists (
        select 1 from author_external_ids aei2
        where aei2.source = author_external_ids.source
          and aei2.external_id = author_external_ids.external_id
          and aei2.author_id = primary_author_id
      );

    -- Delete duplicate book-author entries
    delete from book_authors_join
    where author_id = any(rec.author_ids[2:]);

    -- Delete duplicate authors (keep primary)
    delete from authors
    where id = any(rec.author_ids[2:]);

    merge_count := merge_count + array_length(rec.author_ids, 1) - 1;
  end loop;

  return query select group_count, merge_count;
end;
$$ language plpgsql;

comment on function merge_duplicate_authors is 'Merges authors with identical normalized names, preserving relationships';
