select b.id, b.title, b.subtitle, b.description, b.publisher,
       extract(year from b.published_date)::text published_year,
       b.page_count::text page_count, b.language,
       authors.names authors,
       tags.names classification_tags,
       categories.names collection_categories,
       bac.summary ai_summary,
       bac.reader_fit ai_reader_fit,
       bac.key_themes::text ai_key_themes,
       bac.takeaways::text ai_takeaways,
       bac.context ai_context,
       ratings.average_rating::text average_rating,
       ratings.ratings_count::text ratings_count
from books b
left join book_ai_content bac on bac.book_id = b.id and bac.is_current
left join lateral (
  select string_agg(a.name, ' | ' order by baj.position, a.name) names
  from book_authors_join baj
  join authors a on a.id = baj.author_id
  where baj.book_id = b.id
) authors on true
left join lateral (
  select string_agg(distinct bt.display_name, ' | ' order by bt.display_name) names
  from book_tag_assignments bta
  join book_tags bt on bt.id = bta.tag_id
  where bta.book_id = b.id
) tags on true
left join lateral (
  select string_agg(distinct bc.display_name, ' | ' order by bc.display_name) names
  from book_collections_join bcj
  join book_collections bc on bc.id = bcj.collection_id
  where bcj.book_id = b.id
    and bc.collection_type = 'CATEGORY'
) categories on true
left join lateral (
  select max(average_rating) average_rating, max(ratings_count) ratings_count
  from book_external_ids
  where book_id = b.id
) ratings on true
where b.id in (:book_ids)
