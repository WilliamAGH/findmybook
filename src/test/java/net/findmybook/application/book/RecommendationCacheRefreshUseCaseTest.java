package net.findmybook.application.book;

import net.findmybook.adapters.persistence.RecommendationMaintenanceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationCacheRefreshUseCaseTest {

    @Mock
    private RecommendationMaintenanceRepository recommendationMaintenanceRepository;

    @Test
    void should_ReturnRefreshSummary_When_RefreshingAllRecommendationRows() {
        RecommendationCacheRefreshUseCase useCase =
            new RecommendationCacheRefreshUseCase(recommendationMaintenanceRepository, 30);
        when(recommendationMaintenanceRepository.countRecommendationRows()).thenReturn(100L);
        when(recommendationMaintenanceRepository.countActiveRecommendationRows())
            .thenReturn(0L)
            .thenReturn(100L);
        when(recommendationMaintenanceRepository.refreshAllRecommendationExpirations(30)).thenReturn(100);

        RecommendationCacheRefreshUseCase.RefreshSummary summary = useCase.refreshAllRecommendations();

        assertThat(summary.totalRows()).isEqualTo(100L);
        assertThat(summary.activeRowsBefore()).isEqualTo(0L);
        assertThat(summary.refreshedRows()).isEqualTo(100);
        assertThat(summary.activeRowsAfter()).isEqualTo(100L);
        assertThat(summary.ttlDays()).isEqualTo(30);
        verify(recommendationMaintenanceRepository).refreshAllRecommendationExpirations(30);
    }

    @Test
    void should_PropagateFailure_When_RefreshUpdateThrows() {
        RecommendationCacheRefreshUseCase useCase =
            new RecommendationCacheRefreshUseCase(recommendationMaintenanceRepository, 30);
        when(recommendationMaintenanceRepository.countRecommendationRows()).thenReturn(10L);
        when(recommendationMaintenanceRepository.countActiveRecommendationRows()).thenReturn(1L);
        when(recommendationMaintenanceRepository.refreshAllRecommendationExpirations(30))
            .thenThrow(new IllegalStateException("refresh failed"));

        assertThatThrownBy(useCase::refreshAllRecommendations)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("refresh failed");
    }
}
