package com.finora.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One merchant-learning confirmation waiting to be applied, independently of the import that
 * produced it.
 *
 * <p>Deliverable 0 of the Import Reliability Milestone. The import writes this row inside its own
 * transaction and a worker applies it after that transaction commits, so a learning failure can
 * never roll back a statement the user has already reviewed and approved.
 *
 * <p>Deliberately NOT extending {@link BaseEntity}. That base is for soft-deleted, optimistically
 * locked domain records; a queue row is neither. It is hard-deleted or terminal, and optimistic
 * locking would be actively wrong here — two workers must be prevented from claiming the same row
 * at all (which {@code FOR UPDATE SKIP LOCKED} does), not allowed to race and have one lose at
 * write time. Not extending it also keeps the {@code @Version}-driven {@code merge()} behaviour
 * described in BaseEntity's own doc comment out of this path.
 */
@Entity
@Table(name = "merchant_learning_events")
public class MerchantLearningEvent {

    /**
     * The cap on retries. Five attempts spread over 1 + 2 + 4 + 8 + 16 minutes, so a transient
     * failure has just over half an hour to clear itself before a human is asked to look.
     *
     * <p>In code rather than a CHECK constraint on purpose: this is a policy, and a constraint
     * would turn tuning it into a migration.
     */
    public static final int MAX_ATTEMPTS = 5;

    /** Bounds what a provider's exception message can write into the row. The admin queue shows
     *  this; it does not need a stack trace, and an unbounded message from an unknown failure mode
     *  is how a queue table becomes a log table. */
    private static final int MAX_ERROR_LENGTH = 2000;

    public enum Status {
        /** Waiting for a worker, either freshly enqueued or backing off after a failure. */
        PENDING,
        /** Claimed by a worker. A row stuck here means a worker died mid-processing — see
         *  {@code MerchantLearningEventWorker} for how those are recovered. */
        PROCESSING,
        COMPLETED,
        /** Terminal: {@link #MAX_ATTEMPTS} attempts all failed. Surfaces in the admin queue. */
        FAILED,
        /**
         * Terminal by human decision: an operator looked at a FAILED event and decided no action
         * is needed.
         *
         * <p>Distinct from COMPLETED, and the distinction matters. COMPLETED means the learning was
         * applied; RESOLVED means it never will be and that is fine. Collapsing them would make
         * the queue's own history lie about what the engine learned. Without this state a FAILED
         * row has no way off the queue at all, and the page accumulates permanent noise that
         * trains operators to ignore it.
         */
        RESOLVED
    }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    /** Which import produced this, for the admin queue. Nullable for two distinct reasons, and
     *  both are correct: the import was deleted (nulled rather than cascaded, so the event stays
     *  processable), or there was never an import at all — a bulk recategorization (WI1A) earns
     *  real confirmations with no statement behind them. */
    @Column(name = "source_statement_import_id")
    private UUID sourceStatementImportId;

    /** The staging/review session this came from, when there was one. Null for direct-file
     *  imports, which never have a session, and for every non-import caller — never a synthetic
     *  placeholder, because an operator following a link to a session that never existed would
     *  reasonably conclude the row is corrupt. */
    @Column(name = "source_import_session_id")
    private UUID sourceImportSessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "first_failed_at")
    private Instant firstFailedAt;

    @Column(name = "last_retry_at")
    private Instant lastRetryAt;

    protected MerchantLearningEvent() {
        // JPA
    }

    private MerchantLearningEvent(UUID userId, UUID merchantId, UUID categoryId,
                                   UUID sourceStatementImportId, UUID sourceImportSessionId) {
        this.userId = userId;
        this.merchantId = merchantId;
        this.categoryId = categoryId;
        this.sourceStatementImportId = sourceStatementImportId;
        this.sourceImportSessionId = sourceImportSessionId;
    }

    /** A freshly enqueued event, immediately due. The nudge normally picks it up within
     *  milliseconds; the poller is the backstop if the nudge is lost. */
    public static MerchantLearningEvent pending(UUID userId, UUID merchantId, UUID categoryId,
                                                 UUID sourceStatementImportId, UUID sourceImportSessionId) {
        return new MerchantLearningEvent(userId, merchantId, categoryId,
                sourceStatementImportId, sourceImportSessionId);
    }

    /**
     * Records a failed attempt and schedules the next one, or gives up.
     *
     * <p>Backoff is {@code 2^attemptCount} minutes measured from {@code now}, so the first retry
     * waits a minute and the last waits sixteen. {@code firstFailedAt} is set once and never
     * moved — the admin queue needs "how long has this been broken", which the latest failure
     * cannot answer.
     */
    public void recordFailure(String error, Instant now) {
        this.attemptCount++;
        this.lastError = truncate(error);
        this.lastRetryAt = now;
        if (this.firstFailedAt == null) {
            this.firstFailedAt = now;
        }
        if (this.attemptCount >= MAX_ATTEMPTS) {
            this.status = Status.FAILED;
        } else {
            this.status = Status.PENDING;
            // attemptCount - 1, not attemptCount: it was incremented above, so after the FIRST
            // failure it is already 1, and 2^1 would make the first retry wait two minutes rather
            // than the documented one. Off by one against the schedule this class claims (1, 2, 4,
            // 8, 16) -- small in isolation, but it silently doubled every wait.
            this.nextAttemptAt = now.plus(backoffFor(this.attemptCount - 1));
        }
        this.updatedAt = now;
    }

    /** {@code 2^attempts} minutes. Package-visible so the worker's test can assert the schedule
     *  without reaching through a persisted row. */
    static Duration backoffFor(int attempts) {
        return Duration.ofMinutes(1L << Math.min(attempts, 30));
    }

    public void markCompleted(Instant now) {
        this.status = Status.COMPLETED;
        this.lastError = null;
        this.updatedAt = now;
    }

    /**
     * Puts a FAILED event back in the queue with its attempt budget reset.
     *
     * <p>Backs the admin queue's Retry action. The attempt count resets because an admin retrying
     * is a new decision, usually after fixing whatever caused the failure — inheriting the old
     * budget would let them retry exactly zero times. {@code firstFailedAt} deliberately survives,
     * so the record of when this first broke is not erased by someone retrying it.
     */
    public void requeueForRetry(Instant now) {
        this.status = Status.PENDING;
        this.attemptCount = 0;
        // Cleared, or the queue shows a PENDING row alongside the error from the attempt an admin
        // has already responded to -- which reads as "this failed again" when nothing has run yet.
        // firstFailedAt deliberately survives below; that is history, this is current state.
        this.lastError = null;
        this.nextAttemptAt = now;
        this.lastRetryAt = now;
        this.updatedAt = now;
    }

    /** An operator's decision that this event will never succeed and should stop being shown.
     *  Only meaningful from FAILED; the caller enforces that so the reason for refusing can name
     *  the actual state. */
    public void markResolved(Instant now) {
        this.status = Status.RESOLVED;
        this.updatedAt = now;
    }

    public void markProcessing(Instant now) {
        this.status = Status.PROCESSING;
        this.updatedAt = now;
    }

    private static String truncate(String error) {
        if (error == null) return null;
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getCategoryId() { return categoryId; }
    public UUID getSourceStatementImportId() { return sourceStatementImportId; }
    public UUID getSourceImportSessionId() { return sourceImportSessionId; }
    public Status getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getFirstFailedAt() { return firstFailedAt; }
    public Instant getLastRetryAt() { return lastRetryAt; }
}
