package net.findmybook.service;

import net.findmybook.model.Book;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoredBookTest {

    @Test
    void should_AccumulateScores_When_MergedWithAnother() {
        Book book = bookWithId("b1");
        ScoredBook first = new ScoredBook(book, 2.0, "AUTHOR");
        ScoredBook second = new ScoredBook(book, 3.0, "CATEGORY");

        ScoredBook merged = first.mergeWith(second);

        assertThat(merged.score()).isEqualTo(5.0);
        assertThat(merged.book()).isSameAs(book);
    }

    @Test
    void should_UnionReasons_When_MergedWithAnother() {
        Book book = bookWithId("b1");
        ScoredBook first = new ScoredBook(book, 1.0, "AUTHOR");
        ScoredBook second = new ScoredBook(book, 1.0, "TEXT");

        ScoredBook merged = first.mergeWith(second);

        assertThat(merged.reasons()).containsExactlyInAnyOrder("AUTHOR", "TEXT");
    }

    @Test
    void should_DeduplicateReasons_When_MergedWithSameReason() {
        Book book = bookWithId("b1");
        ScoredBook first = new ScoredBook(book, 1.0, "AUTHOR");
        ScoredBook second = new ScoredBook(book, 2.0, "AUTHOR");

        ScoredBook merged = first.mergeWith(second);

        assertThat(merged.reasons()).containsExactly("AUTHOR");
        assertThat(merged.score()).isEqualTo(3.0);
    }

    @Test
    void should_ReturnUnmodifiableReasons_When_ConstructedWithSingleReason() {
        ScoredBook scored = new ScoredBook(bookWithId("b1"), 1.0, "AUTHOR");

        assertThatThrownBy(() -> scored.reasons().add("HACKED"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_ReturnUnmodifiableReasons_When_ConstructedWithReasonSet() {
        Set<String> mutableReasons = new java.util.LinkedHashSet<>();
        mutableReasons.add("AUTHOR");
        ScoredBook scored = new ScoredBook(bookWithId("b1"), 1.0, mutableReasons);

        assertThatThrownBy(() -> scored.reasons().add("HACKED"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_DefensiveCopyReasons_When_OriginalSetMutated() {
        Set<String> mutableReasons = new java.util.LinkedHashSet<>();
        mutableReasons.add("AUTHOR");
        ScoredBook scored = new ScoredBook(bookWithId("b1"), 1.0, mutableReasons);

        mutableReasons.add("TAMPERED");

        assertThat(scored.reasons()).containsExactly("AUTHOR");
    }

    @Test
    void should_ReturnEmptyReasons_When_ConstructedWithBlankReason() {
        ScoredBook scored = new ScoredBook(bookWithId("b1"), 1.0, "");

        assertThat(scored.reasons()).isEmpty();
    }

    @Test
    void should_ReturnEmptyReasons_When_ConstructedWithNullReason() {
        ScoredBook scored = new ScoredBook(bookWithId("b1"), 1.0, (String) null);

        assertThat(scored.reasons()).isEmpty();
    }

    @Test
    void should_ReturnUnmodifiableReasons_When_MergeResult() {
        ScoredBook first = new ScoredBook(bookWithId("b1"), 1.0, "AUTHOR");
        ScoredBook second = new ScoredBook(bookWithId("b1"), 1.0, "TEXT");

        ScoredBook merged = first.mergeWith(second);

        assertThatThrownBy(() -> merged.reasons().add("HACKED"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    private static Book bookWithId(String id) {
        Book book = new Book();
        book.setId(id);
        return book;
    }
}
