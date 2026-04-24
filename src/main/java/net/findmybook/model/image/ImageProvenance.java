package net.findmybook.model.image;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * Captures cover-image provenance for audit and troubleshooting workflows.
 *
 * <p>The cover pipeline records each attempted source, the final selected image, and optional
 * raw Google Books response context so support tooling can explain why a cover was chosen.</p>
 */
@Getter
@Setter
public class ImageProvenance {

    private String bookId;
    private Object googleBooksApiResponse;
    private List<AttemptedSource> attemptedImageSources;
    private SelectedImage selectedImage;
    private Instant timestamp;

    /**
     * Creates a provenance record with the current creation timestamp.
     */
    public ImageProvenance() {
        this.timestamp = Instant.now();
    }

    /**
     * Describes one attempted image fetch from an external source.
     */
    @Getter
    @Setter
    public static class AttemptedSource {
        private ImageSourceName sourceName;
        private String urlAttempted;
        private ImageAttemptStatus status;
        private String failureReason;
        private Map<String, String> metadata;
        private String fetchedUrl;
        private String dimensions;

        /**
         * Creates an attempt entry before downstream processing enriches the result.
         *
         * @param sourceName image source identifier
         * @param urlAttempted URL requested from the source
         * @param status current attempt status
         */
        public AttemptedSource(ImageSourceName sourceName, String urlAttempted, ImageAttemptStatus status) {
            this.sourceName = sourceName;
            this.urlAttempted = urlAttempted;
            this.status = status;
        }
    }

    /**
     * Describes the final image selected for a book cover.
     */
    @Getter
    @Setter
    public static class SelectedImage {
        private ImageSourceName sourceName;
        private String finalUrl;
        private String resolution;
        private String dimensions;
        private String selectionReason;
        private String storageLocation;
        private String s3Key;
    }
}
