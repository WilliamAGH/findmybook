/**
 * Scheduler for proactively warming caches with popular book data
 * - Reduces API latency by pre-caching frequently accessed books
 * - Runs during off-peak hours to minimize impact on performance
 * - Monitors API usage to stay within rate limits
 * - Prioritizes recently viewed and popular books
 *
 * @author William Callahan
 */
package net.findmybook.scheduler;

import net.findmybook.dto.BookDetail;
import net.findmybook.model.Book;
import net.findmybook.repository.BookQueryRepository;
import net.findmybook.service.ApiRequestMonitor;
import net.findmybook.service.BookIdentifierResolver;
import net.findmybook.service.RecentlyViewedService;
import net.findmybook.util.BookDomainMapper;
import net.findmybook.util.PagingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.util.StringUtils;

/**
 * Scheduler for book data pre-fetching (cache warming functionality disabled)
 * - Previously cached popular books during low-traffic periods
 * - Still prioritizes recently viewed and trending books for fetching
 * - Uses rate-limited execution to avoid overloading the API
 * - Provides configurable behavior via properties
 * Note: Cache warming has been disabled as cache services have been removed
 */
@Configuration
@EnableScheduling
@Slf4j
public class BookCacheWarmingScheduler {

    private final RecentlyViewedService recentlyViewedService;
    private final ObjectProvider<ApiRequestMonitor> apiRequestMonitorProvider;
    private final BookQueryRepository bookQueryRepository;
    private final BookIdentifierResolver bookIdentifierResolver;
    
    private static final int WARMED_BOOKS_TRACKING_LIMIT = 500;
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final int MINUTES_PER_HOUR = 60;
    private static final int API_BUDGET_DIVISOR = 2;
    private static final long TASK_TIMEOUT_BUFFER_MS = 10_000L;

    private final Set<String> recentlyWarmedBooks = ConcurrentHashMap.newKeySet();
    private final boolean cacheWarmingEnabled;
    private final int rateLimit;
    private final int maxBooksPerRun;

    public BookCacheWarmingScheduler(RecentlyViewedService recentlyViewedService,
                                     ObjectProvider<ApiRequestMonitor> apiRequestMonitorProvider,
                                     BookQueryRepository bookQueryRepository,
                                     BookIdentifierResolver bookIdentifierResolver,
                                     @Value("${app.cache.warming.enabled:true}") boolean cacheWarmingEnabled,
                                     @Value("${app.cache.warming.rate-limit-per-minute:3}") int rateLimit,
                                     @Value("${app.cache.warming.max-books-per-run:10}") int maxBooksPerRun) {
        this.recentlyViewedService = recentlyViewedService;
        this.apiRequestMonitorProvider = apiRequestMonitorProvider;
        this.bookQueryRepository = bookQueryRepository;
        this.bookIdentifierResolver = bookIdentifierResolver;
        this.cacheWarmingEnabled = cacheWarmingEnabled;
        this.rateLimit = rateLimit;
        this.maxBooksPerRun = maxBooksPerRun;
    }

