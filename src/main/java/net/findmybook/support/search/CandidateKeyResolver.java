package net.findmybook.support.search;

import net.findmybook.model.Book;
import net.findmybook.util.IsbnUtils;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;

/**
 * Resolves a stable deduplication key for a book search candidate.
 *
 * <p>Keys follow a priority chain: ISBN-13 → ISBN-10 → normalized title+author composite
 * → provider/internal ID. Content keys intentionally outrank IDs so a persisted book and
 * an external fallback row can collapse when providers assign different identifiers to
 * the same work.</p>
 */
public final class CandidateKeyResolver {

    private static final String ISBN13_KEY_PREFIX = "ISBN13:";
    private static final String ISBN10_KEY_PREFIX = "ISBN10:";
    private static final String TITLE_AUTHOR_KEY_PREFIX = "TITLE_AUTHOR:";
    private static final String ID_KEY_PREFIX = "ID:";
    private static final String CONTRIBUTOR_BY_PREFIX = "by ";
    private static final String NON_SEARCH_TEXT_PATTERN = "[^\\p{L}0-9]+";
    private static final String WHITESPACE_PATTERN = "\\s+";

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
            return Optional.of(ISBN13_KEY_PREFIX + isbn13);
        }

        String isbn10 = IsbnUtils.sanitize(book.getIsbn10());
        if (StringUtils.hasText(isbn10)) {
            return Optional.of(ISBN10_KEY_PREFIX + isbn10);
        }

        String title = normalizeText(book.getTitle());
        String firstAuthor = normalizeAuthor(firstAuthor(book));
        if (StringUtils.hasText(title) && StringUtils.hasText(firstAuthor)) {
            return Optional.of(TITLE_AUTHOR_KEY_PREFIX + title + "::" + firstAuthor);
        }

        String id = book.getId();
        if (StringUtils.hasText(id)) {
            return Optional.of(ID_KEY_PREFIX + id);
        }

        if (!title.isBlank() || !firstAuthor.isBlank()) {
            return Optional.of(TITLE_AUTHOR_KEY_PREFIX + title + "::" + firstAuthor);
        }

        return Optional.empty();
    }

    private static String firstAuthor(Book book) {
        if (book == null || book.getAuthors() == null || book.getAuthors().isEmpty()) {
            return "";
        }
        return Optional.ofNullable(book.getAuthors().getFirst()).orElse("");
    }

    private static String normalizeAuthor(String rawAuthor) {
        String normalized = normalizeText(rawAuthor);
        if (normalized.startsWith(CONTRIBUTOR_BY_PREFIX)) {
            return normalized.substring(CONTRIBUTOR_BY_PREFIX.length()).trim();
        }
        return normalized;
    }

    private static String normalizeText(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        return rawText
            .toLowerCase(Locale.ROOT)
            .replaceAll(NON_SEARCH_TEXT_PATTERN, " ")
            .trim()
            .replaceAll(WHITESPACE_PATTERN, " ");
    }
}
