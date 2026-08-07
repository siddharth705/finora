package com.finora.imports.jobs;

import com.finora.entity.ImportJob;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * How long one stage of one import job attempt took, and how it ended.
 *
 * <p>Item 6 of {@code docs/engineering/milestone-2-import-at-scale.md}. {@code import_jobs.status}
 * says which stage a job is in <em>now</em> and {@code statement_analysis_sessions.duration_ms} says
 * how long the whole thing took. Neither can answer "which stage was slow", which is the question
 * every import performance conversation starts with and none of them has ever been able to finish.
 *
 * <h2>Why this passes the diagnostics rule</h2>
 *
 * <p>A diagnostic earns its place by being able to prove a proposed capability <em>unnecessary</em>.
 * Per-stage timing can show that a stage everyone assumed was the bottleneck is not, and stop an
 * optimisation being built. {@link Outcome#SKIPPED} goes further: it can show a stage does not run
 * on this path at all. Both are falsifying observations, which is what separates this from a counter
 * that only ever goes up.
 *
 * <h2>Attempt is part of the identity</h2>
 *
 * <p>A job that fails in {@code ANALYZING} and retries runs {@code PARSING} again. Recording stages
 * per job rather than per attempt would either collide or overwrite, and "PARSING took 40ms" would
 * be the last attempt's timing wearing the whole job's name. Keeping every attempt is also the only
 * way to see that attempt 3 was slower than attempt 1 — a degrading dependency rather than a blip.
 */
@Entity
@Table(name = "import_job_stages")
public class ImportJobStage {

    /**
     * How a stage ended.
     *
     * <p>{@link #RUNNING} is a readable state, not an internal one. A row still {@code RUNNING} long
     * after its job reached a terminal status is a worker that died inside that stage, and naming
     * which stage is exactly why the row is written on entry rather than on exit.
     *
     * <p>{@link #SKIPPED} is the Evidence Rule applied to a stage: a stage that never ran must be
     * distinguishable from a stage nobody instrumented, and only a recorded row can do that.
     */
    public enum Outcome { RUNNING, COMPLETED, FAILED, SKIPPED }

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    /**
     * Stored as the enum's name rather than as a foreign key onto some stage table: the lifecycle
     * is owned by {@link ImportJob.Status}, and this table only observes it.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private ImportJob.Status stage;

    @Column(nullable = false, updatable = false)
    private int attempt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Outcome outcome = Outcome.RUNNING;

    /** Null for a {@link Outcome#SKIPPED} stage — it never started, and 0 would be a lie. */
    @Column(name = "started_at", updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    /** Ordering that survives a skipped stage having no start time. */
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt = Instant.now();

    protected ImportJobStage() {
        // JPA
    }

    private ImportJobStage(UUID jobId, ImportJob.Status stage, int attempt, Outcome outcome,
                           Instant startedAt) {
        this.jobId = jobId;
        this.stage = stage;
        this.attempt = attempt;
        this.outcome = outcome;
        this.startedAt = startedAt;
    }

    /** A stage the worker has just entered. Written now, closed later — see {@link Outcome#RUNNING}. */
    public static ImportJobStage entered(UUID jobId, ImportJob.Status stage, int attempt, Instant now) {
        return new ImportJobStage(jobId, stage, attempt, Outcome.RUNNING, now);
    }

    /** A stage the job passed over on its way to a later one. No timing, deliberately. */
    public static ImportJobStage skipped(UUID jobId, ImportJob.Status stage, int attempt) {
        return new ImportJobStage(jobId, stage, attempt, Outcome.SKIPPED, null);
    }

    /**
     * Closes an open stage.
     *
     * <p>Idempotent by omission rather than by throwing: a stage already closed keeps its first
     * outcome and its first timing. Recording runs in the failure path, and a recorder that objected
     * to being called twice would turn a duplicate report into a lost one.
     */
    public void close(Outcome finalOutcome, Instant now) {
        if (this.outcome != Outcome.RUNNING) return;
        this.outcome = finalOutcome;
        this.endedAt = now;
        this.durationMs = startedAt == null ? null : Math.max(0, Duration.between(startedAt, now).toMillis());
    }

    public UUID getId() { return id; }
    public UUID getJobId() { return jobId; }
    public ImportJob.Status getStage() { return stage; }
    public int getAttempt() { return attempt; }
    public Outcome getOutcome() { return outcome; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public Long getDurationMs() { return durationMs; }
    public Instant getRecordedAt() { return recordedAt; }
}
