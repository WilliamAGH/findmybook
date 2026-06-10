package net.findmybook.support.search;

import net.findmybook.model.Book;
import net.findmybook.util.IsbnUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves a stable deduplication key for a book search candidate.
 *
 * <p>Keys follow a priority chain: canonical ISBN identity → normalized title+author composite
 * → provider/internal ID. Content keys intentionally outrank IDs so a persisted book and
 * an external fallback row can collapse when providers assign different identifiers to
 * the same work.</p>
 *
 * <p>{@link #resolveAliases(Book)} returns every safe content identity for overlap checks.
 * {@link #hasIdentityOverlap(Book, Book)} applies those aliases while preserving conflicting
 * ISBN identities as separate editions.</p>
 *
 * <p>Rows that lack both ISBNs and an internal ID and carry only a partial title or author
 * do not qualify for a dedupe key: partial keys would collapse distinct works, so such
 * candidates are dropped upstream by returning {@link Optional#empty()}.</p>
 */
public final class CandidateKeyResolver {

    private static final String ISBN_KEY_PREFIX = "ISBN:";
    private static final String TITLE_AUTHOR_KEY_PREFIX = "TITLE_AUTHOR:";
    private static final String ID_KEY_PREFIX = "ID:";
    private static final String CONTRIBUTOR_BY_PREFIX = "by ";
    // Retain letters, digits, + and # so programming-language titles like C++/C#/F# stay distinct.
    private static final Pattern NON_SEARCH_TEXT_PATTERN = Pattern.compile("[^\\p{L}0-9+#]+");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private CandidateKeyResolver() {}

    /**
     * Computes a deduplication key for the given book.
     *
     * @param book the candidate book to key
     * @return a stable key string, or empty when the book is null or lacks any usable identifier
     */
    public static Optional<String> resolve(Book book) {
        List<String> aliases = resolveAliases(book);
        return aliases.isEmpty() ? Optional.empty() : Optional.of(aliases.getFirst());
    }

    /**
     * Computes all safe deduplication identities for the given book.
     *
     * @param book the candidate book to key
     * @return ordered alias keys, or an empty list when no usable identity exists
     */
    public static List<String> resolveAliases(Book book) {
        if (book == null) {
            return List.of();
        }

        List<String> aliases = new ArrayList<>(2);
        String isbnIdentity = Optional.ofNullable(IsbnUtils.isbn13Identity(book.getIsbn13()))
            .orElseGet(() -> IsbnUtils.isbn13Identity(book.getIsbn10()));
        if (StringUtils.hasText(isbnIdentity)) {
            aliases.add(ISBN_KEY_PREFIX + isbnIdentity);
        }

        String title = normalizeText(book.getTitle());
        String firstAuthor = normalizeAuthor(firstAuthor(book));
        if (StringUtils.hasText(title) && StringUtils.hasText(firstAuthor)) {
            aliases.add(TITLE_AUTHOR_KEY_PREFIX + title + "::" + firstAuthor);
        }

        String id = book.getId();
        if (aliases.isEmpty() && StringUtils.hasText(id)) {
            aliases.add(ID_KEY_PREFIX + id);
        }

        return List.copyOf(aliases);
    }

    /**
     * Determines whether two candidates represent the same search identity.
     *
     * @param first first candidate to compare
     * @param second second candidate to compare
     * @return true when the candidates share an identity and do not have conflicting ISBN identities
     */
    public static boolean hasIdentityOverlap(Book first, Book second) {
        List<String> firstAliases = resolveAliases(first);
        List<String> secondAliases = resolveAliases(second);
        if (firstAliases.isEmpty() || secondAliases.isEmpty()) {
            return false;
        }
        if (hasConflictingIsbnIdentity(firstAliases, secondAliases)) {
            return false;
        }
        return aliasesOverlap(firstAliases, secondAliases);
    }

    /**
     * Determines whether a candidate overlaps any already accepted candidate.
     *
     * @param acceptedCandidates candidates already accepted into the result set
     * @param candidate candidate being considered
     * @return true when any accepted candidate shares a non-conflicting identity with {@code candidate}
     */
    public static boolean overlapsAny(Iterable<Book> acceptedCandidates, Book candidate) {
        if (acceptedCandidates == null) {
            return false;
        }
        for (Book acceptedCandidate : acceptedCandidates) {
            if (hasIdentityOverlap(acceptedCandidate, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasConflictingIsbnIdentity(List<String> firstAliases, List<String> secondAliases) {
        String firstIsbn = firstIsbnAlias(firstAliases);
        String secondIsbn = firstIsbnAlias(secondAliases);
        return StringUtils.hasText(firstIsbn)
            && StringUtils.hasText(secondIsbn)
            && !firstIsbn.equals(secondIsbn);
    }

    private static boolean aliasesOverlap(List<String> firstAliases, List<String> secondAliases) {
        for (String firstAlias : firstAliases) {
            if (secondAliases.contains(firstAlias)) {
                return true;
            }
        }
        return false;
    }

    private static String firstIsbnAlias(List<String> aliases) {
        for (String alias : aliases) {
            if (alias != null && alias.startsWith(ISBN_KEY_PREFIX)) {
                return alias;
            }
        }
        return "";
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
        String lowered = rawText.toLowerCase(Locale.ROOT);
        String stripped = NON_SEARCH_TEXT_PATTERN.matcher(lowered).replaceAll(" ").trim();
        return WHITESPACE_PATTERN.matcher(stripped).replaceAll(" ");
    }
}
