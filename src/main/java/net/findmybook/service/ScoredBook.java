package net.findmybook.service;

import net.findmybook.model.Book;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Value object tracking a book with its calculated recommendation score.
 *
 * <p>Record fields are shallowly immutable: the {@code reasons} set is always wrapped
 * in {@link Collections#unmodifiableSet}, but the contained {@link Book} is a mutable
 * model class shared with callers.</p>
 *
 * @param book the recommended book
 * @param score the accumulated similarity score (higher = more relevant)
 * @param reasons unmodifiable set of reasons for recommendation (AUTHOR, CATEGORY, TEXT)
 */
public record ScoredBook(Book book, double score, Set<String> reasons) {

    /** Compact constructor enforcing unmodifiable reasons on every construction path. */
    public ScoredBook {
        reasons = Collections.unmodifiableSet(new LinkedHashSet<>(reasons));
    }

    /**
     * Creates a scored book with a single reason.
     */
    public ScoredBook(Book book, double score, String reason) {
        this(book, score, buildReasons(reason));
    }

    private static Set<String> buildReasons(String reason) {
        Set<String> set = new LinkedHashSet<>();
        if (StringUtils.hasText(reason)) {
            set.add(reason);
        }
        return set;
    }

    /**
     * Merges this scored book with another, accumulating scores and unioning reasons.
     *
     * @param other the other scored book to merge
     * @return a new ScoredBook with combined score and merged reasons
     */
    public ScoredBook mergeWith(ScoredBook other) {
        Set<String> mergedReasons = new LinkedHashSet<>(this.reasons);
        mergedReasons.addAll(other.reasons);
        return new ScoredBook(this.book, this.score + other.score, mergedReasons);
    }
}
