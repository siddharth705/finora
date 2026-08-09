package com.finora.imports.storage;

import com.finora.repository.ImportSessionRepository;
import com.finora.repository.StatementImportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * BH-017's decision logic in isolation -- everything a mocked repository can prove: which
 * candidates get deleted, which get skipped and why, and that a no-provider configuration never
 * touches the database at all. {@link StatementStorageSweepServiceIT} covers what only a real
 * Postgres can: that the native reference-counting query and {@code @SQLRestriction} actually
 * interact the way this class assumes.
 */
class StatementStorageSweepServiceTest {

    private StatementStorage storage;
    private StatementImportRepository statementImportRepository;
    private ImportSessionRepository importSessionRepository;
    private StatementStorageSweepService service;

    @BeforeEach
    void setUp() {
        storage = mock(StatementStorage.class);
        statementImportRepository = mock(StatementImportRepository.class);
        importSessionRepository = mock(ImportSessionRepository.class);
        service = newService(Optional.of(storage));
    }

    private StatementStorageSweepService newService(Optional<StatementStorage> storageOptional) {
        StatementStorageSweepService s = new StatementStorageSweepService(
                storageOptional, statementImportRepository, importSessionRepository);
        ReflectionTestUtils.setField(s, "retentionDays", 90);
        ReflectionTestUtils.setField(s, "batchSize", 200);
        ReflectionTestUtils.setField(s, "sweepEnabled", true);
        return s;
    }

    private static Object[] candidate(String hash, String key, Instant lastReferencedAt) {
        // Matches what findObjectsUnreferencedSince actually returns in production: epoch
        // milliseconds as a Long, not java.sql.Timestamp -- see that query's own doc comment for
        // why (FG-019 bans the legacy JDBC date types from production code).
        return new Object[]{hash, key, lastReferencedAt.toEpochMilli()};
    }

    @Test
    void sweep_noOpsEntirely_whenNoStorageProviderIsConfigured() {
        StatementStorageSweepService noProvider = newService(Optional.empty());

        StatementStorageSweepService.Result result = noProvider.sweep();

        assertThat(result.swept()).isZero();
        assertThat(result.skipped()).isZero();
        assertThat(result.failed()).isZero();
        // The whole point: with no provider, this must not even query the database, matching how
        // every other consumer of Optional<StatementStorage> behaves when it's empty.
        verifyNoInteractions(statementImportRepository, importSessionRepository);
    }

