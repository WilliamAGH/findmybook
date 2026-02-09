-- Slug redirect table to handle book slug changes over time
-- When a book's slug changes (due to title/author updates), old URLs redirect to new slug
create table if not exists book_slug_redirect (
  old_slug text primary key,
  book_id uuid not null references books(id) on delete cascade,
  created_at timestamptz not null default now()
);

create index if not exists idx_book_slug_redirect_book_id on book_slug_redirect(book_id);

comment on table book_slug_redirect is 'Maps old book slugs to current book IDs for permanent redirects';
comment on column book_slug_redirect.old_slug is 'Previous SEO slug that no longer matches book.slug';
comment on column book_slug_redirect.book_id is 'Current book ID (use to look up current slug)';
