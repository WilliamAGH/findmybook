with anchor as (
  select qwen_4b_fp16
  from book_similarity_vectors
  where book_id = ?
    and model_version = ?
    and profile_hash = ?
    and qwen_4b_fp16 is not null
)
select b.title,
       authors.names authors,
       1 - (v.qwen_4b_fp16 <=> (select qwen_4b_fp16 from anchor)) similarity
from book_similarity_vectors v
join books b on b.id = v.book_id
left join lateral (
  select string_agg(a.name, ', ' order by baj.position, a.name) names
  from book_authors_join baj
  join authors a on a.id = baj.author_id
  where baj.book_id = b.id
) authors on true
where v.book_id <> ?
  and v.model_version = ?
  and v.profile_hash = ?
  and v.qwen_4b_fp16 is not null
  and exists (select 1 from anchor)
order by v.qwen_4b_fp16 <=> (select qwen_4b_fp16 from anchor)
limit ?
