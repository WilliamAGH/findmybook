with anchor as (
  select qwen_4b_fp16
  from book_similarity_vectors
  where source_type = 'book'
    and book_id = ?
    and model_version = ?
    and profile_hash = ?
    and qwen_4b_fp16 is not null
)
select b.title,
       authors.names authors,
       1 - (v.qwen_4b_fp16 <=> anchor.qwen_4b_fp16) similarity
from anchor
join book_similarity_vectors v
  on v.source_type = 'book'
 and v.book_id <> ?
 and v.model_version = ?
 and v.profile_hash = ?
 and v.qwen_4b_fp16 is not null
join books b on b.id = v.book_id
left join lateral (
  select string_agg(a.name, ', ' order by baj.position, a.name) names
  from book_authors_join baj
  join authors a on a.id = baj.author_id
  where baj.book_id = b.id
) authors on true
where not exists (
  select 1
  from work_cluster_members source_member
  join work_cluster_members candidate_member
    on candidate_member.cluster_id = source_member.cluster_id
  where source_member.book_id = ?
    and candidate_member.book_id = v.book_id
)
order by v.qwen_4b_fp16 <=> anchor.qwen_4b_fp16
limit ?
