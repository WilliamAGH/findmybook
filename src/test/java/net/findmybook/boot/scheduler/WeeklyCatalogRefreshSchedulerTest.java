package net.findmybook.boot.scheduler;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.findmybook.application.book.RecommendationCacheRefreshUseCase;
import net.findmybook.scheduler.NewYorkTimesBestsellerScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        WeeklyCatalogRefreshScheduler.SchedulerConfiguration config = new WeeklyCatalogRefreshScheduler.SchedulerConfiguration(true, true, true);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WeeklyCatalogRefreshScheduler scheduler = new WeeklyCatalogRefreshScheduler(
            newYorkTimesBestsellerScheduler,
            recommendationCacheRefreshUseCase,
            config,
            meterRegistry
        );
        RecommendationCacheRefreshUseCase.RefreshSummary refreshSummary =
            new RecommendationCacheRefreshUseCase.RefreshSummary(20L, 0L, 20, 20L, 30);
        when(recommendationCacheRefreshUseCase.refreshAllRecommendations()).thenReturn(refreshSummary);

        WeeklyCatalogRefreshScheduler.WeeklyRefreshSummary summary = scheduler.forceRunWeeklyRefreshCycle();

        assertThat(summary.nytTriggered()).isTrue();
        assertThat(summary.recommendationTriggered()).isTrue();
        assertThat(summary.recommendationSummary()).contains(refreshSummary);
        assertThat(summary.failures()).isEmpty();
        verify(newYorkTimesBestsellerScheduler).forceProcessNewYorkTimesBestsellers();
        verify(recommendationCacheRefreshUseCase).refreshAllRecommendations();
        assertThat(meterRegistry.get("findmybook.weekly.refresh.phase")
            .tag("phase", "nyt")
            .tag("outcome", "success")
            .counter()
            .count()).isEqualTo(1.0d);
    }

    @Test
    void should_AttemptRecommendationPhaseAndThrow_When_NytPhaseFails() {
        WeeklyCatalogRefreshScheduler.SchedulerConfiguration config = new WeeklyCatalogRefreshScheduler.SchedulerConfiguration(true, true, true);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WeeklyCatalogRefreshScheduler scheduler = new WeeklyCatalogRefreshScheduler(
            newYorkTimesBestsellerScheduler,
            recommendationCacheRefreshUseCase,
            config,
            meterRegistry
        );
        when(recommendationCacheRefreshUseCase.refreshAllRecommendations())
            .thenReturn(new RecommendationCacheRefreshUseCase.RefreshSummary(10L, 1L, 9, 10L, 30));
        org.mockito.Mockito.doThrow(new IllegalStateException("nyt failure"))
            .when(newYorkTimesBestsellerScheduler)
            .forceProcessNewYorkTimesBestsellers();

        assertThatThrownBy(scheduler::forceRunWeeklyRefreshCycle)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("NYT phase failed");

        verify(recommendationCacheRefreshUseCase).refreshAllRecommendations();
        assertThat(meterRegistry.get("findmybook.weekly.refresh.phase")
            .tag("phase", "nyt")
            .tag("outcome", "failure")
            .counter()
            .count()).isEqualTo(1.0d);
    }

    @Test
    void should_SkipExecution_When_SchedulerIsDisabledAndNotForced() {
        WeeklyCatalogRefreshScheduler.SchedulerConfiguration config = new WeeklyCatalogRefreshScheduler.SchedulerConfiguration(false, true, true);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WeeklyCatalogRefreshScheduler scheduler = new WeeklyCatalogRefreshScheduler(
            newYorkTimesBestsellerScheduler,
            recommendationCacheRefreshUseCase,
            config,
            meterRegistry
        );

        scheduler.runWeeklyRefreshCycle();

        verifyNoInteractions(newYorkTimesBestsellerScheduler, recommendationCacheRefreshUseCase);
        assertThat(meterRegistry.get("findmybook.weekly.refresh.phase")
            .tag("phase", "nyt")
            .tag("outcome", "failure")
            .counter()
            .count()).isZero();
    }
}
