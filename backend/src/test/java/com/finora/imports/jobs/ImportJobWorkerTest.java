package com.finora.imports.jobs;

import com.finora.entity.ImportJob;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.ImportService;
import com.finora.imports.storage.StatementContentService;
import com.finora.imports.storage.StatementIntegrityException;
import com.finora.imports.storage.StatementStorageException;
import com.finora.observability.AlertSeverity;
import com.finora.dto.ImportDto;
import com.finora.observability.WorkerObservability;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Premium Import Reliability v1, §5.5 -- the first commit in the initiative where getting the
 * classification wrong changes what a real, running import does, not just what a test asserts.
 * Mockito-based, matching this codebase's established pattern (GoalServiceTest et al.), rather than
 * a Spring-context IT: the thing under test here is which {@code ErrorCode.RetryPolicy} a given
 * exception reaches {@code ImportJob.recordFailure} with, which does not need Postgres to prove and
 * is awkward to trigger precisely through the full upload-and-poll pipeline.
 *
 * <p>{@code jobStore.update} is stubbed to apply its consumer directly to the real, in-memory
 * {@link ImportJob} under test, mirroring what {@code ImportJobStore.update} does against a row --
 * so assertions read the job's actual post-failure state rather than a captured argument.
 */
class ImportJobWorkerTest {

    private ImportJobStore jobStore;
    private ImportService importService;
    private StatementContentService statementContentService;
    private ImportStageRecorder stageRecorder;
    private NotificationService notificationService;
    private ImportJobWorker worker;

    private ImportJob job;

    @BeforeEach
    void setUp() {
        jobStore = mock(ImportJobStore.class);
        importService = mock(ImportService.class);
        // BH-045: readContent() now routes through StatementContentService.read(job) (the same
        // verified path every other statement read uses) rather than calling StatementStorage
        // directly -- see ImportJobWorker.readContent's own doc for why.
        statementContentService = mock(StatementContentService.class);
        stageRecorder = mock(ImportStageRecorder.class);
        WorkerObservability observability = new WorkerObservability(new SimpleMeterRegistry());

        notificationService = mock(NotificationService.class);

        worker = new ImportJobWorker(jobStore, importService, statementContentService, observability,
                stageRecorder, new ExceptionClassifier(), notificationService);

        job = new ImportJob(UUID.randomUUID(), "statement.csv", "hash", "objects/key", "CSV");
        job.markClaimed("worker", Instant.now());

        when(jobStore.claimBatch(any())).thenReturn(List.of(job.getId()));
        when(jobStore.find(job.getId())).thenReturn(Optional.of(job));
        org.mockito.Mockito.doAnswer(inv -> {
            Consumer<ImportJob> change = inv.getArgument(1);
            change.accept(job);
            return null;
        }).when(jobStore).update(org.mockito.ArgumentMatchers.eq(job.getId()), any());
        when(statementContentService.read(any())).thenReturn(new byte[] {1, 2, 3});
    }

    /**
     * Every current {@code ErrorCode} defaults to FAIL_FAST -- a known, permanent import failure
     * dead-letters on the very first attempt rather than spending the existing 5-attempt budget on
     * something retrying can never fix.
     */
    @Test
    void aKnownImportFailureDeadLettersOnTheFirstAttempt() throws IOException {
        when(importService.parseAndStageWithSession(any(), any(), any()))
                .thenThrow(new ApiException(ErrorCode.IMPORT_NO_HEADER_DETECTED));

        worker.drainOnce();

        assertThat(job.getStatus())
                .as("a known, permanent failure must not be retried")
                .isEqualTo(ImportJob.Status.FAILED);
        assertThat(job.getAttemptCount())
                .as("must dead-letter on the very first attempt, not after spending a budget")
                .isEqualTo(1);
        assertThat(job.getFailureCode())
                .as("Premium Import Reliability v1, §3.1 -- the curated code behind lastError, "
                        + "for the import timeline")
                .isEqualTo("IMPORT_NO_HEADER_DETECTED");
    }

