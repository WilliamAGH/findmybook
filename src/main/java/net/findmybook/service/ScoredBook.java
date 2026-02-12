package net.findmybook.service;

import net.findmybook.model.Book;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Immutable value object tracking a book with its calculated recommendation score.
 *
 * <p>Encapsulates:
 * <ul>
 *   <li>Book object with all its metadata</li>
 *   <li>Similarity score that accumulates across multiple recommendation strategies</li>
 *   <li>Reasons why the book was recommended (author match, category match, text match)</li>
 * </ul>
 *
 * <p>Used for ranking recommendations by relevance and allows scores to be
 * combined when a book is found by multiple strategies.</p>
 *
 * @param book the recommended book
 * @param score the accumulated similarity score (higher = more relevant)
 * @param reasons set of reasons for recommendation (AUTHOR, CATEGORY, TEXT)
 */
public record ScoredBook(Book book, double score, Set<String> reasons) {

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
     * Merges another scored book's score and reasons into this one.
     * Used when the same book is found through multiple recommendation strategies.
     *
     * @param other the other scored book to merge
     * @return a new ScoredBook with combined score and merged reasons
     */
    public ScoredBook mergeWith(ScoredBook other) {
        Set<String> mergedReasons = new LinkedHashSet<>(this.reasons);
        mergedReasons.addAll(other.reasons);
        return new ScoredBook(this.book, this.score + other.score, Collections.unmodifiableSet(mergedReasons));
    }

    /**
     * Returns an unmodifiable view of the recommendation reasons.
     */
    public Set<String> getReasons() {
        return reasons;
    }

    /**
     * Returns the book being recommended.
     */
    public Book getBook() {
        return book;
    }

    /**
     * Returns the accumulated similarity score.
     */
    public double getScore() {
        return score;
    }
}
