-- ============================================================================
-- WORK CLUSTERING SYSTEM (replaces edition system)
-- ============================================================================

-- Work clusters table - groups books that are different editions of the same work
create table if not exists work_clusters (
  id uuid primary key default gen_random_uuid(),

  -- ISBN-based clustering
  isbn_prefix text, -- First 9-11 digits of ISBN-13 (publisher/work identifier)

  -- External work identifiers
  oclc_work_id text,
  openlibrary_work_id text,
  goodreads_work_id text,
  google_canonical_id text, -- Extracted from canonicalVolumeLink

  -- Cluster metadata
  canonical_title text not null,
  canonical_author text,
  confidence_score float default 0.5 check (confidence_score >= 0 and confidence_score <= 1),
  cluster_method text not null, -- 'ISBN_PREFIX', 'EXTERNAL_ID', 'ML_SIMILARITY', 'MANUAL'

  -- Statistics
  member_count integer default 0,
  constraint check_reasonable_member_count check (member_count <= 100),

  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- Unique constraints for external identifiers
create unique index if not exists uq_work_clusters_isbn_prefix
  on work_clusters(isbn_prefix) where isbn_prefix is not null;
create unique index if not exists uq_work_clusters_oclc
  on work_clusters(oclc_work_id) where oclc_work_id is not null;
create unique index if not exists uq_work_clusters_openlibrary
  on work_clusters(openlibrary_work_id) where openlibrary_work_id is not null;
create unique index if not exists uq_work_clusters_goodreads
  on work_clusters(goodreads_work_id) where goodreads_work_id is not null;
create unique index if not exists uq_work_clusters_google
  on work_clusters(google_canonical_id) where google_canonical_id is not null;

-- Indexes for lookups
create index if not exists idx_work_clusters_method on work_clusters(cluster_method);
create index if not exists idx_work_clusters_confidence on work_clusters(confidence_score desc);

comment on table work_clusters is 'Groups books that are different editions of the same work';
comment on column work_clusters.isbn_prefix is 'First 9-11 digits of ISBN-13 identifying publisher and work';
comment on column work_clusters.cluster_method is 'How this cluster was created: ISBN_PREFIX, EXTERNAL_ID, ML_SIMILARITY, MANUAL';
