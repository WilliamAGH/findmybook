package net.findmybook.support.sitemap;

/**
 * Package-private SQL builder for sitemap book last-modified projections.
 *
 * <p>This helper centralizes the joined-data timestamp logic so sitemap repository
 * methods reuse one canonical definition for book-level {@code lastmod} values.</p>
 */
public final class SitemapBookLastModifiedSqlSupport {

    private SitemapBookLastModifiedSqlSupport() {
    }

    public static String globalBookLastModifiedCte(String bookUpdatedAtAlias) {
        return """
                WITH change_events AS (
                    SELECT b.id AS book_id,
                           GREATEST(
                               COALESCE(b.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(b.created_at, TIMESTAMP 'epoch')
                           ) AS changed_at
                    FROM books b
                    WHERE b.slug IS NOT NULL
                    UNION ALL
                    SELECT be.book_id,
                           GREATEST(
                               COALESCE(be.last_updated, TIMESTAMP 'epoch'),
                               COALESCE(be.created_at, TIMESTAMP 'epoch')
                           )
                    FROM book_external_ids be
                    UNION ALL
                    SELECT br.book_id,
                           GREATEST(
                               COALESCE(br.fetched_at, TIMESTAMP 'epoch'),
                               COALESCE(br.contributed_at, TIMESTAMP 'epoch'),
                               COALESCE(br.created_at, TIMESTAMP 'epoch')
                           )
                    FROM book_raw_data br
                    UNION ALL
                    SELECT bil.book_id,
                           GREATEST(
                               COALESCE(bil.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(bil.s3_uploaded_at, TIMESTAMP 'epoch'),
                               COALESCE(bil.created_at, TIMESTAMP 'epoch')
                           )
                    FROM book_image_links bil
                    UNION ALL
                    SELECT bd.book_id,
                           GREATEST(
                               COALESCE(bd.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(bd.created_at, TIMESTAMP 'epoch')
                           )
                    FROM book_dimensions bd
                    UNION ALL
                    SELECT bta.book_id,
                           GREATEST(
                               COALESCE(bta.created_at, TIMESTAMP 'epoch'),
                               COALESCE(bt.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(bt.created_at, TIMESTAMP 'epoch')
                           )
                    FROM book_tag_assignments bta
                    JOIN book_tags bt ON bt.id = bta.tag_id
                    UNION ALL
                    SELECT baj.book_id,
                           GREATEST(
                               COALESCE(baj.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(baj.created_at, TIMESTAMP 'epoch'),
                               COALESCE(a.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(a.created_at, TIMESTAMP 'epoch')
                           )
                    FROM book_authors_join baj
                    JOIN authors a ON a.id = baj.author_id
                    UNION ALL
                    SELECT bcj.book_id,
                           GREATEST(
                               COALESCE(bcj.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(bcj.created_at, TIMESTAMP 'epoch'),
                               COALESCE(bcj.added_at, TIMESTAMP 'epoch'),
                               COALESCE(bc.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(bc.created_at, TIMESTAMP 'epoch')
                           )
                    FROM book_collections_join bcj
                    JOIN book_collections bc ON bc.id = bcj.collection_id
                    UNION ALL
                    SELECT bac.book_id,
                           COALESCE(bac.created_at, TIMESTAMP 'epoch')
                    FROM book_ai_content bac
                    UNION ALL
                    SELECT bsm.book_id,
                           COALESCE(bsm.created_at, TIMESTAMP 'epoch')
                    FROM book_seo_metadata bsm
                    UNION ALL
                    SELECT bsr.book_id,
                           COALESCE(bsr.created_at, TIMESTAMP 'epoch')
                    FROM book_slug_redirect bsr
                ),
                book_last_modified AS (
                    SELECT b.id,
                           b.slug,
                           b.title,
                           MAX(change_events.changed_at) AS %s
                    FROM books b
                    LEFT JOIN change_events ON change_events.book_id = b.id
                    WHERE b.slug IS NOT NULL
                    GROUP BY b.id, b.slug, b.title
                )
                """.formatted(bookUpdatedAtAlias);
    }

