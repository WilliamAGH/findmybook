package net.findmybook.service;

import net.findmybook.model.Book;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationScoringStrategyTest {

    private final RecommendationScoringStrategy strategy = new RecommendationScoringStrategy();

    // --- Category overlap ---

    @Test
    void should_ReturnMaxScore_When_CategoriesIdentical() {
        Book source = bookWithCategories(List.of("Fiction", "Thriller"));
        Book candidate = bookWithCategories(List.of("Fiction", "Thriller"));

        double score = strategy.calculateCategoryOverlapScore(source, candidate);

        assertThat(score).isEqualTo(3.0);
    }

    @Test
    void should_ReturnBaseScore_When_CategoriesDisjoint() {
        Book source = bookWithCategories(List.of("Fiction"));
        Book candidate = bookWithCategories(List.of("Science"));

        double score = strategy.calculateCategoryOverlapScore(source, candidate);

        assertThat(score).isEqualTo(1.0);
    }

    @Test
    void should_ReturnBaseScore_When_SourceCategoriesNull() {
        Book source = bookWithCategories(null);
        Book candidate = bookWithCategories(List.of("Fiction"));

        double score = strategy.calculateCategoryOverlapScore(source, candidate);

        assertThat(score).isEqualTo(0.5);
    }

    @Test
    void should_ReturnBaseScore_When_CandidateCategoriesEmpty() {
        Book source = bookWithCategories(List.of("Fiction"));
        Book candidate = bookWithCategories(List.of());

        double score = strategy.calculateCategoryOverlapScore(source, candidate);

        assertThat(score).isEqualTo(0.5);
    }

    @Test
    void should_MatchCaseInsensitively_When_CategoriesCompared() {
        Book source = bookWithCategories(List.of("fiction"));
        Book candidate = bookWithCategories(List.of("Fiction"));

        double score = strategy.calculateCategoryOverlapScore(source, candidate);

        assertThat(score).isEqualTo(3.0);
    }

    @Test
    void should_SplitSlashSegments_When_CategoriesContainSlash() {
        Book source = bookWithCategories(List.of("Fiction / Mystery"));
        Book candidate = bookWithCategories(List.of("Mystery / Crime"));

        double score = strategy.calculateCategoryOverlapScore(source, candidate);

        assertThat(score).isGreaterThan(1.0);
    }

    // --- Keyword extraction ---

    @Test
    void should_ExtractKeywords_When_TitleAndDescriptionPresent() {
        Book book = new Book();
        book.setTitle("Java Concurrency in Practice");
        book.setDescription("A comprehensive guide to concurrent programming");

        Set<String> keywords = strategy.extractKeywords(book);

        assertThat(keywords)
            .contains("java", "concurrency", "practice")
            .doesNotContain("in");
    }

    @Test
    void should_ExcludeStopWords_When_ExtractingKeywords() {
        Book book = new Book();
        book.setTitle("The Art and Science of All Things");
        book.setDescription("This will get you from here");

        Set<String> keywords = strategy.extractKeywords(book);

        assertThat(keywords).noneMatch(kw ->
            Set.of("the", "and", "all", "this", "will", "get", "you", "from").contains(kw));
    }

    @Test
    void should_ReturnEmpty_When_TitleAndDescriptionNull() {
        Book book = new Book();

        Set<String> keywords = strategy.extractKeywords(book);

        assertThat(keywords).isEmpty();
    }

    @Test
    void should_LimitKeywords_When_ManyTokensPresent() {
        Book book = new Book();
        book.setTitle("alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima");

        Set<String> keywords = strategy.extractKeywords(book);

        assertThat(keywords).hasSizeLessThanOrEqualTo(10);
    }

    @Test
    void should_ExcludeShortTokens_When_ExtractingKeywords() {
        Book book = new Book();
        book.setTitle("AI ML Go Rust Python");

        Set<String> keywords = strategy.extractKeywords(book);

        assertThat(keywords).noneMatch(kw -> kw.length() <= 2);
    }

    // --- Main categories ---

    @Test
    void should_ExtractFirstSegment_When_CategoriesContainSlash() {
        Book book = bookWithCategories(List.of("Fiction / Mystery", "Science / Physics"));

        List<String> main = strategy.extractMainCategories(book);

        assertThat(main).containsExactly("Fiction", "Science");
    }

    @Test
    void should_ReturnEmpty_When_CategoriesNull() {
        Book book = bookWithCategories(null);

        List<String> main = strategy.extractMainCategories(book);

        assertThat(main).isEmpty();
    }

    @Test
    void should_LimitToThree_When_ManyCategoriesPresent() {
        Book book = bookWithCategories(List.of("A", "B", "C", "D", "E"));

        List<String> main = strategy.extractMainCategories(book);

        assertThat(main).hasSize(3);
    }

    @Test
    void should_DeduplicateMainCategories_When_SameFirstSegment() {
        Book book = bookWithCategories(List.of("Fiction / Mystery", "Fiction / Thriller"));

        List<String> main = strategy.extractMainCategories(book);

        assertThat(main).containsExactly("Fiction");
    }

    // --- Score constants ---

    @Test
    void should_ReturnFixedScore_When_AuthorMatchScoreCalled() {
        assertThat(strategy.authorMatchScore()).isEqualTo(4.0);
    }

    @Test
    void should_ScaleLinearly_When_TextMatchScoreCalculated() {
        assertThat(strategy.calculateTextMatchScore(1)).isEqualTo(2.0);
        assertThat(strategy.calculateTextMatchScore(3)).isEqualTo(6.0);
    }

    // --- Reason constants ---

    @Test
    void should_ReturnStableReasonStrings() {
        assertThat(strategy.authorReason()).isEqualTo("AUTHOR");
        assertThat(strategy.categoryReason()).isEqualTo("CATEGORY");
        assertThat(strategy.textReason()).isEqualTo("TEXT");
    }

    private static Book bookWithCategories(List<String> categories) {
        Book book = new Book();
        book.setCategories(categories);
        return book;
    }
}
