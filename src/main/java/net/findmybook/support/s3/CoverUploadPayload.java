package net.findmybook.support.s3;

import net.findmybook.model.image.ProcessedImage;

/**
 * Immutable parameter object carrying all data needed for a single S3 cover upload.
 *
 * <p>Replaces the six-parameter tuple that previously threaded through
 * {@link S3CoverStorageGateway} and {@link S3CoverUploadExecutor}, eliminating
 * positional-argument mix-up risk and reducing method signatures to a single value.</p>
 *
 * @param bookId           canonical book identifier
 * @param fileExtension    target file extension (e.g. "jpg", "webp")
 * @param source           cover source label used for S3 key generation
 * @param processedBytes   image bytes ready for upload
 * @param mimeType         MIME type matching {@code processedBytes}
 * @param processedImage   processing metadata (dimensions, format, success flag)
 */
public record CoverUploadPayload(
    String bookId,
    String fileExtension,
    String source,
    byte[] processedBytes,
    String mimeType,
    ProcessedImage processedImage
) {}
