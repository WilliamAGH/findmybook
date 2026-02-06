package net.findmybook.util;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

@Slf4j
public final class ReactiveErrorUtils {
    private ReactiveErrorUtils() {
    }

    public static <T> Function<Throwable, Mono<T>> logAndPropagateMono(String context) {
        return error -> Mono.error(wrapWithContext(context, error));
    }

    public static <T> Function<Throwable, Flux<T>> logAndPropagateFlux(String context) {
        return error -> Flux.error(wrapWithContext(context, error));
    }

    public static <T> Mono<T> logErrorAndPropagate(Throwable error, String context) {
        return Mono.error(wrapWithContext(context, error));
    }

    public static void logError(Throwable error, String context) {
        log.error("{}: {}", context, error.getMessage(), error);
    }

    public static void logErrorWithDetails(Throwable error, String context, Object... details) {
        log.error("{}: {} - Details: {}", context, error.getMessage(), details, error);
    }

    /**
     * Collect a Flux to a list and propagate failures with context.
     */
    public static <T> Mono<List<T>> collectOrPropagate(Flux<T> flux) {
        return flux.collectList()
            .onErrorMap(error -> wrapWithContext("ReactiveErrorUtils.collectOrPropagate failed", error));
    }

    /**
     * Collect a Flux to a list and propagate failures with caller-provided context.
     */
    public static <T> Mono<List<T>> collectOrPropagate(Flux<T> flux, String context) {
        return flux.collectList()
            .onErrorMap(error -> wrapWithContext(context, error));
    }

    /**
     * Take a limited number of items and collect to list.
     */
    public static <T> Mono<List<T>> limitAndCollect(Flux<T> flux, int limit) {
        return flux.take(limit)
            .collectList()
            .defaultIfEmpty(Collections.emptyList());
    }

    /**
     * Take a limited number of items, collect to list, and propagate failures.
     */
    public static <T> Mono<List<T>> limitAndCollectOrPropagate(Flux<T> flux, int limit, String context) {
        return flux.take(limit)
            .collectList()
            .defaultIfEmpty(Collections.emptyList())
            .onErrorMap(error -> wrapWithContext(context, error));
    }

    /**
     * Convert a Mono that might be empty to a default value.
     */
    public static <T> Mono<T> withDefault(Mono<T> mono, T defaultValue) {
        return mono.defaultIfEmpty(defaultValue);
    }

    /**
     * Logs the error with context and wraps only when necessary.
     * Preserves the original exception type for RuntimeExceptions so callers
     * can handle errors by type (e.g., TimeoutException vs DataAccessException).
     */
    private static RuntimeException wrapWithContext(String context, Throwable error) {
        log.error("{}: {}", context, error.getMessage(), error);
        if (error instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException(context, error);
    }
}
