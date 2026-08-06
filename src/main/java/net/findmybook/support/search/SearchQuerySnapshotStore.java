package net.findmybook.support.search;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import net.findmybook.service.SearchPaginationService;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

/**
 * Keeps one immutable ordered candidate snapshot per canonical search request so offset pages
 * cannot observe different repository or provider result universes during active pagination.
 */
public final class SearchQuerySnapshotStore {

    private static final int SNAPSHOT_CACHE_MAXIMUM_SIZE = 100;
    private static final Duration SNAPSHOT_CACHE_TTL = Duration.ofMinutes(2);
    private static final int MAX_CONCURRENT_COLD_LOADS = 4;
    private static final int MAX_COLD_LOADS_PER_MINUTE = 30;

    private final Bulkhead coldLoadBulkhead = Bulkhead.of(
        "searchSnapshotColdLoadBulkhead",
        BulkheadConfig.custom()
            .maxConcurrentCalls(MAX_CONCURRENT_COLD_LOADS)
            .maxWaitDuration(Duration.ZERO)
            .build()
    );
    private final RateLimiter coldLoadRateLimiter = RateLimiter.of(
        "searchSnapshotColdLoadRateLimiter",
        RateLimiterConfig.custom()
            .limitForPeriod(MAX_COLD_LOADS_PER_MINUTE)
            .limitRefreshPeriod(Duration.ofMinutes(1))
            .timeoutDuration(Duration.ZERO)
            .build()
    );

    private final Cache<SearchPaginationService.SearchRequest, Mono<SearchPaginationService.SearchPage>> snapshots =
        Caffeine.newBuilder()
            .maximumSize(SNAPSHOT_CACHE_MAXIMUM_SIZE)
            .expireAfterWrite(SNAPSHOT_CACHE_TTL)
            .build();

    /**
     * Returns the shared immutable snapshot for a request, atomically starting one load when the
     * canonical offset-zero key is absent. Failed loads are evicted so a later request can retry.
     *
     * @param request requested search page
     * @param snapshotLoader loader for the canonical offset-zero request
     * @return cached or newly loaded immutable search snapshot
     */
    public Mono<SearchPaginationService.SearchPage> getOrLoad(
        SearchPaginationService.SearchRequest request,
        Function<SearchPaginationService.SearchRequest, Mono<SearchPaginationService.SearchPage>> snapshotLoader
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(snapshotLoader, "snapshotLoader");
        SearchPaginationService.SearchRequest snapshotKey = request.atStartIndex(0);
        return snapshots.get(snapshotKey, key -> snapshotLoader.apply(key)
            .transformDeferred(RateLimiterOperator.of(coldLoadRateLimiter))
            .transformDeferred(BulkheadOperator.of(coldLoadBulkhead))
            .doOnError(loadFailure -> snapshots.invalidate(key))
            .cache());
    }
}