    public static String scopedAuthorBookLastModifiedQuery(String authorPlaceholders, String bookUpdatedAtAlias) {
        return """
                WITH requested_authors AS (
                    SELECT baj.author_id
                    FROM book_authors_join baj
                    WHERE baj.author_id IN (%s)
                    GROUP BY baj.author_id
                ),
                requested_books AS (
                    SELECT DISTINCT baj.book_id
                    FROM book_authors_join baj
                    JOIN requested_authors ra ON ra.author_id = baj.author_id
                ),
                change_events AS (
                    SELECT b.id AS book_id,
                           GREATEST(
                               COALESCE(b.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(b.created_at, TIMESTAMP 'epoch')
                           ) AS changed_at
                    FROM books b
                    JOIN requested_books rb ON rb.book_id = b.id
                    WHERE b.slug IS NOT NULL
                    UNION ALL
                    SELECT be.book_id,
                           GREATEST(
                               COALESCE(be.last_updated, TIMESTAMP 'epoch'),
                               COALESCE(be.created_at, TIMESTAMP 'epoch')
                           )
                    FROM book_external_ids be
                    JOIN requested_books rb ON rb.book_id = be.book_id
                    UNION ALL
                    SELECT br.book_id,
                           GREATEST(
                               COALESCE(br.fetched_at, TIMESTAMP 'epoch'),
                               COALESCE(br.contributed_at, TIMESTAMP 'epoch'),
                               COALESCE(br.created_at, TIMESTAMP 'epoch')
                           )
                    FROM book_raw_data br
                    JOIN requested_books rb ON rb.book_id = br.book_id
                    UNION ALL
                    SELECT bil.book_id,
                           GREATEST(
                               COALESCE(bil.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(bil.s3_uploaded_at, TIMESTAMP 'epoch'),
                               COALESCE(bil.created_at, TIMESTAMP 'epoch')
                           )
                    FROM book_image_links bil
                    JOIN requested_books rb ON rb.book_id = bil.book_id
                    UNION ALL
                    SELECT bd.book_id,
                           GREATEST(
                               COALESCE(bd.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(bd.created_at, TIMESTAMP 'epoch')
                           )
                    FROM book_dimensions bd
                    JOIN requested_books rb ON rb.book_id = bd.book_id
                    UNION ALL
                    SELECT bta.book_id,
                           GREATEST(
                               COALESCE(bta.created_at, TIMESTAMP 'epoch'),
                               COALESCE(bt.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(bt.created_at, TIMESTAMP 'epoch')
                           )
                    FROM book_tag_assignments bta
                    JOIN requested_books rb ON rb.book_id = bta.book_id
                    JOIN book_tags bt ON bt.id = bta.tag_id
                    UNION ALL
                    SELECT baj.book_id,
                           GREATEST(
                               COALESCE(baj.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(baj.created_at, TIMESTAMP 'epoch'),
                               COALESCE(a.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(a.created_at, TIMESTAMP 'epoch')
                           )
                    FROM book_authors_join baj
                    JOIN requested_books rb ON rb.book_id = baj.book_id
                    JOIN authors a ON a.id = baj.author_id
                    UNION ALL
                    SELECT bcj.book_id,
                           GREATEST(
                               COALESCE(bcj.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(bcj.created_at, TIMESTAMP 'epoch'),
                               COALESCE(bcj.added_at, TIMESTAMP 'epoch'),
                               COALESCE(bc.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(bc.created_at, TIMESTAMP 'epoch')
                           )
                    FROM book_collections_join bcj
                    JOIN requested_books rb ON rb.book_id = bcj.book_id
                    JOIN book_collections bc ON bc.id = bcj.collection_id
                    UNION ALL
                    SELECT bac.book_id,
                           COALESCE(bac.created_at, TIMESTAMP 'epoch')
                    FROM book_ai_content bac
                    JOIN requested_books rb ON rb.book_id = bac.book_id
                    UNION ALL
                    SELECT bsm.book_id,
                           COALESCE(bsm.created_at, TIMESTAMP 'epoch')
                    FROM book_seo_metadata bsm
                    JOIN requested_books rb ON rb.book_id = bsm.book_id
                    UNION ALL
                    SELECT bsr.book_id,
                           COALESCE(bsr.created_at, TIMESTAMP 'epoch')
                    FROM book_slug_redirect bsr
                    JOIN requested_books rb ON rb.book_id = bsr.book_id
                ),
                book_last_modified AS (
                    SELECT b.id,
                           b.slug,
                           b.title,
                           MAX(change_events.changed_at) AS %s
                    FROM books b
                    JOIN requested_books rb ON rb.book_id = b.id
                    LEFT JOIN change_events ON change_events.book_id = b.id
                    WHERE b.slug IS NOT NULL
                    GROUP BY b.id, b.slug, b.title
                )
                SELECT baj.author_id, blm.id, blm.slug, blm.title, blm.%s
                FROM book_authors_join baj
                JOIN requested_authors ra ON ra.author_id = baj.author_id
                JOIN book_last_modified blm ON blm.id = baj.book_id
                ORDER BY baj.author_id, lower(blm.title), blm.slug
                """.formatted(authorPlaceholders, bookUpdatedAtAlias, bookUpdatedAtAlias);
    }
}