    @Test
    void sweep_deletesAnObjectThatIsUnreferencedInBothTables() {
        String key = "statements/aa/bb/aabbcc.bin";
        when(statementImportRepository.findObjectsUnreferencedSince(any(), anyInt()))
                .thenReturn(List.<Object[]>of(candidate("aabbcc", key, Instant.now().minus(120, ChronoUnit.DAYS))));
        when(statementImportRepository.existsByObjectKey(key)).thenReturn(false);
        when(importSessionRepository.existsByObjectKey(key)).thenReturn(false);

        StatementStorageSweepService.Result result = service.sweep();

        assertThat(result.swept()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        verify(storage).delete(key);
    }

    /**
     * The regression test BH-017 exists for. Two StatementImport rows historically shared one
     * object_key because content-addressing deduplicates by bytes (one row per account section of
     * a composite statement, or one per re-import) -- see StatementContentService's class doc.
     * Row A was deleted long enough ago to be a discovery candidate; row B still lives and
     * references the same key.
     *
     * <p>A naive "delete the object when the row that owned it expires/gets deleted" implementation
     * -- which is exactly the shape BH-017 reports as broken, and exactly what Sid decided against
     * building -- would call delete() the moment row A disappeared, with no idea row B exists. This
     * test fails under that implementation and passes only because the sweep re-checks EVERY
     * candidate against BOTH tables, fresh, before acting.
     */
    @Test
    void sweep_doesNotDeleteAnObjectStillReferencedByAnotherLiveRow_provingReferenceCountingMatters() {
        String sharedKey = "statements/aa/bb/aabbcc.bin";
        // Row A is what the discovery query surfaces: it was soft-deleted 120 days ago, so it looks
        // like a candidate in isolation.
        when(statementImportRepository.findObjectsUnreferencedSince(any(), anyInt()))
                .thenReturn(List.<Object[]>of(candidate("aabbcc", sharedKey, Instant.now().minus(120, ChronoUnit.DAYS))));
        // Row B is the live sibling a naive implementation would never look for: same object_key,
        // not deleted.
        when(statementImportRepository.existsByObjectKey(sharedKey)).thenReturn(true);
        when(importSessionRepository.existsByObjectKey(sharedKey)).thenReturn(false);

        StatementStorageSweepService.Result result = service.sweep();

        assertThat(result.swept()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void sweep_doesNotDeleteAnObjectStillReferencedByALiveImportSession() {
        String key = "statements/cc/dd/ccddee.bin";
        when(statementImportRepository.findObjectsUnreferencedSince(any(), anyInt()))
                .thenReturn(List.<Object[]>of(candidate("ccddee", key, Instant.now().minus(120, ChronoUnit.DAYS))));
        when(statementImportRepository.existsByObjectKey(key)).thenReturn(false);
        // The confirmed-import row was itself deleted (hence a candidate), but the ImportSession it
        // was originally staged from -- same content, same key -- hasn't hit its own 48h TTL yet.
        when(importSessionRepository.existsByObjectKey(key)).thenReturn(true);

        StatementStorageSweepService.Result result = service.sweep();

        assertThat(result.swept()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void sweep_continuesTheBatch_whenOneDeleteFails() {
        when(statementImportRepository.findObjectsUnreferencedSince(any(), anyInt()))
                .thenReturn(List.of(
                        candidate("hash1", "key1", Instant.now().minus(120, ChronoUnit.DAYS)),
                        candidate("hash2", "key2", Instant.now().minus(120, ChronoUnit.DAYS))));
        when(statementImportRepository.existsByObjectKey(anyString())).thenReturn(false);
        when(importSessionRepository.existsByObjectKey(anyString())).thenReturn(false);
        org.mockito.Mockito.doThrow(new StatementStorageException("boom", new RuntimeException()))
                .when(storage).delete("key1");

        StatementStorageSweepService.Result result = service.sweep();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.swept()).isEqualTo(1);
        verify(storage).delete("key1");
        verify(storage).delete("key2");
    }

    @Test
    void sweep_passesTheConfiguredBatchSizeAndAnAtLeast90DayOldCutoff_toTheDiscoveryQuery() {
        when(statementImportRepository.findObjectsUnreferencedSince(any(), anyInt())).thenReturn(List.of());

        service.sweep();

        org.mockito.ArgumentCaptor<Instant> cutoffCaptor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(statementImportRepository).findObjectsUnreferencedSince(cutoffCaptor.capture(), eq(200));
        assertThat(cutoffCaptor.getValue()).isBefore(Instant.now().minus(89, ChronoUnit.DAYS));
    }

    /**
     * {@link StatementStorageSweepService#MINIMUM_SAFETY_BUFFER}. Even a misconfigured
     * retention-days of 0 must not make the cutoff "now" -- that would let the sweep consider an
     * object unreferenced moments after its last reference disappeared, into the same race window
     * a concurrent request could still be mid-way through.
     */
    @Test
    void sweep_enforcesAMinimumSafetyBuffer_evenIfRetentionDaysIsMisconfiguredToZero() {
        ReflectionTestUtils.setField(service, "retentionDays", 0);
        when(statementImportRepository.findObjectsUnreferencedSince(any(), anyInt())).thenReturn(List.of());

        service.sweep();

        org.mockito.ArgumentCaptor<Instant> cutoffCaptor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(statementImportRepository).findObjectsUnreferencedSince(cutoffCaptor.capture(), anyInt());
        assertThat(cutoffCaptor.getValue()).isBefore(Instant.now().minus(23, ChronoUnit.HOURS));
    }

    @Test
    void scheduledSweep_doesNothing_whenDisabled() {
        ReflectionTestUtils.setField(service, "sweepEnabled", false);

        service.scheduledSweep();

        verifyNoInteractions(statementImportRepository, importSessionRepository, storage);
    }
}
