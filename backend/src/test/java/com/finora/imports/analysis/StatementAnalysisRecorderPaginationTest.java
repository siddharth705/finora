package com.finora.imports.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SEC-05 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Real integration
 * behavior for {@code recentCustomerFailures} already lives in StatementAnalysisRecorderIT; this
 * covers only the clamp itself, with a mocked repository -- proving 50+ real rows exist just to
 * observe a size cap being applied is unnecessary when the cap is a pure function of the
 * requested limit, checkable directly against what gets passed to the repository.
 */
class StatementAnalysisRecorderPaginationTest {

    private StatementAnalysisSessionRepository repository;
    private StatementAnalysisRecorder recorder;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(StatementAnalysisSessionRepository.class);
        when(repository.findByUserIdAndSourceAndOutcomeOrderByCreatedAtDesc(any(), any(), any(), any()))
                .thenReturn(List.of());
        recorder = new StatementAnalysisRecorder(repository, new ObjectMapper());
    }

    @Test
    void clampsAnOversizedLimitToFifty() {
        recorder.recentCustomerFailures(userId, Integer.MAX_VALUE);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(repository).findByUserIdAndSourceAndOutcomeOrderByCreatedAtDesc(
                eq(userId), eq(StatementAnalysisSession.Source.CUSTOMER_IMPORT),
                eq(StatementAnalysisSession.Outcome.FAILED), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void leavesAnOrdinaryLimitUntouched() {
        recorder.recentCustomerFailures(userId, 10);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(repository).findByUserIdAndSourceAndOutcomeOrderByCreatedAtDesc(
                any(), any(), any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    void clampsAZeroOrNegativeLimitUpToOne() {
        recorder.recentCustomerFailures(userId, -5);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(repository).findByUserIdAndSourceAndOutcomeOrderByCreatedAtDesc(
                any(), any(), any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    void alwaysRequestsTheFirstPage() {
        recorder.recentCustomerFailures(userId, 10);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(repository).findByUserIdAndSourceAndOutcomeOrderByCreatedAtDesc(
                any(), any(), any(), pageable.capture());
        assertThat(pageable.getValue()).isEqualTo(PageRequest.of(0, 10));
    }
}
