package com.finora.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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
 */
@Entity
@Table(name = "import_jobs")
public class ImportJob {

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

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "last_error")
    private String lastError;

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

    protected ImportJob() {}

    public ImportJob(UUID userId, String fileName, String contentHash, String objectKey) {
        this.userId = userId;
        this.fileName = fileName;
        this.contentHash = contentHash;
        this.objectKey = objectKey;
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
     * so the state machine refuses instead. {@code ImportJobWorker} treats this as the cancellation
     * it is rather than as a failure; anything else genuinely is a bug in the caller.
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
    }

    /**
     * Records a failed attempt, either scheduling a retry or dead-lettering.
     *
     * <p>Exponential backoff from one minute, capped: a job whose dependency is down should back
     * off, but a job that will succeed on the next attempt should not wait an hour to prove it.
     *
     * @return true if this attempt exhausted the budget and the job is now FAILED
     */
    public boolean recordFailure(String error, Instant now) {
        this.lastError = error;
        if (attemptCount >= MAX_ATTEMPTS) {
            this.status = Status.FAILED;
            this.finishedAt = now;
            return true;
        }
        this.status = Status.QUEUED;
        this.nextAttemptAt = now.plus(backoffFor(attemptCount));
        this.startedAt = null;
        return false;
    }

    /** 1, 2, 4, 8, 16 minutes. Capped so a transient failure does not park work for an hour. */
    static Duration backoffFor(int attempts) {
        return Duration.ofMinutes(1L << Math.min(Math.max(attempts, 1) - 1, 4));
    }

    /**
     * Returns an abandoned job to the queue without consuming an attempt.
     *
     * <p>A worker that died mid-parse did not prove anything about the job, so charging it an
     * attempt would dead-letter perfectly good work after five unlucky deploys. The learning queue
     * makes the same distinction.
     */
    public void returnToQueue(String reason, Instant now) {
        this.status = Status.QUEUED;
        this.lastError = reason;
        this.startedAt = null;
        this.nextAttemptAt = now;
        if (this.attemptCount > 0) this.attemptCount--;
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
    public Status getStatus() { return status; }
    public Integer getRowsTotal() { return rowsTotal; }
    public int getRowsProcessed() { return rowsProcessed; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public String getCorrelationId() { return correlationId; }
    public UUID getImportSessionId() { return importSessionId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
