-- Join table linking books to collections (categories, lists, etc.)
create table if not exists book_collections_join (
  id                text primary key, -- NanoID (12 chars via IdGenerator.generateLong() - high volume)
  collection_id     text not null references book_collections(id) on delete cascade,
  book_id           uuid not null references books(id) on delete cascade,
  -- Position/ranking in the collection (for ordered lists)
  position          integer, -- Rank in bestseller list or sort order
  -- List-specific metadata
  weeks_on_list     integer, -- For bestseller lists
  rank_last_week    integer, -- Previous rank for trending
  peak_position     integer, -- Best rank achieved
  -- Provider references if the book was added via external data
  provider_isbn13   text, -- ISBN from the list provider
  provider_isbn10   text, -- ISBN-10 from the list provider
  provider_book_ref text, -- Provider's reference/ID for this book
  -- Custom metadata (ratings, notes, etc.)
  user_rating       numeric, -- User's personal rating if custom list
  notes             text, -- User notes about why book is in this collection
  -- Raw data from provider if applicable
  raw_item_json     jsonb, -- Complete list item data from provider
  -- Timestamps
  added_at          timestamptz not null default now(),
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  unique (collection_id, book_id)
);

create unique index if not exists uq_book_collections_join_position
  on book_collections_join (collection_id, position)
  where position is not null;

create index if not exists idx_book_collections_join_collection on book_collections_join(collection_id);
create index if not exists idx_book_collections_join_book on book_collections_join(book_id);
create index if not exists idx_book_collections_join_added on book_collections_join(collection_id, added_at desc);


-- Table comments for book_collections_join
comment on table book_collections_join is 'Many-to-many relationship between books and collections';
comment on column book_collections_join.position is 'Rank in bestseller list or sort order';
comment on column book_collections_join.weeks_on_list is 'Number of weeks on bestseller list';

