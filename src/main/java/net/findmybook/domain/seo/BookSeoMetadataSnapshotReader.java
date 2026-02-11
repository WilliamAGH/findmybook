package net.findmybook.domain.seo;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-side port for loading the current SEO metadata snapshot for a book.
 */
@FunctionalInterface
public interface BookSeoMetadataSnapshotReader {

    /**
     * Loads the current SEO metadata snapshot for the supplied book identifier.
     *
     * @param bookId canonical book UUID
     * @return current snapshot when present
     */
    Optional<BookSeoMetadataSnapshot> fetchCurrent(UUID bookId);
}
