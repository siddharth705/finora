package com.finora.service;

import com.finora.repository.TransactionRepository;
import com.finora.repository.TransactionRepository.CounterpartyBackfillRow;
import com.finora.util.CounterpartyClassifier;
import com.finora.util.CounterpartyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sweep's control flow. What it can prove is bookkeeping -- what gets stamped, what gets
 * skipped, what a failure does to the rest of the batch.
 *
 * <p>What it deliberately CANNOT prove is that the JPQL in
 * {@code TransactionRepository.applyCounterpartyTyping} is valid or writes the columns it claims
 * to; a mocked repository will happily accept a query that would not parse. {@code
 * CounterpartyBackfillSweepIT} covers that against real Postgres, and the two are not
 * interchangeable.
 */
class CounterpartyBackfillSweepServiceTest {

    private TransactionRepository transactionRepository;
    private CounterpartyBackfillSweepService service;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

        // executeWithoutResult is a default method TransactionTemplate inherits rather than
        // overrides, and a Mockito mock does not fall through to a default implementation -- the
        // established fix in this codebase, see NotificationDispatcherTest / AccountPurgeSweepServiceTest.
        doAnswer(inv -> {
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        service = new CounterpartyBackfillSweepService(transactionRepository, transactionTemplate);
        ReflectionTestUtils.setField(service, "sweepEnabled", true);
        ReflectionTestUtils.setField(service, "batchSize", 3);
    }

    @Test
    void itTypesEachRowAndStampsTheCurrentClassifierVersion() {
        UUID person = UUID.randomUUID();
        UUID business = UUID.randomUUID();
        given(List.of(
                row(person, "UPI-SUNIL VERMA-sampleuser@ybl-REF61"),
                row(business, "UPI-PAYTMQR281005-mer@paytm-REF62")));
        when(transactionRepository.applyCounterpartyTyping(any(), any(), any(), anyShort())).thenReturn(1);

        var result = service.sweep();

        assertThat(result.typed()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        // The rail marker outranks the name shape -- a merchant QR cannot be collected on by an
        // individual. Pinned here so a regression in the classifier shows up as a backfill that
        // writes the wrong answer, not just as a unit-test failure one layer away.
        verify(transactionRepository).applyCounterpartyTyping(
                person, CounterpartyType.PERSON, "vpa:sampleuser", CounterpartyClassifier.VERSION);
        verify(transactionRepository).applyCounterpartyTyping(
                business, CounterpartyType.BUSINESS, "vpa:mer", CounterpartyClassifier.VERSION);
    }

    @Test
    void itAsksOnlyForRowsBelowTheCurrentVersion() {
        given(List.of());

        service.sweep();

        // Passing a hardcoded version here would let a VERSION bump silently stop re-typing the
        // rows it exists to re-type.
        verify(transactionRepository).findRowsNeedingCounterpartyTyping(
                eq(CounterpartyClassifier.VERSION), any(Pageable.class));
    }

    @Test
    void aRowThatVanishedBetweenDiscoveryAndWriteIsSkipped_notCountedAsTyped() {
        // The update matches nothing because the row was deleted in between. Not an error and not
        // work done -- counting it as typed would overstate progress in the one number an operator
        // watching a backfill actually reads.
        given(List.of(row(UUID.randomUUID(), "UPI-ACME-acme@ybl-REF63")));
        when(transactionRepository.applyCounterpartyTyping(any(), any(), any(), anyShort())).thenReturn(0);

        var result = service.sweep();

        assertThat(result.typed()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failed()).isZero();
    }

    @Test
    void oneFailingRowIsNeverStamped_andTheRestOfTheBatchIsStillTyped() {
        // The guarantee that keeps the sweep making progress. A poison row is retried forever and
        // logs every pass -- loud and visible -- but it must not take its batch down with it, and it
        // must not be stamped, because stamping would claim revision N examined a row revision N
        // never got through.
        UUID poison = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        given(List.of(row(poison, "POISON"), row(healthy, "UPI-ACME-acme@ybl-REF64")));
        when(transactionRepository.applyCounterpartyTyping(eq(poison), any(), any(), anyShort()))
                .thenThrow(new IllegalStateException("boom"));
        when(transactionRepository.applyCounterpartyTyping(eq(healthy), any(), any(), anyShort()))
                .thenReturn(1);

        var result = service.sweep();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.typed()).isEqualTo(1);
        verify(transactionRepository).applyCounterpartyTyping(
                eq(healthy), any(), any(), eq(CounterpartyClassifier.VERSION));
    }

    @Test
    void aShortPageWithAFailureIsNotDrained_becauseThatRowIsStillACandidate() {
        // A failed row is deliberately left unstamped, so it stays in the candidate set. Calling
        // this drained would announce a finished backfill over rows that come straight back on the
        // next pass -- the one number an operator uses to decide the backfill is done.
        UUID poison = UUID.randomUUID();
        given(List.of(row(poison, "POISON")));   // batchSize is 3, so this page is short
        when(transactionRepository.applyCounterpartyTyping(any(), any(), any(), anyShort()))
                .thenThrow(new IllegalStateException("boom"));

        var result = service.sweep();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.drained()).isFalse();
    }

