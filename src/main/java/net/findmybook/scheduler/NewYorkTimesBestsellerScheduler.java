package net.findmybook.scheduler;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import net.findmybook.dto.BookAggregate;
import net.findmybook.service.BookCollectionPersistenceService;
import net.findmybook.service.BookLookupService;
import net.findmybook.service.BookSupplementalPersistenceService;
import net.findmybook.service.BookUpsertService;
import net.findmybook.service.NewYorkTimesService;
import net.findmybook.support.retry.AdvisoryLockRetrySupport;
import net.findmybook.util.LoggingUtils;
import net.findmybook.util.DateParsingUtils;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import net.findmybook.util.IsbnUtils;

/**
 * Scheduler that ingests New York Times bestseller data directly into Postgres.
 * <p>
 * Responsibilities:
 * <ul>
 *     <li>Fetch overview data from the NYT API.</li>
 *     <li>Persist list metadata in {@code book_collections}.</li>
 *     <li>Persist list membership in {@code book_collections_join}.</li>
 *     <li>Ensure canonical books exist (delegating to {@link BookDataOrchestrator}).</li>
 *     <li>Tag books with NYT specific qualifiers via {@code book_tag_assignments}.</li>
 * </ul>
 */
@Component
@Slf4j
public class NewYorkTimesBestsellerScheduler {

    private final NewYorkTimesService newYorkTimesService;
    private final BookLookupService bookLookupService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final BookCollectionPersistenceService collectionPersistenceService;
    private final BookSupplementalPersistenceService supplementalPersistenceService;
    private final BookUpsertService bookUpsertService;
    private final boolean schedulerEnabled;
    private final boolean nytOnly;

    public NewYorkTimesBestsellerScheduler(NewYorkTimesService newYorkTimesService,
                                           BookLookupService bookLookupService,
                                           ObjectMapper objectMapper,
                                           JdbcTemplate jdbcTemplate,
                                           BookCollectionPersistenceService collectionPersistenceService,
                                           BookSupplementalPersistenceService supplementalPersistenceService,
                                           BookUpsertService bookUpsertService,
                                           @Value("${app.nyt.scheduler.enabled:true}") boolean schedulerEnabled,
                                           @Value("${app.nyt.scheduler.nyt-only:true}") boolean nytOnly) {
        this.newYorkTimesService = newYorkTimesService;
        this.bookLookupService = bookLookupService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.collectionPersistenceService = collectionPersistenceService;
        this.supplementalPersistenceService = supplementalPersistenceService;
        this.bookUpsertService = bookUpsertService;
        this.schedulerEnabled = schedulerEnabled;
        this.nytOnly = nytOnly;
    }

    @Scheduled(cron = "${app.nyt.scheduler.cron:0 0 4 * * SUN}")
    public void processNewYorkTimesBestsellers() {
        processNewYorkTimesBestsellers(null, false);
    }

    public void processNewYorkTimesBestsellers(@Nullable LocalDate requestedDate) {
        processNewYorkTimesBestsellers(requestedDate, false);
    }

    public void forceProcessNewYorkTimesBestsellers(@Nullable LocalDate requestedDate) {
        processNewYorkTimesBestsellers(requestedDate, true);
    }

