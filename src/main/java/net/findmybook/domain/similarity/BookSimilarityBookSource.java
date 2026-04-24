package net.findmybook.domain.similarity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.util.StringUtils;

/**
 * Canonical book data surface used to render similarity embedding sections.
 *
 * <p>This record is the single owner of which book fields participate in each
 * section, so profile files only tune weights and ordering.</p>
 */
public record BookSimilarityBookSource(
    UUID bookId,
    String title,
    String subtitle,
    String authors,
    String classificationTags,
    String collectionCategories,
    String description,
    String aiSummary,
    String aiReaderFit,
    String aiKeyThemes,
    String aiTakeaways,
    String aiContext,
    String publisher,
    String publishedYear,
    String pageCount,
    String language,
    String averageRating,
    String ratingsCount
) {

    /**
     * Renders one weighted section as stable key-value lines.
     *
     * @param sectionKey section being rendered
     * @return key-value text, or an empty string when no section values exist
     */
    public String renderSection(BookSimilaritySectionKey sectionKey) {
        List<String> lines = new ArrayList<>();
        switch (sectionKey) {
            case IDENTITY -> {
                addLine(lines, "title", title);
                addLine(lines, "subtitle", subtitle);
                addLine(lines, "authors", authors);
            }
            case CLASSIFICATION -> {
                addLine(lines, "classification_tags", classificationTags);
                addLine(lines, "collection_categories", collectionCategories);
            }
            case DESCRIPTION -> addLine(lines, "description", description);
            case AI_CONTENT -> {
                addLine(lines, "ai_summary", aiSummary);
                addLine(lines, "ai_reader_fit", aiReaderFit);
                addLine(lines, "ai_key_themes", aiKeyThemes);
                addLine(lines, "ai_takeaways", aiTakeaways);
                addLine(lines, "ai_context", aiContext);
            }
            case BIBLIOGRAPHIC -> {
                addLine(lines, "publisher", publisher);
                addLine(lines, "published_year", publishedYear);
                addLine(lines, "page_count", pageCount);
                addLine(lines, "language", language);
                addLine(lines, "average_rating", averageRating);
                addLine(lines, "ratings_count", ratingsCount);
            }
        }
        return String.join("\n", lines);
    }

    private static void addLine(List<String> lines, String key, String value) {
        if (StringUtils.hasText(value)) {
            lines.add(key + ": " + value.trim());
        }
    }
}
