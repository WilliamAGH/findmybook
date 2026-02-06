package net.findmybook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import net.findmybook.config.SitemapProperties;
import net.findmybook.service.s3.S3FetchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;

/**
 * Loads the most recent sitemap snapshot from S3 for use when Postgres is unavailable.
 */
@Component
public class SitemapSnapshotFallbackLoader implements SitemapFallbackProvider {

    private static final Logger log = LoggerFactory.getLogger(SitemapSnapshotFallbackLoader.class);

    private final ObjectMapper objectMapper;
    private final ObjectProvider<S3StorageService> s3StorageServiceProvider;
    private final SitemapProperties sitemapProperties;

    public SitemapSnapshotFallbackLoader(ObjectMapper objectMapper,
                                         ObjectProvider<S3StorageService> s3StorageServiceProvider,
                                         SitemapProperties sitemapProperties) {
        this.objectMapper = objectMapper;
        this.s3StorageServiceProvider = s3StorageServiceProvider;
        this.sitemapProperties = sitemapProperties;
    }

    @Override
    public Optional<SitemapService.FallbackSnapshot> loadSnapshot() {
        S3StorageService s3 = s3StorageServiceProvider.getIfAvailable();
        if (s3 == null) {
            return Optional.empty();
        }

        String key = sitemapProperties.getS3AccumulatedIdsKey();
        if (!StringUtils.hasText(key)) {
            log.debug("Skipping sitemap fallback load – no S3 key configured.");
            return Optional.empty();
        }

        try {
            S3FetchResult<String> result = s3.fetchUtf8ObjectAsync(key).join();
            if (!result.isSuccess()) {
                logFallbackStatus(key, result);
                return Optional.empty();
            }

            String payload = result.getData().orElse(null);
            if (!StringUtils.hasText(payload)) {
                log.warn("Sitemap fallback payload for key {} was empty.", key);
                return Optional.empty();
            }

            SitemapService.FallbackSnapshot snapshot = parseSnapshot(payload);
            if (snapshot.pageCount(sitemapProperties.getXmlPageSize()) == 0) {
                log.warn("Sitemap fallback snapshot from key {} contained no book entries.", key);
            } else {
                log.info("Loaded sitemap fallback snapshot from key {} ({} books).", key, snapshot.bookCount());
            }
            return Optional.of(snapshot);
        } catch (CompletionException ex) {
            log.warn("Failed to fetch sitemap fallback snapshot from S3 key {}: {}", key, rootCauseMessage(ex));
            return Optional.empty();
        } catch (IOException ex) {
            log.warn("Failed to parse sitemap fallback snapshot from S3 key {}: {}", key, ex.getMessage());
            return Optional.empty();
        } catch (RuntimeException ex) {
            log.warn("Unexpected error loading sitemap fallback snapshot from S3 key {}: {}", key, ex.getMessage());
            return Optional.empty();
        }
    }

    private void logFallbackStatus(String key, S3FetchResult<String> result) {
        switch (result.getStatus()) {
            case NOT_FOUND -> log.debug("No sitemap fallback snapshot found at S3 key {}.", key);
            case SERVICE_ERROR -> log.warn("S3 reported an error fetching sitemap fallback snapshot from key {}: {}",
                    key, result.getErrorMessage().orElse("unknown error"));
            case DISABLED -> log.debug("S3 storage disabled; sitemap fallback snapshot unavailable for key {}.", key);
            default -> log.debug("Sitemap fallback snapshot unavailable for key {} (status: {}).", key, result.getStatus());
        }
    }

    private SitemapService.FallbackSnapshot parseSnapshot(String payload) throws IOException {
        JsonNode root = objectMapper.readTree(payload);
        Instant generatedAt = parseInstant(root.path("generatedAt").asText(null)).orElse(Instant.EPOCH);
        ArrayNode booksNode = root.withArray("books");
        List<SitemapService.BookSitemapItem> books = new ArrayList<>(booksNode.size());
        for (JsonNode bookNode : booksNode) {
            String id = textOrNull(bookNode, "id");
            String slug = textOrNull(bookNode, "slug");
            String title = textOrNull(bookNode, "title");
            Instant updatedAt = parseInstant(textOrNull(bookNode, "updatedAt")).orElse(generatedAt);
            books.add(new SitemapService.BookSitemapItem(id, slug, title, updatedAt));
        }
        return new SitemapService.FallbackSnapshot(generatedAt, books);
    }

    private Optional<Instant> parseInstant(String value) {
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value));
        } catch (DateTimeParseException ex) {
            log.debug("Ignoring invalid instant '{}' in sitemap fallback snapshot: {}", value, ex.getMessage());
            return Optional.empty();
        }
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText(null) : null;
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }
}
