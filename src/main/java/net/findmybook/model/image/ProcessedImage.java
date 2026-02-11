/**
 * Record representing a processed image with its metadata
 *
 * @author William Callahan
 *
 * Features:
 * - Immutable container for image data and metadata
 * - Supports both successful and failed processing results
 * - Tracks image dimensions and format information
 * - Provides static factory methods for easy instantiation
 * - Implements defensive copy for mutable byte arrays
 *
 * @param processedBytes The processed image data as a byte array
 * @param newFileExtension The file extension for the processed image (e.g., ".jpg")
 * @param newMimeType The MIME type of the processed image (e.g., "image/jpeg")
 * @param width The width of the processed image in pixels
 * @param height The height of the processed image in pixels
 * @param grayscale Whether the image is predominantly grayscale/B&amp;W
 * @param processingSuccessful Whether the image processing was successful
 * @param processingError Error message if processing failed
 * @param rejectionReason Typed reason when the image was rejected as not a usable cover (null for successes and infrastructure errors)
 */

 package net.findmybook.model.image;

import jakarta.annotation.Nullable;
import java.util.Arrays;

public record ProcessedImage(
        byte[] processedBytes,
        String newFileExtension,
        String newMimeType,
        int width,
        int height,
        boolean grayscale,
        boolean processingSuccessful,
        String processingError,
        @Nullable CoverRejectionReason rejectionReason) {

    // Compact canonical constructor for defensive copy
    public ProcessedImage {
        // Defensive copy for mutable byte array
        if (processedBytes != null) {
            processedBytes = Arrays.copyOf(processedBytes, processedBytes.length);
        }
    }

    /**
     * Creates a successfully processed image with grayscale detection result.
     *
     * @param processedBytes The processed image data
     * @param newFileExtension The file extension for the processed image
     * @param newMimeType The MIME type of the processed image
     * @param width The width of the processed image in pixels
     * @param height The height of the processed image in pixels
     * @param grayscale Whether the image is predominantly grayscale/B&W
     * @return A new ProcessedImage instance representing a successful operation
     */
    public static ProcessedImage success(byte[] processedBytes, String newFileExtension, String newMimeType, int width, int height, boolean grayscale) {
        return new ProcessedImage(processedBytes, newFileExtension, newMimeType, width, height, grayscale, true, null, null);
    }

    /**
     * Creates a successfully processed image, defaulting grayscale to false.
     *
     * @param processedBytes The processed image data
     * @param newFileExtension The file extension for the processed image
     * @param newMimeType The MIME type of the processed image
     * @param width The width of the processed image in pixels
     * @param height The height of the processed image in pixels
     * @return A new ProcessedImage instance representing a successful operation
     */
    public static ProcessedImage success(byte[] processedBytes, String newFileExtension, String newMimeType, int width, int height) {
        return new ProcessedImage(processedBytes, newFileExtension, newMimeType, width, height, false, true, null, null);
    }

    /**
     * Creates a rejection result with a typed reason indicating no usable cover exists.
     *
     * @param reason Why the image was rejected as not a usable cover
     * @return A new ProcessedImage representing a cover rejection
     */
    public static ProcessedImage rejected(CoverRejectionReason reason) {
        return new ProcessedImage(null, null, null, 0, 0, false, false, reason.description(), reason);
    }

    /**
     * Creates a rejection result with a typed reason and additional detail.
     *
     * @param reason Why the image was rejected as not a usable cover
     * @param detail Additional context for log messages (e.g., actual byte count)
     * @return A new ProcessedImage representing a cover rejection
     */
    public static ProcessedImage rejected(CoverRejectionReason reason, String detail) {
        return new ProcessedImage(null, null, null, 0, 0, false, false, detail, reason);
    }

    /**
     * Creates a failed processing result for infrastructure errors (IOException, etc.)
     * where no typed rejection reason applies.
     *
     * @param processingError The error message describing why processing failed
     * @return A new ProcessedImage instance representing a failed operation
     */
    public static ProcessedImage failure(String processingError) {
        return new ProcessedImage(null, null, null, 0, 0, false, false, processingError, null);
    }

    // Override accessor to return a defensive copy for immutability
    @Override
    public byte[] processedBytes() {
        return processedBytes == null ? null : Arrays.copyOf(processedBytes, processedBytes.length);
    }

    // Getters might be useful for other services, though direct field access is fine for now in a simple DTO
    public byte[] getProcessedBytes() {
        return processedBytes();
    }

    public String getNewFileExtension() {
        return newFileExtension;
    }

    public String getNewMimeType() {
        return newMimeType;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean isGrayscale() {
        return grayscale;
    }

    public boolean isProcessingSuccessful() {
        return processingSuccessful;
    }

    public String getProcessingError() {
        return processingError;
    }
}