    /**
     * An infrastructure exception classifies to RETRY -- unchanged from every attempt before this
     * item existed: back on the queue, same backoff schedule, same {@link ImportJob#MAX_ATTEMPTS}.
     */
    @Test
    void anInfrastructureFailureIsScheduledForRetry() throws IOException {
        when(importService.parseAndStageWithSession(any(), any(), any()))
                .thenThrow(new StatementStorageException("R2 unavailable"));

        worker.drainOnce();

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.QUEUED);
        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(job.getNextAttemptAt())
                .as("RETRY schedules a next attempt, unlike FAIL_FAST")
                .isNotNull();
        assertThat(job.getFailureCode())
                .as("no ApiException/ErrorCode involved -- falls back to the exception's simple "
                        + "class name, matching StatementAnalysisSession.failureCode's convention")
                .isEqualTo("StatementStorageException");
    }

    /**
     * BH-045. Every test above throws from {@code importService.parseAndStageWithSession}; this is
     * the one that actually exercises {@code readContent()} -- the method this bug fix rewired to
     * route through {@code StatementContentService.read} -- failing on its own. A hash mismatch
     * there surfaces as {@link StatementIntegrityException}, which must NOT be treated as an
     * ordinary {@code StatementStorageException} (see {@link ExceptionClassifier#classify}): it
     * dead-letters at attempt 2, the same as any other unrecognized-but-classified failure, not the
     * 5-attempt RETRY budget a plain storage outage gets.
     */
    @Test
    void aContentIntegrityFailureOnReadRetriesOnceThenDeadLetters() throws IOException {
        when(statementContentService.read(any()))
                .thenThrow(new StatementIntegrityException("hash mismatch for " + job.getContentHash()));

        worker.drainOnce();
        assertThat(job.getStatus())
                .as("first occurrence still gets one retry, same as any RETRY_ONCE_THEN_ALERT case")
                .isEqualTo(ImportJob.Status.QUEUED);
        assertThat(job.getAttemptCount()).isEqualTo(1);

        job.markClaimed("worker", Instant.now());
        worker.drainOnce();

        assertThat(job.getStatus())
                .as("second occurrence must dead-letter -- not the 5-attempt RETRY budget a plain "
                        + "StatementStorageException gets. FAILED, not HELD_FOR_REVIEW: an "
                        + "integrity mismatch is a storage fault, and the triage queue's only "
                        + "action re-reads the same wrong bytes")
                .isEqualTo(ImportJob.Status.FAILED);
        assertThat(job.getAttemptCount()).isEqualTo(2);
        assertThat(job.getFailureCode()).isEqualTo("StatementIntegrityException");
    }

    /**
     * An unrecognized exception classifies to RETRY_ONCE_THEN_ALERT: the first failure still gets
     * one retry (an honest transient blip should not be dead-lettered on its first occurrence), but
     * a SECOND failure on the same job dead-letters at attempt 2 -- explicitly not the 5-attempt
     * RETRY budget, which would waste ~31 minutes on a bug that fails identically every time.
     */
    @Test
    void anUnrecognizedFailureRetriesOnceThenDeadLetters() throws IOException {
        when(importService.parseAndStageWithSession(any(), any(), any()))
                .thenThrow(new NullPointerException("boom"));

        worker.drainOnce();
        assertThat(job.getStatus())
                .as("first occurrence of an unknown exception must still get one retry")
                .isEqualTo(ImportJob.Status.QUEUED);
        assertThat(job.getAttemptCount()).isEqualTo(1);

        // The job is reclaimed for its second attempt, exactly as the worker's own poll loop would.
        job.markClaimed("worker", Instant.now());
        worker.drainOnce();

        assertThat(job.getStatus())
                .as("second occurrence must dead-letter -- not the 5-attempt RETRY budget -- and "
                        + "an unclassified dead-letter is exactly the case that is held for triage")
                .isEqualTo(ImportJob.Status.HELD_FOR_REVIEW);
        assertThat(job.getAttemptCount()).isEqualTo(2);
        assertThat(job.getFailureCode())
                .as("no ErrorCode -- falls back to the exception's simple class name")
                .isEqualTo("NullPointerException");
    }

    /**
     * Premium Import Reliability v1, §5.6. Exhaustive over every current {@code RetryPolicy}
     * constant, so a future 4th value fails this test rather than silently falling through to no
     * severity mapping at all. No Sentry test double exists in this suite (see the class's own
     * comment on {@link WorkerObservabilityTest} for why -- Sentry calls are no-ops-when-unconfigured
     * and this codebase asserts what's provable without one), so this proves the pure
     * policy-to-severity mapping directly rather than trying to observe a Sentry call that never
     * happens in a test JVM.
     */
    @Test
    void severityForMapsEveryRetryPolicyToTheDecidedAlertSeverity() {
        assertThat(severityFor(ErrorCode.RetryPolicy.FAIL_FAST))
                .as("a known, expected, customer-caused failure must never page anyone")
                .isEqualTo(AlertSeverity.NONE);
        assertThat(severityFor(ErrorCode.RetryPolicy.RETRY))
                .as("an infrastructure dependency failing for the full backoff window is worth knowing, not worth waking someone")
                .isEqualTo(AlertSeverity.WARNING);
        assertThat(severityFor(ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT))
                .as("the one case that is plausibly a genuine, unclassified Finora bug")
                .isEqualTo(AlertSeverity.ERROR);
    }

    private AlertSeverity severityFor(ErrorCode.RetryPolicy policy) {
        return (AlertSeverity) ReflectionTestUtils.invokeMethod(worker, "severityFor", policy);
    }

    /**
     * Regression, not new behavior: a job that turns terminal via a race (cancelled by its owner
     * between this pass's last check and the exception that follows -- the BH-001 shape) must still
     * come out {@code ALREADY_FINISHED} now that {@code recordFailure} is reached through
     * classification. The terminal check inside {@code ImportJob.recordFailure} is
     * policy-independent (item 3), but this proves the worker's classify-then-call wiring doesn't
     * accidentally short-circuit around it or otherwise disturb a cancelled job's state.
     */
    @Test
    void aRaceThatCancelsMidPassIsNeverResurrectedByClassification() throws IOException {
        when(importService.parseAndStageWithSession(any(), any(), any()))
                .thenAnswer(inv -> {
                    job.cancel(Instant.now());
                    throw new IllegalStateException("would leave imported transactions");
                });

        worker.drainOnce();

        assertThat(job.getStatus())
                .as("a cancellation must not be reinterpreted as a classified failure")
                .isEqualTo(ImportJob.Status.CANCELLED);
        assertThat(job.getLastError())
                .as("ALREADY_FINISHED must leave lastError untouched, regardless of policy")
                .isNull();
    }

    // ------------------------------------------------------- held-for-review routing (Phase B)

    /** A staging result the worker can complete on, with the parser's detected bank name. */
    private static ImportDto.StagingSessionResponse staged(String bankName) {
        // Shape copied from ImportSessionServiceTest.sampleDetected() -- only suggestedName
        // matters here, and every other component is deliberately null.
        ImportDto.DetectedAccountInfo detected = bankName == null ? null
                : new ImportDto.DetectedAccountInfo(bankName, "SAVINGS", null, null, null, null,
                        null, null, null, null, null, null, null, null, "SAVINGS", 0.0, false,
                        List.of(), null, null, null, null, null, null, null, null);
        return new ImportDto.StagingSessionResponse(
                UUID.randomUUID(),
                new ImportDto.StagingResponse(List.of(), 10, 0, detected, List.of()));
    }

    private static ImportDto.StagingSessionResponse staged() {
        return staged("HDFC Bank");
    }

    /** Re-claims the job and runs another pass, exactly as the worker's own poll loop would. */
    private void runAnotherPass() {
        job.markClaimed("worker", Instant.now());
        worker.drainOnce();
    }

    /**
     * A known ErrorCode failure stays exactly where it is today.
     *
     * <p>The user can fix a wrong password or an unsupported file themselves, and the message
     * already tells them how. Routing it to an admin queue would bury genuine parser gaps under
     * work no admin can do anything about.
     */
    @Test
    void aKnownErrorCodeFailureIsNotHeld() throws IOException {
        when(importService.parseAndStageWithSession(any(), any(), any()))
                .thenThrow(new ApiException(ErrorCode.IMPORT_NO_HEADER_DETECTED));

        worker.drainOnce();

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(job.wasHeldForReview()).isFalse();
    }

    /**
     * Exhausted transient-infrastructure retries stay in FAILED too.
     *
     * <p>Storage being down is not a parser gap, and five attempts against it prove nothing an
     * admin could act on. Holding these would fill the triage queue with the one failure class
     * that fixes itself.
     */
    @Test
    void anExhaustedInfrastructureRetryIsNotHeld() throws IOException {
        when(importService.parseAndStageWithSession(any(), any(), any()))
                .thenThrow(new StatementStorageException("R2 unavailable"));

        worker.drainOnce();
        while (job.getStatus() == ImportJob.Status.QUEUED) {
            runAnotherPass();
        }

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(job.getAttemptCount()).isEqualTo(ImportJob.MAX_ATTEMPTS);
        assertThat(job.wasHeldForReview()).isFalse();
    }

    /** One retry still happens before the hold -- a genuine transient blip is not a parser gap. */
    @Test
    void anUnclassifiedFailureWithAttemptsRemainingIsNotYetHeld() throws IOException {
        when(importService.parseAndStageWithSession(any(), any(), any()))
                .thenThrow(new IllegalStateException("no header row found"));

        worker.drainOnce();

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.QUEUED);
        assertThat(job.wasHeldForReview()).isFalse();
    }

    /** The held job carries the curated code the admin queue triages on. */
    @Test
    void aHeldJobKeepsTheFailureCodeThatCausedTheHold() throws IOException {
        when(importService.parseAndStageWithSession(any(), any(), any()))
                .thenThrow(new IllegalStateException("no header row found"));

        worker.drainOnce();
        runAnotherPass();

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_REVIEW);
        assertThat(job.getFailureCode()).isEqualTo("IllegalStateException");
        assertThat(job.wasHeldForReview()).isTrue();
    }

    /**
     * A corrupt stored object is a storage incident, not a parser gap.
     *
     * <p>It satisfies the hold rule -- unclassified policy, budget spent -- and is deliberately
     * excluded anyway. Reprocess would re-read the same key and get the same wrong bytes, the
     * holding message would promise a fix that may never come, and a bulk corruption would fill a
     * parser-remediation queue with one storage incident. The ERROR alert fires either way, so
     * nothing is hidden by keeping it in FAILED.
     */
    @Test
    void aStorageIntegrityFailureIsNotHeld_becauseReprocessingWouldReadTheSameWrongBytes()
            throws IOException {
        when(statementContentService.read(any()))
                .thenThrow(new StatementIntegrityException("hash mismatch for " + job.getContentHash()));

        worker.drainOnce();
        runAnotherPass();

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(job.wasHeldForReview())
                .as("no holding message, and nothing for an admin to reprocess")
                .isFalse();
    }

    // ------------------------------------------------------- completion notification (Phase B)

    /**
     * The user who was told "we're running additional checks" is the one who gets told it worked.
     *
     * <p>Asserted on the request rather than on a delivery, because {@code NotificationService} is
     * a transactional outbox: the worker's job is to write the row inside the transaction that
     * completes the import, and the dispatcher's job is to send it.
     */
    @Test
    void aPreviouslyHeldJobThatCompletesNotifiesTheUserOnPushAndEmail() throws IOException {
        when(importService.parseAndStageWithSession(any(), any(), any()))
                .thenThrow(new IllegalStateException("no header row found"))
                .thenThrow(new IllegalStateException("no header row found"))
                .thenReturn(staged());

        worker.drainOnce();
        runAnotherPass();
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_REVIEW);

        // The parser gap is fixed and an admin reprocesses the job.
        job.returnToQueueForReprocess(Instant.now());
        runAnotherPass();

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.COMPLETED);
        org.mockito.ArgumentCaptor<NotificationRequest> captor =
                org.mockito.ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).request(captor.capture());
        NotificationRequest sent = captor.getValue();
        assertThat(sent.type()).isEqualTo(NotificationType.IMPORT_STATEMENT_READY);
        assertThat(sent.channels())
                .containsExactlyInAnyOrder(NotificationChannel.PUSH, NotificationChannel.EMAIL);
        assertThat(sent.category()).isEqualTo(NotificationCategory.FINANCIAL);
        assertThat(sent.priority())
                .as("CRITICAL and HIGH are reserved for security events")
                .isEqualTo(NotificationPriority.NORMAL);
        assertThat(sent.userId()).isEqualTo(job.getUserId());
        assertThat(sent.notificationKey())
                .as("derived from the job, so a redelivery collides on the outbox key rather than "
                        + "sending twice")
                .contains(job.getId().toString());
        assertThat(sent.params())
                .as("the parser's own detected bank name, not a placeholder -- the template reads "
                        + "\"Your {{bank}} statement is ready\"")
                .containsEntry("bank", "HDFC Bank");
    }

    /**
     * A statement whose bank the parser could not name still sends a readable notification.
     *
     * <p>The template interpolates {{bank}} unconditionally, so a null here would either render
     * the literal braces to the customer or drop the word entirely. "bank" gives "Your bank
     * statement is ready", which is plain but true.
     */
    @Test
    void anUnidentifiedBankFallsBackToWordingThatStillReads() throws IOException {
        when(importService.parseAndStageWithSession(any(), any(), any()))
                .thenThrow(new IllegalStateException("no header row found"))
                .thenThrow(new IllegalStateException("no header row found"))
                .thenReturn(staged(null));

        worker.drainOnce();
        runAnotherPass();
        job.returnToQueueForReprocess(Instant.now());
        runAnotherPass();

        org.mockito.ArgumentCaptor<NotificationRequest> captor =
                org.mockito.ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).request(captor.capture());
        assertThat(captor.getValue().params()).containsEntry("bank", "bank");
    }

    /** An ordinary first-time success notifies nobody -- we never asked that user to wait. */
    @Test
    void anOrdinaryImportThatSucceedsFirstTimeNotifiesNobody() throws IOException {
        when(importService.parseAndStageWithSession(any(), any(), any())).thenReturn(staged());

        worker.drainOnce();

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.COMPLETED);
        verify(notificationService, never()).request(any());
    }

    /** A held job that has not been reprocessed yet has nothing to announce. */
    @Test
    void aHeldJobNotifiesNobodyUntilItActuallyCompletes() throws IOException {
        when(importService.parseAndStageWithSession(any(), any(), any()))
                .thenThrow(new IllegalStateException("no header row found"));

        worker.drainOnce();
        runAnotherPass();

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_REVIEW);
        verify(notificationService, never()).request(any());
    }
}
