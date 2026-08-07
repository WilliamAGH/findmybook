-- Work cluster membership
begin;
set local lock_timeout = '60s';
set local statement_timeout = '15min';

create table if not exists work_cluster_members (
  cluster_id uuid not null references work_clusters(id) on delete cascade,
  book_id uuid not null references books(id) on delete cascade,
  is_primary boolean not null default false, -- The "best" edition in this cluster
  confidence float not null default 0.5 check (confidence >= 0 and confidence <= 1),
  join_reason text not null, -- 'ISBN_PREFIX', 'OCLC_MATCH', 'OPENLIBRARY_MATCH', etc.
  joined_at timestamptz not null default now(),
  primary key (cluster_id, book_id)
);

lock table work_cluster_members in share row exclusive mode;

-- Historical versions allowed multiple primary rows. Preserve the most recently
-- selected, strongest membership and use book_id as the stable final tie-breaker.
with ranked_primary_members as (
  select
    cluster_id,
    book_id,
    row_number() over (
      partition by cluster_id
      order by joined_at desc nulls last, confidence desc nulls last, book_id asc
    ) as primary_preference_rank
  from work_cluster_members
  where is_primary is true
)
update work_cluster_members as members
set is_primary = false
from ranked_primary_members
where ranked_primary_members.cluster_id = members.cluster_id
  and ranked_primary_members.book_id = members.book_id
  and ranked_primary_members.primary_preference_rank > 1;

create index if not exists idx_work_cluster_members_book on work_cluster_members(book_id);

-- The unique primary invariant is installed by migration 37 after every
-- clustering writer has been replaced with demote-before-promote ordering.

comment on table work_cluster_members is 'Links books to their work clusters with confidence scoring';
comment on column work_cluster_members.is_primary is 'Marks the best/primary edition in this cluster';
comment on column work_cluster_members.join_reason is 'Why this book was added to this cluster';

commit;
