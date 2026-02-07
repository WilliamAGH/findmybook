package net.findmybook.mapper;

import tools.jackson.databind.JsonNode;
import net.findmybook.dto.BookAggregate;
import net.findmybook.util.CategoryNormalizer;
import net.findmybook.util.DateParsingUtils;
import net.findmybook.util.IsbnUtils;
import net.findmybook.util.SlugGenerator;
import net.findmybook.util.TextUtils;
import org.springframework.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static net.findmybook.mapper.GoogleBooksJsonSupport.getIntValue;
import static net.findmybook.mapper.GoogleBooksJsonSupport.getTextValue;

/**
 * Maps Google Books Volume JSON to normalized BookAggregate.
 * <p>
 * Reference: <a href="https://developers.google.com/books/docs/v1/reference/volumes">Google Books Volume Resource</a>
 * <p>
 * Delegates JSON value extraction to {@link GoogleBooksJsonSupport} and
 * identifier construction to {@link GoogleBooksIdentifierExtractor}.
 */
@Component
@Slf4j
public class GoogleBooksMapper implements ExternalBookMapper {

    private static final String SOURCE_NAME = "GOOGLE_BOOKS";

    @Override
    public BookAggregate map(JsonNode json) {
        if (json == null || !json.has("volumeInfo")) {
            log.warn("Invalid Google Books JSON: missing volumeInfo");
            return null;
        }

        JsonNode volumeInfo = json.get("volumeInfo");
        JsonNode saleInfo = json.has("saleInfo") ? json.get("saleInfo") : null;
        JsonNode accessInfo = json.has("accessInfo") ? json.get("accessInfo") : null;

        // Extract primary ID
        String externalId = json.has("id") ? json.get("id").asText() : null;
        if (externalId == null) {
            log.warn("Google Books volume missing 'id' field");
            return null;
        }

        // Extract title (required)
        String title = getTextValue(volumeInfo, "title");
        if (title == null || title.isBlank()) {
            log.warn("Google Books volume {} missing title", externalId);
            return null;
        }

        // Extract ISBNs
        String isbn13 = extractIsbn(volumeInfo, "ISBN_13");
        String isbn10 = extractIsbn(volumeInfo, "ISBN_10");

        // Extract authors
        List<String> authors = extractAuthors(volumeInfo);

        // Extract categories
        List<String> categories = extractCategories(volumeInfo);

        // Build external identifiers (delegated to extractor)
        BookAggregate.ExternalIdentifiers identifiers = GoogleBooksIdentifierExtractor
            .buildExternalIdentifiers(externalId, volumeInfo, saleInfo, accessInfo, isbn10, isbn13);

        // Parse published date
        LocalDate publishedDate = parsePublishedDate(volumeInfo);

        // Generate slug base
        String slugBase = SlugGenerator.generateBookSlug(title, authors);

        // Extract dimensions
        BookAggregate.Dimensions dimensions = extractDimensions(volumeInfo);

        return BookAggregate.builder()
            .title(title)
            .subtitle(getTextValue(volumeInfo, "subtitle"))
            .description(getTextValue(volumeInfo, "description"))
            .isbn13(isbn13)
            .isbn10(isbn10)
            .publishedDate(publishedDate)
            .language(getTextValue(volumeInfo, "language"))
            .publisher(getTextValue(volumeInfo, "publisher"))
            .pageCount(getIntValue(volumeInfo, "pageCount"))
            .authors(authors)
            .categories(categories)
            .identifiers(identifiers)
            .slugBase(slugBase)
            .dimensions(dimensions)
            .editionNumber(null)  // TODO: Derive from title/subtitle/contentVersion
            // Task #6: editionGroupKey removed - replaced by work_clusters system
            .build();
    }

    @Override
    public String getSourceName() {
        return SOURCE_NAME;
    }

