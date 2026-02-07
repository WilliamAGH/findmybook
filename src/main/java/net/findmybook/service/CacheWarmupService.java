package net.findmybook.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Warms up critical caches on application startup to prevent first-request delays.
 * Also provides scheduled refresh to keep cache warm.
 */
@Service
@Slf4j
public class CacheWarmupService {

    private static final Duration MIN_BESTSELLER_WARMUP_INTERVAL = Duration.ofSeconds(5);

    private final NewYorkTimesService newYorkTimesService;
    private final AtomicBoolean bestsellersWarmupInProgress = new AtomicBoolean(false);
    private final AtomicLong lastBestsellersWarmupStartedAt = new AtomicLong(0);

    @Value("${app.cache.warmup.bestsellers.list-name:hardcover-fiction}")
    private String bestsellerListName;

    @Value("${app.cache.warmup.bestsellers.limit:8}")
    private int bestsellerLimit;

    public CacheWarmupService(NewYorkTimesService newYorkTimesService) {
        this.newYorkTimesService = newYorkTimesService;
    }

    /**
     * Warm cache when application is ready.
     * Runs asynchronously to not delay startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmupCachesOnStartup() {
        log.info("Starting cache warmup...");
        
        // Warm NYT bestsellers cache
        warmupBestsellersCache();
        
        log.info("Cache warmup completed");
    }

    /**
     * Refresh bestsellers cache every 15 minutes to keep it warm.
     */
    @Scheduled(fixedDelayString = "${app.cache.warmup.bestsellers.refresh-interval:900000}") // 15 minutes
    public void scheduledBestsellersRefresh() {
        log.debug("Refreshing bestsellers cache (scheduled)");
        warmupBestsellersCache();
    }

    private void warmupBestsellersCache() {
        long now = System.currentTimeMillis();
        long lastStart = lastBestsellersWarmupStartedAt.get();
        if (lastStart > 0 && now - lastStart < MIN_BESTSELLER_WARMUP_INTERVAL.toMillis()) {
            log.debug("Skipping bestsellers cache warmup; last run started {} ms ago", now - lastStart);
            return;
        }

        if (!bestsellersWarmupInProgress.compareAndSet(false, true)) {
            log.debug("Bestsellers cache warmup already running; skipping duplicate invocation");
            return;
        }

        try {
            lastBestsellersWarmupStartedAt.set(now);
            var warmed = newYorkTimesService
                .getCurrentBestSellersCards(bestsellerListName, bestsellerLimit)
                .block(Duration.ofSeconds(30));
            int warmedCount = warmed != null ? warmed.size() : 0;
            log.info("Warmed bestsellers cache with {} books from '{}'", warmedCount, bestsellerListName);
        } catch (RuntimeException e) {
            log.error("Bestsellers cache warmup failed for list '{}': {}", bestsellerListName, e.getMessage(), e);
            throw new IllegalStateException("Bestsellers cache warmup failed", e);
        } finally {
            bestsellersWarmupInProgress.set(false);
        }
    }
}