    @Test
    void aShortPageWithNoFailuresIsDrained() {
        given(List.of(row(UUID.randomUUID(), "UPI-ACME-acme@ybl-REF69")));
        when(transactionRepository.applyCounterpartyTyping(any(), any(), any(), anyShort())).thenReturn(1);

        assertThat(service.sweep().drained()).isTrue();
    }

    @Test
    void anEmptyCandidateSetDoesNoWorkAndReportsDrained() {
        given(List.of());

        var result = service.sweep();

        assertThat(result.drained()).isTrue();
        assertThat(result.typed()).isZero();
        verify(transactionRepository, never()).applyCounterpartyTyping(any(), any(), any(), anyShort());
    }

    @Test
    void aFullPageMeansMoreRemain_soTheSweepDoesNotReportItselfDrained() {
        // batchSize is 3 here; a full page is the only evidence available that more work exists,
        // and it is free, where a COUNT(*) of the remainder would not be.
        given(List.of(
                row(UUID.randomUUID(), "UPI-A-a@ybl-REF65"),
                row(UUID.randomUUID(), "UPI-B-b@ybl-REF66"),
                row(UUID.randomUUID(), "UPI-C-c@ybl-REF67")));
        when(transactionRepository.applyCounterpartyTyping(any(), any(), any(), anyShort())).thenReturn(1);

        assertThat(service.sweep().drained()).isFalse();
    }

    @Test
    void aRowWithNoNarrationAtAllIsTypedUnknown_notLeftForever() {
        // transactions.description is nullable (V1), so the backfill will meet these. Leaving them
        // unstamped would make the sweep re-discover the same rows on every pass for the life of
        // the table.
        UUID blank = UUID.randomUUID();
        given(List.of(row(blank, null)));
        when(transactionRepository.applyCounterpartyTyping(any(), any(), any(), anyShort())).thenReturn(1);

        var result = service.sweep();

        assertThat(result.typed()).isEqualTo(1);
        verify(transactionRepository).applyCounterpartyTyping(
                blank, CounterpartyType.UNKNOWN, null, CounterpartyClassifier.VERSION);
    }

    @Test
    void theStampedVersionIsAlwaysTheOneTheClassifierReports() {
        given(List.of(row(UUID.randomUUID(), "NEFT-ACME LTD-REF68")));
        when(transactionRepository.applyCounterpartyTyping(any(), any(), any(), anyShort())).thenReturn(1);

        service.sweep();

        ArgumentCaptor<Short> version = ArgumentCaptor.forClass(Short.class);
        verify(transactionRepository).applyCounterpartyTyping(any(), any(), any(), version.capture());
        assertThat(version.getValue()).isEqualTo(CounterpartyClassifier.VERSION);
    }

    // --- helpers -----------------------------------------------------------------------------

    /** Stubs discovery. Takes an already-built list rather than building the projection mocks
     *  inline: creating and stubbing a mock while an outer {@code when(...)} is still open throws
     *  UnfinishedStubbingException, which this codebase has hit before. */
    private void given(List<CounterpartyBackfillRow> rows) {
        when(transactionRepository.findRowsNeedingCounterpartyTyping(anyShort(), any(Pageable.class)))
                .thenReturn(rows);
    }

    private static CounterpartyBackfillRow row(UUID id, String description) {
        CounterpartyBackfillRow row = mock(CounterpartyBackfillRow.class);
        when(row.getId()).thenReturn(id);
        when(row.getDescription()).thenReturn(description);
        return row;
    }
}
