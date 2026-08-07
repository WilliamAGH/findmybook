package net.findmybook.scheduler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import net.findmybook.service.BookCollectionPersistenceService;
import net.findmybook.service.NewYorkTimesService;
import net.findmybook.util.DateParsingUtils;
import net.findmybook.util.LoggingUtils;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
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
 *
 * @implNote LOC1 split plan: extract {@code NytListProcessor}
 *     (per-list and per-entry processing logic).
 */
@Component
@Slf4j
public class NewYorkTimesBestsellerScheduler {

    private final NewYorkTimesService newYorkTimesService;
    private final JdbcTemplate jdbcTemplate;
    private final BookCollectionPersistenceService collectionPersistenceService;
    private final NytBestsellerPayloadMapper payloadMapper;
    private final NytBestsellerPersistenceCollaborator persistenceCollaborator;
    private final boolean schedulerEnabled;
    private final boolean standaloneScheduleEnabled;
    private final boolean nytOnly;

    public NewYorkTimesBestsellerScheduler(NytIngestServices services,
                                           SchedulerConfig config) {
        this.newYorkTimesService = services.newYorkTimesService();
        this.jdbcTemplate = services.jdbcTemplate();
        this.collectionPersistenceService = services.collectionPersistenceService();
        this.payloadMapper = services.payloadMapper();
        this.persistenceCollaborator = services.persistenceCollaborator();
        this.schedulerEnabled = config.schedulerEnabled();
        this.standaloneScheduleEnabled = config.standaloneScheduleEnabled();
        this.nytOnly = config.nytOnly();
    }

    @Component
    public static class ConfigLoader {
        @Bean
        public SchedulerConfig nytSchedulerConfig(
            @Value("${app.nyt.scheduler.enabled:true}") boolean schedulerEnabled,
            @Value("${app.nyt.scheduler.standalone-enabled:false}") boolean standaloneScheduleEnabled,
            @Value("${app.nyt.scheduler.nyt-only:true}") boolean nytOnly
        ) {
            return new SchedulerConfig(schedulerEnabled, standaloneScheduleEnabled, nytOnly);
        }

        @Bean
        public NytIngestServices nytIngestServices(
            NewYorkTimesService newYorkTimesService,
            JdbcTemplate jdbcTemplate,
            BookCollectionPersistenceService collectionPersistenceService,
            NytBestsellerPayloadMapper payloadMapper,
            NytBestsellerPersistenceCollaborator persistenceCollaborator
        ) {
            return new NytIngestServices(
                newYorkTimesService,
                jdbcTemplate,
                collectionPersistenceService,
                payloadMapper,
                persistenceCollaborator
            );
        }
    }

    public record SchedulerConfig(boolean schedulerEnabled, boolean standaloneScheduleEnabled, boolean nytOnly) {}

    public record NytIngestServices(
        NewYorkTimesService newYorkTimesService,
        JdbcTemplate jdbcTemplate,
        BookCollectionPersistenceService collectionPersistenceService,
        NytBestsellerPayloadMapper payloadMapper,
        NytBestsellerPersistenceCollaborator persistenceCollaborator
    ) {}

    @Scheduled(cron = "${app.nyt.scheduler.cron:0 0 4 * * SUN}")
    public void processNewYorkTimesBestsellers() {
        if (!standaloneScheduleEnabled) {
            log.info("Skipping standalone NYT scheduler execution because weekly catalog refresh owns this run.");
            return;
        }
        processNewYorkTimesBestsellers(null, false);
    }

    /**
     * Processes one NYT overview using normal scheduler enablement rules.
     *
     * @param requestedDate optional historical publication date
     * @return typed outcome used by callers to distinguish ingestion from a disabled skip
     */
    public NytIngestSummary processNewYorkTimesBestsellers(@Nullable LocalDate requestedDate) {
        return processNewYorkTimesBestsellers(requestedDate, false);
    }