    private void processNewYorkTimesBestsellers(@Nullable LocalDate requestedDate, boolean forceExecution) {
        if (!forceExecution && !schedulerEnabled) {
            log.info("NYT bestseller scheduler disabled via configuration.");
            return;
        }
        assertNytOnly();
        if (jdbcTemplate == null) {
            log.warn("JdbcTemplate unavailable; NYT bestseller ingest skipped.");
            return;
        }

        log.info("Starting NYT bestseller ingest{}.",
            requestedDate != null ? " for " + requestedDate : "");
        JsonNode overview = newYorkTimesService.fetchBestsellerListOverview(requestedDate)
                .onErrorMap(e -> {
                    LoggingUtils.error(log, e, "Unable to fetch NYT bestseller overview");
                    return new IllegalStateException("Unable to fetch NYT bestseller overview", e);
                })
                .block(Duration.ofMinutes(2));

        if (overview == null || overview.isEmpty()) {
            log.info("NYT overview returned no data. Job complete.");
            return;
        }

        JsonNode results = overview.path("results");
        String bestsellersDateStr = emptyToNull(results.path("bestsellers_date").asString());
        LocalDate bestsellersDate = bestsellersDateStr == null ? null : parseDate(bestsellersDateStr);
        String publishedDateStr = emptyToNull(results.path("published_date").asString());
        LocalDate publishedDate = publishedDateStr == null ? null : parseDate(publishedDateStr);
        ArrayNode lists = results.has("lists") && results.get("lists").isArray() ? (ArrayNode) results.get("lists") : null;

        if (lists == null || lists.isEmpty()) {
            log.info("NYT overview contained no lists. Job complete.");
            return;
        }

        lists.forEach(listNode -> persistList(listNode, bestsellersDate, publishedDate));
        log.info("NYT bestseller ingest completed successfully{}.",
            requestedDate != null ? " for " + requestedDate : "");
    }

    private void persistList(JsonNode listNode, LocalDate bestsellersDate, LocalDate publishedDate) {
        String displayName = emptyToNull(listNode.path("display_name").asString());
        String listCode = emptyToNull(listNode.path("list_name_encoded").asString());
        if (listCode == null || listCode.isBlank()) {
            log.warn("Skipping NYT list without list_name_encoded.");
            return;
        }
        String providerListId = emptyToNull(listNode.path("list_id").asString());
        String description = emptyToNull(listNode.path("list_name").asString());
        String normalized = listCode.toLowerCase(Locale.ROOT);

        String publishedDateStr = emptyToNull(listNode.path("published_date").asString());
        LocalDate listPublishedDate = publishedDateStr == null ? null : parseDate(publishedDateStr);
        if (listPublishedDate == null) {
            listPublishedDate = publishedDate;
        }
        String collectionId = collectionPersistenceService
            .upsertBestsellerCollection(providerListId, listCode, displayName, normalized, description, bestsellersDate, listPublishedDate, listNode)
            .orElse(null);

        if (collectionId == null) {
            log.warn("Failed to upsert NYT collection for list code {}", listCode);
            return;
        }

        ArrayNode booksNode = listNode.has("books") && listNode.get("books").isArray() ? (ArrayNode) listNode.get("books") : null;
        if (booksNode == null || booksNode.isEmpty()) {
            log.info("NYT list '{}' contained no books.", listCode);
            return;
        }

        booksNode.forEach(bookNode -> persistListEntry(collectionId, listCode, bookNode));
    }

    private void persistListEntry(String collectionId, String listCode, JsonNode bookNode) {
        String isbn13 = IsbnUtils.sanitize(emptyToNull(bookNode.path("primary_isbn13").asString()));
        String isbn10 = IsbnUtils.sanitize(emptyToNull(bookNode.path("primary_isbn10").asString()));

        // Validate ISBN formats; discard non-ISBN vendor codes (e.g., X0234484)
        if (isbn13 != null && !IsbnUtils.isValidIsbn13(isbn13)) {
            isbn13 = null;
        }
        if (isbn10 != null && !IsbnUtils.isValidIsbn10(isbn10)) {
            isbn10 = null;
        }

        if (isbn13 == null && isbn10 == null) {
            log.warn("Skipping NYT list entry without valid ISBNs for list '{}'.", listCode);
            return;
        }

        String canonicalId = resolveOrCreateCanonicalBook(bookNode, listCode, isbn13, isbn10);
        if (canonicalId == null) {
            return;
        }

        Integer rank = bookNode.path("rank").isInt() ? bookNode.get("rank").asInt() : null;
        Integer weeksOnList = bookNode.path("weeks_on_list").isInt() ? bookNode.get("weeks_on_list").asInt() : null;
        Integer rankLastWeek = bookNode.path("rank_last_week").isInt() ? bookNode.get("rank_last_week").asInt() : null;
        Integer peakPosition = calculatePeakPosition(bookNode);
        String providerRef = emptyToNull(bookNode.path("amazon_product_url").asString());
        String rawItem = serializeBookNode(bookNode, canonicalId);

        collectionPersistenceService.upsertBestsellerMembership(
            collectionId, canonicalId, rank, weeksOnList, rankLastWeek,
            peakPosition, isbn13, isbn10, providerRef, rawItem
        );

        assignCoreTags(canonicalId, listCode, rank);
    }

