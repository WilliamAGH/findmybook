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
import net.findmybook.util.IdGenerator;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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

    /**
     * Reprocesses all historical NYT bestseller publication dates currently tracked in Postgres.
     * <p>
     * This is intended for admin-driven backfills to upsert newly mapped NYT metadata onto
     * existing canonical books, external identifiers, list memberships, and tag assignments.
     *
     * @return summary containing success/failure counts and failing dates
     */
    public HistoricalRerunSummary rerunHistoricalBestsellers() {
        assertNytOnly();
        if (jdbcTemplate == null) {
            throw new IllegalStateException("JdbcTemplate unavailable; NYT historical rerun skipped.");
        }

        List<LocalDate> publishedDates = loadHistoricalPublishedDates();
        if (publishedDates.isEmpty()) {
            log.info("No historical NYT published dates found in Postgres. Running latest overview once instead.");
            forceProcessNewYorkTimesBestsellers(null);
            return new HistoricalRerunSummary(0, 1, 0, List.of());
        }

        int succeededDates = 0;
        int failedDates = 0;
        List<String> failures = new ArrayList<>();
        for (LocalDate publishedDate : publishedDates) {
            try {
                forceProcessNewYorkTimesBestsellers(publishedDate);
                succeededDates++;
            } catch (RuntimeException exception) {
                failedDates++;
                String failureDetail = publishedDate + ": " + resolveFailureMessage(exception);
                failures.add(failureDetail);
                log.error("Historical NYT rerun failed for publishedDate={}. Continuing with remaining dates.",
                    publishedDate,
                    exception);
            }
        }

        return new HistoricalRerunSummary(
            publishedDates.size(),
            succeededDates,
            failedDates,
            List.copyOf(failures)
        );
    }

    private List<LocalDate> loadHistoricalPublishedDates() {
        return jdbcTemplate.query(
            """
            SELECT DISTINCT published_date
            FROM book_collections
            WHERE source = 'NYT'
              AND collection_type = 'BESTSELLER_LIST'
              AND published_date IS NOT NULL
            ORDER BY published_date ASC
            """,
            (resultSet, rowNum) -> resultSet.getObject("published_date", LocalDate.class)
        );
    }

    private static String resolveFailureMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (StringUtils.hasText(message)) {
            return message;
        }
        return exception.getClass().getSimpleName();
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
        String bestsellersDateStr = firstNonEmptyText(results, "bestsellers_date");
        LocalDate bestsellersDate = bestsellersDateStr == null ? null : parseDate(bestsellersDateStr);
        String publishedDateStr = firstNonEmptyText(results, "published_date");
        LocalDate publishedDate = publishedDateStr == null ? null : parseDate(publishedDateStr);
        ArrayNode lists = results.has("lists") && results.get("lists").isArray() ? (ArrayNode) results.get("lists") : null;

        if (lists == null || lists.isEmpty()) {
            log.info("NYT overview contained no lists. Job complete.");
            return;
        }

        int failedLists = 0;
        for (JsonNode listNode : lists) {
            try {
                persistList(listNode, bestsellersDate, publishedDate);
            } catch (RuntimeException exception) {
                failedLists++;
                String listCode = firstNonEmptyText(listNode, "list_name_encoded");
                log.error("Failed processing NYT list '{}' during ingest. Continuing with remaining lists.",
                    listCode != null ? listCode : "unknown",
                    exception);
            }
        }
        if (failedLists > 0) {
            log.warn("NYT ingest completed with {} failed list(s). Review prior errors for details.", failedLists);
        }
        log.info("NYT bestseller ingest completed successfully{}.",
            requestedDate != null ? " for " + requestedDate : "");
    }

    private void persistList(JsonNode listNode, LocalDate bestsellersDate, LocalDate publishedDate) {
        String listCode = firstNonEmptyText(listNode, "list_name_encoded");
        if (listCode == null || listCode.isBlank()) {
            log.warn("Skipping NYT list without list_name_encoded.");
            return;
        }
        String displayName = firstNonEmptyText(listNode, "display_name");
        String listName = firstNonEmptyText(listNode, "list_name");
        String naturalListLabel = resolveNaturalListLabel(displayName, listName, listCode);
        String providerListId = firstNonEmptyText(listNode, "list_id");
        String description = listName;
        String updatedFrequency = firstNonEmptyText(listNode, "updated");
        String normalized = listCode.toLowerCase(Locale.ROOT);

        String publishedDateStr = firstNonEmptyText(listNode, "published_date");
        LocalDate listPublishedDate = publishedDateStr == null ? null : parseDate(publishedDateStr);
        if (listPublishedDate == null) {
            listPublishedDate = publishedDate;
        }
        String collectionId = collectionPersistenceService
            .upsertBestsellerCollection(
                providerListId,
                listCode,
                naturalListLabel,
                normalized,
                description,
                bestsellersDate,
                listPublishedDate,
                updatedFrequency,
                listNode
            )
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

        NytListContext listContext = new NytListContext(
            collectionId,
            listCode,
            naturalListLabel,
            listName,
            providerListId,
            updatedFrequency,
            bestsellersDate,
            listPublishedDate
        );

        int failedEntries = 0;
        for (JsonNode bookNode : booksNode) {
            try {
                persistListEntry(listContext, bookNode);
            } catch (RuntimeException exception) {
                failedEntries++;
                String title = firstNonEmptyText(bookNode, "title", "book_title");
                log.error("Failed processing NYT book '{}' for list '{}'. Continuing with remaining entries.",
                    title != null ? title : "unknown",
                    listCode,
                    exception);
            }
        }
        if (failedEntries > 0) {
            log.warn("NYT list '{}' completed with {} failed entr{}.",
                listCode,
                failedEntries,
                failedEntries == 1 ? "y" : "ies");
        }
    }

    private void persistListEntry(NytListContext listContext, JsonNode bookNode) {
        String isbn13 = resolveNytIsbn13(bookNode);
        String isbn10 = resolveNytIsbn10(bookNode);

        if (isbn13 == null && isbn10 == null) {
            log.warn("Skipping NYT list entry without valid ISBNs for list '{}'.", listContext.listCode());
            return;
        }

        String canonicalId = resolveOrCreateCanonicalBook(bookNode, listContext, isbn13, isbn10);
        if (canonicalId == null) {
            return;
        }

        Integer rank = bookNode.path("rank").isInt() ? bookNode.get("rank").asInt() : null;
        Integer weeksOnList = bookNode.path("weeks_on_list").isInt() ? bookNode.get("weeks_on_list").asInt() : null;
        Integer rankLastWeek = bookNode.path("rank_last_week").isInt() ? bookNode.get("rank_last_week").asInt() : null;
        Integer peakPosition = calculatePeakPosition(bookNode);
        String providerRef = firstNonEmptyText(bookNode, "amazon_product_url");
        String rawItem = serializeBookNode(bookNode, canonicalId);

        collectionPersistenceService.upsertBestsellerMembership(
            listContext.collectionId(), canonicalId, rank, weeksOnList, rankLastWeek,
            peakPosition, isbn13, isbn10, providerRef, rawItem
        );

        assignCoreTags(canonicalId, listContext, bookNode, rank, weeksOnList, rankLastWeek, peakPosition);
    }

    @Nullable
    private String resolveOrCreateCanonicalBook(JsonNode bookNode, NytListContext listContext,
                                                 String isbn13, String isbn10) {
        String canonicalId = resolveCanonicalBookId(isbn13, isbn10);
        boolean isNewBook = (canonicalId == null);

        // IMPORTANT: For NYT ingestion, we DO NOT consult any external sources (Google/OpenLibrary) at all.
        // If the book is not already present in Postgres, create a minimal canonical record directly from NYT data.
        if (canonicalId == null) {
            canonicalId = createCanonicalFromNyt(bookNode, listContext, isbn13, isbn10);
        } else {
            enrichExistingCanonicalBookMetadata(canonicalId, bookNode);
        }
        upsertNytExternalIdentifiers(canonicalId, bookNode, isbn13, isbn10);

        String title = firstNonEmptyText(bookNode, "title");
        if (canonicalId == null) {
            log.warn("Unable to locate or create canonical book for NYT list entry (ISBN13: {}, ISBN10: {}, title: {}).",
                isbn13, isbn10, title != null ? title : "unknown");
            return null;
        }

        log.info("Processing NYT book: canonicalId='{}', isNew={}, listCode='{}', isbn13='{}', title='{}'",
            canonicalId, isNewBook, listContext.listCode(), isbn13, title != null ? title : "unknown");
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

    private String createCanonicalFromNyt(JsonNode bookNode, NytListContext listContext, String isbn13, String isbn10) {
        BookAggregate aggregate = buildBookAggregateFromNyt(bookNode, listContext, isbn13, isbn10);
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

    private BookAggregate buildBookAggregateFromNyt(JsonNode bookNode, NytListContext listContext, String isbn13, String isbn10) {
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
            .categories(buildNytCategories(listContext))
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
        addAuthors(authors, firstNonEmptyText(bookNode, "author"));
        addAuthors(authors, firstNonEmptyText(bookNode, "contributor"));
        addAuthors(authors, firstNonEmptyText(bookNode, "contributor_note"));
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

    private String firstNonEmptyText(@Nullable JsonNode node, String... fieldNames) {
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

    private void assignCoreTags(String bookId,
                                NytListContext listContext,
                                JsonNode bookNode,
                                @Nullable Integer rank,
                                @Nullable Integer weeksOnList,
                                @Nullable Integer rankLastWeek,
                                @Nullable Integer peakPosition) {
        String naturalListLabel = resolveNaturalListLabel(
            listContext.listDisplayName(),
            listContext.listName(),
            listContext.listCode()
        );
        Map<String, Object> metadata = buildNytTagMetadata(
            listContext,
            bookNode,
            rank,
            weeksOnList,
            rankLastWeek,
            peakPosition,
            naturalListLabel
        );

        String listTagKey = "nyt_list_" + listContext.listCode().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
        String listTagDisplayName = "NYT List: " + naturalListLabel;

        supplementalPersistenceService.assignTag(bookId, "nyt_bestseller", "NYT Bestseller", "NYT", 1.0, metadata);
        supplementalPersistenceService.assignTag(bookId, listTagKey, listTagDisplayName, "NYT", 1.0, metadata);
    }

    private Map<String, Object> buildNytTagMetadata(NytListContext listContext,
                                                    JsonNode bookNode,
                                                    @Nullable Integer rank,
                                                    @Nullable Integer weeksOnList,
                                                    @Nullable Integer rankLastWeek,
                                                    @Nullable Integer peakPosition,
                                                    String naturalListLabel) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("list_code", listContext.listCode());
        putIfHasText(metadata, "list_display_name", naturalListLabel);
        putIfHasText(metadata, "list_name", listContext.listName());
        putIfHasText(metadata, "provider_list_id", listContext.providerListId());
        putIfHasText(metadata, "updated_frequency", listContext.updatedFrequency());
        putIfHasText(metadata, "published_date", formatDate(listContext.publishedDate()));
        putIfHasText(metadata, "bestsellers_date", formatDate(listContext.bestsellersDate()));
        if (rank != null) {
            metadata.put("rank", rank);
        }
        if (weeksOnList != null) {
            metadata.put("weeks_on_list", weeksOnList);
        }
        if (rankLastWeek != null) {
            metadata.put("rank_last_week", rankLastWeek);
        }
        if (peakPosition != null) {
            metadata.put("peak_position", peakPosition);
        }
        putIfHasText(metadata, "title", firstNonEmptyText(bookNode, "title", "book_title"));
        putIfHasText(metadata, "description", firstNonEmptyText(bookNode, "description", "summary"));
        putIfHasText(metadata, "author", firstNonEmptyText(bookNode, "author"));
        putIfHasText(metadata, "contributor", firstNonEmptyText(bookNode, "contributor"));
        putIfHasText(metadata, "contributor_note", firstNonEmptyText(bookNode, "contributor_note"));
        putIfHasText(metadata, "publisher", firstNonEmptyText(bookNode, "publisher"));
        putIfHasText(metadata, "primary_isbn13", resolveNytIsbn13(bookNode));
        putIfHasText(metadata, "primary_isbn10", resolveNytIsbn10(bookNode));
        putIfHasText(metadata, "created_date", firstNonEmptyText(bookNode, "created_date"));
        putIfHasText(metadata, "updated_date", firstNonEmptyText(bookNode, "updated_date"));
        putIfHasText(metadata, "asterisk", firstNonEmptyText(bookNode, "asterisk"));
        putIfHasText(metadata, "dagger", firstNonEmptyText(bookNode, "dagger"));
        putIfHasText(metadata, "age_group", firstNonEmptyText(bookNode, "age_group"));
        putIfHasText(metadata, "price", firstNonEmptyText(bookNode, "price"));
        putIfHasText(metadata, "book_uri", firstNonEmptyText(bookNode, "book_uri"));
        putIfHasText(metadata, "book_review_link", firstNonEmptyText(bookNode, "book_review_link"));
        putIfHasText(metadata, "sunday_review_link", firstNonEmptyText(bookNode, "sunday_review_link"));
        putIfHasText(metadata, "article_chapter_link", firstNonEmptyText(bookNode, "article_chapter_link"));
        putIfHasText(metadata, "first_chapter_link", firstNonEmptyText(bookNode, "first_chapter_link"));
        putIfHasText(metadata, "amazon_product_url", firstNonEmptyText(bookNode, "amazon_product_url"));
        putIfHasText(metadata, "book_image", firstNonEmptyText(bookNode, "book_image"));
        Integer imageWidth = parseIntegerField(bookNode, "book_image_width");
        if (imageWidth != null) {
            metadata.put("book_image_width", imageWidth);
        }
        Integer imageHeight = parseIntegerField(bookNode, "book_image_height");
        if (imageHeight != null) {
            metadata.put("book_image_height", imageHeight);
        }
        Map<String, String> buyLinks = extractBuyLinks(bookNode);
        if (!buyLinks.isEmpty()) {
            metadata.put("buy_links", buyLinks);
        }
        List<Map<String, String>> isbnEntries = extractIsbnEntries(bookNode);
        if (!isbnEntries.isEmpty()) {
            metadata.put("isbns", isbnEntries);
        }
        return metadata;
    }

    private void enrichExistingCanonicalBookMetadata(String canonicalId, JsonNode bookNode) {
        String nytDescription = nullIfBlank(firstNonEmptyText(bookNode, "description", "summary"));
        String nytPublisher = nullIfBlank(firstNonEmptyText(bookNode, "publisher"));
        LocalDate nytPublishedDate = parsePublishedLocalDate(bookNode);
        java.sql.Date sqlPublishedDate = nytPublishedDate != null ? java.sql.Date.valueOf(nytPublishedDate) : null;
        boolean hasDescription = nytDescription != null;
        boolean hasPublisher = nytPublisher != null;
        boolean hasPublishedDate = sqlPublishedDate != null;

        jdbcTemplate.update(
            """
            UPDATE books
            SET description = CASE
                    WHEN (books.description IS NULL OR btrim(books.description) = '') AND ?
                        THEN ?
                    ELSE books.description
                END,
                publisher = CASE
                    WHEN (books.publisher IS NULL OR btrim(books.publisher) = '') AND ?
                        THEN ?
                    ELSE books.publisher
                END,
                published_date = COALESCE(books.published_date, ?),
                updated_at = CASE
                    WHEN ((books.description IS NULL OR btrim(books.description) = '') AND ?)
                        OR ((books.publisher IS NULL OR btrim(books.publisher) = '') AND ?)
                        OR (books.published_date IS NULL AND ?)
                        THEN NOW()
                    ELSE books.updated_at
                END
            WHERE id = ?
            """,
            hasDescription,
            nytDescription,
            hasPublisher,
            nytPublisher,
            sqlPublishedDate,
            hasDescription,
            hasPublisher,
            hasPublishedDate,
            UUID.fromString(canonicalId)
        );
    }

    private void upsertNytExternalIdentifiers(String canonicalId, JsonNode bookNode, String isbn13, String isbn10) {
        if (!StringUtils.hasText(canonicalId) || jdbcTemplate == null) {
            return;
        }
        UUID canonicalUuid = UUID.fromString(canonicalId);
        String externalId = nullIfBlank(isbn13 != null ? isbn13 : isbn10);
        String infoLink = nullIfBlank(firstNonEmptyText(bookNode, "book_review_link", "sunday_review_link", "article_chapter_link"));
        String previewLink = nullIfBlank(firstNonEmptyText(bookNode, "first_chapter_link"));
        String webReaderLink = nullIfBlank(firstNonEmptyText(bookNode, "article_chapter_link"));
        String purchaseLink = nullIfBlank(firstNonEmptyText(bookNode, "amazon_product_url"));
        String canonicalVolumeLink = nullIfBlank(firstNonEmptyText(bookNode, "book_uri"));

        int updatedRows = jdbcTemplate.update(
            """
            UPDATE book_external_ids
            SET external_id = COALESCE(book_external_ids.external_id, ?),
                provider_isbn13 = COALESCE(book_external_ids.provider_isbn13, ?),
                provider_isbn10 = COALESCE(book_external_ids.provider_isbn10, ?),
                info_link = COALESCE(NULLIF(book_external_ids.info_link, ''), ?),
                preview_link = COALESCE(NULLIF(book_external_ids.preview_link, ''), ?),
                web_reader_link = COALESCE(NULLIF(book_external_ids.web_reader_link, ''), ?),
                purchase_link = COALESCE(NULLIF(book_external_ids.purchase_link, ''), ?),
                canonical_volume_link = COALESCE(NULLIF(book_external_ids.canonical_volume_link, ''), ?),
                last_updated = NOW()
            WHERE book_id = ? AND source = 'NEW_YORK_TIMES'
            """,
            externalId,
            isbn13,
            isbn10,
            infoLink,
            previewLink,
            webReaderLink,
            purchaseLink,
            canonicalVolumeLink,
            canonicalUuid
        );
        if (updatedRows > 0) {
            return;
        }

        jdbcTemplate.update(
            """
            INSERT INTO book_external_ids (
                id,
                book_id,
                source,
                external_id,
                provider_isbn13,
                provider_isbn10,
                info_link,
                preview_link,
                web_reader_link,
                purchase_link,
                canonical_volume_link,
                last_updated,
                created_at
            )
            VALUES (?, ?, 'NEW_YORK_TIMES', ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            """,
            IdGenerator.generateLong(),
            canonicalUuid,
            externalId,
            isbn13,
            isbn10,
            infoLink,
            previewLink,
            webReaderLink,
            purchaseLink,
            canonicalVolumeLink
        );
    }

    private static String resolveNaturalListLabel(@Nullable String displayName,
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
    private static String formatDate(@Nullable LocalDate date) {
        return date != null ? date.toString() : null;
    }

    private static void putIfHasText(Map<String, Object> metadata, String key, @Nullable String value) {
        if (StringUtils.hasText(value)) {
            metadata.put(key, value.trim());
        }
    }

    @Nullable
    private static Integer parseIntegerField(JsonNode node, String fieldName) {
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

    @Nullable
    private String resolveNytIsbn13(JsonNode bookNode) {
        String primaryIsbn13 = sanitizeValidIsbn13(firstNonEmptyText(bookNode, "primary_isbn13"));
        if (primaryIsbn13 != null) {
            return primaryIsbn13;
        }
        return firstValidIsbnFromArray(bookNode, "isbn13", true);
    }

    @Nullable
    private String resolveNytIsbn10(JsonNode bookNode) {
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

    private Map<String, String> extractBuyLinks(JsonNode bookNode) {
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

    private List<Map<String, String>> extractIsbnEntries(JsonNode bookNode) {
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

    private record NytListContext(
        String collectionId,
        String listCode,
        String listDisplayName,
        @Nullable String listName,
        @Nullable String providerListId,
        @Nullable String updatedFrequency,
        @Nullable LocalDate bestsellersDate,
        @Nullable LocalDate publishedDate
    ) {}

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

    /**
     * Summary returned by historical NYT rerun executions.
     *
     * @param totalDates total number of historical publication dates selected for rerun
     * @param succeededDates number of publication dates that completed successfully
     * @param failedDates number of publication dates that failed
     * @param failures per-date failure details in {@code yyyy-MM-dd: message} format
     */
    public record HistoricalRerunSummary(
        int totalDates,
        int succeededDates,
        int failedDates,
        List<String> failures
    ) {}

}
