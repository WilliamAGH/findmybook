package net.findmybook.service;

import net.findmybook.model.Book;
import net.findmybook.util.PagingUtils;
import net.findmybook.util.StringUtils;
import net.findmybook.util.ValidationUtils;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Scoring strategy for book recommendations.
 *
 * <p>Encapsulates scoring algorithm, ranking, and deduplication logic to provide
 * a single responsibility class for recommendation candidate processing.</p>
 */
@Component
public class RecommendationScoringStrategy {

    private static final double AUTHOR_MATCH_SCORE = 4.0;
    private static final double BASE_CATEGORY_SCORE = 0.5;
    private static final double CATEGORY_SCORE_BASE = 1.0;
    private static final double CATEGORY_SCORE_RANGE = 2.0;
    private static final double TEXT_MATCH_SCORE_MULTIPLIER = 2.0;
    private static final int MAX_MAIN_CATEGORIES = 3;
    private static final int MAX_EXTRACTED_KEYWORDS = 10;

    private static final String REASON_AUTHOR = "AUTHOR";
    private static final String REASON_CATEGORY = "CATEGORY";
    private static final String REASON_TEXT = "TEXT";

    /**
     * Spring component constructor - intentionally empty as this service has no external dependencies.
     * All configuration is via static constants; instantiation is managed by Spring's component scanning.
     */
    public RecommendationScoringStrategy() {
        // No dependencies to inject; constants defined above provide all configuration.
    }

    /** Stop words for keyword extraction from title/description. */
    private static final Set<String> STOP_WORDS = Set.of(
        "the", "and", "for", "with", "are", "was", "from", "that", "this", "but", "not",
        "you", "your", "get", "will", "all", "any", "uses", "using", "learn", "what",
        "which", "its", "into", "then", "also"
    );

    /**
     * Calculates author match score.
     */
    public double authorMatchScore() {
        return AUTHOR_MATCH_SCORE;
    }

    /** Returns the recommendation reason constant for an author match. */
    public String authorReason() {
        return REASON_AUTHOR;
    }

    /**
     * Calculates category overlap score between two books.
     */
    public double calculateCategoryOverlapScore(Book sourceBook, Book candidateBook) {
        if (ValidationUtils.isNullOrEmpty(sourceBook.getCategories()) ||
            ValidationUtils.isNullOrEmpty(candidateBook.getCategories())) {
            return BASE_CATEGORY_SCORE;
        }

        Set<String> sourceCategories = normalizeCategories(sourceBook.getCategories());
        Set<String> candidateCategories = normalizeCategories(candidateBook.getCategories());

        Set<String> intersection = new HashSet<>(sourceCategories);
        intersection.retainAll(candidateCategories);

        double overlapRatio = (double) intersection.size() /
                PagingUtils.atLeast(Math.min(sourceCategories.size(), candidateCategories.size()), 1);

        return CATEGORY_SCORE_BASE + (overlapRatio * CATEGORY_SCORE_RANGE);
    }

    /** Returns the recommendation reason constant for a category overlap match. */
    public String categoryReason() {
        return REASON_CATEGORY;
    }

    /**
     * Calculates text match score based on keyword count.
     */
    public double calculateTextMatchScore(int matchCount) {
        return TEXT_MATCH_SCORE_MULTIPLIER * matchCount;
    }

    /** Returns the recommendation reason constant for a text/keyword match. */
    public String textReason() {
        return REASON_TEXT;
    }

    /**
     * Extracts main categories from book (first segment before "/").
     */
    public List<String> extractMainCategories(Book book) {
        if (ValidationUtils.isNullOrEmpty(book.getCategories())) {
            return List.of();
        }
        return book.getCategories().stream()
            .map(category -> category.split("\\s*/\\s*")[0])
            .distinct()
            .limit(MAX_MAIN_CATEGORIES)
            .toList();
    }

    /**
     * Extracts keywords from book title and description.
     */
    public Set<String> extractKeywords(Book book) {
        boolean titleMissing = !StringUtils.hasText(book.getTitle());
        boolean descriptionMissing = !StringUtils.hasText(book.getDescription());
        if (titleMissing && descriptionMissing) {
            return Set.of();
        }

        String safeTitle = Optional.ofNullable(book.getTitle()).orElse("");
        String safeDescription = Optional.ofNullable(book.getDescription()).orElse("");
        String combinedText = (safeTitle + " " + safeDescription).toLowerCase(Locale.ROOT);
        String[] tokens = combinedText.split("[^a-z0-9]+");
        Set<String> keywords = new LinkedHashSet<>();
        for (String token : tokens) {
            if (token.length() > 2 && !STOP_WORDS.contains(token)) {
                keywords.add(token);
                if (keywords.size() >= MAX_EXTRACTED_KEYWORDS) break;
            }
        }
        return keywords;
    }

    private Set<String> normalizeCategories(List<String> categories) {
        Set<String> normalized = new HashSet<>();
        for (String category : categories) {
            if (category == null) continue;
            String[] parts = category.split("\\s*/\\s*");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    normalized.add(trimmed.toLowerCase(Locale.ROOT));
                }
            }
        }
        return normalized;
    }
}
