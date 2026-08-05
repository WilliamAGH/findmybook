package net.findmybook.support.search;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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

    private static final int SNAPSHOT_CACHE_MAXIMUM_SIZE = 1_000;
    private static final Duration SNAPSHOT_CACHE_TTL = Duration.ofMinutes(2);

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
            .doOnError(loadFailure -> snapshots.invalidate(key))
            .cache());
    }
}
