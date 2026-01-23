-- ============================================================================
-- OPTIMIZED BOOK QUERY FUNCTIONS
-- Single source of truth for view-specific book data fetching
-- ============================================================================
--
-- These functions replace N+1 query hydration patterns with single optimized queries
-- that fetch exactly what each view needs, dramatically improving performance.
--
-- Benefits:
-- - 80-120 queries per page → 1-2 queries per page
-- - 10-80x faster page loads
-- - Simpler, more maintainable code
-- - No over-fetching of unused data
--
-- Usage: Run this file against your database to create/replace the functions
-- ============================================================================

-- ============================================================================
-- FUNCTION: get_book_cards
-- Purpose: Fetch minimal book data for card displays (homepage, search grid)
-- Replaces: hydrateBatchAuthors, hydrateBatchCategories, hydrateBatchCovers, hydrateBatchProviderMetadata
-- Performance: Single query vs. 5 queries per book (8 books = 40 queries → 1 query)
-- ============================================================================
DROP FUNCTION IF EXISTS get_book_cards(UUID[]);

CREATE FUNCTION get_book_cards(book_ids UUID[])
RETURNS TABLE (
    id UUID,
    slug TEXT,
    title TEXT,
    authors TEXT[],
    cover_url TEXT,
    cover_s3_key TEXT,
    cover_fallback_url TEXT,
    average_rating NUMERIC,
    ratings_count INTEGER,
    tags JSONB
) AS $$
BEGIN
    RETURN QUERY
    WITH input_ids AS (
        SELECT ids.book_id, ids.ord
        FROM unnest(book_ids) WITH ORDINALITY AS ids(book_id, ord)
    ),
    card_data AS (
        SELECT
            input_ids.ord,
            b.id,
            b.slug,
            b.title,
            COALESCE(
                (
                    SELECT ARRAY(
                        SELECT DISTINCT a_inner.name
                        FROM book_authors_join baj_inner
                        JOIN authors a_inner ON a_inner.id = baj_inner.author_id
                        WHERE baj_inner.book_id = b.id
                          AND a_inner.name IS NOT NULL
                        ORDER BY a_inner.name
                    )
                ),
                ARRAY[]::TEXT[]
            ) as authors,
            cover_meta.cover_url,
            cover_meta.cover_s3_key,
            cover_meta.cover_fallback_url,
            bei.average_rating,
            bei.ratings_count,
            COALESCE(
                (SELECT jsonb_object_agg(bt.key, bta.metadata)
                 FROM book_tag_assignments bta
                 JOIN book_tags bt ON bt.id = bta.tag_id
                 WHERE bta.book_id = b.id),
                '{}'::JSONB
            ) as tags
        FROM input_ids
        JOIN books b ON b.id = input_ids.book_id
        LEFT JOIN book_authors_join baj ON b.id = baj.book_id
        LEFT JOIN authors a ON a.id = baj.author_id
        LEFT JOIN book_external_ids bei ON bei.book_id = b.id AND bei.source = 'GOOGLE_BOOKS'
        LEFT JOIN LATERAL (
            SELECT chosen.cover_url,
                   chosen.cover_s3_key,
                   chosen.cover_fallback_url,
                   chosen.width,
                   chosen.height,
                   chosen.is_high_resolution
            FROM (
                SELECT strict.cover_url,
                       strict.cover_s3_key,
                       strict.cover_fallback_url,
                       strict.width,
                       strict.height,
                       strict.is_high_resolution,
                       strict.cover_priority,
                       strict.height_value,
                       strict.width_value,
                       strict.created_value,
                       0 AS quality_rank
                FROM (
                    SELECT
                        COALESCE(bil.s3_image_path, bil.url) AS cover_url,
                        bil.s3_image_path AS cover_s3_key,
                        COALESCE(
                            (
                                SELECT bil_fallback.url
                                FROM book_image_links bil_fallback
                                WHERE bil_fallback.book_id = b.id
                                  AND bil_fallback.download_error IS NULL
                                  AND bil_fallback.s3_image_path IS NULL
                                  AND bil_fallback.url <> bil.url
                                  AND bil_fallback.url NOT LIKE '%printsec=titlepage%'
                                  AND bil_fallback.url NOT LIKE '%printsec=copyright%'
                                  AND bil_fallback.url NOT LIKE '%printsec=toc%'
                                  AND (bil_fallback.height::float / NULLIF(bil_fallback.width, 0)) BETWEEN 1.2 AND 2.0
                                  AND bil_fallback.width >= 180
                                  AND bil_fallback.height >= 280
                                ORDER BY
                                    CASE
                                        WHEN bil_fallback.is_high_resolution THEN 0
                                        WHEN bil_fallback.width >= 320 AND bil_fallback.height >= 320 THEN 1
                                        WHEN bil_fallback.image_type = 'extraLarge' THEN 2
                                        WHEN bil_fallback.image_type = 'large' THEN 3
                                        WHEN bil_fallback.image_type = 'medium' THEN 4
                                        ELSE 5
                                    END,
                                    bil_fallback.height DESC NULLS LAST,
                                    bil_fallback.width DESC NULLS LAST,
                                    bil_fallback.created_at DESC
                                LIMIT 1
                            ),
                            bil.url
                        ) AS cover_fallback_url,
                        bil.width,
                        bil.height,
                        COALESCE(bil.is_high_resolution, false) AS is_high_resolution,
                        CASE
                            WHEN bil.s3_image_path IS NOT NULL AND COALESCE(bil.is_high_resolution, false) THEN 0
                            WHEN bil.s3_image_path IS NOT NULL THEN 1
                            WHEN bil.url LIKE '%edge=curl%' THEN 5
                            WHEN COALESCE(bil.is_high_resolution, false) THEN 10
                            WHEN bil.width >= 320 AND bil.height >= 320 THEN 11
                            WHEN bil.image_type = 'extraLarge' THEN 12
                            WHEN bil.image_type = 'large' THEN 13
                            WHEN bil.image_type = 'medium' THEN 14
                            WHEN bil.image_type = 'small' THEN 15
                            WHEN bil.image_type = 'thumbnail' THEN 16
                            ELSE 20
                        END AS cover_priority,
                        COALESCE(bil.height, 0) AS height_value,
                        COALESCE(bil.width, 0) AS width_value,
                        bil.created_at AS created_value
                    FROM book_image_links bil
                    WHERE bil.book_id = b.id
                      AND bil.download_error IS NULL
                      AND bil.width >= 180
                      AND bil.height >= 280
                      AND (bil.height::float / NULLIF(bil.width, 0)) BETWEEN 1.2 AND 2.0
                      AND bil.url NOT LIKE '%printsec=titlepage%'
                      AND bil.url NOT LIKE '%printsec=copyright%'
                      AND bil.url NOT LIKE '%printsec=toc%'
                    ORDER BY
                        CASE
                            WHEN bil.s3_image_path IS NOT NULL AND COALESCE(bil.is_high_resolution, false) THEN 0
                            WHEN bil.s3_image_path IS NOT NULL THEN 1
                            WHEN bil.url LIKE '%edge=curl%' THEN 5
                            WHEN COALESCE(bil.is_high_resolution, false) THEN 10
                            WHEN bil.width >= 320 AND bil.height >= 320 THEN 11
                            WHEN bil.image_type = 'extraLarge' THEN 12
                            WHEN bil.image_type = 'large' THEN 13
                            WHEN bil.image_type = 'medium' THEN 14
                            WHEN bil.image_type = 'small' THEN 15
                            WHEN bil.image_type = 'thumbnail' THEN 16
                            ELSE 20
                        END,
                        bil.height DESC NULLS LAST,
                        bil.width DESC NULLS LAST,
                        bil.created_at DESC
                    LIMIT 1
                ) strict
                UNION ALL
                SELECT relaxed_inner.cover_url,
                       relaxed_inner.cover_s3_key,
                       relaxed_inner.cover_fallback_url,
                       relaxed_inner.width,
                       relaxed_inner.height,
                       relaxed_inner.is_high_resolution,
                       relaxed_inner.cover_priority,
                       relaxed_inner.height_value,
                       relaxed_inner.width_value,
                       relaxed_inner.created_value,
                       1 AS quality_rank
                FROM (
                    SELECT
                        COALESCE(bil_relaxed.s3_image_path, bil_relaxed.url) AS cover_url,
                        bil_relaxed.s3_image_path AS cover_s3_key,
                        NULLIF(bil_relaxed.url, '') AS cover_fallback_url,
                        bil_relaxed.width,
                        bil_relaxed.height,
                        COALESCE(bil_relaxed.is_high_resolution, false) AS is_high_resolution,
                        CASE
                            WHEN bil_relaxed.s3_image_path IS NOT NULL AND COALESCE(bil_relaxed.is_high_resolution, false) THEN 0
                            WHEN bil_relaxed.s3_image_path IS NOT NULL THEN 1
                            WHEN bil_relaxed.url LIKE '%edge=curl%' THEN 5
                            WHEN COALESCE(bil_relaxed.is_high_resolution, false) THEN 10
                            WHEN bil_relaxed.width >= 320 AND bil_relaxed.height >= 320 THEN 11
                            WHEN bil_relaxed.image_type = 'extraLarge' THEN 12
                            WHEN bil_relaxed.image_type = 'large' THEN 13
                            WHEN bil_relaxed.image_type = 'medium' THEN 14
                            WHEN bil_relaxed.image_type = 'small' THEN 15
                            WHEN bil_relaxed.image_type = 'thumbnail' THEN 16
                            ELSE 20
                        END AS cover_priority,
                        COALESCE(bil_relaxed.height, 0) AS height_value,
                        COALESCE(bil_relaxed.width, 0) AS width_value,
                        bil_relaxed.created_at AS created_value
                    FROM book_image_links bil_relaxed
                    WHERE bil_relaxed.book_id = b.id
                      AND bil_relaxed.download_error IS NULL
                    ORDER BY
                        COALESCE((bil_relaxed.width::bigint * bil_relaxed.height::bigint), 0) DESC,
                        bil_relaxed.created_at DESC
                    LIMIT 1
                ) relaxed_inner
            ) chosen
            ORDER BY
                chosen.quality_rank,
                chosen.cover_priority,
                chosen.height_value DESC,
                chosen.width_value DESC,
                chosen.created_value DESC
            LIMIT 1
        ) cover_meta ON TRUE
        GROUP BY input_ids.ord, b.id, b.slug, b.title,
                 cover_meta.cover_url, cover_meta.cover_s3_key, cover_meta.cover_fallback_url,
                 bei.average_rating, bei.ratings_count
    )
    SELECT
        card_data.id,
        card_data.slug,
        card_data.title,
        card_data.authors,
        card_data.cover_url,
        card_data.cover_s3_key,
        card_data.cover_fallback_url,
        card_data.average_rating,
        card_data.ratings_count,
        card_data.tags
    FROM card_data
    ORDER BY card_data.ord;
