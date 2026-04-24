with anchor as (select cast(? as uuid) id),
anchor_author_ids as (
  select author_id from book_authors_join where book_id = (select id from anchor)
),
anchor_tag_ids as (
  select tag_id from book_tag_assignments where book_id = (select id from anchor)
),
anchor_collection_ids as (
  select collection_id from book_collections_join where book_id = (select id from anchor)
),
lexical as (
  select book_id id from search_books(?, 100)
),
candidate_source as (
  select id, 1000 numeric_score from anchor
  union all
  select distinct book_id, 260 from book_authors_join
  where author_id in (select author_id from anchor_author_ids)
    and book_id <> (select id from anchor)
  union all
  select recommended_book_id, 220 + coalesce(score, 0) * 20 from book_recommendations
  where source_book_id = (select id from anchor)
  union all
  select source_book_id, 160 + coalesce(score, 0) * 10 from book_recommendations
  where recommended_book_id = (select id from anchor)
  union all
  select id, 150 from lexical where id <> (select id from anchor)
  union all
  select distinct book_id, 90 from book_tag_assignments
  where tag_id in (select tag_id from anchor_tag_ids)
    and book_id <> (select id from anchor)
  union all
  select distinct book_id, 90 from book_collections_join
  where collection_id in (select collection_id from anchor_collection_ids)
    and book_id <> (select id from anchor)
),
scored as (
  select b.id,
         coalesce(wcm.cluster_id::text, b.id::text) cluster_key,
         sum(cs.numeric_score) signal,
         length(coalesce(b.description, '')) description_length
  from candidate_source cs
  join books b on b.id = cs.id
  left join work_cluster_members wcm on wcm.book_id = b.id
  group by b.id, coalesce(wcm.cluster_id::text, b.id::text)
),
ranked as (
  select *,
         row_number() over (
           partition by cluster_key
           order by signal desc, description_length desc, id
         ) cluster_rank
  from scored
)
select id
from ranked
where cluster_rank = 1
order by case when id = cast(? as uuid) then 0 else 1 end,
         signal desc,
         description_length desc
limit ?
