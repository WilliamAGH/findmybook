-- Book similarity embedding cache and fused-vector search storage.
--
-- Section embeddings are cached by exact model/input hash so profile weights can be
-- tuned without re-calling the embeddings API. Fused vectors are the searchable
-- section-weighted outputs for a specific profile contract.

create table if not exists book_embedding_sections (
  id                 text primary key default substring(replace(gen_random_uuid()::text, '-', '') from 1 for 12),
  book_id            uuid not null references books(id) on delete cascade,
  section_key        text not null,
  input_format       text not null,
  input_hash         text not null,
  model              text not null,
  embedding          halfvec(2560) not null,
  input_preview      text,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  constraint book_embedding_sections_section_key_check check (
    section_key in ('identity', 'classification', 'description', 'ai_content', 'bibliographic')
  ),
  constraint book_embedding_sections_input_format_check check (input_format = 'key_value'),
  constraint book_embedding_sections_input_hash_check check (input_hash ~ '^[a-f0-9]{64}$')
);

create unique index if not exists uq_book_embedding_sections_cache_key
  on book_embedding_sections (book_id, section_key, model, input_format, input_hash);

create index if not exists idx_book_embedding_sections_book_model
  on book_embedding_sections (book_id, model, input_format, section_key, updated_at desc);

create table if not exists book_similarity_vectors (
  id                   text primary key default substring(replace(gen_random_uuid()::text, '-', '') from 1 for 12),
  book_id              uuid not null references books(id) on delete cascade,
  profile_id           text not null,
  profile_hash         text not null,
  model                text not null,
  model_version        text not null,
  input_format         text not null,
  section_hash         text not null,
  section_input_hashes jsonb not null,
  embedding            halfvec(2560) not null,
  created_at           timestamptz not null default now(),
  updated_at           timestamptz not null default now(),
  constraint book_similarity_vectors_input_format_check check (input_format = 'key_value'),
  constraint book_similarity_vectors_profile_hash_check check (profile_hash ~ '^[a-f0-9]{64}$'),
  constraint book_similarity_vectors_section_hash_check check (section_hash ~ '^[a-f0-9]{64}$')
);

create unique index if not exists uq_book_similarity_vectors_current_profile
  on book_similarity_vectors (book_id, model_version, profile_hash);

create index if not exists idx_book_similarity_vectors_profile
  on book_similarity_vectors (model_version, profile_hash, updated_at desc);

create index if not exists idx_book_similarity_vectors_embedding_hnsw
  on book_similarity_vectors using hnsw (embedding halfvec_cosine_ops);

comment on table book_embedding_sections is
  'Section-level book embedding cache keyed by exact model/input hash for similarity profile tuning.';
comment on table book_similarity_vectors is
  'Searchable section-fused book similarity vectors keyed by profile contract hash.';
