package net.findmybook.util.cover;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import net.findmybook.model.image.CoverImageSource;

/**
 * Single Source of Truth for S3 object key generation for book cover images.
 * 
 * Consolidates logic from:
 * - S3CoverKeyResolver / S3 cover storage gateway paths
 * 
 * Key format: images/book-covers/{bookId}-lg-{source}.{ext}
 * Example: images/book-covers/9780123456789-lg-google-books.jpg
 * 
 * @author William Callahan
 */
public final class S3KeyGenerator {
    
    private static final String COVER_IMAGES_DIRECTORY = "images/book-covers/";
    private static final String PROVENANCE_DATA_DIRECTORY = "images/provenance-data/";
    private static final String LARGE_SUFFIX = "-lg";
    
    private S3KeyGenerator() {
        // Utility class - prevent instantiation
    }
    
    /**
     * Generates S3 object key for a book cover image.
     * 
     * @param bookId Book identifier (ISBN or Google Volume ID)
     * @param fileExtension File extension including dot (e.g., ".jpg")
     * @param source Cover image source
     * @return S3 object key for the cover image
     * @throws IllegalArgumentException if bookId is invalid
     */
    public static String generateCoverKey(String bookId, String fileExtension, CoverImageSource source) {
        validateBookId(bookId);
        
        String normalizedExtension = normalizeExtension(fileExtension);
        String sourceSegment = CoverSourceMapper.toS3KeySegment(source);
        
        return COVER_IMAGES_DIRECTORY + bookId + LARGE_SUFFIX + "-" + sourceSegment + normalizedExtension;
    }

    /**
     * Generates S3 object key for a book cover image using a raw, possibly free-form source label.
     * <p>
     * Legacy code paths often pass download identifiers such as "GoogleHint-AsIs" or
     * "google_books 2". This helper normalizes those labels to the same canonical
     * segments used elsewhere (lowercase, hyphen separated, alphanumeric only) so
     * uploads and lookups remain deterministic.
     *
     * @param bookId Book identifier (ISBN or Google Volume ID)
     * @param fileExtension File extension including dot (e.g., ".jpg")
     * @param rawSource Raw download/source label
     * @return S3 object key for the cover image
     */
    public static String generateCoverKeyFromRawSource(String bookId, String fileExtension, String rawSource) {
        validateBookId(bookId);

        String normalizedExtension = normalizeExtension(fileExtension);
        String sourceSegment = normalizeRawSource(rawSource);

        return COVER_IMAGES_DIRECTORY + bookId + LARGE_SUFFIX + "-" + sourceSegment + normalizedExtension;
    }

    /**
     * Derives the canonical S3 key segment from arbitrary input.
     */
    public static String normalizeRawSource(String rawSource) {
        if (rawSource == null || rawSource.isBlank()) {
            return "unknown";
        }

        // Only map to canonical sources when the label matches the enum name directly.
        String normalizedEnumCandidate = rawSource
            .trim()
            .toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]+", "_");

        for (CoverImageSource source : CoverImageSource.values()) {
            if (source == CoverImageSource.UNDEFINED || source == CoverImageSource.ANY) {
                continue;
            }

            if (source.name().equals(normalizedEnumCandidate)) {
                return CoverSourceMapper.toS3KeySegment(source);
            }
        }

        // Fallback: slugify arbitrary input.
        String slug = rawSource.trim().toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", "-")              // collapse whitespace
            .replaceAll("[^a-z0-9_-]", "-")         // non-url-safe -> '-'
            .replaceAll("-+", "-")                  // collapse duplicate hyphens
            .replaceAll("^-|-$", "");               // trim leading/trailing hyphen

