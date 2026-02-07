package net.findmybook.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import net.findmybook.config.SitemapProperties;
import net.findmybook.service.SitemapService.BookSitemapItem;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.Nullable;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds sitemap snapshots from Postgres and synchronizes supporting artifacts (S3 JSON, cover probes, API warmups).
 */
@Service
@Slf4j
public class BookSitemapService {

    
    private final SitemapService sitemapService;
    private final SitemapProperties sitemapProperties;
    private final ObjectMapper objectMapper;
    private final S3StorageService s3StorageService;

    public BookSitemapService(SitemapService sitemapService,
                              SitemapProperties sitemapProperties,
                              ObjectMapper objectMapper,
                              @Nullable S3StorageService s3StorageService) {
        this.sitemapService = sitemapService;
        this.sitemapProperties = sitemapProperties;
        this.objectMapper = objectMapper;
        this.s3StorageService = s3StorageService;
    }

    @PostConstruct
    void validateConfiguration() {
        if (s3StorageService != null) {
            String s3Key = sitemapProperties.getS3AccumulatedIdsKey();
            if (s3Key == null || s3Key.isBlank()) {
                throw new IllegalStateException("BookSitemapService requires sitemap.s3.accumulated-ids-key when S3 storage is enabled.");
            }
        }
    }

    public SnapshotSyncResult synchronizeSnapshot() {
        SitemapSnapshot snapshot = buildSnapshot();
        boolean uploaded = uploadSnapshot(snapshot);
        return new SnapshotSyncResult(snapshot, uploaded, sitemapProperties.getS3AccumulatedIdsKey());
    }

    public SitemapSnapshot buildSnapshot() {
        int pageCount = sitemapService.getBooksXmlPageCount();
        if (pageCount == 0) {
            log.info("Sitemap snapshot build found no book pages to process.");
            return new SitemapSnapshot(Instant.now(), Collections.emptyList());
        }

        List<BookSitemapItem> aggregated = new ArrayList<>();
        for (int page = 1; page <= pageCount; page++) {
            List<BookSitemapItem> pageItems = sitemapService.getBooksForXmlPage(page);
            if (pageItems.isEmpty()) {
                continue;
            }
            aggregated.addAll(pageItems);
        }

        log.info("Built sitemap snapshot with {} book entries across {} XML pages.", aggregated.size(), pageCount);
        return new SitemapSnapshot(Instant.now(), Collections.unmodifiableList(aggregated));
    }

    public boolean uploadSnapshot(SitemapSnapshot snapshot) {
        if (snapshot.books().isEmpty()) {
            log.info("Skipping sitemap snapshot upload because no books were harvested.");
            return false;
        }
        if (s3StorageService == null) {
            log.info("Skipping sitemap snapshot upload because S3 storage service is not configured.");
            return false;
        }
        String s3Key = sitemapProperties.getS3AccumulatedIdsKey();
        if (s3Key == null || s3Key.isBlank()) {
            log.warn("Sitemap snapshot upload skipped – no S3 key configured (sitemap.s3.accumulated-ids-key).");
            return false;
        }

        try {
            String payload = buildSnapshotPayload(snapshot);
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
            s3StorageService.uploadFileAsync(s3Key, inputStream, bytes.length, "application/json").join();
            log.info("Uploaded sitemap snapshot ({} entries) to S3 key '{}'.", snapshot.books().size(), s3Key);
            return true;
        } catch (JacksonException | java.util.concurrent.CompletionException | IllegalArgumentException ex) {
            throw new IllegalStateException("Failed to upload sitemap snapshot to S3 key '" + s3Key + "'", ex);
        } catch (RuntimeException ex) {
            log.error("Unexpected runtime failure uploading sitemap snapshot to S3 key '{}': {}",
                s3Key, ex.getMessage(), ex);
            throw new IllegalStateException(
                "Unexpected runtime failure while uploading sitemap snapshot to S3 key '" + s3Key + "'",
                ex
            );
        }
    }

    private String buildSnapshotPayload(SitemapSnapshot snapshot) throws JacksonException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("generatedAt", snapshot.generatedAt().toString());
        root.put("totalBooks", snapshot.books().size());
        ArrayNode items = root.putArray("books");
        snapshot.books().forEach(book -> {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", book.bookId());
            node.put("slug", book.slug());
            node.put("title", book.title());
            if (book.updatedAt() != null) {
                node.put("updatedAt", book.updatedAt().toString());
            }
            items.add(node);
        });
        return objectMapper.writeValueAsString(root);
    }

    public record SitemapSnapshot(Instant generatedAt, List<BookSitemapItem> books) {}

    public record SnapshotSyncResult(SitemapSnapshot snapshot, boolean uploaded, String s3Key) {}

    public record ExternalHydrationSummary(int attempted, int succeeded, int failed, int skipped) {}
}
