package net.findmybook.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.util.StringUtils;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for book card displays (homepage, search grid).
 * Contains ONLY fields actually rendered in card templates - no over-fetching.
 * 
 * This DTO is populated by optimized SQL queries that fetch exactly what's needed
 * in a single database round-trip, eliminating N+1 query problems.
 * 
 * Used by:
 * - Homepage bestsellers grid
 * - Search results grid view
 * - Recently viewed books section
 * 
 * @param id Book UUID as string
 * @param slug URL-friendly book identifier
 * @param title Book title
 * @param authors List of author names in order
 * @param coverUrl Primary cover image URL (S3 or resolved CDN)
 * @param coverS3Key Persisted S3 object key when available
 * @param fallbackCoverUrl Secondary cover to try when the primary fails (typically external source)
 * @param averageRating Average rating (0.0-5.0)
 * @param ratingsCount Total number of ratings
 * @param tags Qualifier tags as key-value pairs (e.g., {"nyt_bestseller": {"list": "hardcover-fiction"}})
 */
public record BookCard(
    String id,
    String slug,
    String title,
    
    @JsonProperty("authors")
    List<String> authors,
    
    @JsonProperty("cover_url")
    String coverUrl,

    @JsonProperty("cover_s3_key")
    String coverS3Key,

    @JsonProperty("fallback_cover_url")
    String fallbackCoverUrl,
    
    @JsonProperty("average_rating")
    Double averageRating,
    
    @JsonProperty("ratings_count")
    Integer ratingsCount,
    
    /**
     * Tags/qualifiers for rendering badges like "NYT Bestseller", "Award Winner", etc.
     * Key is tag type (e.g., "nyt_bestseller"), value is metadata object
     *
     * Bug #9 Fix: Single canonical representation - NO duplication with extras field.
     * This is the ONLY place qualifiers are stored in DTOs.
     */
    Map<String, Object> tags,

    @JsonProperty("cover_grayscale")
    Boolean coverGrayscale,

    @JsonProperty("published_date")
    LocalDate publishedDate
) {
    /**
     * Compact constructor ensuring defensive copies for immutability
     */
    public BookCard {
        authors = authors == null ? List.of() : List.copyOf(authors);
        coverS3Key = StringUtils.hasText(coverS3Key) ? coverS3Key : null;
        // Keep fallbackCoverUrl as-is (null if not provided) to enable proper fallback cascade
        // Do NOT default to coverUrl - that breaks the fallback chain
        fallbackCoverUrl = StringUtils.hasText(fallbackCoverUrl) ? fallbackCoverUrl : null;
        tags = tags == null ? Map.of() : Map.copyOf(tags);
        coverGrayscale = Boolean.TRUE.equals(coverGrayscale) ? Boolean.TRUE : null;
    }

    /** Backward-compatible constructor without publishedDate (defaults null). */
    public BookCard(String id,
                    String slug,
                    String title,
                    List<String> authors,
                    String coverUrl,
                    String coverS3Key,
                    String fallbackCoverUrl,
                    Double averageRating,
                    Integer ratingsCount,
                    Map<String, Object> tags,
                    Boolean coverGrayscale) {
        this(id, slug, title, authors, coverUrl, coverS3Key, fallbackCoverUrl, averageRating, ratingsCount, tags, coverGrayscale, null);
    }

    /** Backward-compatible constructor without coverGrayscale or publishedDate (defaults null). */
    public BookCard(String id,
                    String slug,
                    String title,
                    List<String> authors,
                    String coverUrl,
                    String coverS3Key,
                    String fallbackCoverUrl,
                    Double averageRating,
                    Integer ratingsCount,
                    Map<String, Object> tags) {
        this(id, slug, title, authors, coverUrl, coverS3Key, fallbackCoverUrl, averageRating, ratingsCount, tags, null, null);
    }

    /** Convenience constructor for external sources (no S3, no fallback, no grayscale, no date). */
    public BookCard(String id,
                    String slug,
                    String title,
                    List<String> authors,
                    String coverUrl,
                    Double averageRating,
                    Integer ratingsCount,
                    Map<String, Object> tags) {
        this(id, slug, title, authors, coverUrl, null, coverUrl, averageRating, ratingsCount, tags, null, null);
    }
    
    /**
     * Check if book has a specific qualifier tag
     */
    public boolean hasTag(String tagKey) {
        return tags != null && tags.containsKey(tagKey);
    }
}
