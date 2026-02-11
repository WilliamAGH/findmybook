-- ============================================================================
-- BOOK AI CONTENT QUALITY GUARDS
-- Last-resort database constraints preventing degenerate AI output from
-- being persisted. These complement the application-layer validator in
-- AiContentQualityValidator; the DB constraints act as a safety net when
-- code paths bypass or mis-configure the validator.
--
-- This migration is self-contained and re-runnable:
--   1. Deletes toxic records that violate the constraints
--   2. Drops constraints if they exist (idempotency)
--   3. Re-adds all constraints
-- ============================================================================

-- ---------------------------------------------------------------------------
-- PHASE 1: Delete degenerate / poisoned records
-- ---------------------------------------------------------------------------

-- Delete summaries that are pure character-repetition spam (e.g. 999 '@' chars).
-- Condition: any single ASCII character makes up > 50% of the summary.
-- Uses regexp_replace to strip one character class at a time and compare lengths.
delete from book_ai_content
where char_length(summary) > 0
  and char_length(regexp_replace(summary, '[a-zA-Z]', '', 'g')) * 2 > char_length(summary);

-- Delete records with leaked prompt / control tokens in summary.
delete from book_ai_content
where summary like '%<|%|>%';

-- ---------------------------------------------------------------------------
-- PHASE 2: Drop existing constraints if present (idempotency)
-- ---------------------------------------------------------------------------

do $$ begin
  if exists (select 1 from pg_constraint where conname = 'book_ai_content_summary_min_length') then
    alter table book_ai_content drop constraint book_ai_content_summary_min_length;
  end if;
  if exists (select 1 from pg_constraint where conname = 'book_ai_content_summary_max_length') then
    alter table book_ai_content drop constraint book_ai_content_summary_max_length;
  end if;
  if exists (select 1 from pg_constraint where conname = 'book_ai_content_summary_letter_ratio') then
    alter table book_ai_content drop constraint book_ai_content_summary_letter_ratio;
  end if;
  if exists (select 1 from pg_constraint where conname = 'book_ai_content_summary_no_prompt_leak') then
    alter table book_ai_content drop constraint book_ai_content_summary_no_prompt_leak;
  end if;
  if exists (select 1 from pg_constraint where conname = 'book_ai_content_reader_fit_max_length') then
    alter table book_ai_content drop constraint book_ai_content_reader_fit_max_length;
  end if;
  if exists (select 1 from pg_constraint where conname = 'book_ai_content_context_max_length') then
    alter table book_ai_content drop constraint book_ai_content_context_max_length;
  end if;
end $$;

-- ---------------------------------------------------------------------------
-- PHASE 3: Add CHECK constraints
-- ---------------------------------------------------------------------------

-- Summary must be real prose: at least 20 chars trimmed, at most 2000.
alter table book_ai_content
  add constraint book_ai_content_summary_min_length
    check (char_length(btrim(summary)) >= 20);

alter table book_ai_content
  add constraint book_ai_content_summary_max_length
    check (char_length(summary) <= 2000);

-- Reject summaries where letters make up less than half the content.
-- regexp_replace strips all ASCII letters; the remaining length ratio
-- tells us how much of the original is non-letter junk.
-- Formula: (original - non_letter) / original >= 0.5
-- Rewritten to avoid division: non_letter_length * 2 <= original_length.
alter table book_ai_content
  add constraint book_ai_content_summary_letter_ratio
    check (
      char_length(summary) = 0
      or char_length(regexp_replace(summary, '[a-zA-Z]', '', 'g')) * 2 <= char_length(summary)
    );

-- Block prompt-injection control tokens from being persisted.
alter table book_ai_content
  add constraint book_ai_content_summary_no_prompt_leak
    check (summary not like '%<|%|>%');

-- Prose field length ceilings for reader_fit and context.
alter table book_ai_content
  add constraint book_ai_content_reader_fit_max_length
    check (reader_fit is null or char_length(reader_fit) <= 1500);

alter table book_ai_content
  add constraint book_ai_content_context_max_length
    check (context is null or char_length(context) <= 1500);
