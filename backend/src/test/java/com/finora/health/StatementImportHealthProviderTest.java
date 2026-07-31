package com.finora.health;

import com.finora.repository.StatementImportRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatementImportHealthProviderTest {

    private final StatementImportRepository statementImportRepository = mock(StatementImportRepository.class);
    private final StatementImportHealthProvider provider = new StatementImportHealthProvider(statementImportRepository);

    @Test
    void check_reportsUp_whenNoImportsHappenedInTheWindow_ratherThanDegradedOrDown() {
        when(statementImportRepository.countByImportedAtAfter(any())).thenReturn(0L);

        HealthCheckResult result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
        assertThat(result.detail()).contains("No imports");
    }

    @Test
    void check_reportsUp_whenSkipRateIsBelowTheDegradedThreshold() {
        when(statementImportRepository.countByImportedAtAfter(any())).thenReturn(10L);
        when(statementImportRepository.countWithSkippedRowsAfter(any())).thenReturn(1L); // 10%

        assertThat(provider.check().status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void check_reportsDegraded_whenSkipRateCrossesTheThreshold() {
        when(statementImportRepository.countByImportedAtAfter(any())).thenReturn(10L);
        when(statementImportRepository.countWithSkippedRowsAfter(any())).thenReturn(3L); // 30%

        HealthCheckResult result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.DEGRADED);
        assertThat(result.detail()).contains("3 with skipped rows");
    }
}
