create table if not exists book_tag_equivalents (
  id text primary key, -- NanoID (10 chars)
  canonical_tag_id text not null references book_tags(id) on delete cascade,
  equivalent_tag_id text not null references book_tags(id) on delete cascade,
  created_at timestamptz not null default now(),
  unique (canonical_tag_id, equivalent_tag_id)
);

create index if not exists idx_book_tag_equivalents_canonical on book_tag_equivalents(canonical_tag_id);
create index if not exists idx_book_tag_equivalents_equivalent on book_tag_equivalents(equivalent_tag_id);

comment on table book_tag_equivalents is 'Pairs of tags that should be treated as equivalents/aliases.';
comment on column book_tag_equivalents.canonical_tag_id is 'Primary tag in an equivalence relationship.';
comment on column book_tag_equivalents.equivalent_tag_id is 'Alias tag that resolves to the canonical tag.';