    /**
     * Extract ISBN of specific type from industryIdentifiers array.
     */
    private String extractIsbn(JsonNode volumeInfo, String type) {
        if (!volumeInfo.has("industryIdentifiers")) {
            return null;
        }

        JsonNode identifiers = volumeInfo.get("industryIdentifiers");
        if (!identifiers.isArray()) {
            return null;
        }

        for (JsonNode id : identifiers) {
            if (id.has("type") && type.equals(id.get("type").asText())) {
                String identifier = id.get("identifier").asText();
                String sanitized = IsbnUtils.sanitize(identifier);
                if (sanitized != null) {
                    return sanitized;
                }
            }
        }

        return null;
    }

    /**
     * Extract authors from volumeInfo.authors array with name normalization.
     */
    private List<String> extractAuthors(JsonNode volumeInfo) {
        List<String> authors = new ArrayList<>();

        if (!volumeInfo.has("authors") || !volumeInfo.get("authors").isArray()) {
            return authors;
        }

        JsonNode authorsNode = volumeInfo.get("authors");
        for (JsonNode authorNode : authorsNode) {
            String normalized = TextUtils.normalizeAuthorName(authorNode.asText(null));
            if (StringUtils.hasText(normalized)) {
                authors.add(normalized);
            }
        }

        return authors;
    }

    /**
     * Extract and normalize categories from volumeInfo.categories array.
     *
     * <p>Uses {@link CategoryNormalizer#normalizeAndDeduplicate(List)} to split compound
     * categories, remove duplicates, and filter invalid entries.</p>
     */
    private List<String> extractCategories(JsonNode volumeInfo) {
        List<String> rawCategories = new ArrayList<>();

        if (!volumeInfo.has("categories") || !volumeInfo.get("categories").isArray()) {
            return rawCategories;
        }

        JsonNode categoriesNode = volumeInfo.get("categories");
        for (JsonNode categoryNode : categoriesNode) {
            String category = categoryNode.asText();
            if (category != null && !category.isBlank()) {
                rawCategories.add(category.trim());
            }
        }

        return CategoryNormalizer.normalizeAndDeduplicate(rawCategories);
    }

    /**
     * Parse published date from various Google Books formats.
     * Handles: "2023", "2023-05", "2023-05-15"
     */
    private LocalDate parsePublishedDate(JsonNode volumeInfo) {
        if (!volumeInfo.has("publishedDate")) {
            return null;
        }

        String dateStr = volumeInfo.get("publishedDate").asText();
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }

        try {
            LocalDate date = DateParsingUtils.parseIsoLocalDate(dateStr);
            if (date != null) {
                return date;
            }

            // Handle year-only format
            if (dateStr.matches("\\d{4}")) {
                return LocalDate.of(Integer.parseInt(dateStr), 1, 1);
            }

            // Handle year-month format (e.g., "2023-05")
            if (dateStr.matches("\\d{4}-\\d{2}")) {
                String[] parts = dateStr.split("-");
                return LocalDate.of(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    1
                );
            }

            log.debug("Unable to parse publishedDate: {}", dateStr);
            return null;
        } catch (RuntimeException e) {
            log.debug("Error parsing publishedDate '{}': {}", dateStr, e.getMessage());
            return null;
        }
    }

    /**
     * Extract dimensions from volumeInfo.dimensions per official Google Books API schema.
     * Per https://developers.google.com/books/docs/v1/reference/volumes:
     * dimensions.height, dimensions.width, dimensions.thickness (all in cm).
     */
    private BookAggregate.Dimensions extractDimensions(JsonNode volumeInfo) {
        if (!volumeInfo.has("dimensions")) {
            return null;
        }

        JsonNode dimensionsNode = volumeInfo.get("dimensions");
        if (!dimensionsNode.isObject()) {
            return null;
        }

        String height = getTextValue(dimensionsNode, "height");
        String width = getTextValue(dimensionsNode, "width");
        String thickness = getTextValue(dimensionsNode, "thickness");

        if (height == null && width == null && thickness == null) {
            return null;
        }

        return BookAggregate.Dimensions.builder()
            .height(height)
            .width(width)
            .thickness(thickness)
            .build();
    }
}
