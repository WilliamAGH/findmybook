package net.findmybook.service.image;

import net.findmybook.exception.S3UploadException;
import net.findmybook.model.Book;
import net.findmybook.util.ValidationUtils;
import net.findmybook.util.cover.CoverIdentifierResolver;

final class S3UploadValidation {

    private S3UploadValidation() {
    }

    static String resolveUploadSource(String source) {
        return ValidationUtils.hasText(source) ? source : "unknown";
    }

    static void validateUploadInput(String imageUrl, String bookId) {
        if (!ValidationUtils.hasText(bookId)) {
            throw new S3UploadException("Book ID is required for S3 upload", bookId, imageUrl, false, null);
        }
        if (!ValidationUtils.hasText(imageUrl)) {
            throw new S3UploadException("Image URL is required for S3 upload", bookId, imageUrl, false, null);
        }
    }

    static void validateProcessedUploadInput(byte[] processedImageBytes,
                                             String fileExtension,
                                             String mimeType,
                                             int width,
                                             int height,
                                             String bookId) {
        if (!ValidationUtils.hasText(bookId)) {
            throw new S3UploadException("Book ID is required for processed cover upload", bookId, null, false, null);
        }
        if (processedImageBytes == null || processedImageBytes.length == 0) {
            throw new S3UploadException("Processed image bytes are required for S3 upload", bookId, null, false, null);
        }
        if (!ValidationUtils.hasText(fileExtension)) {
            throw new S3UploadException("File extension is required for processed cover upload", bookId, null, false, null);
        }
        if (!ValidationUtils.hasText(mimeType)) {
            throw new S3UploadException("MIME type is required for processed cover upload", bookId, null, false, null);
        }
        if (width <= 0 || height <= 0) {
            throw new S3UploadException("Image dimensions must be positive for processed cover upload", bookId, null, false, null);
        }
    }

    static String resolveBookLookupKey(Book book) {
        String bookKey = CoverIdentifierResolver.getPreferredIsbn(book);
        if (!ValidationUtils.hasText(bookKey)) {
            bookKey = CoverIdentifierResolver.resolve(book);
        }
        if (!ValidationUtils.hasText(bookKey)) {
            bookKey = book.getId();
        }
        return bookKey;
    }
}
