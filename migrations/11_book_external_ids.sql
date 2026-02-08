-- External IDs mapping for many-to-one association to canonical books
-- This includes Google Books ID, OpenLibrary ID, etc. with provider-specific metadata
-- IMPORTANT: One book can have multiple external IDs (Google, Amazon, OpenLibrary, etc.)
create table if not exists book_external_ids (
  id text primary key, -- NanoID (10 chars via IdGenerator.generate())
  book_id uuid not null references books(id) on delete cascade,
  source text not null, -- 'GOOGLE_BOOKS', 'OPEN_LIBRARY', 'AMAZON', etc.
  external_id text not null, -- Provider's ID (JSON: id for Google Books)
  -- Industry identifiers from this provider
  provider_isbn10 text, -- JSON: volumeInfo.industryIdentifiers[type=ISBN_10].identifier
  provider_isbn13 text, -- JSON: volumeInfo.industryIdentifiers[type=ISBN_13].identifier
  provider_oclc text, -- OCLC number if provided
  provider_lccn text, -- Library of Congress Control Number if provided
  provider_asin text, -- Amazon Standard Identification Number
  -- Provider-specific metadata
  info_link text, -- JSON: volumeInfo.infoLink (Google Books info page)
  preview_link text, -- JSON: volumeInfo.previewLink
  web_reader_link text, -- JSON: accessInfo.webReaderLink
  purchase_link text, -- Link to purchase page if available
  canonical_volume_link text, -- JSON: volumeInfo.canonicalVolumeLink
  average_rating numeric, -- JSON: volumeInfo.averageRating (provider's rating)
  ratings_count integer, -- JSON: volumeInfo.ratingsCount
  review_count integer, -- Number of reviews on this platform
  -- Provider-specific availability
  is_ebook boolean, -- JSON: saleInfo.isEbook
  pdf_available boolean, -- JSON: accessInfo.pdf.isAvailable
  epub_available boolean, -- JSON: accessInfo.epub.isAvailable
  embeddable boolean, -- JSON: accessInfo.embeddable
  public_domain boolean, -- JSON: accessInfo.publicDomain
  viewability text, -- JSON: accessInfo.viewability ('FULL', 'PARTIAL', 'NO_PAGES', 'ALL_PAGES')
  text_readable boolean, -- JSON: volumeInfo.readingModes.text
  image_readable boolean, -- JSON: volumeInfo.readingModes.image
  -- Content metadata
  print_type text, -- JSON: volumeInfo.printType ('BOOK', 'MAGAZINE')
  maturity_rating text, -- JSON: volumeInfo.maturityRating ('NOT_MATURE', 'MATURE')
  content_version text, -- JSON: volumeInfo.contentVersion
  text_to_speech_permission text, -- JSON: accessInfo.textToSpeechPermission
  -- Sale info
  saleability text, -- JSON: saleInfo.saleability ('FOR_SALE', 'NOT_FOR_SALE', 'FREE')
  country_code text, -- JSON: saleInfo.country or accessInfo.country
  is_ebook_for_sale boolean, -- Derived from saleInfo
  -- Pricing info if available
  list_price numeric,
  retail_price numeric,
  currency_code text,
  -- Work identifiers (for clustering editions)
  oclc_work_id text, -- OCLC Work ID from WorldCat
  openlibrary_work_id text, -- OpenLibrary work identifier (e.g., /works/OL45804W)
  goodreads_work_id text, -- Goodreads work ID for grouping editions
  google_canonical_id text, -- Google Books ID extracted from canonicalVolumeLink

  -- Metadata
  last_updated timestamptz,
  created_at timestamptz not null default now(),
  unique(source, external_id)
);

create index if not exists idx_book_external_ids_book_id on book_external_ids(book_id);
create index if not exists idx_book_external_ids_external_id on book_external_ids(external_id);
create index if not exists idx_book_external_ids_source_id on book_external_ids(source, external_id);
create unique index if not exists uq_book_external_ids_provider_isbn13
  on book_external_ids(source, provider_isbn13) where provider_isbn13 is not null;
create unique index if not exists uq_book_external_ids_provider_isbn10
  on book_external_ids(source, provider_isbn10) where provider_isbn10 is not null;
create index if not exists idx_book_external_ids_isbn13 on book_external_ids(provider_isbn13) where provider_isbn13 is not null;
create index if not exists idx_book_external_ids_isbn10 on book_external_ids(provider_isbn10) where provider_isbn10 is not null;
create index if not exists idx_book_external_ids_asin on book_external_ids(provider_asin) where provider_asin is not null;

-- Table and column comments for book_external_ids
comment on table book_external_ids is 'External provider IDs and metadata for books (Google Books, Amazon, OpenLibrary, etc.)';
comment on column book_external_ids.external_id is 'Provider-specific ID (e.g., Google Books ID from JSON id field)';
comment on column book_external_ids.source is 'Provider name: GOOGLE_BOOKS, OPEN_LIBRARY, AMAZON, etc.';
comment on column book_external_ids.provider_isbn10 is 'ISBN-10 from volumeInfo.industryIdentifiers[type=ISBN_10]';
comment on column book_external_ids.provider_isbn13 is 'ISBN-13 from volumeInfo.industryIdentifiers[type=ISBN_13]';
comment on column book_external_ids.info_link is 'Provider info page from volumeInfo.infoLink';
comment on column book_external_ids.preview_link is 'Preview page from volumeInfo.previewLink';
comment on column book_external_ids.web_reader_link is 'Web reader from accessInfo.webReaderLink';
comment on column book_external_ids.canonical_volume_link is 'Canonical link from volumeInfo.canonicalVolumeLink';
comment on column book_external_ids.average_rating is 'Provider rating from volumeInfo.averageRating';
comment on column book_external_ids.ratings_count is 'Rating count from volumeInfo.ratingsCount';
comment on column book_external_ids.viewability is 'Access level from accessInfo.viewability (FULL, PARTIAL, NO_PAGES)';
comment on column book_external_ids.print_type is 'Content type from volumeInfo.printType (BOOK, MAGAZINE)';
comment on column book_external_ids.maturity_rating is 'Content rating from volumeInfo.maturityRating';
comment on column book_external_ids.saleability is 'Sale status from saleInfo.saleability';