END;
$$ LANGUAGE plpgsql STABLE;

COMMENT ON FUNCTION get_book_cards IS 'Optimized query for book cards - prefers high-quality covers (S3 or 180x280 edge=curl) but gracefully falls back to the best available image when none meet the strict rules';

-- ============================================================================
-- FUNCTION: get_book_list_items
-- Purpose: Fetch extended book data for search list view
-- Adds: description, categories beyond card data
-- Performance: Single query vs. 6 queries per book
-- ============================================================================
DROP FUNCTION IF EXISTS get_book_list_items(uuid[]);

CREATE FUNCTION get_book_list_items(book_ids UUID[])
RETURNS TABLE (
    id UUID,
    slug TEXT,
    title TEXT,
    description TEXT,
    authors TEXT[],
    categories TEXT[],
    cover_url TEXT,
    cover_s3_key TEXT,
    cover_fallback_url TEXT,
    cover_width INTEGER,
    cover_height INTEGER,
    cover_is_high_resolution BOOLEAN,
    average_rating NUMERIC,
    ratings_count INTEGER,
    tags JSONB
) AS $$
BEGIN
    RETURN QUERY
    WITH input_ids AS (
        SELECT ids.book_id, ids.ord
        FROM unnest(book_ids) WITH ORDINALITY AS ids(book_id, ord)
    ),
    list_data AS (
        SELECT
            input_ids.ord,
            b.id,
            b.slug,
            b.title,
            b.description,
            COALESCE(
                (
                    SELECT ARRAY(
                        SELECT DISTINCT a_inner.name
                        FROM book_authors_join baj_inner
                        JOIN authors a_inner ON a_inner.id = baj_inner.author_id
                        WHERE baj_inner.book_id = b.id
                          AND a_inner.name IS NOT NULL
                        ORDER BY a_inner.name
                    )
                ),
                ARRAY[]::TEXT[]
            ) as authors,
            COALESCE(
                (
                    SELECT ARRAY_AGG(category_name ORDER BY category_name)
                    FROM (
                        SELECT DISTINCT bc_inner.display_name AS category_name
                        FROM book_collections_join bcj_inner
                        JOIN book_collections bc_inner ON bc_inner.id = bcj_inner.collection_id
                        WHERE bcj_inner.book_id = b.id
                          AND bc_inner.collection_type = 'CATEGORY'
                          AND bc_inner.display_name IS NOT NULL
                    ) category_list
                ),
                ARRAY[]::TEXT[]
            ) as categories,
            cover_meta.cover_url as cover_url,
            cover_meta.cover_s3_key as cover_s3_key,
            cover_meta.cover_fallback_url as cover_fallback_url,
            cover_meta.width as cover_width,
            cover_meta.height as cover_height,
            cover_meta.is_high_resolution as cover_is_high_resolution,
            bei.average_rating,
            bei.ratings_count,
            COALESCE(
                (SELECT jsonb_object_agg(bt.key, bta.metadata)
                 FROM book_tag_assignments bta
                 JOIN book_tags bt ON bt.id = bta.tag_id
                 WHERE bta.book_id = b.id),
                '{}'::JSONB
            ) as tags
        FROM input_ids
        JOIN books b ON b.id = input_ids.book_id
        LEFT JOIN book_authors_join baj ON b.id = baj.book_id
        LEFT JOIN authors a ON a.id = baj.author_id
        LEFT JOIN book_collections_join bcj ON bcj.book_id = b.id
        LEFT JOIN book_collections bc ON bc.id = bcj.collection_id
        LEFT JOIN book_external_ids bei ON bei.book_id = b.id AND bei.source = 'GOOGLE_BOOKS'
        LEFT JOIN LATERAL (
            SELECT chosen.cover_url,
                   chosen.cover_s3_key,
                   chosen.cover_fallback_url,
                   chosen.width,
                   chosen.height,
                   chosen.is_high_resolution
            FROM (
                SELECT strict.cover_url,
                       strict.cover_s3_key,
                       strict.cover_fallback_url,
                       strict.width,
                       strict.height,
                       strict.is_high_resolution,
                       strict.cover_priority,
                       strict.height_value,
                       strict.width_value,
                       strict.created_value,
                       0 AS quality_rank
                FROM (
                    SELECT
                        COALESCE(bil_meta.s3_image_path, bil_meta.url) AS cover_url,
                        bil_meta.s3_image_path AS cover_s3_key,
                        COALESCE(
                            (
                                SELECT bil_fallback.url
                                FROM book_image_links bil_fallback
                                WHERE bil_fallback.book_id = b.id
                                  AND bil_fallback.download_error IS NULL
                                  AND bil_fallback.s3_image_path IS NULL
                                  AND bil_fallback.url <> bil_meta.url
                                  AND bil_fallback.url NOT LIKE '%printsec=titlepage%'
                                  AND bil_fallback.url NOT LIKE '%printsec=copyright%'
                                  AND bil_fallback.url NOT LIKE '%printsec=toc%'
                                  AND (bil_fallback.height::float / NULLIF(bil_fallback.width, 0)) BETWEEN 1.2 AND 2.0
                                  AND bil_fallback.width >= 180
                                  AND bil_fallback.height >= 280
                                ORDER BY
                                    CASE
                                        WHEN bil_fallback.is_high_resolution THEN 0
                                        WHEN bil_fallback.width >= 320 AND bil_fallback.height >= 320 THEN 1
                                        WHEN bil_fallback.image_type = 'extraLarge' THEN 2
                                        WHEN bil_fallback.image_type = 'large' THEN 3
                                        WHEN bil_fallback.image_type = 'medium' THEN 4
                                        ELSE 5
                                    END,
                                    bil_fallback.height DESC NULLS LAST,
                                    bil_fallback.width DESC NULLS LAST,
                                    bil_fallback.created_at DESC
                                LIMIT 1
                            ),
                            bil_meta.url
                        ) AS cover_fallback_url,
                        bil_meta.width,
                        bil_meta.height,
                        COALESCE(bil_meta.is_high_resolution, false) AS is_high_resolution,
                        CASE
                            WHEN bil_meta.s3_image_path IS NOT NULL AND COALESCE(bil_meta.is_high_resolution, false) THEN 0
                            WHEN bil_meta.s3_image_path IS NOT NULL THEN 1
                            WHEN bil_meta.url LIKE '%edge=curl%' THEN 5
                            WHEN COALESCE(bil_meta.is_high_resolution, false) THEN 10
                            WHEN bil_meta.width >= 320 AND bil_meta.height >= 320 THEN 11
                            WHEN bil_meta.image_type = 'extraLarge' THEN 12
                            WHEN bil_meta.image_type = 'large' THEN 13
                            WHEN bil_meta.image_type = 'medium' THEN 14
                            WHEN bil_meta.image_type = 'small' THEN 15
                            WHEN bil_meta.image_type = 'thumbnail' THEN 16
                            ELSE 20
                        END AS cover_priority,
                        COALESCE(bil_meta.height, 0) AS height_value,
                        COALESCE(bil_meta.width, 0) AS width_value,
                        bil_meta.created_at AS created_value
                    FROM book_image_links bil_meta
                    WHERE bil_meta.book_id = b.id
                      AND bil_meta.download_error IS NULL
                      AND bil_meta.width >= 180
                      AND bil_meta.height >= 280
                      AND (bil_meta.height::float / NULLIF(bil_meta.width, 0)) BETWEEN 1.2 AND 2.0
                      AND bil_meta.url NOT LIKE '%printsec=titlepage%'
                      AND bil_meta.url NOT LIKE '%printsec=copyright%'
                      AND bil_meta.url NOT LIKE '%printsec=toc%'
                    ORDER BY
                        CASE
                            WHEN bil_meta.s3_image_path IS NOT NULL AND COALESCE(bil_meta.is_high_resolution, false) THEN 0
                            WHEN bil_meta.s3_image_path IS NOT NULL THEN 1
                            WHEN bil_meta.url LIKE '%edge=curl%' THEN 5
                            WHEN COALESCE(bil_meta.is_high_resolution, false) THEN 10
                            WHEN bil_meta.width >= 320 AND bil_meta.height >= 320 THEN 11
                            WHEN bil_meta.image_type = 'extraLarge' THEN 12
                            WHEN bil_meta.image_type = 'large' THEN 13
                            WHEN bil_meta.image_type = 'medium' THEN 14
                            WHEN bil_meta.image_type = 'small' THEN 15
                            WHEN bil_meta.image_type = 'thumbnail' THEN 16
                            ELSE 20
                        END,
                        bil_meta.height DESC NULLS LAST,
                        bil_meta.width DESC NULLS LAST,
                        bil_meta.created_at DESC
                    LIMIT 1
                ) strict
                UNION ALL
                SELECT relaxed_inner.cover_url,
                       relaxed_inner.cover_s3_key,
                       relaxed_inner.cover_fallback_url,
                       relaxed_inner.width,
                       relaxed_inner.height,
                       relaxed_inner.is_high_resolution,
                       relaxed_inner.cover_priority,
                       relaxed_inner.height_value,
                       relaxed_inner.width_value,
                       relaxed_inner.created_value,
                       1 AS quality_rank
                FROM (
                    SELECT
                        COALESCE(bil_relaxed.s3_image_path, bil_relaxed.url) AS cover_url,
                        bil_relaxed.s3_image_path AS cover_s3_key,
                        NULLIF(bil_relaxed.url, '') AS cover_fallback_url,
                        bil_relaxed.width,
                        bil_relaxed.height,
                        COALESCE(bil_relaxed.is_high_resolution, false) AS is_high_resolution,
                        CASE
                            WHEN bil_relaxed.s3_image_path IS NOT NULL AND COALESCE(bil_relaxed.is_high_resolution, false) THEN 0
                            WHEN bil_relaxed.s3_image_path IS NOT NULL THEN 1
                            WHEN bil_relaxed.url LIKE '%edge=curl%' THEN 5
                            WHEN COALESCE(bil_relaxed.is_high_resolution, false) THEN 10
                            WHEN bil_relaxed.width >= 320 AND bil_relaxed.height >= 320 THEN 11
                            WHEN bil_relaxed.image_type = 'extraLarge' THEN 12
                            WHEN bil_relaxed.image_type = 'large' THEN 13
                            WHEN bil_relaxed.image_type = 'medium' THEN 14
                            WHEN bil_relaxed.image_type = 'small' THEN 15
                            WHEN bil_relaxed.image_type = 'thumbnail' THEN 16
                            ELSE 20
                        END AS cover_priority,
                        COALESCE(bil_relaxed.height, 0) AS height_value,
                        COALESCE(bil_relaxed.width, 0) AS width_value,
                        bil_relaxed.created_at AS created_value
                    FROM book_image_links bil_relaxed
                    WHERE bil_relaxed.book_id = b.id
                      AND bil_relaxed.download_error IS NULL
                    ORDER BY
                        COALESCE((bil_relaxed.width::bigint * bil_relaxed.height::bigint), 0) DESC,
                        bil_relaxed.created_at DESC
                    LIMIT 1
                ) relaxed_inner
            ) chosen
            ORDER BY
                chosen.quality_rank,
                chosen.cover_priority,
                chosen.height_value DESC,
                chosen.width_value DESC,
                chosen.created_value DESC
            LIMIT 1
        ) cover_meta ON TRUE
        GROUP BY input_ids.ord, b.id, b.slug, b.title, b.description,
                 bei.average_rating, bei.ratings_count,
                 cover_meta.cover_url, cover_meta.cover_s3_key, cover_meta.cover_fallback_url,
                 cover_meta.width, cover_meta.height, cover_meta.is_high_resolution
    )
    SELECT
        list_data.id,
        list_data.slug,
        list_data.title,
        list_data.description,
        list_data.authors,
        list_data.categories,
        list_data.cover_url,
        list_data.cover_s3_key,
        list_data.cover_fallback_url,
        list_data.cover_width,
        list_data.cover_height,
        list_data.cover_is_high_resolution,
        list_data.average_rating,
        list_data.ratings_count,
        list_data.tags
    FROM list_data
    ORDER BY list_data.ord;
