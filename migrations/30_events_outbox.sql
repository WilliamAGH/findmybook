-- ============================================================================
-- ASYNC BACKFILL INFRASTRUCTURE
-- ============================================================================
-- NOTE: Backfill queue now uses BackfillQueueService (in-memory queue)
-- instead of database table. See BackfillQueueService.java and BackfillCoordinator.java

-- Transactional outbox pattern for WebSocket events
-- Ensures events are reliably delivered even if WebSocket publish fails
create table if not exists events_outbox (
  event_id uuid primary key default gen_random_uuid(),
  topic text not null, -- WebSocket topic path: /topic/search.{id}, /topic/book.{id}
  payload jsonb not null, -- Event data as JSON
  created_at timestamptz not null default now(),
  sent_at timestamptz, -- NULL until successfully published to WebSocket
  retry_count int not null default 0
);

create index if not exists idx_events_outbox_unsent on events_outbox(created_at) where sent_at is null;
create index if not exists idx_events_outbox_sent on events_outbox(sent_at desc) where sent_at is not null;

comment on table events_outbox is 'Transactional outbox for reliable WebSocket event delivery';
comment on column events_outbox.topic is 'WebSocket destination topic (e.g., /topic/book.{bookId})';
comment on column events_outbox.sent_at is 'When event was successfully published (NULL = pending)';
