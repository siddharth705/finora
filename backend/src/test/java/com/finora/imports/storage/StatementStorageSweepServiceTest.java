package com.finora.imports.storage;

import com.finora.entity.ImportJob;
import com.finora.repository.ImportJobRepository;
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
    private ImportJobRepository importJobRepository;
    private StatementStorageSweepService service;

    @BeforeEach
    void setUp() {
        storage = mock(StatementStorage.class);
        statementImportRepository = mock(StatementImportRepository.class);
        importSessionRepository = mock(ImportSessionRepository.class);
        importJobRepository = mock(ImportJobRepository.class);
        service = newService(Optional.of(storage));
    }

    private StatementStorageSweepService newService(Optional<StatementStorage> storageOptional) {
        StatementStorageSweepService s = new StatementStorageSweepService(
                storageOptional, statementImportRepository, importSessionRepository, importJobRepository);
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
        verifyNoInteractions(statementImportRepository, importSessionRepository, importJobRepository);
    }

    /**
     * The clean-sweep path: none of the three tables reference this key. Explicitly stubs AND
     * verifies the import_jobs check specifically (not just relying on Mockito's unstubbed-boolean
     * default of {@code false}) -- without that verify, this test could not tell "checked, and it
     * came back false" apart from "never checked at all", and would keep passing unchanged if a
     * future edit accidentally dropped the import_jobs clause from the OR entirely.
     */
    @Test
    void sweep_deletesAnObjectThatIsUnreferencedInAllThreeTables() {
        String key = "statements/aa/bb/aabbcc.bin";
        when(statementImportRepository.findObjectsUnreferencedSince(any(), anyInt()))
                .thenReturn(List.<Object[]>of(candidate("aabbcc", key, Instant.now().minus(120, ChronoUnit.DAYS))));
        when(statementImportRepository.existsByObjectKey(key)).thenReturn(false);
        when(importSessionRepository.existsByObjectKey(key)).thenReturn(false);
        when(importJobRepository.existsByObjectKeyAndStatusNotIn(eq(key), any())).thenReturn(false);

        StatementStorageSweepService.Result result = service.sweep();

        assertThat(result.swept()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        verify(storage).delete(key);
        verify(importJobRepository).existsByObjectKeyAndStatusNotIn(eq(key), any());
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
     * candidate against all three tables, fresh, before acting.
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

    /**
     * The gap this change closes. A FAILED import_jobs row has no counterpart in either
     * statement_imports or import_sessions -- work that fails before producing a confirmable row
     * leaves no trace there at all -- so before this check existed, this exact object would have
     * been swept out from under a future "retry without re-upload" the moment it looked old enough.
     */
    @Test
    void sweep_doesNotDeleteAnObjectStillReferencedByALiveFailedImportJob() {
        String key = "statements/ee/ff/eeff00.bin";
        when(statementImportRepository.findObjectsUnreferencedSince(any(), anyInt()))
                .thenReturn(List.<Object[]>of(candidate("eeff00", key, Instant.now().minus(120, ChronoUnit.DAYS))));
        when(statementImportRepository.existsByObjectKey(key)).thenReturn(false);
        when(importSessionRepository.existsByObjectKey(key)).thenReturn(false);
        when(importJobRepository.existsByObjectKeyAndStatusNotIn(eq(key), any())).thenReturn(true);

        StatementStorageSweepService.Result result = service.sweep();

        assertThat(result.swept()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        verify(storage, never()).delete(anyString());
    }

    /**
     * The same gap, the CANCELLED half. A job the user cancelled before staging finished (see
     * {@code ImportJob#isCancellable}) also has no row in either of the other two tables -- there
     * was never a session to stage. Regression test for a bug this fix originally shipped with:
     * the repository check excluded only COMPLETED, so a CANCELLED-only reference was treated as
     * NOT protecting the object -- silently sweepable the moment it looked old enough, exactly the
     * failure mode this whole change exists to prevent, just for a different status.
     */
    @Test
    void sweep_doesNotDeleteAnObjectStillReferencedByALiveCancelledImportJob() {
        String key = "statements/22/33/223344.bin";
        when(statementImportRepository.findObjectsUnreferencedSince(any(), anyInt()))
                .thenReturn(List.<Object[]>of(candidate("223344", key, Instant.now().minus(120, ChronoUnit.DAYS))));
        when(statementImportRepository.existsByObjectKey(key)).thenReturn(false);
        when(importSessionRepository.existsByObjectKey(key)).thenReturn(false);
        when(importJobRepository.existsByObjectKeyAndStatusNotIn(eq(key), any())).thenReturn(true);

        StatementStorageSweepService.Result result = service.sweep();

        assertThat(result.swept()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        verify(storage, never()).delete(anyString());
    }

    /**
     * The other half: a COMPLETED or CANCELLED job must NOT protect its object on its own.
     * import_jobs rows never expire (only cascading away with the owning user), so if either
     * counted here, that object would become permanently unsweepable the moment the job reached
     * that status -- for COMPLETED, even long after the user deletes the statement and its
     * originating session expires; for CANCELLED, with no other reference ever having existed at
     * all. This is exactly why the repository check excludes both rather than matching every
     * status the way the other two tables' checks do.
     */
    @Test
    void sweep_deletesAnObjectWhoseOnlyImportJobReferenceIsCompletedOrCancelled() {
        String key = "statements/11/22/112233.bin";
        when(statementImportRepository.findObjectsUnreferencedSince(any(), anyInt()))
                .thenReturn(List.<Object[]>of(candidate("112233", key, Instant.now().minus(120, ChronoUnit.DAYS))));
        when(statementImportRepository.existsByObjectKey(key)).thenReturn(false);
        when(importSessionRepository.existsByObjectKey(key)).thenReturn(false);
        when(importJobRepository.existsByObjectKeyAndStatusNotIn(eq(key), any())).thenReturn(false);

        StatementStorageSweepService.Result result = service.sweep();

        assertThat(result.swept()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        verify(storage).delete(key);
    }

    /**
     * Regression test for the exact exclusion set, not just its effect. The two tests above prove
     * the sweep behaves correctly for whatever set production code happens to pass; this proves
     * production code passes the RIGHT set -- {@code {COMPLETED, CANCELLED}}, no more and no less.
     * A future edit that widened this (e.g. to also exclude FAILED, silently undoing this whole
     * fix) or narrowed it (e.g. back to just {COMPLETED}, reintroducing the CANCELLED gap the test
     * above catches only via its stub, not via what's actually sent to the repository) would pass
     * every other test in this class unchanged -- Mockito's stub matches on the stubbed key
     * regardless of which set accompanies it. This is the one test that inspects the set itself.
     */
    @Test
    void sweep_excludesExactlyCompletedAndCancelled_fromTheImportJobReferenceCheck() {
        String key = "statements/44/55/445566.bin";
        when(statementImportRepository.findObjectsUnreferencedSince(any(), anyInt()))
                .thenReturn(List.<Object[]>of(candidate("445566", key, Instant.now().minus(120, ChronoUnit.DAYS))));
        when(statementImportRepository.existsByObjectKey(key)).thenReturn(false);
        when(importSessionRepository.existsByObjectKey(key)).thenReturn(false);
        when(importJobRepository.existsByObjectKeyAndStatusNotIn(eq(key), any())).thenReturn(false);

        service.sweep();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Collection<ImportJob.Status>> excludedCaptor =
                org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(importJobRepository).existsByObjectKeyAndStatusNotIn(eq(key), excludedCaptor.capture());
        assertThat(excludedCaptor.getValue())
                .containsExactlyInAnyOrder(ImportJob.Status.COMPLETED, ImportJob.Status.CANCELLED);
    }

    /**
     * A held job's stored object must survive, exactly as a FAILED job's does.
     *
     * <p>No production change was needed for this -- {@code IMPORT_JOB_EXCLUDED_STATUSES} names the
     * statuses that do NOT protect an object, so a new status is protected by default -- and that is
     * precisely why the test exists. The protection is implicit, and an edit that "tidied" the set
     * by adding HELD_FOR_REVIEW to it would read as harmless and would delete the very files the
     * triage queue exists to reprocess. A held job is the worst possible thing to get wrong here: it
     * waits on a human fixing a parser, so it can sit far longer than a failed one, and its object
     * is the entire input to the reprocess.
     */
    @Test
    void sweep_retainsTheObjectOfAHeldForReviewJob() {
        String key = "statements/66/77/667788.bin";
        when(statementImportRepository.findObjectsUnreferencedSince(any(), anyInt()))
                .thenReturn(List.<Object[]>of(candidate("667788", key, Instant.now().minus(120, ChronoUnit.DAYS))));
        when(statementImportRepository.existsByObjectKey(key)).thenReturn(false);
        when(importSessionRepository.existsByObjectKey(key)).thenReturn(false);
        // The real repository answers "yes, a job outside the excluded set references this" for a
        // held job, because HELD_FOR_REVIEW is not one of the two excluded statuses.
        when(importJobRepository.existsByObjectKeyAndStatusNotIn(eq(key), any())).thenReturn(true);

        service.sweep();

        verify(storage, never()).delete(key);
    }

    /** The set that decides the above, asserted directly: HELD_FOR_REVIEW must never join it. */
    @Test
    void heldForReviewIsNotAnExcludedStatus_soItsObjectIsRetained() {
        assertThat(StatementStorageSweepService.IMPORT_JOB_EXCLUDED_STATUSES)
                .as("adding HELD_FOR_REVIEW here would delete the statements this feature "
                        + "reprocesses")
                .doesNotContain(ImportJob.Status.HELD_FOR_REVIEW);
    }

    @Test
    void sweep_continuesTheBatch_whenOneDeleteFails() {
        when(statementImportRepository.findObjectsUnreferencedSince(any(), anyInt()))
                .thenReturn(List.of(
                        candidate("hash1", "key1", Instant.now().minus(120, ChronoUnit.DAYS)),
                        candidate("hash2", "key2", Instant.now().minus(120, ChronoUnit.DAYS))));
        when(statementImportRepository.existsByObjectKey(anyString())).thenReturn(false);
        when(importSessionRepository.existsByObjectKey(anyString())).thenReturn(false);
        when(importJobRepository.existsByObjectKeyAndStatusNotIn(anyString(), any())).thenReturn(false);
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

        verifyNoInteractions(statementImportRepository, importSessionRepository, importJobRepository, storage);
    }
}
