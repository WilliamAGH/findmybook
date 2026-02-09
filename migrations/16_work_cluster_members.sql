-- Work cluster membership
create table if not exists work_cluster_members (
  cluster_id uuid not null references work_clusters(id) on delete cascade,
  book_id uuid not null references books(id) on delete cascade,
  is_primary boolean not null default false, -- The "best" edition in this cluster
  confidence float not null default 0.5 check (confidence >= 0 and confidence <= 1),
  join_reason text not null, -- 'ISBN_PREFIX', 'OCLC_MATCH', 'OPENLIBRARY_MATCH', etc.
  joined_at timestamptz not null default now(),
  primary key (cluster_id, book_id)
);

create index if not exists idx_work_cluster_members_book on work_cluster_members(book_id);
create unique index if not exists ux_work_cluster_members_primary
  on work_cluster_members(cluster_id) where is_primary = true;

comment on table work_cluster_members is 'Links books to their work clusters with confidence scoring';
comment on column work_cluster_members.is_primary is 'Marks the best/primary edition in this cluster';
comment on column work_cluster_members.join_reason is 'Why this book was added to this cluster';
