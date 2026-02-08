package net.findmybook.service.event;

import java.util.Map;

/**
 * Event published when a book has been upserted into Postgres.
 * <p>
 * Besides the canonical identifiers, the event now exposes the provider source and
 * the image links that were persisted so downstream listeners (e.g., S3
 * orchestration) can act without re-querying the database.
 */
public class BookUpsertEvent {
    private final String bookId;
    private final String slug;
    private final String title;
    private final boolean isNew;
    private final String context;
    private final Map<String, String> imageLinks;
    private final String canonicalImageUrl;
    private final String source;

    public BookUpsertEvent(String bookId,
                           String slug,
                           String title,
                           boolean isNew,
                           String context,
                           Map<String, String> imageLinks,
                           String canonicalImageUrl,
                           String source) {
        this.bookId = bookId;
        this.slug = slug;
        this.title = title;
        this.isNew = isNew;
        this.context = context;
        this.imageLinks = imageLinks != null ? Map.copyOf(imageLinks) : Map.of();
        this.canonicalImageUrl = canonicalImageUrl;
        this.source = source;
    }

    public String getBookId() {
        return bookId;
    }

    public String getSlug() {
        return slug;
    }

    public String getTitle() {
        return title;
    }

    public boolean isNew() {
        return isNew;
    }

    public String getContext() {
        return context;
    }

    /**
     * Returns the provider image variants as persisted during the upsert. Keys follow the
     * source's naming (e.g., thumbnail, large).
     */
    public Map<String, String> getImageLinks() {
        return imageLinks;
    }

    /**
     * Returns the best guess canonical image URL that should be uploaded to S3 (may be null).
     */
    public String getCanonicalImageUrl() {
        return canonicalImageUrl;
    }

    /**
     * Returns the provider/source associated with the upsert (e.g., GOOGLE_BOOKS).
     */
    public String getSource() {
        return source;
    }
}
