-- View to easily see book editions
-- Postgres does not allow CREATE OR REPLACE VIEW to add/drop/reorder columns.
-- To keep startup idempotent and allow schema evolution, drop then recreate.
drop view if exists book_work_editions;
create view book_work_editions (
  book_id,
  title,
  isbn13,
  work_title,
  related_book_id,
  related_title,
  related_isbn13,
  related_publisher,
  confidence,
  cluster_method
) as
select
  b1.id as book_id,
  b1.title,
  b1.isbn13,
  wc.canonical_title as work_title,
  b2.id as related_book_id,
  b2.title as related_title,
  b2.isbn13 as related_isbn13,
  b2.publisher as related_publisher,
  wcm1.confidence,
  wc.cluster_method
from work_cluster_members wcm1
join work_clusters wc on wc.id = wcm1.cluster_id
join work_cluster_members wcm2 on wcm1.cluster_id = wcm2.cluster_id
join books b1 on b1.id = wcm1.book_id
join books b2 on b2.id = wcm2.book_id
where wcm1.book_id != wcm2.book_id;

comment on view book_work_editions is 'Shows all editions of books in the same work cluster';
