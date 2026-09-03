package com.finora.entity;

import com.finora.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The job lifecycle's rules, which are pure and need no database.
 *
 * <p>Separated from {@code ImportJobStoreIT} deliberately: these assert the state machine, which is
 * decided entirely in memory, while that asserts queue semantics that only PostgreSQL can prove
 * ({@code SKIP LOCKED}, partial indexes, claim visibility). Running these against Testcontainers
 * would cost a container start to test arithmetic.
 */
class ImportJobTest {

    private ImportJob job() {
        return new ImportJob(UUID.randomUUID(), "statement.csv", "hash", "objects/key", "CSV");
    }

    @Test
    void stagesOnlyAdvance() {
        // A progress bar that goes backwards is a bug report. More importantly, a stage that can
        // move either way is not a lifecycle and cannot be reasoned about.
        ImportJob job = job();
        job.markClaimed("worker", Instant.now());
        job.advanceTo(ImportJob.Status.DEDUPING);

        assertThatThrownBy(() -> job.advanceTo(ImportJob.Status.ANALYZING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stages only advance");
    }

    @Test
    void terminalStatesAreNotReachableByAdvancing() {
        // complete/fail/cancel each record more than a status -- a finish timestamp, an error, a
        // session id. Reaching them through advanceTo would set the status and none of the rest.
        assertThatThrownBy(() -> job().advanceTo(ImportJob.Status.COMPLETED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job().advanceTo(ImportJob.Status.FAILED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancellationStopsAtThePointOfNoReturn() {
        // Before IMPORTING a failure discards staged work safely. After it, user-visible financial
        // rows exist, and a CANCELLED job sitting over real transactions explains nothing.
        ImportJob job = job();
        job.markClaimed("worker", Instant.now());
        assertThat(job.isCancellable()).isTrue();

        job.advanceTo(ImportJob.Status.IMPORTING);

        assertThat(job.isCancellable()).isFalse();
        assertThatThrownBy(() -> job.cancel(Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("would leave imported transactions");
    }

    /**
     * The race the worker's stage-boundary checks cannot close on their own.
     *
     * <p>A cancel arriving between the worker's last check and its call to {@code complete} would
     * otherwise flip a stopped job to COMPLETED and hand the user the staged session they had just
     * asked not to have. Held here rather than in the worker because the next caller of
     * {@code complete} would have to remember the same rule, and the one after that.
     */
    @Test
    void completingCannotOverwriteACancellation() {
        ImportJob job = job();
        job.markClaimed("worker", Instant.now());
        job.cancel(Instant.now());

        assertThatThrownBy(() -> job.complete(UUID.randomUUID(), Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("asked to stop");

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.CANCELLED);
        assertThat(job.getImportSessionId())
                .as("a refused completion must not leave the session id behind either")
                .isNull();
    }

    @Test
    void backoffGrowsAndThenStops() {
        // Exponential so a job whose dependency is down stops hammering it; capped so a job that
        // would succeed on the next attempt does not wait an hour to prove it.
        assertThat(ImportJob.backoffFor(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(ImportJob.backoffFor(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(ImportJob.backoffFor(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(ImportJob.backoffFor(5)).isEqualTo(Duration.ofMinutes(16));
        assertThat(ImportJob.backoffFor(50))
                .as("capped -- otherwise a long-failing job parks for days")
                .isEqualTo(Duration.ofMinutes(16));
    }

    @Test
    void aFailureDeadLettersOnlyOnceTheBudgetIsSpent() {
        ImportJob job = job();
        for (int attempt = 1; attempt < ImportJob.MAX_ATTEMPTS; attempt++) {
            job.markClaimed("worker", Instant.now());
            assertThat(job.recordFailure("boom", Instant.now()))
                    .as("attempt %d must not be terminal", attempt)
                    .isEqualTo(ImportJob.FailureOutcome.RETRY_SCHEDULED);
            assertThat(job.getStatus()).isEqualTo(ImportJob.Status.QUEUED);
        }

        job.markClaimed("worker", Instant.now());
        assertThat(job.recordFailure("boom", Instant.now()))
                .isEqualTo(ImportJob.FailureOutcome.DEAD_LETTERED);
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.FAILED);
    }

    /**
     * BH-001. The bug this closes, stated as the sequence that produced it.
     *
     * <p>{@code complete()} already refused to overwrite a cancellation, and that refusal was
     * undone one line later: the worker catches {@code ImportJobCancelledException} specifically,
     * this throws {@code IllegalStateException}, so the general handler ran {@code recordFailure},
     * which wrote {@code status = QUEUED} unconditionally. The job was re-claimed, {@code
     * abortIfCancelled} now saw QUEUED rather than CANCELLED, and it ran to the end -- handing the
     * user the staged session they had pressed Stop on.
     *
     * <p>Asserting the refusal alone (which {@link #completingCannotOverwriteACancellation} does,
     * and did throughout) could not catch that. The assertion has to continue past the throw into
     * what the caller does with it.
     */
    @Test
    void aFailureCannotResurrectACancelledJob() {
        ImportJob job = job();
        job.markClaimed("worker", Instant.now());
        job.advanceTo(ImportJob.Status.ANALYZING);
        job.cancel(Instant.now());

        // The worker's pass, continuing exactly as ImportJobWorker.runOne does.
        IllegalStateException refused = catchThrowableOfType(IllegalStateException.class,
                () -> job.complete(UUID.randomUUID(), Instant.now()));
        assertThat(refused).isNotNull();

        ImportJob.FailureOutcome outcome = job.recordFailure("IllegalStateException: " + refused.getMessage(),
                Instant.now());

        assertThat(outcome).isEqualTo(ImportJob.FailureOutcome.ALREADY_FINISHED);
        assertThat(job.getStatus())
                .as("a cancelled job must not return to the queue -- it would be re-claimed and staged")
                .isEqualTo(ImportJob.Status.CANCELLED);
        assertThat(job.getLastError())
                .as("the owner stopped it; the exception the worker hit on its way out is not its story")
                .isNull();
    }

    /** The same rule for the other terminal states, so this closes a class rather than a case. */
    @Test
    void aFailureCannotResurrectAFinishedJob() {
        ImportJob completed = job();
        completed.markClaimed("worker", Instant.now());
        completed.complete(UUID.randomUUID(), Instant.now());
        assertThat(completed.recordFailure("late bookkeeping blew up", Instant.now()))
                .isEqualTo(ImportJob.FailureOutcome.ALREADY_FINISHED);
        assertThat(completed.getStatus()).isEqualTo(ImportJob.Status.COMPLETED);

        ImportJob failed = job();
        for (int i = 0; i <= ImportJob.MAX_ATTEMPTS; i++) {
            failed.markClaimed("worker", Instant.now());
            failed.recordFailure("boom", Instant.now());
        }
        assertThat(failed.getStatus()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(failed.recordFailure("boom again", Instant.now()))
                .as("a dead-lettered job must stay dead-lettered")
                .isEqualTo(ImportJob.FailureOutcome.ALREADY_FINISHED);
    }

    /**
     * Premium Import Reliability v1, §5.4. FAIL_FAST is a known, permanent failure -- retrying
     * cannot help, so this dead-letters on the very first call regardless of how little of the
     * attempt budget has been spent.
     */
    @Test
    void aFailFastFailureDeadLettersImmediately() {
        ImportJob job = job();
        job.markClaimed("worker", Instant.now());
        assertThat(job.getAttemptCount())
                .as("must be nowhere near MAX_ATTEMPTS -- FAIL_FAST does not care")
                .isLessThan(ImportJob.MAX_ATTEMPTS);

        ImportJob.FailureOutcome outcome =
                job.recordFailure("locked PDF", ErrorCode.RetryPolicy.FAIL_FAST, Instant.now());

        assertThat(outcome).isEqualTo(ImportJob.FailureOutcome.DEAD_LETTERED);
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(job.getFinishedAt()).isNotNull();
    }

    /**
     * The RETRY policy must reproduce the pre-existing 2-arg overload's schedule exactly --
     * {@link #aFailureDeadLettersOnlyOnceTheBudgetIsSpent} proves the 2-arg form itself; this
     * proves the 3-arg form gives the identical answer when passed RETRY explicitly, which is the
     * whole point of the 2-arg form now delegating to it.
     */
    @Test
    void aRetryPolicyFailureMatchesTheExistingBudget() {
        ImportJob job = job();
        for (int attempt = 1; attempt < ImportJob.MAX_ATTEMPTS; attempt++) {
            job.markClaimed("worker", Instant.now());
            assertThat(job.recordFailure("boom", ErrorCode.RetryPolicy.RETRY, Instant.now()))
                    .as("attempt %d must not be terminal", attempt)
                    .isEqualTo(ImportJob.FailureOutcome.RETRY_SCHEDULED);
            assertThat(job.getStatus()).isEqualTo(ImportJob.Status.QUEUED);
        }

        job.markClaimed("worker", Instant.now());
        assertThat(job.recordFailure("boom", ErrorCode.RetryPolicy.RETRY, Instant.now()))
                .isEqualTo(ImportJob.FailureOutcome.DEAD_LETTERED);
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.FAILED);
    }

    /**
     * Premium Import Reliability v1, §5.4. An unrecognized exception gets exactly one retry, not
     * the full five -- spending all five is wasted on a genuine bug that will fail identically
     * every time, but zero risks losing a real transient crash on its first occurrence.
     */
    @Test
    void aRetryOnceThenAlertFailureDeadLettersOnTheSecondAttempt() {
        ImportJob job = job();

        job.markClaimed("worker", Instant.now());
        assertThat(job.recordFailure("mystery", ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT, Instant.now()))
                .as("first failure must still get one retry")
                .isEqualTo(ImportJob.FailureOutcome.RETRY_SCHEDULED);
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.QUEUED);

        job.markClaimed("worker", Instant.now());
        assertThat(job.recordFailure("mystery again", ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT, Instant.now()))
                .as("second failure must dead-letter -- explicitly not MAX_ATTEMPTS (5)")
                .isEqualTo(ImportJob.FailureOutcome.DEAD_LETTERED);
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.FAILED);
    }

    /**
     * Premium Import Reliability v1, §3.1. The 3-arg overload must keep leaving {@code
     * failureCode} untouched (delegates with {@code null}), and the 4-arg form must actually store
     * it -- both proven directly, not inferred from the delegation alone.
     */
    @Test
    void the3ArgOverloadLeavesFailureCodeNull() {
        ImportJob job = job();
        job.markClaimed("worker", Instant.now());

        job.recordFailure("locked PDF", ErrorCode.RetryPolicy.FAIL_FAST, Instant.now());

        assertThat(job.getFailureCode()).isNull();
    }

    @Test
    void the4ArgOverloadStoresTheFailureCode() {
        ImportJob job = job();
        job.markClaimed("worker", Instant.now());

        job.recordFailure("locked PDF", "IMPORT_PDF_PASSWORD_REQUIRED",
                ErrorCode.RetryPolicy.FAIL_FAST, Instant.now());

        assertThat(job.getFailureCode()).isEqualTo("IMPORT_PDF_PASSWORD_REQUIRED");
    }

    /** Overwritten on every failure, same as {@code lastError} -- this column describes the most
     *  recent attempt's reason, not a history. */
    @Test
    void aLaterFailureOverwritesAnEarlierFailureCode() {
        ImportJob job = job();
        job.markClaimed("worker", Instant.now());
        job.recordFailure("transient", "IMPORT_006", ErrorCode.RetryPolicy.RETRY, Instant.now());
        assertThat(job.getFailureCode()).isEqualTo("IMPORT_006");

        job.markClaimed("worker", Instant.now());
        job.recordFailure("permanent", "IMPORT_CORRUPT_PDF", ErrorCode.RetryPolicy.FAIL_FAST, Instant.now());

        assertThat(job.getFailureCode()).isEqualTo("IMPORT_CORRUPT_PDF");
    }

    /** Policy-independent, same as {@code lastError} -- a terminal job's failureCode must not be
     *  overwritten by an exception the worker hit on its way out after the job already finished. */
    @Test
    void aTerminalJobsFailureCodeIsUntouchedByALaterFailure() {
        ImportJob cancelled = job();
        cancelled.markClaimed("worker", Instant.now());
        cancelled.cancel(Instant.now());

        cancelled.recordFailure("boom", "IMPORT_001", ErrorCode.RetryPolicy.FAIL_FAST, Instant.now());

        assertThat(cancelled.getFailureCode()).isNull();
    }

    /** The terminal-state refusal is policy-independent -- checked before any policy is consulted. */
    @Test
    void aTerminalJobRefusesEveryPolicy() {
        ImportJob cancelled = job();
        cancelled.markClaimed("worker", Instant.now());
        cancelled.cancel(Instant.now());

        for (ErrorCode.RetryPolicy policy : ErrorCode.RetryPolicy.values()) {
            assertThat(cancelled.recordFailure("boom", policy, Instant.now()))
                    .as("%s must not resurrect a cancelled job", policy)
                    .isEqualTo(ImportJob.FailureOutcome.ALREADY_FINISHED);
            assertThat(cancelled.getStatus()).isEqualTo(ImportJob.Status.CANCELLED);
        }
    }

    @Test
    void recoveryReturnsAJobWithoutSpendingAnAttempt() {
        // A worker that died proved nothing about the job. Charging it would dead-letter perfectly
        // good work after five unlucky deploys.
        ImportJob job = job();
        job.markClaimed("worker", Instant.now());
        int afterClaim = job.getAttemptCount();

        assertThat(job.returnToQueue("abandoned", Instant.now())).isFalse();

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.QUEUED);
        assertThat(job.getAttemptCount()).isLessThan(afterClaim);
        assertThat(job.getStartedAt()).as("no longer in flight").isNull();
    }

    /**
     * BH-002. Forgiveness has to terminate.
     *
     * <p>{@code markClaimed} increments the attempt count and {@code returnToQueue} decrements it,
     * so a job whose parse reliably kills its worker cycled claim -> crash -> recover -> claim for
     * ever at a net attempt count of zero: never dead-lettered, never in the admin queue, holding
     * one of ten claim slots on every pass. Recoveries are counted separately now so the two
     * counters cannot cancel, and {@code MAX_RECOVERIES} bounds the loop.
     */
    @Test
    void aJobThatKeepsKillingItsWorkerEventuallyDeadLetters() {
        ImportJob job = job();

        for (int recovery = 1; recovery <= ImportJob.MAX_RECOVERIES; recovery++) {
            job.markClaimed("worker", Instant.now());
            assertThat(job.returnToQueue("worker died", Instant.now()))
                    .as("recovery %d is still within budget", recovery)
                    .isFalse();
            assertThat(job.getStatus()).isEqualTo(ImportJob.Status.QUEUED);
        }

        job.markClaimed("worker", Instant.now());
        assertThat(job.returnToQueue("worker died again", Instant.now()))
                .as("past the recovery budget, the job is the common factor")
                .isTrue();
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(job.getFinishedAt()).isNotNull();
        assertThat(job.getRecoveryCount()).isGreaterThan(ImportJob.MAX_RECOVERIES);
    }

    /**
     * BH-002's shape, extended by a code review's own finding on Premium Import Reliability v1,
     * §3.1: {@code returnToQueue} is a DIFFERENT event from the curated failure {@code
     * recordFailure} already recorded on an earlier attempt, and must not let that earlier
     * failure's {@code failureCode} survive into it. Left uncleared, a job whose first attempt hit
     * a real, curated failure (a corrupt PDF, say) and then recovered from worker crashes until it
     * exhausted its recovery budget would reach the customer import timeline still describing the
     * corrupt PDF -- the actual reason for the FINAL failed state (repeated worker crashes) having
     * nothing to do with the stale code left behind.
     */
    @Test
    void recoveryExhaustionClearsAnEarlierAttemptsStaleFailureCode() {
        ImportJob job = job();
        job.markClaimed("worker", Instant.now());
        job.recordFailure("locked PDF", "IMPORT_PDF_PASSWORD_REQUIRED",
                ErrorCode.RetryPolicy.RETRY, Instant.now());
        assertThat(job.getFailureCode())
                .as("the real, curated failure from attempt 1")
                .isEqualTo("IMPORT_PDF_PASSWORD_REQUIRED");

        for (int recovery = 1; recovery <= ImportJob.MAX_RECOVERIES; recovery++) {
            job.markClaimed("worker", Instant.now());
            job.returnToQueue("worker died", Instant.now());
        }
        job.markClaimed("worker", Instant.now());
        boolean deadLettered = job.returnToQueue("worker died again", Instant.now());

        assertThat(deadLettered).isTrue();
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(job.getFailureCode())
                .as("the FINAL failure was recovery exhaustion, not the PDF password -- a stale "
                        + "code here would tell the customer the wrong story")
                .isNull();
    }

    @Test
    void completingClearsTheLastError() {
        // Otherwise a job that failed once and then succeeded shows an error forever, and the admin
        // queue reads as though something is still wrong.
        ImportJob job = job();
        job.markClaimed("worker", Instant.now());
        job.recordFailure("transient", "IMPORT_006", ErrorCode.RetryPolicy.RETRY, Instant.now());
        job.markClaimed("worker", Instant.now());

        job.complete(UUID.randomUUID(), Instant.now());

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.COMPLETED);
        assertThat(job.getLastError()).isNull();
        assertThat(job.getFailureCode())
                .as("same rule as lastError -- a completed job has nothing left to explain")
                .isNull();
        assertThat(job.getFinishedAt()).isNotNull();
    }

    @Test
    void progressTotalSurvivesAnUpdateThatDoesNotKnowIt() {
        // rows_total is set once PARSING has counted; later updates report only rows processed and
        // must not blank it, or the UI would flip from "42 of 500" back to "42 of unknown".
        ImportJob job = job();
        job.recordProgress(500, 0);
        job.recordProgress(null, 42);

        assertThat(job.getRowsTotal()).isEqualTo(500);
        assertThat(job.getRowsProcessed()).isEqualTo(42);
    }

    @Test
    void inFlightAndTerminalSetsDoNotOverlap() {
        // The two sets drive the claim and recovery queries. A status in both would be claimable
        // and finished at the same time.
        for (ImportJob.Status status : ImportJob.Status.values()) {
            assertThat(status.isInFlight() && status.isTerminal())
                    .as("%s cannot be both in flight and terminal", status)
                    .isFalse();
        }
        assertThat(ImportJob.Status.QUEUED.isInFlight()).isFalse();
        assertThat(ImportJob.Status.QUEUED.isTerminal()).isFalse();
    }

    /**
     * Guards the exemption recorded in {@link ImportJob#getCompressionType()} and
     * docs/architecture/data/statement-storage-migration.md §0.2: {@code ImportJobService.accept()}
     * writes to {@code StatementStorage} directly, never through {@code StatementContentService} --
     * the only place that compresses -- so a job's object is never gzipped, in every state a job can
     * reach, not just the freshly-enqueued one. {@code StatementContentService.read} decompresses (or
     * not) strictly by this column, so a value that drifted from reality on even one status would
     * make that read path try to gunzip bytes that were never compressed.
     */
    @Test
    void compressionTypeIsAlwaysNone_regardlessOfJobState() {
        ImportJob job = job();
        assertThat(job.getCompressionType()).isEqualTo(com.finora.imports.storage.CompressionType.NONE);

        job.markClaimed("worker", Instant.now());
        job.advanceTo(ImportJob.Status.IMPORTING);
        job.complete(UUID.randomUUID(), Instant.now());

        assertThat(job.getCompressionType())
                .as("terminal state must not change what the async path's read-back decodes by")
                .isEqualTo(com.finora.imports.storage.CompressionType.NONE);
    }

    // ------------------------------------------------------------------ held for review

    /** Drives a job to the state Task 2's routing puts a held one in: dead-lettered, then held. */
    private ImportJob deadLetteredUnclassifiedJob() {
        ImportJob job = job();
        job.markClaimed("worker", Instant.now());
        job.markClaimed("worker", Instant.now());
        ImportJob.FailureOutcome outcome = job.recordFailure(
                "IllegalStateException: no header row found", "IllegalStateException",
                ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT, Instant.now());
        assertThat(outcome)
                .as("fixture must actually reach the dead-letter branch, or these tests prove nothing")
                .isEqualTo(ImportJob.FailureOutcome.DEAD_LETTERED);
        job.holdForReview("IllegalStateException", Instant.now());
        return job;
    }

    @Test
    void holdForReview_isTerminalForTheWorkerButDistinctFromFailed() {
        ImportJob job = deadLetteredUnclassifiedJob();

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_REVIEW);
        assertThat(job.getStatus()).isNotEqualTo(ImportJob.Status.FAILED);
        assertThat(ImportJob.Status.TERMINAL).contains(ImportJob.Status.HELD_FOR_REVIEW);
        assertThat(ImportJob.Status.IN_FLIGHT).doesNotContain(ImportJob.Status.HELD_FOR_REVIEW);
    }

    @Test
    void holdForReview_recordsTheUnrecognizedFailureCode() {
        assertThat(deadLetteredUnclassifiedJob().getFailureCode()).isEqualTo("IllegalStateException");
    }

    /**
     * The hold has to survive a later stray failure, or it is not a hold.
     *
     * <p>{@code recordFailure} declines to move a terminal job -- being in TERMINAL is exactly what
     * buys that. Without it, a double-fault on the way out of a pass would quietly downgrade a held
     * job to FAILED and the user would see the dead end this feature exists to avoid.
     */
    @Test
    void aHeldJobIsNotMovedByAFurtherFailure() {
        ImportJob job = deadLetteredUnclassifiedJob();

        ImportJob.FailureOutcome outcome = job.recordFailure(
                "something else went wrong", "RuntimeException",
                ErrorCode.RetryPolicy.FAIL_FAST, Instant.now());

        assertThat(outcome).isEqualTo(ImportJob.FailureOutcome.ALREADY_FINISHED);
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_REVIEW);
    }

    /** Only the explicit transition may enter the hold -- advanceTo refuses every terminal state. */
    @Test
    void advanceToCannotEnterTheHold() {
        assertThatThrownBy(() -> job().advanceTo(ImportJob.Status.HELD_FOR_REVIEW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnToQueueForReprocess_clearsAttemptsSoAFixedParserGetsAFullBudget() {
        ImportJob job = deadLetteredUnclassifiedJob();

        job.returnToQueueForReprocess(Instant.now());

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.QUEUED);
        assertThat(job.getAttemptCount()).isZero();
        assertThat(job.getRecoveryCount()).isZero();
        assertThat(job.getFinishedAt()).isNull();
        assertThat(job.getStartedAt()).isNull();
    }

    /**
     * A stale code from the attempt that failed against the OLD parser must not survive into the
     * new run -- the same defect {@code returnToQueue} documents at length, where it reached the
     * customer timeline describing a failure that did not happen.
     */
    @Test
    void returnToQueueForReprocess_clearsTheFailureOfThePreviousParser() {
        ImportJob job = deadLetteredUnclassifiedJob();

        job.returnToQueueForReprocess(Instant.now());

        assertThat(job.getFailureCode()).isNull();
        assertThat(job.getLastError()).isNull();
    }

    /**
     * The marker is what tells the success path this user was asked to wait. Clearing it on the
     * reprocess -- the obvious "reset everything" simplification -- would silence the notification
     * on exactly the jobs the whole feature exists to notify about.
     */
    @Test
    void wasHeldForReview_survivesTheReprocessAndTheCompletionThatFollows() {
        ImportJob job = deadLetteredUnclassifiedJob();
        assertThat(job.wasHeldForReview()).isTrue();

        job.returnToQueueForReprocess(Instant.now());
        assertThat(job.wasHeldForReview()).isTrue();

        job.markClaimed("worker", Instant.now());
        job.advanceTo(ImportJob.Status.ANALYZING);
        job.complete(UUID.randomUUID(), Instant.now());

        assertThat(job.wasHeldForReview())
                .as("the completed job still has to know it was held, or nobody is notified")
                .isTrue();
    }

    @Test
    void anOrdinaryJobWasNeverHeld() {
        ImportJob job = job();
        job.markClaimed("worker", Instant.now());
        job.complete(UUID.randomUUID(), Instant.now());

        assertThat(job.wasHeldForReview()).isFalse();
    }

    @Test
    void returnToQueueForReprocess_isRejectedForAJobThatIsNotHeld() {
        assertThatThrownBy(() -> job().returnToQueueForReprocess(Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QUEUED");
    }

    @Test
    void resolveWithoutFix_landsTheJobWhereItWouldHaveLandedAnyway() {
        ImportJob job = deadLetteredUnclassifiedJob();

        job.resolveWithoutFix(Instant.now());

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.FAILED);
    }

    @Test
    void resolveWithoutFix_isRejectedForAJobThatIsNotHeld() {
        assertThatThrownBy(() -> job().resolveWithoutFix(Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ trust telemetry

    /** Telemetry is evidence, not a decision -- it must not touch anything the worker branches on. */
    @Test
    void verificationTelemetry_doesNotChangeTheJobsOwnLifecycle() {
        ImportJob job = job();
        job.markClaimed("worker", Instant.now());

        job.recordVerificationTelemetry(
                com.finora.imports.ImportReliabilityStatus.NEEDS_ATTENTION, "OCR", true, 3, 1, 2, "sha");

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.PARSING);
        assertThat(job.wasHeldForReview()).isFalse();
        assertThat(job.getFailureCode()).isNull();
        assertThat(job.getReliabilityStatus())
                .isEqualTo(com.finora.imports.ImportReliabilityStatus.NEEDS_ATTENTION);
        assertThat(job.getParserVersion()).isEqualTo("sha");
    }

    /**
     * Null means "predates telemetry" and must stay distinguishable from a recorded clean result --
     * otherwise every historical row silently claims to have been verified.
     */
    @Test
    void verificationTelemetry_keepsUnobservedDistinctFromClean() {
        ImportJob job = job();

        job.recordVerificationTelemetry(null, null, null, null, null, null, "sha");

        assertThat(job.getReliabilityStatus()).isNull();
        assertThat(job.getVerificationFindingsCount()).isNull();
        assertThat(job.getParserVersion())
                .as("the parser version is known even when nothing was verified")
                .isEqualTo("sha");
    }
}
