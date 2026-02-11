package net.findmybook.scheduler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import net.findmybook.dto.BookAggregate;
import net.findmybook.service.BookCollectionPersistenceService;
import net.findmybook.service.BookLookupService;
import net.findmybook.service.BookUpsertService;
import net.findmybook.service.NewYorkTimesService;
import net.findmybook.support.retry.AdvisoryLockRetrySupport;
import net.findmybook.util.DateParsingUtils;
import net.findmybook.util.LoggingUtils;
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
import java.util.List;

/**
 * Orchestrates NYT bestseller ingestion from API payload to canonical persistence services.
 */
@Component
@Slf4j
public class NewYorkTimesBestsellerScheduler {

    private final NewYorkTimesService newYorkTimesService;
    private final BookLookupService bookLookupService;
    private final JdbcTemplate jdbcTemplate;
    private final BookCollectionPersistenceService collectionPersistenceService;
    private final BookUpsertService bookUpsertService;
    private final NytBestsellerPayloadMapper payloadMapper;
    private final NytBestsellerPersistenceCollaborator persistenceCollaborator;
    private final boolean schedulerEnabled;
    private final boolean standaloneScheduleEnabled;
    private final boolean nytOnly;

    public NewYorkTimesBestsellerScheduler(NewYorkTimesService newYorkTimesService,
                                           BookLookupService bookLookupService,
                                           JdbcTemplate jdbcTemplate,
                                           BookCollectionPersistenceService collectionPersistenceService,
                                           BookUpsertService bookUpsertService,
                                           NytBestsellerPayloadMapper payloadMapper,
                                           NytBestsellerPersistenceCollaborator persistenceCollaborator,
                                           @Value("${app.nyt.scheduler.enabled:true}") boolean schedulerEnabled,
                                           @Value("${app.nyt.scheduler.standalone-enabled:false}") boolean standaloneScheduleEnabled,
                                           @Value("${app.nyt.scheduler.nyt-only:true}") boolean nytOnly) {
        this.newYorkTimesService = newYorkTimesService;
        this.bookLookupService = bookLookupService;
        this.jdbcTemplate = jdbcTemplate;
        this.collectionPersistenceService = collectionPersistenceService;
        this.bookUpsertService = bookUpsertService;
        this.payloadMapper = payloadMapper;
        this.persistenceCollaborator = persistenceCollaborator;
        this.schedulerEnabled = schedulerEnabled;
        this.standaloneScheduleEnabled = standaloneScheduleEnabled;
        this.nytOnly = nytOnly;
    }

    @Scheduled(cron = "${app.nyt.scheduler.cron:0 0 4 * * SUN}")
    public void processNewYorkTimesBestsellers() {
        if (!standaloneScheduleEnabled) {
            log.info("Skipping standalone NYT scheduler execution because weekly catalog refresh owns this run.");
            return;
        }
        processNewYorkTimesBestsellers(null, false);
    }

    public void processNewYorkTimesBestsellers(@Nullable LocalDate requestedDate) {
        processNewYorkTimesBestsellers(requestedDate, false);
    }

    public void forceProcessNewYorkTimesBestsellers() {
        processNewYorkTimesBestsellers(null, true);
    }

    public void forceProcessNewYorkTimesBestsellers(@Nullable LocalDate requestedDate) {
        processNewYorkTimesBestsellers(requestedDate, true);
    }

