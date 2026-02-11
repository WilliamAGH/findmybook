package net.findmybook.boot.scheduler;

import net.findmybook.application.book.RecommendationCacheRefreshUseCase;
import net.findmybook.scheduler.NewYorkTimesBestsellerScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeeklyCatalogRefreshSchedulerTest {

    @Mock
    private NewYorkTimesBestsellerScheduler newYorkTimesBestsellerScheduler;

    @Mock
    private RecommendationCacheRefreshUseCase recommendationCacheRefreshUseCase;

    @Test
    void should_TriggerNytAndRecommendationPhases_When_WeeklyRefreshRunsSuccessfully() {
        WeeklyCatalogRefreshScheduler scheduler = new WeeklyCatalogRefreshScheduler(
            newYorkTimesBestsellerScheduler,
            recommendationCacheRefreshUseCase,
            true,
            true,
            true
        );
        RecommendationCacheRefreshUseCase.RefreshSummary refreshSummary =
            new RecommendationCacheRefreshUseCase.RefreshSummary(20L, 0L, 20, 20L, 30);
        when(recommendationCacheRefreshUseCase.refreshAllRecommendations()).thenReturn(refreshSummary);

        WeeklyCatalogRefreshScheduler.WeeklyRefreshSummary summary = scheduler.forceRunWeeklyRefreshCycle();

        assertThat(summary.nytTriggered()).isTrue();
        assertThat(summary.recommendationTriggered()).isTrue();
        assertThat(summary.recommendationSummary()).isEqualTo(refreshSummary);
        assertThat(summary.failures()).isEmpty();
        verify(newYorkTimesBestsellerScheduler).forceProcessNewYorkTimesBestsellers(nullable(java.time.LocalDate.class));
        verify(recommendationCacheRefreshUseCase).refreshAllRecommendations();
    }

    @Test
    void should_AttemptRecommendationPhaseAndThrow_When_NytPhaseFails() {
        WeeklyCatalogRefreshScheduler scheduler = new WeeklyCatalogRefreshScheduler(
            newYorkTimesBestsellerScheduler,
            recommendationCacheRefreshUseCase,
            true,
            true,
            true
        );
        when(recommendationCacheRefreshUseCase.refreshAllRecommendations())
            .thenReturn(new RecommendationCacheRefreshUseCase.RefreshSummary(10L, 1L, 9, 10L, 30));
        org.mockito.Mockito.doThrow(new IllegalStateException("nyt failure"))
            .when(newYorkTimesBestsellerScheduler)
            .forceProcessNewYorkTimesBestsellers(nullable(java.time.LocalDate.class));

        assertThatThrownBy(scheduler::forceRunWeeklyRefreshCycle)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("NYT phase failed");

        verify(recommendationCacheRefreshUseCase).refreshAllRecommendations();
    }

    @Test
    void should_SkipExecution_When_SchedulerIsDisabledAndNotForced() {
        WeeklyCatalogRefreshScheduler scheduler = new WeeklyCatalogRefreshScheduler(
            newYorkTimesBestsellerScheduler,
            recommendationCacheRefreshUseCase,
            false,
            true,
            true
        );

        scheduler.runWeeklyRefreshCycle();

        verifyNoInteractions(newYorkTimesBestsellerScheduler, recommendationCacheRefreshUseCase);
    }
}
