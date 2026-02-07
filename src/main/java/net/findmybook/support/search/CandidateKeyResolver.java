package net.findmybook.support.search;

import net.findmybook.model.Book;
import net.findmybook.util.IsbnUtils;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;

/**
 * Resolves a stable deduplication key for a book search candidate.
 *
 * <p>Keys follow a priority chain: ISBN-13 → ISBN-10 → internal ID → title+author composite.
 * The first non-blank identifier wins, ensuring deterministic deduplication across provider
 * boundaries where the same book may carry different metadata.</p>
 */
public final class CandidateKeyResolver {

    private CandidateKeyResolver() {}

    /**
     * Computes a deduplication key for the given book.
     *
     * @param book the candidate book to key
     * @return a stable key string, or empty when the book is null or lacks any usable identifier
     */
    public static Optional<String> resolve(Book book) {
        if (book == null) {
            return Optional.empty();
        }

        String isbn13 = IsbnUtils.sanitize(book.getIsbn13());
        if (StringUtils.hasText(isbn13)) {
            return Optional.of("ISBN13:" + isbn13);
        }

        String isbn10 = IsbnUtils.sanitize(book.getIsbn10());
        if (StringUtils.hasText(isbn10)) {
            return Optional.of("ISBN10:" + isbn10);
        }

        String id = book.getId();
        if (StringUtils.hasText(id)) {
            return Optional.of("ID:" + id);
        }

        String title = Optional.ofNullable(book.getTitle()).orElse("").trim().toLowerCase(Locale.ROOT);
        String firstAuthor = (book.getAuthors() == null || book.getAuthors().isEmpty())
            ? ""
            : Optional.ofNullable(book.getAuthors().get(0)).orElse("").trim().toLowerCase(Locale.ROOT);

        if (!title.isBlank() || !firstAuthor.isBlank()) {
            return Optional.of("TITLE_AUTHOR:" + title + "::" + firstAuthor);
        }

        return Optional.empty();
    }
}