    /**
     * Scheduled task that runs during off-peak hours (e.g., 3 AM)
     * to warm caches for popular books
     */
    @Scheduled(cron = "${app.cache.warming.cron:0 0 3 * * ?}")
    public void warmPopularBookCaches() {
        if (!cacheWarmingEnabled) {
            log.debug("Book cache warming is disabled");
            return;
        }

        log.info("Starting scheduled book cache warming");
        
        // Get books to warm (recently viewed, popular, etc.)
        List<String> bookIdsToWarm = getBookIdsToWarm();
        
        if (recentlyWarmedBooks.size() > WARMED_BOOKS_TRACKING_LIMIT) {
            recentlyWarmedBooks.clear();
        }
        
        // If no books to warm, we're done
        if (bookIdsToWarm.isEmpty()) {
            log.info("No books to warm in cache");
            return;
        }
        
        AtomicInteger warmedCount = new AtomicInteger(0);
        
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        List<ScheduledFuture<?>> scheduledTasks = new ArrayList<>();

        try {
            // Validate rate limit configuration
            if (rateLimit <= 0) {
                log.warn("Invalid rateLimit configuration: {}. Skipping cache warming run.", rateLimit);
                return;
            }

            final long delayMillis = MILLIS_PER_MINUTE / rateLimit;
            
            // Check current API call count from metrics if possible
            // Inject ApiRequestMonitor if available
            ApiRequestMonitor apiRequestMonitor = apiRequestMonitorProvider.getIfAvailable();
            if (apiRequestMonitor == null) {
                throw new IllegalStateException("ApiRequestMonitor bean is required for cache warming but was not available.");
            }
            int currentHourlyRequests = apiRequestMonitor.getCurrentHourlyRequests();
            log.info("Current hourly API request count: {}. Will adjust cache warming accordingly.", currentHourlyRequests);
            
            int hourlyLimit = rateLimit * MINUTES_PER_HOUR;
            int requestBudget = PagingUtils.atLeast(hourlyLimit / API_BUDGET_DIVISOR - currentHourlyRequests, 0);
            int booksToWarm = Math.min(Math.min(bookIdsToWarm.size(), maxBooksPerRun), requestBudget);
            
            log.info("Warming {} books based on rate limit {} per minute and current API usage", 
                    booksToWarm, rateLimit);
            
            for (int i = 0; i < booksToWarm; i++) {
                final String bookId = bookIdsToWarm.get(i);
                
                // Schedule each book to be warmed with a delay
                ScheduledFuture<?> scheduledFuture = executor.schedule(() -> {
                    // Note: Cache warming functionality has been disabled as the cache service has been removed
                    log.info("Attempting to warm book ID: {} (cache functionality disabled)", bookId);
                    Book book = fetchBookForWarming(bookId).toCompletableFuture().join();
                    if (book != null) {
                        warmedCount.incrementAndGet();
                        log.info("Successfully fetched book for warming: {}",
                            book.getTitle() != null ? book.getTitle() : bookId);
                    } else {
                        log.debug("No book found for ID: {}", bookId);
                    }

                    // Track that we've processed this book
                    recentlyWarmedBooks.add(bookId);
                }, i * delayMillis, TimeUnit.MILLISECONDS);
                scheduledTasks.add(scheduledFuture);
            }
            
            executor.shutdown();
            long timeoutMillis = maxBooksPerRun * delayMillis + TASK_TIMEOUT_BUFFER_MS;
            for (ScheduledFuture<?> scheduledTask : scheduledTasks) {
                scheduledTask.get(timeoutMillis, TimeUnit.MILLISECONDS);
            }
            executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);

            log.info("Book cache warming completed. Warmed {} of {} scheduled books", warmedCount.get(), booksToWarm);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Book cache warming interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("Book cache warming task failed", cause);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Book cache warming task timed out", e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Error during book cache warming", e);
        } finally {
            if (!executor.isTerminated()) {
                executor.shutdownNow();
            }
        }
    }

    private CompletionStage<Book> fetchBookForWarming(String bookId) {
        return CompletableFuture.completedFuture(resolveBookForWarming(bookId));
    }

    /**
     * Get a list of book IDs to warm in the cache, based on recently viewed books.
     * This currently prioritizes recently viewed books that have not been warmed recently.
     * (Future consideration: popular/trending books or those specifically marked for warming could be added.)
     * 
     * @return List of book IDs to warm
     */
    private List<String> getBookIdsToWarm() {
        List<String> result = new ArrayList<>();

        int lookupLimit = Math.max(maxBooksPerRun * 2, 20);
        List<String> recentlyViewedIds = recentlyViewedService.getRecentlyViewedBookIds(lookupLimit);
        for (String id : recentlyViewedIds) {
            if (id != null && !id.isBlank() && !recentlyWarmedBooks.contains(id)) {
                result.add(id);
            }
        }

        return result;
    }

    private Book resolveBookForWarming(String identifier) {
        if (!StringUtils.hasText(identifier)) {
            return null;
        }
        String trimmed = identifier.trim();

        Optional<BookDetail> bySlug = bookQueryRepository.fetchBookDetailBySlug(trimmed);
        if (bySlug.isPresent()) {
            return BookDomainMapper.fromDetail(bySlug.get());
        }

        Optional<UUID> maybeUuid = bookIdentifierResolver.resolveToUuid(trimmed);
        if (maybeUuid.isEmpty()) {
            return null;
        }

        UUID uuid = maybeUuid.get();
        return bookQueryRepository.fetchBookDetail(uuid)
            .map(BookDomainMapper::fromDetail)
            .or(() -> bookQueryRepository.fetchBookCard(uuid).map(BookDomainMapper::fromCard))
            .orElse(null);
    }
}
