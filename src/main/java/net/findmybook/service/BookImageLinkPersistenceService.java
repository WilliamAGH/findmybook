package net.findmybook.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import net.findmybook.dto.BookAggregate;
import net.findmybook.service.image.CoverPersistenceService;
import net.findmybook.util.UrlUtils;
import net.findmybook.util.cover.CoverQuality;
import net.findmybook.util.cover.ImageDimensionUtils;
import jakarta.annotation.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Persists and evaluates cover image links for books.
 */
@Service
@Slf4j
public class BookImageLinkPersistenceService {

    private final JdbcTemplate jdbcTemplate;
    private final CoverPersistenceService coverPersistenceService;

    public BookImageLinkPersistenceService(JdbcTemplate jdbcTemplate,
                                           CoverPersistenceService coverPersistenceService) {
        this.jdbcTemplate = jdbcTemplate;
        this.coverPersistenceService = coverPersistenceService;
    }

    /**
     * Persists incoming image links if they outrank existing cover quality, and returns
     * normalized links plus canonical URL metadata for downstream events.
     */
    public ImageLinkPersistenceResult persistImageLinks(UUID bookId, @Nullable BookAggregate.ExternalIdentifiers identifiers) {
        if (identifiers == null || identifiers.getImageLinks() == null || identifiers.getImageLinks().isEmpty()) {
            return ImageLinkPersistenceResult.empty();
        }

        Map<String, String> normalizedImageLinks = collectNormalizedImageLinks(identifiers.getImageLinks());
        if (normalizedImageLinks.isEmpty()) {
            return ImageLinkPersistenceResult.empty();
        }

        CoverQualitySnapshot incomingQuality = computeIncomingCoverQuality(normalizedImageLinks);
        if (!incomingQuality.hasData()) {
            return ImageLinkPersistenceResult.empty();
        }

        CoverQualitySnapshot existingQuality = fetchExistingCoverQuality(bookId);
        if (existingQuality.isStrictlyBetterThan(incomingQuality)) {
            log.debug(
                "Existing cover quality for book {} (score={}, pixels={}) outranks incoming (score={}, pixels={}); skipping update",
                bookId,
                existingQuality.score(),
                existingQuality.pixelArea(),
                incomingQuality.score(),
                incomingQuality.pixelArea()
            );
            return new ImageLinkPersistenceResult(false, normalizedImageLinks, selectPreferredImageUrl(normalizedImageLinks));
        }

        String source = identifiers.getSource() != null ? identifiers.getSource() : "GOOGLE_BOOKS";
        try {
            CoverPersistenceService.PersistenceResult result = coverPersistenceService.persistFromGoogleImageLinks(
                bookId,
                normalizedImageLinks,
                source
            );
            if (!result.success()) {
                throw new IllegalStateException("Cover persistence returned unsuccessful result for " + bookId);
            }

            String canonicalImageUrl = result.canonicalUrl() != null && !result.canonicalUrl().isBlank()
                ? result.canonicalUrl()
                : selectPreferredImageUrl(normalizedImageLinks);
            return new ImageLinkPersistenceResult(true, normalizedImageLinks, canonicalImageUrl);
        } catch (DataAccessException exception) {
            log.error("Cover persistence data access failure for book {}: {}", bookId, exception.getMessage(), exception);
            throw exception;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            log.error("Cover persistence validation failure for book {}: {}", bookId, exception.getMessage(), exception);
            throw exception;
        }
    }