END;
$$ LANGUAGE plpgsql STABLE;

COMMENT ON FUNCTION get_book_list_items IS 'Optimized query for book list view - prefers high-quality covers (S3 or 180x280 edge=curl) but gracefully falls back to the best available image when none meet the strict rules';

DROP FUNCTION IF EXISTS get_book_detail(UUID);

-- ============================================================================
-- FUNCTION: get_book_detail
-- Purpose: Fetch complete book detail for detail page
-- Excludes: dimensions, rawPayload (never rendered in template)
-- Performance: Single query vs. 6-10 queries per book
-- ============================================================================
CREATE OR REPLACE FUNCTION get_book_detail(book_id_param UUID)
RETURNS TABLE (
    id UUID,
    slug TEXT,
    title TEXT,
    description TEXT,
    publisher TEXT,
    published_date DATE,
    language TEXT,
    page_count INTEGER,
    authors TEXT[],
    categories TEXT[],
    cover_url TEXT,
    cover_s3_key TEXT,
    cover_fallback_url TEXT,
    thumbnail_url TEXT,
    cover_width INTEGER,
    cover_height INTEGER,
    cover_is_high_resolution BOOLEAN,
    data_source TEXT,
    average_rating NUMERIC,
    ratings_count INTEGER,
    isbn_10 TEXT,
    isbn_13 TEXT,
    preview_link TEXT,
    info_link TEXT,
    tags JSONB
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        b.id,
        b.slug,
        b.title,
        b.description,
        b.publisher,
        b.published_date,
        b.language,
        b.page_count,
        -- Aggregate authors in canonical order (position ascending, then alpha)
        COALESCE(
            (
                SELECT array_agg(ordered_authors.author_name ORDER BY ordered_authors.author_position, ordered_authors.author_name)
                FROM (
                    SELECT
                        a_inner.id AS author_id,
                        a_inner.name AS author_name,
                        MIN(COALESCE(baj_inner.position, 9999)) AS author_position
                    FROM book_authors_join baj_inner
                    JOIN authors a_inner ON a_inner.id = baj_inner.author_id
                    WHERE baj_inner.book_id = b.id
                    GROUP BY a_inner.id, a_inner.name
                ) ordered_authors
            ),
            ARRAY[]::TEXT[]
        ) as authors,
        -- Aggregate categories
        COALESCE(
            (
                SELECT ARRAY(
                    SELECT DISTINCT bc_inner.display_name
                    FROM book_collections_join bcj_inner
                    JOIN book_collections bc_inner ON bc_inner.id = bcj_inner.collection_id
                    WHERE bcj_inner.book_id = b.id
                      AND bc_inner.display_name IS NOT NULL
                      AND bc_inner.collection_type = 'CATEGORY'
                    ORDER BY bc_inner.display_name
                )
            ),
            ARRAY[]::TEXT[]
        ) as categories,
        cover_meta.cover_url as cover_url,
        cover_meta.cover_s3_key as cover_s3_key,
        cover_meta.cover_fallback_url as cover_fallback_url,
        -- Get thumbnail for smaller displays
        (SELECT bil.url
         FROM book_image_links bil
         WHERE bil.book_id = b.id
         ORDER BY CASE bil.image_type
                   WHEN 'thumbnail' THEN 1
                   WHEN 'medium' THEN 2
                   WHEN 'smallThumbnail' THEN 3
                   WHEN 'small' THEN 4
                   WHEN 'external' THEN 5
                   WHEN 'large' THEN 6
                   WHEN 'extraLarge' THEN 7
                   ELSE 8
                  END
         LIMIT 1) as thumbnail_url,
        cover_meta.width as cover_width,
        cover_meta.height as cover_height,
        cover_meta.is_high_resolution as cover_is_high_resolution,
        CASE
            WHEN EXISTS (
                SELECT 1
                FROM book_collections_join bcj_nyt
                JOIN book_collections bc_nyt ON bc_nyt.id = bcj_nyt.collection_id
                WHERE bcj_nyt.book_id = b.id
                  AND bc_nyt.source = 'NYT'
                LIMIT 1
            ) THEN 'NYT'
            ELSE COALESCE(provider_source.source, 'POSTGRES')
        END as data_source,
        bei.average_rating,
        bei.ratings_count,
        b.isbn10 as isbn_10,
        b.isbn13 as isbn_13,
        bei.preview_link,
        bei.info_link,
        -- Aggregate tags
        COALESCE(
            (SELECT jsonb_object_agg(bt.key, bta.metadata)
             FROM book_tag_assignments bta
             JOIN book_tags bt ON bt.id = bta.tag_id
             WHERE bta.book_id = b.id),
            '{}'::JSONB
        ) as tags
    FROM books b
    LEFT JOIN book_collections_join bcj ON bcj.book_id = b.id
    LEFT JOIN book_collections bc ON bc.id = bcj.collection_id
    LEFT JOIN book_external_ids bei ON bei.book_id = b.id AND bei.source = 'GOOGLE_BOOKS'
    LEFT JOIN LATERAL (
        SELECT source
        FROM book_external_ids bei_primary
        WHERE bei_primary.book_id = b.id
        ORDER BY CASE bei_primary.source
                    WHEN 'GOOGLE_BOOKS' THEN 0
                    WHEN 'OPEN_LIBRARY' THEN 1
                    WHEN 'AMAZON' THEN 2
                    ELSE 3
                 END,
                 bei_primary.external_id
        LIMIT 1
    ) provider_source ON TRUE
    LEFT JOIN LATERAL (
        SELECT coalesce(bil_meta.s3_image_path, bil_meta.url) as cover_url,
               bil_meta.s3_image_path as cover_s3_key,
               NULLIF(bil_meta.url, '') as cover_fallback_url,
               bil_meta.width,
               bil_meta.height,
               bil_meta.is_high_resolution
        FROM book_image_links bil_meta
        WHERE bil_meta.book_id = b.id
          AND bil_meta.download_error IS NULL
        ORDER BY
            CASE
                WHEN bil_meta.s3_image_path IS NOT NULL AND bil_meta.is_high_resolution THEN 0
                WHEN bil_meta.s3_image_path IS NOT NULL THEN 1
                WHEN bil_meta.is_high_resolution THEN 2
                WHEN bil_meta.width >= 320 AND bil_meta.height >= 320 THEN 3
                WHEN bil_meta.image_type = 'extraLarge' THEN 4
                WHEN bil_meta.image_type = 'large' THEN 5
                WHEN bil_meta.image_type = 'medium' THEN 6
                WHEN bil_meta.image_type = 'small' THEN 7
                WHEN bil_meta.image_type = 'thumbnail' THEN 8
                ELSE 9
            END,
            bil_meta.height DESC NULLS LAST,
            bil_meta.width DESC NULLS LAST,
            bil_meta.created_at DESC
        LIMIT 1
    ) cover_meta ON TRUE
    WHERE b.id = book_id_param
    GROUP BY b.id, b.slug, b.title, b.description, b.publisher, b.published_date,
             b.language, b.page_count, b.isbn10, b.isbn13,
             bei.average_rating, bei.ratings_count, bei.preview_link, bei.info_link,
             cover_meta.cover_url, cover_meta.cover_s3_key, cover_meta.cover_fallback_url,
             cover_meta.width, cover_meta.height, cover_meta.is_high_resolution,
             provider_source.source;
END;
$$ LANGUAGE plpgsql STABLE;

COMMENT ON FUNCTION get_book_detail IS 'Optimized query for book detail page - single query replaces 6-10 separate hydration queries';

-- ============================================================================
-- FUNCTION: get_book_editions
-- Purpose: Fetch other editions of a book (for Editions tab)
-- Note: Only loaded when editions tab is accessed (lazy loading)
-- Performance: Single query for all editions
-- ============================================================================
CREATE OR REPLACE FUNCTION get_book_editions(book_id_param UUID)
RETURNS TABLE (
    id UUID,
    slug TEXT,
    title TEXT,
    published_date DATE,
    publisher TEXT,
    isbn_13 TEXT,
    cover_url TEXT,
    language TEXT,
    page_count INTEGER
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        b.id,
        b.slug,
        b.title,
        b.published_date,
        b.publisher,
        b.isbn13 as isbn_13,
        edition_cover.cover_url,
        b.language,
        b.page_count
    FROM books b
    JOIN work_cluster_members wcm ON wcm.book_id = b.id
    LEFT JOIN LATERAL (
        SELECT COALESCE(bil.s3_image_path, bil.url) AS cover_url
        FROM book_image_links bil
        WHERE bil.book_id = b.id
          AND bil.download_error IS NULL
        ORDER BY
            CASE
                WHEN bil.s3_image_path IS NOT NULL AND bil.is_high_resolution THEN 0
                WHEN bil.s3_image_path IS NOT NULL THEN 1
                WHEN bil.is_high_resolution THEN 2
                WHEN bil.width >= 320 AND bil.height >= 320 THEN 3
                WHEN bil.image_type = 'extraLarge' THEN 4
                WHEN bil.image_type = 'large' THEN 5
                WHEN bil.image_type = 'medium' THEN 6
                WHEN bil.image_type = 'small' THEN 7
                WHEN bil.image_type = 'thumbnail' THEN 8
                ELSE 9
            END,
            bil.height DESC NULLS LAST,
            bil.width DESC NULLS LAST,
            bil.created_at DESC
        LIMIT 1
    ) edition_cover ON TRUE
    WHERE wcm.cluster_id IN (
        SELECT cluster_id
        FROM work_cluster_members
        WHERE book_id = book_id_param
    )
    AND b.id != book_id_param  -- Exclude the current book itself
    ORDER BY b.published_date DESC NULLS LAST, b.created_at DESC
    LIMIT 20;  -- Reasonable limit for editions display
END;
$$ LANGUAGE plpgsql STABLE;

COMMENT ON FUNCTION get_book_editions IS 'Optimized query for book editions - fetches related editions from work clusters';

-- ============================================================================
-- HELPER FUNCTION: get_book_cards_by_collection
-- Purpose: Convenience function to fetch book cards for a specific collection
-- Used by: Homepage for bestseller lists, category browsing
-- ============================================================================
DROP FUNCTION IF EXISTS get_book_cards_by_collection(TEXT, INTEGER);

CREATE OR REPLACE FUNCTION get_book_cards_by_collection(
    collection_id_param TEXT,
    limit_param INTEGER DEFAULT 12
)
RETURNS TABLE (
    id UUID,
    slug TEXT,
    title TEXT,
    authors TEXT[],
    cover_url TEXT,
    cover_s3_key TEXT,
    cover_fallback_url TEXT,
    average_rating NUMERIC,
    ratings_count INTEGER,
    tags JSONB
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        bc.id,
        bc.slug,
        bc.title,
        bc.authors,
        bc.cover_url,
        bc.cover_s3_key,
        bc.cover_fallback_url,
        bc.average_rating,
        bc.ratings_count,
        bc.tags
    FROM book_collections_join bcj
    CROSS JOIN LATERAL get_book_cards(ARRAY[bcj.book_id]) AS bc(
        id,
        slug,
        title,
        authors,
        cover_url,
        cover_s3_key,
        cover_fallback_url,
        average_rating,
        ratings_count,
        tags
    )
    WHERE bcj.collection_id = collection_id_param
    ORDER BY COALESCE(bcj.position, 2147483647), bcj.created_at ASC
    LIMIT limit_param;
END;
$$ LANGUAGE plpgsql STABLE;

COMMENT ON FUNCTION get_book_cards_by_collection IS 'Fetch book cards for a specific collection (bestseller list, category, etc.)';

-- ============================================================================
-- INDEXES FOR OPTIMAL PERFORMANCE
-- Ensure these indexes exist for the query functions to perform well
-- ============================================================================

-- book_authors_join indexes
CREATE INDEX IF NOT EXISTS idx_book_authors_book_id ON book_authors_join(book_id);
CREATE INDEX IF NOT EXISTS idx_book_authors_position ON book_authors_join(book_id, position) WHERE position IS NOT NULL;

-- book_collections_join indexes
CREATE INDEX IF NOT EXISTS idx_book_collections_join_book_id ON book_collections_join(book_id);
CREATE INDEX IF NOT EXISTS idx_book_collections_join_collection_id ON book_collections_join(collection_id);
CREATE INDEX IF NOT EXISTS idx_book_collections_join_position ON book_collections_join(collection_id, position) WHERE position IS NOT NULL;

-- book_image_links indexes
CREATE INDEX IF NOT EXISTS idx_book_image_links_book_id ON book_image_links(book_id);
CREATE INDEX IF NOT EXISTS idx_book_image_links_type_priority ON book_image_links(book_id, image_type);

-- book_tag_assignments indexes
CREATE INDEX IF NOT EXISTS idx_book_tag_assignments_book_id ON book_tag_assignments(book_id);
CREATE INDEX IF NOT EXISTS idx_book_tag_assignments_tag_id ON book_tag_assignments(tag_id);

-- book_external_ids indexes (already exist but verify)
CREATE INDEX IF NOT EXISTS idx_book_external_ids_book_source ON book_external_ids(book_id, source);

-- work_cluster_members indexes
CREATE INDEX IF NOT EXISTS idx_work_cluster_members_book_id ON work_cluster_members(book_id);
CREATE INDEX IF NOT EXISTS idx_work_cluster_members_cluster_id ON work_cluster_members(cluster_id);

-- ============================================================================
-- VERIFICATION QUERIES
-- Run these to verify the functions work correctly
-- ============================================================================

-- Test get_book_cards with a few books
-- SELECT * FROM get_book_cards(ARRAY[
--     (SELECT id FROM books LIMIT 1)::UUID,
--     (SELECT id FROM books OFFSET 1 LIMIT 1)::UUID
-- ]);

-- Test get_book_detail
-- SELECT * FROM get_book_detail((SELECT id FROM books LIMIT 1)::UUID);

-- Test get_book_cards_by_collection (if you have collections)
-- SELECT * FROM get_book_cards_by_collection(
--     (SELECT id FROM book_collections WHERE collection_type = 'BESTSELLER_LIST' LIMIT 1),
--     8
-- );

-- ============================================================================
-- END OF OPTIMIZED BOOK QUERY FUNCTIONS
-- ============================================================================
