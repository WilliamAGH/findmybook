select b.id
from books b
where length(btrim(coalesce(b.description, ''))) >= 50
  and (
    not exists (select 1 from work_cluster_members wcm where wcm.book_id = b.id)
    or exists (
      select 1
      from work_cluster_members wcm
      where wcm.book_id = b.id
        and wcm.is_primary = true
    )
  )
order by b.updated_at desc
limit ?
