create or replace function notify_primary_edition_change()
returns trigger as $$
begin
  if OLD.is_primary is true
     and (NEW.is_primary is distinct from OLD.is_primary)
     and (NEW.is_primary is null or NEW.is_primary = false) then
    insert into events_outbox (topic, payload, created_at)
    values (
      '/topic/cluster.' || OLD.cluster_id,
      jsonb_build_object(
        'event', 'primary_changed',
        'old_primary_book_id', OLD.book_id::text,
        'cluster_id', OLD.cluster_id::text,
        'timestamp', floor(extract(epoch from now()) * 1000)
      ),
      now()
    );
  end if;

  if NEW.is_primary is true
     and (NEW.is_primary is distinct from OLD.is_primary) then
    insert into events_outbox (topic, payload, created_at)
    values (
      '/topic/cluster.' || NEW.cluster_id,
      jsonb_build_object(
        'event', 'primary_changed',
        'new_primary_book_id', NEW.book_id::text,
        'cluster_id', NEW.cluster_id::text,
        'timestamp', floor(extract(epoch from now()) * 1000)
      ),
      now()
    );
  end if;

  return NEW;
end;
$$ language plpgsql;

drop trigger if exists work_cluster_primary_change on work_cluster_members;
create trigger work_cluster_primary_change
  after update on work_cluster_members
  for each row
  when (OLD.is_primary is distinct from NEW.is_primary)
  execute function notify_primary_edition_change();

-- Get all editions of a book
drop function if exists get_book_editions(uuid);
create or replace function get_book_editions(target_book_id uuid)
returns table (
  book_id uuid,
  title text,
  subtitle text,
  isbn13 text,
  isbn10 text,
  publisher text,
  published_date date,
  is_primary boolean,
  confidence float,
  cluster_method text
) as $$
begin
  return query
  select distinct
    b.id,
    b.title,
    b.subtitle,
    b.isbn13,
    b.isbn10,
    b.publisher,
    b.published_date,
    wcm.is_primary,
    wcm.confidence,
    wc.cluster_method
  from work_cluster_members wcm1
  join work_cluster_members wcm on wcm.cluster_id = wcm1.cluster_id
  join books b on b.id = wcm.book_id
  join work_clusters wc on wc.id = wcm.cluster_id
  where wcm1.book_id = target_book_id
    and wcm.book_id != target_book_id
  order by wcm.is_primary desc, wcm.confidence desc, b.published_date desc;
end;
$$ language plpgsql;
