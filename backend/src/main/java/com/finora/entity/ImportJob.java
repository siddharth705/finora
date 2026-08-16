package com.finora.entity;

import com.finora.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * One statement import, as durable work rather than a request in flight.
 *
 * <p>Phase 1 of {@code docs/engineering/enterprise-scale-milestone-design.md}. Import runs inline on
 * the request thread today: throughput is bounded by the web tier, a connection from a pool capped
 * at 10 is held for the whole parse, and a user uploading a large statement gets a request that
 * either takes minutes or times out with the work half done and no record it happened. A row here is
 * that record.
 *
 * <h2>Status and stage are one column</h2>
 *
 * <p>A separate status and stage can disagree -- "FAILED at stage IMPORTING" says nothing about
 * whether the import actually happened -- and every such pair eventually does. This is one state
 * machine and the enum says so.
 *
 * <h2>IMPORTING is the point of no return</h2>
 *
 * <p>Before it, a failure discards staged work and the whole job is safe to retry. After it,
 * user-visible financial data exists, so a retry must be idempotent -- which is Phase 2 of the
 * design and is why {@link #isCancellable()} stops at that line rather than being a general
 * "not finished yet" check.
 *
 * <h2>{@link com.finora.imports.storage.StoredStatement}</h2>
 *
 * <p>BH-045: implemented so {@link com.finora.imports.storage.StatementContentService#read} can
 * be the ONE path that reads a job's bytes back from storage, the same as every other statement
 * reader -- {@link #getFileContent()} always returns {@code null} because a job never carries
 * bytes at all (see {@link com.finora.imports.jobs.ImportJobWorker}'s own doc on why: "a job
 * carries an address, never the bytes"), which is exactly what {@code StoredStatement}'s "legacy"
 * fallback state means for a row with no database-held content: correctly forces every read
 * through the addressed (content-hash-verified) path, never a silent no-op.
 */
@Entity
@Table(name = "import_jobs")
public class ImportJob implements com.finora.imports.storage.StoredStatement {

    /**
     * The lifecycle, in order.
     *
     * <p>Declared in progression order so {@code ordinal()} is meaningful and
     * {@link #isBefore} can compare stages without a lookup table. Terminal states sit at the end;
     * nothing compares against them positionally.
     */
    public enum Status {
        QUEUED, PARSING, ANALYZING, DEDUPING, IMPORTING, LEARNING, COMPLETED, FAILED, CANCELLED;

        /** The states a worker holds a job in. A row sitting in one of these with no live worker is
         *  abandoned, and recovery returns it to the queue -- see the in-flight index in V66. */
        public static final Set<Status> IN_FLIGHT =
                EnumSet.of(PARSING, ANALYZING, DEDUPING, IMPORTING, LEARNING);

        public static final Set<Status> TERMINAL = EnumSet.of(COMPLETED, FAILED, CANCELLED);

        public boolean isTerminal() { return TERMINAL.contains(this); }
        public boolean isInFlight() { return IN_FLIGHT.contains(this); }
        boolean isBefore(Status other) { return ordinal() < other.ordinal(); }
    }

    /** Retries before a job dead-letters into the admin queue. Matches the learning queue's
     *  behaviour so one runbook covers both. */
    public static final int MAX_ATTEMPTS = 5;

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "content_hash")
    private String contentHash;

    @Column(name = "object_key")
    private String objectKey;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.QUEUED;

    @Column(name = "rows_total")
    private Integer rowsTotal;

    @Column(name = "rows_processed", nullable = false)
    private int rowsProcessed;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    /**
     * How many times recovery has returned this job to the queue after a worker abandoned it.
     *
     * <p>Its own column rather than a reuse of {@code attemptCount}, because the two count
     * different evidence and used to cancel each other out -- see {@link #returnToQueue}.
     */
    @Column(name = "recovery_count", nullable = false)
    private int recoveryCount;

    /**
     * Two writers touch a job concurrently by design: the worker, through
     * {@code ImportJobStore.update} in its own REQUIRES_NEW transaction, and the owner, through
     * {@code ImportJobService.cancel} in the request's transaction. Both are read-modify-write and
     * neither could see the other, so the conflict surfaced inside business logic (a
     * {@code complete()} that throws) instead of as a lock failure -- which is the mechanism behind
     * BH-001. Every other concurrently-written entity here already carries this;
     * {@code GlobalExceptionHandler.handleOptimisticLock} already answers 409 for it.
     */
    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "last_error")
    private String lastError;

    /**
     * The curated identifier behind {@link #lastError}, when there is one -- Premium Import
     * Reliability v1, §3.1 (the customer-facing import timeline). {@code lastError} is {@code
     * ImportJobWorker.describe(Exception)}, raw internal detail never fit to show a customer
     * directly; this is either an {@code ErrorCode} enum name (a known, curated import failure) or
     * an exception's simple class name, exactly mirroring how {@code
     * StatementAnalysisSession.failureCode} is written -- so a caller translating this to a
     * customer-facing wire code can reuse the identical "not a valid ErrorCode name -> null"
     * handling either table already needs.
     *
     * <p>Overwritten on every failure, same as {@code lastError} -- this column describes the most
     * recent attempt's reason, not a history (the history is {@code ImportJobStage} rows).
     */
    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "import_session_id")
    private UUID importSessionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /**
     * Which parser this job runs. BH-029.
     *
     * <p>A recorded fact, not a function of {@link #fileName}. It used to be neither: the upload
     * endpoint and the worker each called {@code ImportJobService.formatOf(fileName)} minutes
     * apart, and they agreed only because they happened to read the same string through the same
     * function. {@code statement_imports.source_format} (V36) is the same column for the same
     * reason, added after re-inferring a format from a filename routed a PDF's bytes through
     * {@code CsvParser}.
     *
     * <p>A String rather than {@code StatementUpload.Format} to match
     * {@code StatementImport.sourceFormat}'s existing shape and V36's vocabulary, so the two
     * columns that hold this answer hold it identically.
     */
    @Column(name = "source_format", nullable = false)
    private String sourceFormat;

    protected ImportJob() {}

    public ImportJob(UUID userId, String fileName, String contentHash, String objectKey, String sourceFormat) {
        this.userId = userId;
        this.fileName = fileName;
        this.contentHash = contentHash;
        this.objectKey = objectKey;
        this.sourceFormat = sourceFormat;
    }

    // ------------------------------------------------------------------ transitions

    /** A worker has taken this job. Records when, so recovery can tell abandoned from merely slow. */
    public void markClaimed(String correlationId, Instant now) {
        this.status = Status.PARSING;
        this.correlationId = correlationId;
        this.startedAt = now;
        this.attemptCount++;
    }

    /**
     * Moves to the next stage.
     *
     * <p>Rejects going backwards or jumping into a terminal state, because a stage that can move
     * either way is not a lifecycle -- and a progress bar that goes backwards is a bug report.
     */
    public void advanceTo(Status next) {
        if (next.isTerminal()) {
            throw new IllegalArgumentException(
                    "Use complete/fail/cancel for terminal states, not advanceTo(" + next + ")");
        }
        if (!this.status.isBefore(next)) {
            throw new IllegalStateException(
                    "Import job " + id + " cannot move from " + this.status + " to " + next
                            + ": stages only advance.");
        }
        this.status = next;
    }

    /** Progress for the polling endpoint. Total is set once PARSING has counted; until then null,
     *  which lets the UI say "reading your statement" rather than "0 of 0". */
    public void recordProgress(Integer rowsTotal, int rowsProcessed) {
        if (rowsTotal != null) this.rowsTotal = rowsTotal;
        this.rowsProcessed = rowsProcessed;
    }

    /**
     * Marks the work done.
     *
     * <p><b>Refuses to overwrite a cancellation.</b> The worker checks for one at each stage
     * boundary, but a cancel landing between that last check and this call would otherwise complete
     * a job the user had already stopped — handing them a staged session they asked not to have. The
     * window is small and closing it in the worker would mean closing it again in the next caller,
     * so the state machine refuses instead.
     *
     * <p><b>The refusal alone was not enough, and this comment used to claim it was.</b> It said
     * "{@code ImportJobWorker} treats this as the cancellation it is rather than as a failure",
     * which was never true: the worker catches {@code ImportJobCancelledException} specifically and
     * this throws {@code IllegalStateException}, so the general handler ran and called
     * {@link #recordFailure}, which used to put the CANCELLED job straight back on the queue. The
     * cancellation was refused here and undone one line later. {@code recordFailure} now declines
     * to move a terminal job and reports {@link FailureOutcome#ALREADY_FINISHED}, which is what
     * makes the refusal stick; the worker reports that outcome rather than a retry.
     */
    public void complete(UUID importSessionId, Instant now) {
        if (this.status == Status.CANCELLED) {
            throw new IllegalStateException(
                    "Import job " + id + " was cancelled; completing it would hand the user a "
                            + "staged import they asked to stop.");
        }
        this.status = Status.COMPLETED;
        this.importSessionId = importSessionId;
        this.finishedAt = now;
        this.lastError = null;
        this.failureCode = null;
    }

    /**
     * What {@link #recordFailure} actually did, so the caller can report it honestly.
     *
     * <p>A boolean could say "dead-lettered or not" and could not say "this job was already
     * finished and I left it alone" -- which is the outcome that matters, because reporting a
     * cancelled job as a scheduled retry is how BH-001 stayed invisible.
     */
    public enum FailureOutcome {
        /** Back on the queue, with backoff. */
        RETRY_SCHEDULED,
        /** Attempt budget spent; the job is FAILED and waiting in the admin queue. */
        DEAD_LETTERED,
        /** The job had already reached a terminal state. Nothing was changed. */
        ALREADY_FINISHED
    }

    /**
     * How many attempts {@code RETRY_ONCE_THEN_ALERT} spends before dead-lettering -- deliberately
     * much smaller than {@link #MAX_ATTEMPTS}. Premium Import Reliability v1, §5.4: the honest
     * answer to "is an exception this unrecognized transient or permanent" is "not yet known", and
     * one retry absorbs a real transient blip without spending the full 5-attempt/~31-minute
     * budget on a genuine bug that will fail identically every time. See
     * {@link com.finora.exception.ErrorCode.RetryPolicy} for the three-tier model this codifies.
     */
    public static final int RETRY_ONCE_THEN_ALERT_MAX_ATTEMPTS = 2;

    /**
     * Records a failed attempt, either scheduling a retry or dead-lettering -- {@link
     * ErrorCode.RetryPolicy#RETRY}, matching every caller before this overload existed.
     *
     * @return what happened -- see {@link FailureOutcome}
     * @see #recordFailure(String, ErrorCode.RetryPolicy, Instant)
     */
    public FailureOutcome recordFailure(String error, Instant now) {
        return recordFailure(error, null, ErrorCode.RetryPolicy.RETRY, now);
    }

    /**
     * Records a failed attempt, either scheduling a retry or dead-lettering, according to how the
     * exception that caused it was classified -- Premium Import Reliability v1, §5.4
     * ({@code ExceptionClassifier}, §5.3, is what produces the {@link ErrorCode.RetryPolicy} a
     * caller passes in; not wired to anything yet as of this method, see {@code ImportJobWorker}).
     *
     * <p>Exponential backoff from one minute, capped: a job whose dependency is down should back
     * off, but a job that will succeed on the next attempt should not wait an hour to prove it.
     * The backoff schedule itself does not change per policy -- only WHEN a policy gives up and
     * dead-letters does:
     * <ul>
     *   <li>{@link ErrorCode.RetryPolicy#FAIL_FAST} dead-letters on this very call, unconditionally,
     *       regardless of {@code attemptCount}. A known, permanent failure (a locked PDF, an
     *       unreadable layout) cannot be fixed by retrying, so there is nothing to schedule.</li>
     *   <li>{@link ErrorCode.RetryPolicy#RETRY} dead-letters at {@link #MAX_ATTEMPTS} -- unchanged
     *       from before this overload existed.</li>
     *   <li>{@link ErrorCode.RetryPolicy#RETRY_ONCE_THEN_ALERT} dead-letters at {@link
     *       #RETRY_ONCE_THEN_ALERT_MAX_ATTEMPTS} -- one retry, not five. The alert half of the name
     *       is not this method's job: nothing here calls an alert hook (§5.6, a separate, later
     *       item, gated on the standing Sentry/{@code SENTRY_DSN} pre-launch gap) -- this method
     *       only makes the STATE MACHINE stop early; a caller that wants to alert on this
     *       particular kind of dead-letter has to tell {@link FailureOutcome#DEAD_LETTERED} apart
     *       itself, which it cannot yet, since the outcome does not currently say which policy
     *       produced it. Noted as a real, open gap this commit does not close, not silently
     *       assumed to not matter.</li>
     * </ul>
     *
     * <p><b>Refuses to move a job that has already finished.</b> This used to write
     * {@code status = QUEUED} unconditionally, which made every terminal state reversible by a
     * later failure -- and one path reached it routinely. A cancel landing between the worker's
     * last {@code abortIfCancelled} and its call to {@link #complete} makes that method throw;
     * {@code ImportJobWorker} catches it in its general handler and calls this, which put the
     * CANCELLED job back on the queue. It was then re-claimed, ran to completion, and handed the
     * user the staged session they had pressed Stop on. Same shape for a COMPLETED job whose
     * post-completion bookkeeping fails. This check is policy-independent -- a terminal job stays
     * finished no matter what classification a caller passes.
     *
     * <p>The check lives here rather than in the worker for the reason {@link #complete}'s own
     * comment gives: the next caller would have to remember the same rule, and the one after that.
     *
     * @return what happened -- see {@link FailureOutcome}
     * @see #recordFailure(String, String, ErrorCode.RetryPolicy, Instant)
     */
    public FailureOutcome recordFailure(String error, ErrorCode.RetryPolicy retryPolicy, Instant now) {
        return recordFailure(error, null, retryPolicy, now);
    }

    /**
     * The same, plus the curated identifier behind {@code error} -- Premium Import Reliability v1,
     * §3.1. A {@code null} {@code failureCode} is not an error; most callers before the import
     * timeline existed have no curated identifier to offer and pass it as such.
     *
     * @return what happened -- see {@link FailureOutcome}
     */
    public FailureOutcome recordFailure(String error, String failureCode,
                                        ErrorCode.RetryPolicy retryPolicy, Instant now) {
        if (this.status.isTerminal()) {
            // Deliberately does not touch lastError/failureCode either. A CANCELLED job's story is
            // "the owner stopped it", and overwriting that with the exception the worker happened
            // to hit on its way out would make the admin queue describe a failure that did not
            // happen.
            return FailureOutcome.ALREADY_FINISHED;
        }
        this.lastError = error;
        this.failureCode = failureCode;
        if (shouldDeadLetter(retryPolicy)) {
            this.status = Status.FAILED;
            this.finishedAt = now;
            return FailureOutcome.DEAD_LETTERED;
        }
        this.status = Status.QUEUED;
        this.nextAttemptAt = now.plus(backoffFor(attemptCount));
        this.startedAt = null;
        return FailureOutcome.RETRY_SCHEDULED;
    }

    private boolean shouldDeadLetter(ErrorCode.RetryPolicy retryPolicy) {
        return switch (retryPolicy) {
            case FAIL_FAST -> true;
            case RETRY -> attemptCount >= MAX_ATTEMPTS;
            case RETRY_ONCE_THEN_ALERT -> attemptCount >= RETRY_ONCE_THEN_ALERT_MAX_ATTEMPTS;
        };
    }

    /** 1, 2, 4, 8, 16 minutes. Capped so a transient failure does not park work for an hour. */
    static Duration backoffFor(int attempts) {
        return Duration.ofMinutes(1L << Math.min(Math.max(attempts, 1) - 1, 4));
    }

    /**
     * How many times recovery may return one job to the queue before it is treated as a job that
     * kills workers rather than a job that met unlucky deploys.
     *
     * <p>Separate from {@link #MAX_ATTEMPTS} and deliberately smaller. An attempt is evidence
     * about the DOCUMENT -- the parse ran and threw. A recovery is evidence about the WORKER --
     * something killed the process, and the job may be entirely innocent. Three is enough to
     * absorb a deploy, a restart and one genuine crash; a fourth says the job is the common
     * factor.
     */
    public static final int MAX_RECOVERIES = 3;

    /**
     * Returns an abandoned job to the queue without consuming an attempt.
     *
     * <p>A worker that died mid-parse did not prove anything about the job, so charging it an
     * attempt would dead-letter perfectly good work after five unlucky deploys.
     *
     * <p><b>But not for free, and not for ever.</b> This used to decrement {@code attemptCount},
     * exactly cancelling the increment {@link #markClaimed} had just made -- so a job whose parse
     * reliably killed its worker (an OOM on a large PDF, a stack overflow in the table locator)
     * cycled claim -> crash -> recover -> claim indefinitely at a net attempt count of zero. It
     * never dead-lettered, never appeared in the admin queue, and consumed a claim slot out of ten
     * on every pass. The recovery is now counted in its own column so the two counters cannot
     * cancel, and {@link #MAX_RECOVERIES} bounds the loop.
     *
     * <p>Worth stating because the previous comment claimed otherwise: the learning queue does
     * <em>not</em> make the same distinction. {@code MerchantLearningEventWorker.recoverAbandoned}
     * calls {@code recordFailure}, which charges an attempt and moves the event toward
     * dead-lettering. The two queues genuinely differ; this one is more forgiving, and now has a
     * ceiling so that forgiveness terminates.
     *
     * @return true if this recovery exhausted the recovery budget and the job is now FAILED
     */
    public boolean returnToQueue(String reason, Instant now) {
        this.recoveryCount++;
        this.lastError = reason;
        // A crashed-worker recovery is never a curated ErrorCode -- there is no ApiException here,
        // just a process that died. Left untouched, a stale failureCode from an EARLIER, unrelated
        // attempt would survive into this event and, if recovery exhausts here (see below), reach
        // the customer timeline describing the wrong failure entirely -- e.g. "this PDF is corrupt"
        // for a job that actually died from repeated worker crashes. Cleared unconditionally, not
        // just on the terminal branch: a job that recovers and later fails again for a real curated
        // reason gets that reason fresh from recordFailure, same as lastError already does.
        this.failureCode = null;
        this.startedAt = null;
        if (this.recoveryCount > MAX_RECOVERIES) {
            this.status = Status.FAILED;
            this.finishedAt = now;
            return true;
        }
        this.status = Status.QUEUED;
        this.nextAttemptAt = now;
        if (this.attemptCount > 0) this.attemptCount--;
        return false;
    }

    /** Cancellable only before user-visible data exists -- see the class comment. */
    public boolean isCancellable() {
        return status == Status.QUEUED || status.isBefore(Status.IMPORTING);
    }

    public void cancel(Instant now) {
        if (!isCancellable()) {
            throw new IllegalStateException(
                    "Import job " + id + " is at " + status + "; cancelling after IMPORTING would "
                            + "leave imported transactions with no job explaining them.");
        }
        this.status = Status.CANCELLED;
        this.finishedAt = now;
    }

    // ------------------------------------------------------------------ accessors

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getContentHash() { return contentHash; }
    public String getObjectKey() { return objectKey; }
    public String getFileName() { return fileName; }
    public String getSourceFormat() { return sourceFormat; }
    public Status getStatus() { return status; }
    public Integer getRowsTotal() { return rowsTotal; }
    public int getRowsProcessed() { return rowsProcessed; }
    public int getAttemptCount() { return attemptCount; }
    public int getRecoveryCount() { return recoveryCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public String getFailureCode() { return failureCode; }
    public String getCorrelationId() { return correlationId; }
    public UUID getImportSessionId() { return importSessionId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }

    /** {@link com.finora.imports.storage.StoredStatement}: always null, see this class's own doc
     *  on why -- a job carries an address, never the bytes. */
    @Override
    public byte[] getFileContent() { return null; }
}
