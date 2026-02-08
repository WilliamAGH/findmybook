-- Store dimensions separately (not all books have this)
create table if not exists book_dimensions (
  book_id uuid primary key references books(id) on delete cascade,
  height numeric, -- JSON: volumeInfo.dimensions.height (parsed from "23.00 cm")
  width numeric, -- JSON: volumeInfo.dimensions.width (if available)
  thickness numeric, -- JSON: volumeInfo.dimensions.thickness (if available)
  weight_grams numeric, -- From other sources if available
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- Table comments for book_dimensions
comment on table book_dimensions is 'Physical dimensions of books when available';
comment on column book_dimensions.height is 'Height in cm parsed from volumeInfo.dimensions.height';
comment on column book_dimensions.width is 'Width in cm parsed from volumeInfo.dimensions.width';
comment on column book_dimensions.thickness is 'Thickness in cm parsed from volumeInfo.dimensions.thickness';
