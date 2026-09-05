package com.finora.service;

import com.finora.dto.HeldImportDto;
import com.finora.entity.ImportJob;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.jobs.ImportJobWorker;
import com.finora.repository.ImportJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The operator half of the held-imports queue.
 *
 * <p>Mockito rather than a Spring-context IT, matching {@code AdminLearningQueueService}'s own
 * tests and this codebase's established pattern: what is being asserted is which transition an
 * operator action applies and what it audits, neither of which needs Postgres to prove.
 */
class AdminHeldImportServiceTest {

    private ImportJobRepository repository;
    private ImportJobWorker worker;
    private AuditService auditService;
    private com.finora.imports.storage.StatementContentService statementContentService;
    private AdminHeldImportService service;

    private final UUID adminUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(ImportJobRepository.class);
        worker = mock(ImportJobWorker.class);
        auditService = mock(AuditService.class);
        statementContentService = mock(com.finora.imports.storage.StatementContentService.class);
        service = new AdminHeldImportService(repository, worker, auditService, statementContentService);
        when(repository.save(any(ImportJob.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** A job in exactly the state the worker's routing leaves a held one in. */
    private ImportJob heldJob() {
        ImportJob job = new ImportJob(UUID.randomUUID(), "statement.pdf", "hash", "objects/key", "PDF");
        job.markClaimed("worker", Instant.now());
        job.markClaimed("worker", Instant.now());
        job.recordFailure("IllegalStateException: no header row", "IllegalStateException",
                ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT, Instant.now());
        job.holdForReview("IllegalStateException", Instant.now());
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_REVIEW);
        return job;
    }

    private ImportJob heldJobFor(UUID userId, String contentHash) {
        ImportJob job = new ImportJob(userId, "statement.pdf", contentHash, "objects/key", "PDF");
        job.markClaimed("worker", Instant.now());
        job.markClaimed("worker", Instant.now());
        job.recordFailure("IllegalStateException: no header row", "IllegalStateException",
                ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT, Instant.now());
        job.holdForReview("IllegalStateException", Instant.now());
        return job;
    }

    private void noLiveDuplicate() {
        when(repository.findFirstByUserIdAndContentHashAndStatusNotInOrderByCreatedAtDesc(
                any(), anyString(), any())).thenReturn(Optional.empty());
    }

    // ------------------------------------------------------------------ audit

    /**
     * Opening a held statement means looking at a real person's financial document. Every such view
     * is recorded against the admin who did it -- this is the privacy commitment the feature rests
     * on, not a nice-to-have.
     */
    @Test
    void detail_auditsEveryViewOfAHeldStatement() {
        ImportJob job = heldJob();
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));

        service.detail(adminUserId, job.getId());

        verify(auditService).record(eq(adminUserId), eq("HELD_IMPORT_VIEWED"), eq("ImportJob"),
                eq(job.getId()), any(Map.class));
    }

    @Test
    void download_auditsAndReturnsTheStatementBytes() {
        ImportJob job = heldJob();
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        byte[] bytes = "%PDF-1.4 fixture".getBytes();
        when(statementContentService.read(job)).thenReturn(bytes);

        AdminHeldImportService.DownloadedStatement result = service.download(adminUserId, job.getId());

        assertThat(result.content()).isEqualTo(bytes);
        assertThat(result.fileName()).isEqualTo(job.getFileName());
        assertThat(result.contentType()).isEqualTo("application/pdf");
        verify(auditService).record(eq(adminUserId), eq("HELD_IMPORT_DOWNLOADED"), eq("ImportJob"),
                eq(job.getId()), any());
    }

