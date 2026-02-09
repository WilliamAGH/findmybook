-- Store various image URLs from providers and map them to our S3 copies
create table if not exists book_image_links (
  id text primary key, -- NanoID (10 chars)
  book_id uuid not null references books(id) on delete cascade,
  image_type text not null, -- JSON keys: 'smallThumbnail', 'thumbnail', 'small', 'medium', 'large', 'extraLarge'
  url text not null, -- JSON: volumeInfo.imageLinks.{imageType} - full external URL
  s3_image_path text, -- Internal S3 key when image has been persisted
  source text, -- 'GOOGLE_BOOKS', etc. - which API provided this image
  width integer, -- Image width in pixels (estimated or actual)
  height integer, -- Image height in pixels (estimated or actual)
  is_high_resolution boolean default false, -- True for extraLarge/large images
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(book_id, image_type)
);

create index if not exists idx_book_image_links_book_id on book_image_links(book_id);

-- Table comments for book_image_links
comment on table book_image_links is 'Maps external image URLs to our S3-persisted copies';
comment on column book_image_links.image_type is 'Image size: smallThumbnail, thumbnail, small, medium, large, extraLarge';
comment on column book_image_links.url is 'External URL from volumeInfo.imageLinks';
comment on column book_image_links.width is 'Image width in pixels (estimated or detected)';
comment on column book_image_links.height is 'Image height in pixels (estimated or detected)';
comment on column book_image_links.is_high_resolution is 'True for high-resolution images (extraLarge, large)';

-- ============================================================================
-- IMAGE METADATA EXTENSION
-- ============================================================================

-- Add metadata columns to book_image_links for completeness
alter table book_image_links
add column if not exists width integer,
add column if not exists height integer,
add column if not exists file_size_bytes integer,
add column if not exists is_high_resolution boolean,
add column if not exists updated_at timestamptz,
add column if not exists s3_uploaded_at timestamptz,
add column if not exists download_error text;

update book_image_links
set updated_at = coalesce(updated_at, created_at, now())
where updated_at is null;

alter table book_image_links
alter column updated_at set default now();

update book_image_links
set s3_uploaded_at = coalesce(s3_uploaded_at, created_at, now())
where s3_image_path is not null
  and s3_uploaded_at is null;

delete from book_image_links
where lower(image_type) in ('preferred', 'fallback', 's3')
   or lower(url) like '%placeholder-book-cover.svg%'
   or url !~* '^https?://';

do $$
begin
  if not exists (
    select 1
    from pg_constraint c
    join pg_class t on t.oid = c.conrelid
    where t.relname = 'book_image_links'
      and c.conname = 'check_book_image_links_disallowed_types'
  ) then
    alter table book_image_links
      add constraint check_book_image_links_disallowed_types
      check (lower(image_type) <> all (array['preferred', 'fallback', 's3'])) not valid;
  end if;
end
$$;

do $$
begin
  if not exists (
    select 1
    from pg_constraint c
    join pg_class t on t.oid = c.conrelid
    where t.relname = 'book_image_links'
      and c.conname = 'check_book_image_links_http_url'
  ) then
    alter table book_image_links
      add constraint check_book_image_links_http_url
      check (url ~* '^https?://') not valid;
  end if;
end
$$;

do $$
begin
  if not exists (
    select 1
    from pg_constraint c
    join pg_class t on t.oid = c.conrelid
    where t.relname = 'book_image_links'
      and c.conname = 'check_book_image_links_no_placeholder'
  ) then
    alter table book_image_links
      add constraint check_book_image_links_no_placeholder
      check (position('placeholder-book-cover.svg' in lower(url)) = 0) not valid;
  end if;
end
$$;

alter table book_image_links validate constraint check_book_image_links_disallowed_types;
alter table book_image_links validate constraint check_book_image_links_http_url;
alter table book_image_links validate constraint check_book_image_links_no_placeholder;

create or replace function set_book_image_links_updated_at()
returns trigger as $$
begin
  new.updated_at := now();
  return new;
end;
$$ language plpgsql;

drop trigger if exists book_image_links_set_updated_at on book_image_links;
create trigger book_image_links_set_updated_at
  before update on book_image_links
  for each row
  execute function set_book_image_links_updated_at();

comment on column book_image_links.width is 'Image width in pixels';
comment on column book_image_links.height is 'Image height in pixels';
comment on column book_image_links.file_size_bytes is 'File size in bytes';
comment on column book_image_links.is_high_resolution is 'True if image is high quality (>300px width or >100KB)';
comment on column book_image_links.s3_image_path is 'S3 path to our persisted copy of this image';
comment on column book_image_links.updated_at is 'Timestamp of the most recent mutation to this image link row';
comment on column book_image_links.s3_uploaded_at is 'When we uploaded this image to S3';
comment on column book_image_links.download_error is 'Error message if download failed';
