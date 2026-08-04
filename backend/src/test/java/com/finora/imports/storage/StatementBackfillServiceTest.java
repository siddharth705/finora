package com.finora.imports.storage;

import com.finora.repository.ImportSessionRepository;
import com.finora.repository.StatementImportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatementBackfillServiceTest {

    private StatementImportRepository imports;
    private ImportSessionRepository sessions;
    private StatementBackfillWorker worker;
    private StatementBackfillService service;

    @BeforeEach
    void setUp() {
        imports = mock(StatementImportRepository.class);
        sessions = mock(ImportSessionRepository.class);
        worker = mock(StatementBackfillWorker.class);
        service = new StatementBackfillService(imports, sessions, worker,
                Optional.of(mock(StatementStorage.class)));
        when(imports.findIdsWithoutContentAddress(any())).thenReturn(List.of());
        when(sessions.findIdsWithoutContentAddress(any())).thenReturn(List.of());
    }

    @Test
    void refusesToRunWithNoProviderConfigured_ratherThanSilentlyDoingNothing() {
        // Reporting "0 processed" here would read as "already done" on a status page and could
        // green-light Phase 4, which would drop the only copy of every unaddressed row.
        StatementBackfillService unconfigured =
                new StatementBackfillService(imports, sessions, worker, Optional.empty());

        assertThatThrownBy(() -> unconfigured.runBatch(10))
                .isInstanceOf(StatementStorageException.class)
                .hasMessageContaining("No statement storage provider is configured");
        verify(worker, never()).addressStatementImport(any());
    }

    @Test
    void separatesNewlyStoredFromAlreadyPresent_whichIsTheDeduplicationMeasurement() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        when(imports.findIdsWithoutContentAddress(any())).thenReturn(List.of(a, b, c));
        when(worker.addressStatementImport(a)).thenReturn(true);   // first copy of this file
        when(worker.addressStatementImport(b)).thenReturn(false);  // another section of it
        when(worker.addressStatementImport(c)).thenReturn(false);  // a re-import of it

        BackfillResultAssert result = new BackfillResultAssert(service.runBatch(10));

        // Three rows, one object. This is the number that answers "how much of the database was
        // the same file over and over" -- see §2.1 of the migration doc.
        result.hasStored(1).hasDeduplicated(2).hasProcessed(3).hasFailed(0);
    }

    @Test
    void oneBadRowDoesNotStopTheBatch_andIsLeftForTheNextRun() {
        UUID good = UUID.randomUUID(), bad = UUID.randomUUID(), alsoGood = UUID.randomUUID();
        when(imports.findIdsWithoutContentAddress(any())).thenReturn(List.of(good, bad, alsoGood));
        when(worker.addressStatementImport(good)).thenReturn(true);
        when(worker.addressStatementImport(bad)).thenThrow(new StatementStorageException("unreadable"));
        when(worker.addressStatementImport(alsoGood)).thenReturn(true);

        var result = service.runBatch(10);

        assertThat(result.stored()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failures()).hasSize(1).first().asString().contains("unreadable");
        // The failing row keeps its bytes and no address, so it is simply selected again next run.
        verify(worker).addressStatementImport(alsoGood);
    }

    @Test
    void doesBothTablesInOneBatch() {
        when(imports.findIdsWithoutContentAddress(any())).thenReturn(List.of(UUID.randomUUID()));
        UUID sessionId = UUID.randomUUID();
        when(sessions.findIdsWithoutContentAddress(any())).thenReturn(List.of(sessionId));
        when(worker.addressStatementImport(any())).thenReturn(true);
        when(worker.addressImportSession(sessionId)).thenReturn(true);

        assertThat(service.runBatch(10).stored()).isEqualTo(2);
        verify(worker).addressImportSession(sessionId);
    }

    @Test
    void batchSizeIsCapped_soNobodyCanAskForEveryTenMegabyteRowAtOnce() {
        service.runBatch(100_000);

        verify(imports).findIdsWithoutContentAddress(
                org.mockito.ArgumentMatchers.argThat(p -> p.getPageSize() == 200));
    }

    @Test
    void statusReportsCompleteOnlyWhenBothTablesAreDone() {
        when(imports.countAllIncludingDeleted()).thenReturn(10L);
        when(imports.countWithoutContentAddress()).thenReturn(0L);
        when(sessions.countWithoutContentAddress()).thenReturn(3L);

        // Phase 4 drops file_content, so "complete" gating on both tables rather than the visible
        // one is what stops sessions being silently left behind.
        assertThat(service.status().complete()).isFalse();

        when(sessions.countWithoutContentAddress()).thenReturn(0L);
        assertThat(service.status().complete()).isTrue();
        assertThat(service.status().importsAddressed()).isEqualTo(10L);
    }

    /** Small fluent wrapper -- the counts are easy to transpose otherwise. */
    private record BackfillResultAssert(StatementBackfillService.BackfillBatchResult actual) {
        BackfillResultAssert hasStored(int n) { assertThat(actual.stored()).isEqualTo(n); return this; }
        BackfillResultAssert hasDeduplicated(int n) { assertThat(actual.deduplicated()).isEqualTo(n); return this; }
        BackfillResultAssert hasProcessed(int n) { assertThat(actual.processed()).isEqualTo(n); return this; }
        BackfillResultAssert hasFailed(int n) { assertThat(actual.failed()).isEqualTo(n); return this; }
    }
}
