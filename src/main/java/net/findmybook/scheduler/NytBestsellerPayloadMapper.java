package net.findmybook.scheduler;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import net.findmybook.dto.BookAggregate;
import net.findmybook.util.DateParsingUtils;
import net.findmybook.util.IsbnUtils;
import net.findmybook.util.SlugGenerator;
import net.findmybook.util.TextUtils;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Maps and normalizes NYT payload fields into internal ingest contracts.
 */
@Component
public class NytBestsellerPayloadMapper {

    private static final Logger log = LoggerFactory.getLogger(NytBestsellerPayloadMapper.class);
    private static final Pattern AUTHOR_AND_SEPARATOR_PATTERN = Pattern.compile("(?i)\\band\\b");
    private static final String AUTHOR_DELIMITER_PATTERN = "[,;&]";
    private final ObjectMapper objectMapper;

    public NytBestsellerPayloadMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Nullable
    public String firstNonEmptyText(@Nullable JsonNode node, String... fieldNames) {
        if (node == null) {
            return null;
        }
        for (String field : fieldNames) {
            if (node.hasNonNull(field)) {
                String value = node.get(field).asString();
                if (StringUtils.hasText(value)) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    @Nullable
    public String nullIfBlank(@Nullable String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    public String resolveNaturalListLabel(@Nullable String displayName,
                                          @Nullable String listName,
                                          @Nullable String listCode) {
        if (StringUtils.hasText(displayName)) {
            return displayName.trim();
        }
        if (StringUtils.hasText(listName)) {
            return listName.trim();
        }
        if (!StringUtils.hasText(listCode)) {
            return "Unknown";
        }
        return listCode.trim().replace('-', ' ');
    }

    @Nullable
    public String resolveNytIsbn13(JsonNode bookNode) {
        String primaryIsbn13 = sanitizeValidIsbn13(firstNonEmptyText(bookNode, "primary_isbn13"));
        if (primaryIsbn13 != null) {
            return primaryIsbn13;
        }
        return firstValidIsbnFromArray(bookNode, "isbn13", true);
    }

    @Nullable
    public String resolveNytIsbn10(JsonNode bookNode) {
        String primaryIsbn10 = sanitizeValidIsbn10(firstNonEmptyText(bookNode, "primary_isbn10"));
        if (primaryIsbn10 != null) {
            return primaryIsbn10;
        }
        return firstValidIsbnFromArray(bookNode, "isbn10", false);
    }

    @Nullable
    private String firstValidIsbnFromArray(JsonNode bookNode, String fieldName, boolean isbn13) {
        JsonNode isbnsNode = bookNode.path("isbns");
        if (!isbnsNode.isArray() || isbnsNode.isEmpty()) {
            return null;
        }
        for (JsonNode isbnNode : isbnsNode) {
            String candidate = firstNonEmptyText(isbnNode, fieldName);
            String sanitized = isbn13 ? sanitizeValidIsbn13(candidate) : sanitizeValidIsbn10(candidate);
            if (sanitized != null) {
                return sanitized;
            }
        }
        return null;
    }

    @Nullable
    private static String sanitizeValidIsbn13(@Nullable String candidate) {
        String sanitized = IsbnUtils.sanitize(candidate);
        if (!StringUtils.hasText(sanitized)) {
            return null;
        }
        return IsbnUtils.isValidIsbn13(sanitized) ? sanitized : null;
    }

    @Nullable
    private static String sanitizeValidIsbn10(@Nullable String candidate) {
        String sanitized = IsbnUtils.sanitize(candidate);
        if (!StringUtils.hasText(sanitized)) {
            return null;
        }
        return IsbnUtils.isValidIsbn10(sanitized) ? sanitized : null;
    }

    @Nullable
    public Integer calculatePeakPosition(JsonNode bookNode) {
        Integer peakPosition = null;
        JsonNode ranksHistory = bookNode.path("ranks_history");
        if (ranksHistory.isArray()) {
            for (JsonNode ranksHistoryNode : ranksHistory) {
                if (ranksHistoryNode.path("rank").isInt()) {
                    int rank = ranksHistoryNode.get("rank").asInt();
                    if (peakPosition == null || rank < peakPosition) {
                        peakPosition = rank;
                    }
                }
            }
        }
        if (peakPosition == null) {
            if (bookNode.path("rank").isInt()) {
                peakPosition = bookNode.get("rank").asInt();
            } else if (bookNode.path("rank_last_week").isInt()) {
                peakPosition = bookNode.get("rank_last_week").asInt();
            }
        }
        return peakPosition;
    }

    @Nullable
    public String serializeBookNode(JsonNode bookNode) {
        try {
            return objectMapper.writeValueAsString(bookNode);
        } catch (JacksonException exception) {
            log.error("Failed to serialize NYT book node: {}", exception.getMessage(), exception);
            return null;
        }
    }

    public BookAggregate buildBookAggregateFromNyt(JsonNode bookNode,
                                                   NytListContext listContext,
                                                   String isbn13,
                                                   String isbn10) {
        String title = firstNonEmptyText(bookNode, "book_title", "title");
        if (!StringUtils.hasText(title)) {
            return null;
        }

        String normalizedTitle = TextUtils.normalizeBookTitle(title);
        List<String> normalizedAuthors = extractAuthors(bookNode).stream()
            .map(TextUtils::normalizeAuthorName)
            .toList();

        return BookAggregate.builder()
            .title(normalizedTitle)
            .description(nullIfBlank(firstNonEmptyText(bookNode, "description", "summary")))
            .publisher(nullIfBlank(firstNonEmptyText(bookNode, "publisher")))
            .isbn13(nullIfBlank(isbn13))
            .isbn10(nullIfBlank(isbn10))
            .publishedDate(parsePublishedLocalDate(bookNode))
            .authors(normalizedAuthors.isEmpty() ? null : normalizedAuthors)
            .categories(buildNytCategories(listContext))
            .identifiers(buildNytIdentifiers(bookNode, isbn13, isbn10))
            .slugBase(SlugGenerator.generateBookSlug(normalizedTitle, normalizedAuthors))
            .build();
    }

    @Nullable
    public LocalDate parsePublishedLocalDate(JsonNode bookNode) {
        Date publishedDate = parsePublishedDate(bookNode);
        return publishedDate != null ? new java.sql.Date(publishedDate.getTime()).toLocalDate() : null;
    }

    private Date parsePublishedDate(JsonNode bookNode) {
        String dateString = firstNonEmptyText(bookNode, "published_date", "publication_dt", "created_date");
        if (!StringUtils.hasText(dateString)) {
            return null;
        }
        return DateParsingUtils.parseFlexibleDate(dateString);
    }

    private List<String> extractAuthors(JsonNode bookNode) {
        List<String> authors = new ArrayList<>();
        addAuthors(authors, firstNonEmptyText(bookNode, "author"));
        addAuthors(authors, firstNonEmptyText(bookNode, "contributor"));
        addAuthors(authors, firstNonEmptyText(bookNode, "contributor_note"));
        if (authors.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> dedupedAuthors = new LinkedHashSet<>(authors);
        return new ArrayList<>(dedupedAuthors);
    }

    private void addAuthors(List<String> authors, @Nullable String rawValue) {
        String sanitized = rawValue == null ? "" : rawValue;
        if (!StringUtils.hasText(sanitized)) {
            return;
        }
        String normalized = AUTHOR_AND_SEPARATOR_PATTERN.matcher(sanitized).replaceAll(",");
        for (String authorPart : normalized.split(AUTHOR_DELIMITER_PATTERN)) {
            String cleaned = authorPart.trim();
            if (StringUtils.hasText(cleaned)) {
                authors.add(cleaned);
            }
        }
    }

    @Nullable
    private List<String> buildNytCategories(NytListContext listContext) {
        String naturalListLabel = resolveNaturalListLabel(
            listContext.listDisplayName(),
            listContext.listName(),
            listContext.listCode()
        );
        if (!StringUtils.hasText(naturalListLabel)) {
            return null;
        }
        return List.of("NYT " + naturalListLabel);
    }

    private BookAggregate.ExternalIdentifiers buildNytIdentifiers(JsonNode bookNode, String isbn13, String isbn10) {
        Map<String, String> imageLinks = new HashMap<>();
        String imageUrl = firstNonEmptyText(bookNode, "book_image", "book_image_url");
        if (StringUtils.hasText(imageUrl)) {
            imageLinks.put("thumbnail", imageUrl);
        }

        BookAggregate.ExternalIdentifiers.ExternalIdentifiersBuilder builder =
            BookAggregate.ExternalIdentifiers.builder()
                .source("NEW_YORK_TIMES")
                .externalId(isbn13 != null ? isbn13 : isbn10)
                .providerIsbn13(isbn13)
                .providerIsbn10(isbn10)
                .imageLinks(imageLinks);

        String purchaseUrl = firstNonEmptyText(bookNode, "amazon_product_url");
        if (StringUtils.hasText(purchaseUrl)) {
            builder.purchaseLink(purchaseUrl);
        }
        String infoLink = firstNonEmptyText(bookNode, "book_review_link", "sunday_review_link", "article_chapter_link");
        if (StringUtils.hasText(infoLink)) {
            builder.infoLink(infoLink);
        }
        String previewLink = firstNonEmptyText(bookNode, "first_chapter_link");
        if (StringUtils.hasText(previewLink)) {
            builder.previewLink(previewLink);
        }
        String articleChapterLink = firstNonEmptyText(bookNode, "article_chapter_link");
        if (StringUtils.hasText(articleChapterLink)) {
            builder.webReaderLink(articleChapterLink);
        }
        String canonicalVolumeLink = firstNonEmptyText(bookNode, "book_uri");
        if (StringUtils.hasText(canonicalVolumeLink)) {
            builder.canonicalVolumeLink(canonicalVolumeLink);
        }
        return builder.build();
    }

    public Map<String, String> extractBuyLinks(JsonNode bookNode) {
        JsonNode buyLinks = bookNode.path("buy_links");
        if (!buyLinks.isArray() || buyLinks.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalizedLinks = new LinkedHashMap<>();
        for (JsonNode linkNode : buyLinks) {
            String linkName = firstNonEmptyText(linkNode, "name");
            String linkUrl = firstNonEmptyText(linkNode, "url");
            if (StringUtils.hasText(linkName) && StringUtils.hasText(linkUrl)) {
                normalizedLinks.putIfAbsent(linkName, linkUrl);
            }
        }
        return normalizedLinks.isEmpty() ? Map.of() : Map.copyOf(normalizedLinks);
    }

    public List<Map<String, String>> extractIsbnEntries(JsonNode bookNode) {
        JsonNode isbnsNode = bookNode.path("isbns");
        if (!isbnsNode.isArray() || isbnsNode.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> entries = new ArrayList<>();
        for (JsonNode isbnNode : isbnsNode) {
            String isbn13 = sanitizeValidIsbn13(firstNonEmptyText(isbnNode, "isbn13"));
            String isbn10 = sanitizeValidIsbn10(firstNonEmptyText(isbnNode, "isbn10"));
            if (isbn13 == null && isbn10 == null) {
                continue;
            }
            Map<String, String> entry = new LinkedHashMap<>();
            if (isbn13 != null) {
                entry.put("isbn13", isbn13);
            }
            if (isbn10 != null) {
                entry.put("isbn10", isbn10);
            }
            entries.add(Map.copyOf(entry));
        }
        return entries.isEmpty() ? List.of() : List.copyOf(entries);
    }

    @Nullable
    public Integer parseIntegerField(JsonNode node, String fieldName) {
        JsonNode fieldNode = node.path(fieldName);
        if (fieldNode.isInt()) {
            return fieldNode.intValue();
        }
        if (fieldNode.isString()) {
            String raw = fieldNode.asString();
            if (!StringUtils.hasText(raw)) {
                return null;
            }
            try {
                return Integer.valueOf(raw.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