    @Nullable
    private String resolveOrCreateCanonicalBook(JsonNode bookNode, String listCode,
                                                 String isbn13, String isbn10) {
        String canonicalId = resolveCanonicalBookId(isbn13, isbn10);
        boolean isNewBook = (canonicalId == null);

        // IMPORTANT: For NYT ingestion, we DO NOT consult any external sources (Google/OpenLibrary) at all.
        // If the book is not already present in Postgres, create a minimal canonical record directly from NYT data.
        if (canonicalId == null) {
            canonicalId = createCanonicalFromNyt(bookNode, listCode, isbn13, isbn10);
        }

        String title = emptyToNull(bookNode.path("title").asString());
        if (canonicalId == null) {
            log.warn("Unable to locate or create canonical book for NYT list entry (ISBN13: {}, ISBN10: {}, title: {}).",
                isbn13, isbn10, title != null ? title : "unknown");
            return null;
        }

        log.info("Processing NYT book: canonicalId='{}', isNew={}, isbn13='{}', title='{}'",
            canonicalId, isNewBook, isbn13, title != null ? title : "unknown");
        return canonicalId;
    }

    @Nullable
    private Integer calculatePeakPosition(JsonNode bookNode) {
        Integer peakPosition = null;
        JsonNode ranksHistory = bookNode.path("ranks_history");
        if (ranksHistory.isArray()) {
            for (JsonNode rh : ranksHistory) {
                if (rh.path("rank").isInt()) {
                    int r = rh.get("rank").asInt();
                    if (peakPosition == null || r < peakPosition) {
                        peakPosition = r;
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
    private String serializeBookNode(JsonNode bookNode, String canonicalId) {
        try {
            return objectMapper.writeValueAsString(bookNode);
        } catch (JacksonException e) {
            log.error("Failed to serialize bestseller book node for canonicalId={}: {}",
                canonicalId, e.getMessage(), e);
            return null;
        }
    }

    private String resolveCanonicalBookId(String isbn13, String isbn10) {
        return bookLookupService.resolveCanonicalBookId(isbn13, isbn10);
    }

    private String createCanonicalFromNyt(JsonNode bookNode, String listCode, String isbn13, String isbn10) {
        BookAggregate aggregate = buildBookAggregateFromNyt(bookNode, listCode, isbn13, isbn10);
        if (aggregate == null) {
            return null;
        }

        try {
            BookUpsertService.UpsertResult result = AdvisoryLockRetrySupport.execute(
                AdvisoryLockRetrySupport.RetryConfig.forBookUpsert(log),
                "NYT upsert isbn13=" + isbn13 + ",isbn10=" + isbn10,
                () -> bookUpsertService.upsert(aggregate)
            );
            return result.getBookId().toString();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                "Failed to create canonical book from NYT data (isbn13=" + isbn13 + ", isbn10=" + isbn10 + ")",
                e
            );
        }
    }

    private BookAggregate buildBookAggregateFromNyt(JsonNode bookNode, String listCode, String isbn13, String isbn10) {
        String title = firstNonEmptyText(bookNode, "book_title", "title");
        if (!StringUtils.hasText(title)) {
            return null;
        }

        String normalizedTitle = net.findmybook.util.TextUtils.normalizeBookTitle(title);
        List<String> normalizedAuthors = extractAuthors(bookNode).stream()
            .map(net.findmybook.util.TextUtils::normalizeAuthorName)
            .toList();

        return BookAggregate.builder()
            .title(normalizedTitle)
            .description(nullIfBlank(firstNonEmptyText(bookNode, "description", "summary")))
            .publisher(nullIfBlank(firstNonEmptyText(bookNode, "publisher")))
            .isbn13(nullIfBlank(isbn13))
            .isbn10(nullIfBlank(isbn10))
            .publishedDate(parsePublishedLocalDate(bookNode))
            .authors(normalizedAuthors.isEmpty() ? null : normalizedAuthors)
            .categories(buildNytCategories(listCode))
            .identifiers(buildNytIdentifiers(bookNode, isbn13, isbn10))
            .slugBase(net.findmybook.util.SlugGenerator.generateBookSlug(normalizedTitle, normalizedAuthors))
            .build();
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
        return builder.build();
    }

    @Nullable
    private List<String> buildNytCategories(String listCode) {
        if (!StringUtils.hasText(listCode)) {
            return null;
        }
        return List.of("NYT " + listCode.replace('-', ' '));
    }

    @Nullable
    private LocalDate parsePublishedLocalDate(JsonNode bookNode) {
        Date published = parsePublishedDate(bookNode);
        return published != null ? new java.sql.Date(published.getTime()).toLocalDate() : null;
    }

    @Nullable
    private static String nullIfBlank(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private Date parsePublishedDate(JsonNode bookNode) {
        String dateStr = firstNonEmptyText(bookNode, "published_date", "publication_dt", "created_date");
        if (!StringUtils.hasText(dateStr)) {
            return null;
        }
        return DateParsingUtils.parseFlexibleDate(dateStr);
    }

    private List<String> extractAuthors(JsonNode bookNode) {
        List<String> authors = new ArrayList<>();
        addAuthors(authors, emptyToNull(bookNode.path("author").asString()));
        addAuthors(authors, emptyToNull(bookNode.path("contributor").asString()));
        addAuthors(authors, emptyToNull(bookNode.path("contributor_note").asString()));
        if (authors.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> deduped = new LinkedHashSet<>(authors);
        return new ArrayList<>(deduped);
    }

    private static final Pattern AUTHOR_AND_SEPARATOR_PATTERN = Pattern.compile("(?i)\\band\\b");
    private static final String AUTHOR_DELIMITER_PATTERN = "[,;&]";

    private void addAuthors(List<String> authors, @Nullable String raw) {
        String sanitized = raw == null ? "" : raw;
        if (!StringUtils.hasText(sanitized)) {
            return;
        }
        String normalized = AUTHOR_AND_SEPARATOR_PATTERN.matcher(sanitized).replaceAll(",");
        for (String part : normalized.split(AUTHOR_DELIMITER_PATTERN)) {
            String cleaned = part.trim();
            if (StringUtils.hasText(cleaned)) {
                authors.add(cleaned);
            }
        }
    }

    private String firstNonEmptyText(JsonNode node, String... fieldNames) {
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

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private void assignCoreTags(String bookId, String listCode, Integer rank) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("list_code", listCode);
        if (rank != null) {
            metadata.put("rank", rank);
        }
        supplementalPersistenceService.assignTag(bookId, "nyt_bestseller", "NYT Bestseller", "NYT", 1.0, metadata);
        supplementalPersistenceService.assignTag(bookId, "nyt_list_" + listCode.replaceAll("[^a-z0-9]", "_"), "NYT List: " + listCode, "NYT", 1.0, metadata);
    }

    private void assertNytOnly() {
        if (!nytOnly) {
            throw new IllegalStateException("NYT-only enforcement is active: set app.nyt.scheduler.nyt-only=true to run this job.");
        }
    }

    private LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        LocalDate parsed = DateParsingUtils.parseBestsellerDate(date);
        if (parsed == null) {
            log.warn("Failed to parse date from non-blank input: '{}'", date);
        }
        return parsed;
    }

}