        if (slug.isBlank()) {
            return "unknown";
        }
        return slug;
    }
    
    /**
     * Builds an ordered list of candidate S3 keys by generating plausible
     * variants of the raw source label (spaces, hyphens, underscores, canonical form).
     *
     * @param bookId        Book identifier
     * @param fileExtension File extension including dot
     * @param rawSource     Raw source label from download or external API
     * @return Ordered candidate keys, most likely match first
     */
    public static List<String> buildCandidateKeys(String bookId, String fileExtension, String rawSource) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String segment : candidateSegmentsFor(rawSource)) {
            keys.add(generateCoverKeyFromRawSource(bookId, fileExtension, segment));
        }
        return new ArrayList<>(keys);
    }

    /**
     * Generates S3 object key for provenance data (debug mode).
     * 
     * @param imageS3Key The S3 key of the associated cover image
     * @return S3 object key for the provenance data JSON file
     */
    public static String generateProvenanceKey(String imageS3Key) {
        if (imageS3Key == null || imageS3Key.isBlank()) {
            throw new IllegalArgumentException("Image S3 key cannot be null or blank");
        }
        
        // Extract filename from image key
        String filename = imageS3Key.substring(imageS3Key.lastIndexOf('/') + 1);
        
        // Replace image extension with .txt for provenance file
        String provenanceFilename = filename.replaceAll("\\.(?i)(jpg|jpeg|png|gif|webp|svg)$", ".txt");
        
        // If no extension was replaced, append .txt
        if (provenanceFilename.equals(filename)) {
            provenanceFilename = filename + ".txt";
        }
        
        return PROVENANCE_DATA_DIRECTORY + provenanceFilename;
    }
    
    /**
     * Validates book ID for S3 key generation.
     * 
     * @param bookId The book ID to validate
     * @throws IllegalArgumentException if bookId is invalid
     */
    private static void validateBookId(String bookId) {
        if (bookId == null || bookId.isBlank()) {
            throw new IllegalArgumentException("Book ID cannot be null or empty");
        }
        
        if (!bookId.matches("[a-zA-Z0-9_-]+")) {
            throw new IllegalArgumentException(
                "Book ID contains invalid characters: " + bookId + 
                ". Only alphanumeric characters, hyphens, and underscores are allowed."
            );
        }
    }
    
    /**
     * Normalizes file extension to include leading dot and default to .jpg.
     * 
     * @param fileExtension The file extension (with or without dot)
     * @return Normalized extension with leading dot
     */
    private static String normalizeExtension(String fileExtension) {
        if (fileExtension == null || fileExtension.isBlank()) {
            return ".jpg";
        }
        
        String trimmed = fileExtension.trim().toLowerCase();
        
        // Ensure leading dot
        if (!trimmed.startsWith(".")) {
            trimmed = "." + trimmed;
        }
        
        // Validate it's a known image extension
        if (!trimmed.matches("\\.(jpg|jpeg|png|gif|webp|svg)")) {
            return ".jpg";
        }
        
        return trimmed;
    }
    
    /**
     * Gets the cover images directory prefix.
     * 
     * @return The S3 directory prefix for cover images
     */
    public static String getCoverImagesDirectory() {
        return COVER_IMAGES_DIRECTORY;
    }
    
    /**
     * Gets the provenance data directory prefix.
     * 
     * @return The S3 directory prefix for provenance data
     */
    public static String getProvenanceDataDirectory() {
        return PROVENANCE_DATA_DIRECTORY;
    }

    private static List<String> candidateSegmentsFor(String rawSource) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();

        if (rawSource != null) {
            String trimmed = rawSource.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                variants.add(trimmed);
                variants.add(trimmed.replace(' ', '-'));
                variants.add(trimmed.replace(' ', '_'));
            }
        }

        String canonical = normalizeRawSource(rawSource);
        if (canonical != null && !canonical.isBlank()) {
            variants.add(canonical);
            variants.add(canonical.replace('-', '_'));
            variants.add(canonical.replace('_', '-'));
        }

        variants.removeIf(String::isBlank);
        return new ArrayList<>(variants);
    }
}