    /**
     * Forces the latest NYT overview to run regardless of scheduler enablement.
     *
     * @return validated nonempty ingestion summary
     */
    public NytIngestSummary forceProcessNewYorkTimesBestsellers() {
        return processNewYorkTimesBestsellers(null, true);
    }

    /**
     * Forces one NYT overview to run regardless of scheduler enablement.
     *
     * @param requestedDate optional historical publication date
     * @return validated nonempty ingestion summary
     */
    public NytIngestSummary forceProcessNewYorkTimesBestsellers(@Nullable LocalDate requestedDate) {
        return processNewYorkTimesBestsellers(requestedDate, true);
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

    private NytIngestSummary processNewYorkTimesBestsellers(@Nullable LocalDate requestedDate, boolean forceExecution) {
        if (!forceExecution && !schedulerEnabled) {
            log.info("NYT bestseller scheduler disabled via configuration.");
            return NytIngestSummary.skipped();
        }
        assertNytOnly();
        if (jdbcTemplate == null) {
            throw new IllegalStateException("JdbcTemplate unavailable; NYT bestseller ingest cannot run.");
        }

        log.info("Starting NYT bestseller ingest{}.", requestedDate != null ? " for " + requestedDate : "");
        JsonNode overview = newYorkTimesService.fetchBestsellerListOverview(requestedDate)
            .onErrorMap(exception -> {
                LoggingUtils.error(log, exception, "Unable to fetch NYT bestseller overview");
                return new IllegalStateException("Unable to fetch NYT bestseller overview", exception);
            })
            .block(Duration.ofMinutes(2));

        if (overview == null || overview.isEmpty()) {
            throw new IllegalStateException("NYT overview returned no data.");
        }

        JsonNode results = overview.path("results");
        String bestsellersDateText = payloadMapper.firstNonEmptyText(results, "bestsellers_date");
        LocalDate bestsellersDate = bestsellersDateText == null ? null : parseDate(bestsellersDateText);
        String publishedDateText = payloadMapper.firstNonEmptyText(results, "published_date");
        LocalDate publishedDate = publishedDateText == null ? null : parseDate(publishedDateText);
        ArrayNode lists = results.has("lists") && results.get("lists").isArray() ? (ArrayNode) results.get("lists") : null;

        if (lists == null || lists.isEmpty()) {
            throw new IllegalStateException("NYT overview contained no usable lists.");
        }

        int failedLists = 0;
        int totalLists = lists.size();
        int usableLists = 0;
        int processedEntries = 0;
        int persistedMemberships = 0;
        for (JsonNode listNode : lists) {
            try {
                ListIngestOutcome listOutcome = persistList(listNode, bestsellersDate, publishedDate);
                if (listOutcome.usableList()) {
                    usableLists++;
                }
                processedEntries += listOutcome.processedEntries();
                persistedMemberships += listOutcome.persistedMemberships();
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

        NytIngestSummary summary = new NytIngestSummary(
            true,
            totalLists,
            usableLists,
            processedEntries,
            persistedMemberships
        );
        if (usableLists == 0) {
            throw new IllegalStateException("NYT overview contained no usable lists.");
        }
        if (!summary.hasValidatedIngestion()) {
            throw new IllegalStateException(
                "NYT ingest persisted zero bestseller memberships from %d processed entries."
                    .formatted(processedEntries)
            );
        }

        log.info(
            "NYT bestseller ingest completed successfully{} (usableLists={}, persistedMemberships={}).",
            requestedDate != null ? " for " + requestedDate : "",
            usableLists,
            persistedMemberships
        );
        return summary;
    }

    private ListIngestOutcome persistList(JsonNode listNode,
                                         @Nullable LocalDate bestsellersDate,
                                         @Nullable LocalDate publishedDate) {
        String listCode = payloadMapper.firstNonEmptyText(listNode, "list_name_encoded");
        if (!StringUtils.hasText(listCode)) {
            log.warn("Skipping NYT list without list_name_encoded.");
            return ListIngestOutcome.unusable();
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
            .upsertBestsellerCollection(new BookCollectionPersistenceService.BestsellerCollectionDto(
                providerListId,
                listCode,
                naturalListLabel,
                listCode.toLowerCase(),
                listName,
                bestsellersDate,
                listPublishedDate,
                updatedFrequency,
                listNode
            ))
            .orElseThrow(() -> new IllegalStateException(
                "NYT collection upsert returned no id for list code " + listCode));

        ArrayNode booksNode = listNode.has("books") && listNode.get("books").isArray() ? (ArrayNode) listNode.get("books") : null;
        if (booksNode == null || booksNode.isEmpty()) {
            log.info("NYT list '{}' contained no books.", listCode);
            return ListIngestOutcome.unusable();
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
        int persistedMemberships = 0;
        for (JsonNode bookNode : booksNode) {
            try {
                if (persistListEntry(listContext, bookNode)) {
                    persistedMemberships++;
                }
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
        return new ListIngestOutcome(true, totalEntries, persistedMemberships);
    }

    private boolean persistListEntry(NytListContext listContext, JsonNode bookNode) {
        String isbn13 = payloadMapper.resolveNytIsbn13(bookNode);
        String isbn10 = payloadMapper.resolveNytIsbn10(bookNode);

        if (payloadMapper.resolveNytExternalId(bookNode, isbn13, isbn10) == null) {
            log.warn("Skipping NYT list entry without reproducible provider identity for list '{}'.",
                listContext.listCode());
            return false;
        }

        String canonicalId = persistenceCollaborator.resolveOrCreateCanonicalBook(
            bookNode,
            listContext,
            isbn13,
            isbn10
        );
        if (canonicalId == null) {
            return false;
        }

        Integer rank = bookNode.path("rank").isInt() ? bookNode.get("rank").asInt() : null;
        Integer weeksOnList = bookNode.path("weeks_on_list").isInt() ? bookNode.get("weeks_on_list").asInt() : null;
        Integer rankLastWeek = bookNode.path("rank_last_week").isInt() ? bookNode.get("rank_last_week").asInt() : null;
        Integer peakPosition = payloadMapper.calculatePeakPosition(bookNode);
        String providerRef = payloadMapper.firstNonEmptyText(bookNode, "amazon_product_url");
        String rawItem = payloadMapper.serializeBookNode(bookNode);

        collectionPersistenceService.upsertBestsellerMembership(new BookCollectionPersistenceService.BestsellerMembershipDto(
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
        ));

        persistenceCollaborator.assignCoreTags(
            canonicalId,
            listContext,
            bookNode,
            new NytBestsellerPersistenceCollaborator.RankingStats(
                rank,
                weeksOnList,
                rankLastWeek,
                peakPosition
            )
        );
        return true;
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
     * Outcome of one NYT overview ingestion attempt.
     *
     * @param executed whether provider ingestion ran instead of an intentional disabled skip
     * @param totalLists number of provider list nodes received
     * @param usableLists number of lists with a stable code and at least one entry
     * @param processedEntries number of book entries inspected across usable lists
     * @param persistedMemberships number of bestseller memberships persisted successfully
     */
    public record NytIngestSummary(
        boolean executed,
        int totalLists,
        int usableLists,
        int processedEntries,
        int persistedMemberships
    ) {

        private static NytIngestSummary skipped() {
            return new NytIngestSummary(false, 0, 0, 0, 0);
        }

        /**
         * Indicates that the provider response produced observable catalog membership data.
         *
         * @return true only for executed, nonempty ingestion
         */
        public boolean hasValidatedIngestion() {
            return executed && usableLists > 0 && processedEntries > 0 && persistedMemberships > 0;
        }
    }

    private record ListIngestOutcome(boolean usableList, int processedEntries, int persistedMemberships) {

        private static ListIngestOutcome unusable() {
            return new ListIngestOutcome(false, 0, 0);
        }
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
