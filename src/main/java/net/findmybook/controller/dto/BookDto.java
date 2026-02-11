package net.findmybook.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * Canonical API representation of a book assembled from Postgres-backed data.
 */
public record BookDto(String id,
                      String slug,
                      String title,
                      String description,
                      PublicationDto publication,
                      List<AuthorDto> authors,
                      List<String> categories,
                      List<CollectionDto> collections,
                      List<TagDto> tags,
                      CoverDto cover,
                      List<EditionDto> editions,
                      List<String> recommendationIds,
                      Map<String, Object> extras,
                      DescriptionContent descriptionContent,
                      BookAiContentSnapshotDto aiContent,
                      ViewMetricsDto viewMetrics) {
    /**
     * Canonical description payload formatted on the backend for deterministic client rendering.
     *
     * @param raw    original provider/database value before transformation
     * @param format detected source format for {@code raw}
     * @param html   sanitized HTML representation suitable for direct rendering
     * @param text   plain-text representation for snippets and metadata
     */
    public record DescriptionContent(String raw,
                                     DescriptionFormat format,
                                     String html,
                                     String text) {
        public DescriptionContent {
            format = format == null ? DescriptionFormat.UNKNOWN : format;
            html = html == null ? "" : html;
            text = text == null ? "" : text;
        }
    }

    /**
     * Declares which source syntax a raw description most likely used.
     */
    public enum DescriptionFormat {
        HTML,
        MARKDOWN,
        PLAIN_TEXT,
        UNKNOWN
    }

    /**
     * Optional view-count metrics for the current detail request window.
     *
     * @param window query window selector ({@code 30d}, {@code 90d}, {@code all})
     * @param totalViews total recorded views for the selected window
     */
    public record ViewMetricsDto(String window, long totalViews) {
    }

    /**
     * Backward-compatible constructor that omits detail-page view metrics.
     */
    public BookDto(String id,
                   String slug,
                   String title,
                   String description,
                   PublicationDto publication,
                   List<AuthorDto> authors,
                   List<String> categories,
                   List<CollectionDto> collections,
                   List<TagDto> tags,
                   CoverDto cover,
                   List<EditionDto> editions,
                   List<String> recommendationIds,
                   Map<String, Object> extras,
                   DescriptionContent descriptionContent,
                   BookAiContentSnapshotDto aiContent) {
        this(
            id,
            slug,
            title,
            description,
            publication,
            authors,
            categories,
            collections,
            tags,
            cover,
            editions,
            recommendationIds,
            extras,
            descriptionContent,
            aiContent,
            null
        );
    }

    /**
     * Returns a copy with an updated AI snapshot while preserving all existing fields.
     */
    public BookDto withAiContent(BookAiContentSnapshotDto aiContentSnapshot) {
        return new BookDto(
            id,
            slug,
            title,
            description,
            publication,
            authors,
            categories,
            collections,
            tags,
            cover,
            editions,
            recommendationIds,
            extras,
            descriptionContent,
            aiContentSnapshot,
            viewMetrics
        );
    }

    /**
     * Returns a copy with updated detail-page view metrics.
     */
    public BookDto withViewMetrics(ViewMetricsDto metrics) {
        return new BookDto(
            id,
            slug,
            title,
            description,
            publication,
            authors,
            categories,
            collections,
            tags,
            cover,
            editions,
            recommendationIds,
            extras,
            descriptionContent,
            aiContent,
            metrics
        );
    }
}