    /**
     * Reprocesses all historical NYT publication dates currently tracked in Postgres.
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
            forceProcessNewYorkTimesBestsellers();
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

        log.info("Starting NYT bestseller ingest{}.", requestedDate != null ? " for " + requestedDate : "");
        JsonNode overview = newYorkTimesService.fetchBestsellerListOverview(requestedDate)
            .onErrorMap(exception -> {
                LoggingUtils.error(log, exception, "Unable to fetch NYT bestseller overview");
                return new IllegalStateException("Unable to fetch NYT bestseller overview", exception);
            })
            .block(Duration.ofMinutes(2));

        if (overview == null || overview.isEmpty()) {
            log.info("NYT overview returned no data. Job complete.");
            return;
        }

        JsonNode results = overview.path("results");
        String bestsellersDateText = payloadMapper.firstNonEmptyText(results, "bestsellers_date");
        LocalDate bestsellersDate = bestsellersDateText == null ? null : parseDate(bestsellersDateText);
        String publishedDateText = payloadMapper.firstNonEmptyText(results, "published_date");
        LocalDate publishedDate = publishedDateText == null ? null : parseDate(publishedDateText);
        ArrayNode lists = results.has("lists") && results.get("lists").isArray() ? (ArrayNode) results.get("lists") : null;

        if (lists == null || lists.isEmpty()) {
            log.info("NYT overview contained no lists. Job complete.");
            return;
        }

        int failedLists = 0;
        int totalLists = lists.size();
        for (JsonNode listNode : lists) {
            try {
                persistList(listNode, bestsellersDate, publishedDate);
            } catch (RuntimeException exception) {
                failedLists++;
                String listCode = payloadMapper.firstNonEmptyText(listNode, "list_name_encoded");
                log.error("Failed processing NYT list '{}' during ingest. Continuing with remaining lists.",
                    listCode != null ? listCode : "unknown",
                    exception);
            }
        }
        if (failedLists > 0) {
            throw new IllegalStateException(
                "NYT ingest completed with %d of %d list(s) failed. Review prior logged errors for details."
                    .formatted(failedLists, totalLists));
        }

        log.info("NYT bestseller ingest completed successfully{}.", requestedDate != null ? " for " + requestedDate : "");
    }

    private void persistList(JsonNode listNode, @Nullable LocalDate bestsellersDate, @Nullable LocalDate publishedDate) {
        String listCode = payloadMapper.firstNonEmptyText(listNode, "list_name_encoded");
        if (!StringUtils.hasText(listCode)) {
            log.warn("Skipping NYT list without list_name_encoded.");
            return;
        }

        String displayName = payloadMapper.firstNonEmptyText(listNode, "display_name");
        String listName = payloadMapper.firstNonEmptyText(listNode, "list_name");
        String naturalListLabel = payloadMapper.resolveNaturalListLabel(displayName, listName, listCode);
        String providerListId = payloadMapper.firstNonEmptyText(listNode, "list_id");
        String updatedFrequency = payloadMapper.firstNonEmptyText(listNode, "updated");

        String listPublishedDateText = payloadMapper.firstNonEmptyText(listNode, "published_date");
        LocalDate listPublishedDate = listPublishedDateText == null ? null : parseDate(listPublishedDateText);
        if (listPublishedDate == null) {
            listPublishedDate = publishedDate;
        }

        String collectionId = collectionPersistenceService
            .upsertBestsellerCollection(
                providerListId,
                listCode,
                naturalListLabel,
                listCode.toLowerCase(),
                listName,
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
        int totalEntries = booksNode.size();
        for (JsonNode bookNode : booksNode) {
            try {
                persistListEntry(listContext, bookNode);
            } catch (RuntimeException exception) {
                failedEntries++;
                String title = payloadMapper.firstNonEmptyText(bookNode, "title", "book_title");
                log.error("Failed processing NYT book '{}' for list '{}'. Continuing with remaining entries.",
                    title != null ? title : "unknown",
                    listCode,
                    exception);
            }
        }
        if (failedEntries > 0) {
            throw new IllegalStateException(
                "NYT list '%s' completed with %d of %d failed entr%s."
                    .formatted(listCode, failedEntries, totalEntries, failedEntries == 1 ? "y" : "ies"));
        }
    }

    private void persistListEntry(NytListContext listContext, JsonNode bookNode) {
        String isbn13 = payloadMapper.resolveNytIsbn13(bookNode);
        String isbn10 = payloadMapper.resolveNytIsbn10(bookNode);

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
        Integer peakPosition = payloadMapper.calculatePeakPosition(bookNode);
        String providerRef = payloadMapper.firstNonEmptyText(bookNode, "amazon_product_url");
        String rawItem = payloadMapper.serializeBookNode(bookNode);

        collectionPersistenceService.upsertBestsellerMembership(
            listContext.collectionId(),
            canonicalId,
            rank,
            weeksOnList,
            rankLastWeek,
            peakPosition,
            isbn13,
            isbn10,
            providerRef,
            rawItem
        );

        persistenceCollaborator.assignCoreTags(
            canonicalId,
            listContext,
            bookNode,
            rank,
            weeksOnList,
            rankLastWeek,
            peakPosition
        );
    }

    @Nullable
    private String resolveOrCreateCanonicalBook(JsonNode bookNode,
                                                NytListContext listContext,
                                                String isbn13,
                                                String isbn10) {
        String canonicalId = bookLookupService.resolveCanonicalBookId(isbn13, isbn10);
        boolean isNewBook = (canonicalId == null);

        if (canonicalId == null) {
            canonicalId = createCanonicalFromNyt(bookNode, listContext, isbn13, isbn10);
        } else {
            persistenceCollaborator.enrichExistingCanonicalBookMetadata(canonicalId, bookNode);
        }
        persistenceCollaborator.upsertNytExternalIdentifiers(canonicalId, bookNode, isbn13, isbn10);

        String title = payloadMapper.firstNonEmptyText(bookNode, "title");
        if (canonicalId == null) {
            log.warn("Unable to locate or create canonical book for NYT list entry (ISBN13: {}, ISBN10: {}, title: {}).",
                isbn13,
                isbn10,
                title != null ? title : "unknown");
            return null;
        }

        log.info("Processing NYT book: canonicalId='{}', isNew={}, listCode='{}', isbn13='{}', title='{}'",
            canonicalId,
            isNewBook,
            listContext.listCode(),
            isbn13,
            title != null ? title : "unknown");
        return canonicalId;
    }

    @Nullable
    private String createCanonicalFromNyt(JsonNode bookNode, NytListContext listContext, String isbn13, String isbn10) {
        BookAggregate aggregate = payloadMapper.buildBookAggregateFromNyt(bookNode, listContext, isbn13, isbn10);
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
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                "Failed to create canonical book from NYT data (isbn13=" + isbn13 + ", isbn10=" + isbn10 + ")",
                exception
            );
        }
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

    private void assertNytOnly() {
        if (!nytOnly) {
            throw new IllegalStateException("NYT-only enforcement is active: set app.nyt.scheduler.nyt-only=true to run this job.");
        }
    }

    @Nullable
    private LocalDate parseDate(@Nullable String dateText) {
        if (!StringUtils.hasText(dateText)) {
            return null;
        }
        LocalDate parsed = DateParsingUtils.parseBestsellerDate(dateText);
        if (parsed == null) {
            log.warn("Failed to parse date from non-blank input: '{}'", dateText);
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