    private CoverQualitySnapshot fetchExistingCoverQuality(UUID bookId) {
        try {
            return jdbcTemplate.query(
                """
                SELECT s3_image_path, url, width, height, is_high_resolution
                FROM book_image_links
                WHERE book_id = ?
                ORDER BY COALESCE(is_high_resolution, false) DESC,
                         COALESCE(width * height, 0) DESC,
                         created_at DESC
                LIMIT 1
                """,
                rs -> {
                    if (!rs.next()) {
                        return CoverQualitySnapshot.absent();
                    }
                    String s3Path = rs.getString("s3_image_path");
                    String url = rs.getString("url");
                    Integer width = rs.getObject("width", Integer.class);
                    Integer height = rs.getObject("height", Integer.class);
                    Boolean highRes = rs.getObject("is_high_resolution", Boolean.class);
                    int score = CoverQuality.rank(s3Path, url, width, height, highRes);
                    long pixels = ImageDimensionUtils.totalPixels(width, height);
                    boolean resolvedHighRes = Boolean.TRUE.equals(highRes) || ImageDimensionUtils.isHighResolution(width, height);
                    return new CoverQualitySnapshot(score, pixels, resolvedHighRes, true);
                },
                bookId
            );
        } catch (DataAccessException exception) {
            log.error("Failed to evaluate existing cover quality for book {}: {}", bookId, exception.getMessage(), exception);
            throw new IllegalStateException("Cover quality evaluation failed for book " + bookId, exception);
        }
    }

    private CoverQualitySnapshot computeIncomingCoverQuality(Map<String, String> imageLinks) {
        CoverQualitySnapshot best = CoverQualitySnapshot.absent();
        for (Map.Entry<String, String> entry : imageLinks.entrySet()) {
            String url = entry.getValue();
            if (url == null || url.isBlank()) {
                continue;
            }

            ImageDimensionUtils.DimensionEstimate estimate = ImageDimensionUtils.estimateFromGoogleType(entry.getKey());
            int score = CoverQuality.rank(null, url, estimate.width(), estimate.height(), estimate.highRes());
            long pixels = ImageDimensionUtils.totalPixels(estimate.width(), estimate.height());
            boolean highRes = estimate.highRes() || ImageDimensionUtils.isHighResolution(estimate.width(), estimate.height());
            CoverQualitySnapshot candidate = new CoverQualitySnapshot(score, pixels, highRes, true);
            if (candidate.isStrictlyBetterThan(best)) {
                best = candidate;
            }
        }
        return best;
    }

    private Map<String, String> collectNormalizedImageLinks(Map<String, String> imageLinks) {
        Map<String, String> sanitized = new LinkedHashMap<>();
        imageLinks.forEach((key, value) -> {
            if (key != null && value != null && !value.isBlank()) {
                sanitized.put(key, UrlUtils.normalizeToHttps(value));
            }
        });
        return sanitized.isEmpty() ? Map.of() : Map.copyOf(sanitized);
    }

    private String selectPreferredImageUrl(Map<String, String> imageLinks) {
        List<String> priorityOrder = List.of(
            "canonical",
            "extraLarge",
            "large",
            "medium",
            "small",
            "thumbnail",
            "smallThumbnail"
        );

        for (String key : priorityOrder) {
            String candidate = imageLinks.get(key);
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return imageLinks.values().stream()
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse(null);
    }

    private record CoverQualitySnapshot(int score,
                                        long pixelArea,
                                        boolean highRes,
                                        boolean present) {

        static CoverQualitySnapshot absent() {
            return new CoverQualitySnapshot(0, 0, false, false);
        }

        boolean hasData() {
            return present;
        }

        boolean isStrictlyBetterThan(CoverQualitySnapshot other) {
            if (!present) {
                return false;
            }
            if (other == null || !other.present) {
                return true;
            }
            if (score > other.score) {
                return true;
            }
            if (score < other.score) {
                return false;
            }
            if (pixelArea > other.pixelArea) {
                return true;
            }
            if (pixelArea < other.pixelArea) {
                return false;
            }
            return highRes && !other.highRes;
        }
    }

    /**
     * Result data for image link persistence and event payload hydration.
     */
    public record ImageLinkPersistenceResult(
        boolean persisted,
        Map<String, String> normalizedImageLinks,
        @Nullable String canonicalImageUrl
    ) {

        public static ImageLinkPersistenceResult empty() {
            return new ImageLinkPersistenceResult(false, Map.of(), null);
        }
    }
}
