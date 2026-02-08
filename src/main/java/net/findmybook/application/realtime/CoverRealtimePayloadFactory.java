package net.findmybook.application.realtime;

import jakarta.annotation.Nullable;
import java.util.Date;
import java.util.List;
import java.util.Map;
import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.service.event.BookCoverUpdatedEvent;
import net.findmybook.service.event.BookUpsertEvent;
import net.findmybook.service.event.SearchProgressEvent;
import net.findmybook.service.event.SearchResultsUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Builds stable WebSocket payloads from domain events.
 *
 * <p>Centralizing payload construction keeps event contracts consistent across
 * websocket listeners and prevents mapping logic from drifting between handlers.</p>
 */
@Component
public class CoverRealtimePayloadFactory {

    private static final Logger logger = LoggerFactory.getLogger(CoverRealtimePayloadFactory.class);

    /**
     * Builds a cover-update payload for a book-specific realtime topic.
     */
    public CoverUpdatePayload createCoverUpdatePayload(BookCoverUpdatedEvent event) {
        CoverImageSource source = event.getSource() != null ? event.getSource() : CoverImageSource.UNDEFINED;
        return new CoverUpdatePayload(
            event.getGoogleBookId(),
            event.getNewCoverUrl(),
            event.getIdentifierKey(),
            source.name(),
            source.getDisplayName()
        );
    }

    /**
     * Builds an incremental search-results payload for query-specific realtime topics.
     */
    public SearchResultsPayload createSearchResultsPayload(SearchResultsUpdatedEvent event) {
        List<SearchResultBookSnapshot> bookSnapshots = event.getNewResults().stream()
            .map(book -> toBookSnapshot(book, event.getSource()))
            .toList();

        return new SearchResultsPayload(
            event.getSearchQuery(),
            event.getSource(),
            event.getTotalResultsNow(),
            event.isComplete(),
            event.getNewResults().size(),
            bookSnapshots
        );
    }

    /**
     * Builds a search-progress payload for query-specific realtime topics.
     */
    public SearchProgressPayload createSearchProgressPayload(SearchProgressEvent event) {
        return new SearchProgressPayload(
            event.getSearchQuery(),
            event.getStatus().name(),
            event.getMessage(),
            event.getSource()
        );
    }

    /**
     * Builds a book-upsert payload for a book-specific realtime topic.
     */
    public BookUpsertPayload createBookUpsertPayload(BookUpsertEvent event) {
        return new BookUpsertPayload(
            event.getBookId(),
            event.getSlug(),
            event.getTitle(),
            event.isNew(),
            event.getContext(),
            event.getCanonicalImageUrl()
        );
    }

    private SearchResultBookSnapshot toBookSnapshot(Book book, String eventSource) {
        String matchType = qualifierAsString(book, "search.matchType");
        Double relevanceScore = qualifierAsDouble(book, "search.relevanceScore");
        CoverSnapshot cover = toCoverSnapshot(book);

        return new SearchResultBookSnapshot(
            book.getId(),
            StringUtils.hasText(book.getSlug()) ? book.getSlug() : book.getId(),
            book.getTitle(),
            book.getAuthors(),
            book.getDescription(),
            book.getPublishedDate(),
            book.getPageCount(),
            book.getCategories(),
            book.getIsbn10(),
            book.getIsbn13(),
            book.getPublisher(),
            book.getLanguage(),
            eventSource,
            matchType,
            relevanceScore,
            cover
        );
    }

    private CoverSnapshot toCoverSnapshot(Book book) {
        String preferredUrl = null;
        String fallbackUrl = null;
        String coverSource = null;

        if (book.getCoverImages() != null) {
            preferredUrl = book.getCoverImages().getPreferredUrl();
            fallbackUrl = book.getCoverImages().getFallbackUrl();
            if (book.getCoverImages().getSource() != null) {
                coverSource = book.getCoverImages().getSource().name();
            }
        }

        return new CoverSnapshot(
            book.getS3ImagePath(),
            book.getExternalImageUrl(),
            preferredUrl,
            fallbackUrl,
            coverSource
        );
    }

    @Nullable
    private String qualifierAsString(Book book, String key) {
        Map<String, Object> qualifiers = book.getQualifiers();
        if (qualifiers == null || !StringUtils.hasText(key)) {
            return null;
        }

        var qualifier = qualifiers.get(key);
        return qualifier == null ? null : qualifier.toString();
    }

    @Nullable
    private Double qualifierAsDouble(Book book, String key) {
        Map<String, Object> qualifiers = book.getQualifiers();
        if (qualifiers == null || !StringUtils.hasText(key)) {
            return null;
        }

        var qualifier = qualifiers.get(key);
        if (qualifier == null) {
            return null;
        }
        if (qualifier instanceof Number number) {
            return number.doubleValue();
        }

        try {
            return Double.parseDouble(qualifier.toString());
        } catch (NumberFormatException parseFailure) {
            logger.warn("Qualifier '{}' for book {} is not a valid double: '{}'",
                key, book.getId(), qualifier);
            return null;
        }
    }

    /**
     * Realtime payload for cover update notifications.
     */
    public record CoverUpdatePayload(
        String googleBookId,
        String newCoverUrl,
        String identifierKey,
        String sourceName,
        String sourceDisplayName
    ) {}

    /**
     * Realtime payload for incremental search-result notifications.
     */
    public record SearchResultsPayload(
        String searchQuery,
        String source,
        int totalResultsNow,
        boolean isComplete,
        int newResultsCount,
        List<SearchResultBookSnapshot> newResults
    ) {}

    /**
     * Realtime payload for search-progress notifications.
     */
    public record SearchProgressPayload(
        String searchQuery,
        String status,
        String message,
        String source
    ) {}

    /**
     * Realtime payload for book upsert notifications.
     */
    public record BookUpsertPayload(
        String bookId,
        @Nullable String slug,
        @Nullable String title,
        boolean isNew,
        @Nullable String context,
        @Nullable String canonicalImageUrl
    ) {}

    /**
     * Realtime snapshot of a search-result book.
     */
    public record SearchResultBookSnapshot(
        String id,
        String slug,
        @Nullable String title,
        @Nullable List<String> authors,
        @Nullable String description,
        @Nullable Date publishedDate,
        @Nullable Integer pageCount,
        @Nullable List<String> categories,
        @Nullable String isbn10,
        @Nullable String isbn13,
        @Nullable String publisher,
        @Nullable String language,
        @Nullable String source,
        @Nullable String matchType,
        @Nullable Double relevanceScore,
        CoverSnapshot cover
    ) {}

    /**
     * Realtime snapshot of cover metadata attached to a search result.
     */
    public record CoverSnapshot(
        @Nullable String s3ImagePath,
        @Nullable String externalImageUrl,
        @Nullable String preferredUrl,
        @Nullable String fallbackUrl,
        @Nullable String source
    ) {}
}