    @Test
    void download_throwsNotFoundForAnUnknownJob() {
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.download(adminUserId, missing))
                .isInstanceOf(ApiException.class);
        verify(auditService, never()).record(any(), anyString(), anyString(), any(), any());
    }

    /**
     * The row form withholds the raw parser error; the detail form carries it. That split is what
     * makes browsing the queue cheap and opening one statement accountable, so it is asserted
     * rather than left to the DTO's doc comment.
     */
    @Test
    void theListRowCarriesNoRawErrorText_onlyTheCuratedCode() {
        ImportJob job = heldJob();
        when(repository.findByStatus(eq(ImportJob.Status.HELD_FOR_REVIEW), any()))
                .thenReturn(new PageImpl<>(List.of(job)));

        var page = service.list(0, 25);

        HeldImportDto row = page.content().get(0);
        assertThat(row.failureCode()).isEqualTo("IllegalStateException");
        assertThat(row.toString())
                .as("a parser error quotes the input that defeated it -- customer content must not "
                        + "reach the unaudited list view")
                .doesNotContain("no header row");
    }

    @Test
    void detail_carriesTheRawErrorTheEngineerActuallyNeeds() {
        ImportJob job = heldJob();
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));

        HeldImportDto.Detail detail = service.detail(adminUserId, job.getId());

        assertThat(detail.lastError()).contains("no header row");
    }

    // ------------------------------------------------------------------ reprocess

    @Test
    void reprocess_returnsTheJobToTheQueueWithAFreshBudgetAndAudits() {
        ImportJob job = heldJob();
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        noLiveDuplicate();

        service.reprocess(adminUserId, job.getId());

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.QUEUED);
        assertThat(job.getAttemptCount()).isZero();
        verify(auditService).record(eq(adminUserId), eq("HELD_IMPORT_REPROCESSED"), eq("ImportJob"),
                eq(job.getId()), any(Map.class));
    }

    /**
     * The transition clears the failure code off the entity, so the audit entry is the only place
     * the original reason survives. Losing it would make "did fixing X clear these?" unanswerable.
     */
    @Test
    void reprocess_recordsTheOriginalFailureCodeOnTheAuditEntry() {
        ImportJob job = heldJob();
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        noLiveDuplicate();

        service.reprocess(adminUserId, job.getId());

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> metadata =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(adminUserId), eq("HELD_IMPORT_REPROCESSED"), eq("ImportJob"),
                eq(job.getId()), metadata.capture());
        assertThat(metadata.getValue()).containsEntry("originalFailureCode", "IllegalStateException");
        assertThat(job.getFailureCode())
                .as("a stale code would describe the wrong attempt on the customer timeline")
                .isNull();
    }

    @Test
    void reprocess_isRejectedForAJobThatIsNotHeld() {
        ImportJob job = new ImportJob(UUID.randomUUID(), "statement.csv", "hash", "objects/key", "CSV");
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.reprocess(adminUserId, job.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("QUEUED");
        verify(worker, never()).nudge();
    }

    /**
     * A held job is excluded from {@code idx_import_jobs_live_content} so the user can re-upload
     * the statement they were told to stop worrying about. If they did, moving the held job back to
     * QUEUED would make two live jobs for the same document and the unique index would reject it.
     * Caught as a 409 that explains itself rather than a constraint violation surfacing as a 500.
     */
    @Test
    void reprocess_isRefusedWhenTheUserHasAlreadyReUploadedTheSameStatement() {
        ImportJob job = heldJob();
        ImportJob newerLiveJob = new ImportJob(
                job.getUserId(), "statement.pdf", "hash", "objects/key", "PDF");
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        when(repository.findFirstByUserIdAndContentHashAndStatusNotInOrderByCreatedAtDesc(
                any(), anyString(), any())).thenReturn(Optional.of(newerLiveJob));

        assertThatThrownBy(() -> service.reprocess(adminUserId, job.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("re-uploaded");
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_REVIEW);
        verify(worker, never()).nudge();
    }

    // ------------------------------------------------------------------ bulk

    /**
     * A capped batch that reports plain success reads as a complete one. The audit entry records
     * what was left behind so the operator's "reprocess everything" is reconstructable.
     */
    @Test
    void reprocessAll_recordsWhatItLeftBehind() {
        List<ImportJob> held = List.of(heldJob(), heldJob());
        when(repository.findByStatus(eq(ImportJob.Status.HELD_FOR_REVIEW), any()))
                .thenReturn(new PageImpl<>(held));
        when(repository.countByStatus(ImportJob.Status.HELD_FOR_REVIEW)).thenReturn(5L);
        noLiveDuplicate();

        int reprocessed = service.reprocessAll(adminUserId);

        assertThat(reprocessed).isEqualTo(2);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> metadata =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(adminUserId), eq("HELD_IMPORTS_REPROCESSED_BULK"),
                eq("ImportJob"), eq(null), metadata.capture());
        assertThat(metadata.getValue()).containsEntry("count", 2);
        assertThat(metadata.getValue()).containsEntry("stillHeld", 3L);
    }

    /**
     * The held count has to be read before the batch is written.
     *
     * <p>Against a real database, Hibernate auto-flushes pending UPDATEs before querying the same
     * table, so a count taken afterwards already excludes everything the batch just requeued --
     * and subtracting the batch size from it would remove the same jobs twice. A mocked repository
     * returns the same number whenever it is asked, so nothing else in this class can catch that.
     * This pins the ordering directly.
     */
    @Test
    void reprocessAll_readsTheHeldCountBeforeItMutatesAnything() {
        ImportJob job = heldJob();
        when(repository.findByStatus(eq(ImportJob.Status.HELD_FOR_REVIEW), any()))
                .thenReturn(new PageImpl<>(List.of(job)));
        when(repository.countByStatus(ImportJob.Status.HELD_FOR_REVIEW)).thenReturn(4L);
        noLiveDuplicate();

        service.reprocessAll(adminUserId);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(repository);
        inOrder.verify(repository).countByStatus(ImportJob.Status.HELD_FOR_REVIEW);
        inOrder.verify(repository).saveAll(any());
    }

    /** One un-reprocessable job must not abort the whole batch. */
    @Test
    void reprocessAll_skipsDuplicatesRatherThanFailingTheBatch() {
        ImportJob reprocessable = heldJob();
        ImportJob blocked = heldJob();
        when(repository.findByStatus(eq(ImportJob.Status.HELD_FOR_REVIEW), any()))
                .thenReturn(new PageImpl<>(List.of(reprocessable, blocked)));
        when(repository.countByStatus(ImportJob.Status.HELD_FOR_REVIEW)).thenReturn(2L);
        when(repository.findFirstByUserIdAndContentHashAndStatusNotInOrderByCreatedAtDesc(
                eq(reprocessable.getUserId()), anyString(), any())).thenReturn(Optional.empty());
        when(repository.findFirstByUserIdAndContentHashAndStatusNotInOrderByCreatedAtDesc(
                eq(blocked.getUserId()), anyString(), any())).thenReturn(Optional.of(blocked));

        int reprocessed = service.reprocessAll(adminUserId);

        assertThat(reprocessed).isEqualTo(1);
        assertThat(reprocessable.getStatus()).isEqualTo(ImportJob.Status.QUEUED);
        assertThat(blocked.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_REVIEW);
    }

    /**
     * Two held jobs can legitimately hold the same (user, document).
     *
     * <p>V134 excludes HELD_FOR_REVIEW from idx_import_jobs_live_content precisely so a user who
     * was told "no action needed" can re-upload anyway. Requeuing both in one batch makes them both
     * live and the unique index rejects the second -- failing the whole batch with a 500 on what
     * the operator experienced as a single click. hasNoLiveDuplicate cannot catch this: neither job
     * is live at the moment it is asked.
     */
    @Test
    void reprocessAll_requeuesOnlyOneOfTwoHeldJobsForTheSameDocument() {
        UUID sameUser = UUID.randomUUID();
        ImportJob first = heldJobFor(sameUser, "same-hash");
        ImportJob second = heldJobFor(sameUser, "same-hash");
        when(repository.findByStatus(eq(ImportJob.Status.HELD_FOR_REVIEW), any()))
                .thenReturn(new PageImpl<>(List.of(first, second)));
        when(repository.countByStatus(ImportJob.Status.HELD_FOR_REVIEW)).thenReturn(2L);
        noLiveDuplicate();

        int reprocessed = service.reprocessAll(adminUserId);

        assertThat(reprocessed).isEqualTo(1);
        assertThat(first.getStatus())
                .as("the oldest submission is the one the user has waited on longest")
                .isEqualTo(ImportJob.Status.QUEUED);
        assertThat(second.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_REVIEW);
    }

    /** Different users uploading identical bytes is not a collision -- the index is per user. */
    @Test
    void reprocessAll_doesNotConflateIdenticalDocumentsFromDifferentUsers() {
        ImportJob mine = heldJobFor(UUID.randomUUID(), "same-hash");
        ImportJob theirs = heldJobFor(UUID.randomUUID(), "same-hash");
        when(repository.findByStatus(eq(ImportJob.Status.HELD_FOR_REVIEW), any()))
                .thenReturn(new PageImpl<>(List.of(mine, theirs)));
        when(repository.countByStatus(ImportJob.Status.HELD_FOR_REVIEW)).thenReturn(2L);
        noLiveDuplicate();

        assertThat(service.reprocessAll(adminUserId)).isEqualTo(2);
    }

    @Test
    void reprocessAll_doesNothingAndNudgesNobodyWhenTheQueueIsEmpty() {
        when(repository.findByStatus(eq(ImportJob.Status.HELD_FOR_REVIEW), any()))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(service.reprocessAll(adminUserId)).isZero();
        verify(worker, never()).nudge();
        verify(auditService, never()).record(any(), anyString(), anyString(), any(), any());
    }

    // ------------------------------------------------------------------ resolve

    @Test
    void resolve_movesTheJobToPlainFailedAndRecordsTheReasonInTheAudit() {
        ImportJob job = heldJob();
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));

        service.resolve(adminUserId, job.getId(), "bank publishes an image with no text layer");

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.FAILED);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> metadata =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(adminUserId), eq("HELD_IMPORT_RESOLVED"), eq("ImportJob"),
                eq(job.getId()), metadata.capture());
        assertThat(metadata.getValue())
                .containsEntry("reason", "bank publishes an image with no text layer");
    }

    /** Map.of rejects nulls, and an operator is not required to explain themselves. */
    @Test
    void resolve_acceptsNoReason() {
        ImportJob job = heldJob();
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));

        service.resolve(adminUserId, job.getId(), null);

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.FAILED);
    }

    @Test
    void resolve_isRejectedForAJobThatIsNotHeld() {
        ImportJob job = new ImportJob(UUID.randomUUID(), "statement.csv", "hash", "objects/key", "CSV");
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.resolve(adminUserId, job.getId(), "no"))
                .isInstanceOf(ApiException.class);
    }

    // ------------------------------------------------------------------ summary

    /**
     * "Reprocessing" counts QUEUED jobs that were once held -- not every QUEUED job. The queue is
     * mostly ordinary uploads, and counting those would report work nobody is waiting on.
     */
    @Test
    void summary_countsHeldAndInFlightReprocessesSeparately() {
        when(repository.countByStatus(ImportJob.Status.HELD_FOR_REVIEW)).thenReturn(7L);
        when(repository.countByStatusAndWasHeldForReviewTrue(ImportJob.Status.QUEUED)).thenReturn(2L);

        HeldImportDto.Summary summary = service.summary();

        assertThat(summary.held()).isEqualTo(7L);
        assertThat(summary.reprocessing()).isEqualTo(2L);
    }

    @Test
    void anUnknownJobIs404NotAnUnhandledException() {
        when(repository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(adminUserId, UUID.randomUUID()))
                .isInstanceOf(ApiException.class);
    }
}
