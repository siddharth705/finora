package com.finora.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.sentry.Breadcrumb;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One pass of background work, instrumented.
 *
 * <p>The platform contract every worker implements, rather than each solving observability
 * independently. Obtained from {@link WorkerObservability#begin} and used with try-with-resources,
 * so cleanup cannot be forgotten:
 *
 * <pre>{@code
 * try (WorkerExecution execution = observability.begin(WORKER, JOB_KIND)) {
 *     List<UUID> claimed = claimBatch();
 *     execution.claimed(claimed.size());
 *     for (UUID id : claimed) {
 *         execution.started(id, queuedAt);
 *         ...
 *         execution.completed(id);
 *     }
 * }
 * }</pre>
 *
 * <h2>Why try-with-resources and not a callback</h2>
 *
 * <p>{@link #close()} pops the Sentry scope, restores MDC and records the pass duration. All three
 * must happen even when the body throws, and all three are wrong if they happen twice. A callback
 * form was the obvious alternative, but it forces every lifecycle event to be reached through a
 * parameter and makes partial passes awkward to express. {@code AutoCloseable} gets the same
 * guarantee from the language.
 *
 * <h2>Cleanup is not optional bookkeeping</h2>
 *
 * <p>Worker threads are pooled and long-lived. A leaked MDC entry or Sentry tag attaches itself to
 * every later job the thread picks up, which is worse than having none: it attributes one job's
 * failure to another job's id. That is why cleanup is in a language construct rather than a
 * convention.
 *
 * <p><b>Not thread-safe, by design.</b> One execution represents one pass on one thread. Sharing
 * one across threads would interleave MDC state between them.
 */
public final class WorkerExecution implements AutoCloseable {

    private final WorkerMeters meters;
    private final String worker;
    private final String jobKind;
    private final String correlationId;
    private final String previousCorrelationId;
    private final Instant startedAt;
    private boolean closed;

    WorkerExecution(WorkerMeters meters, String worker, String jobKind, String correlationPrefix) {
        this.meters = meters;
        this.worker = worker;
        this.jobKind = jobKind;
        this.startedAt = Instant.now();

        this.previousCorrelationId = MDC.get(WorkerObservability.MDC_KEY);
        this.correlationId = correlationPrefix + "-" + UUID.randomUUID();
        MDC.put(WorkerObservability.MDC_KEY, correlationId);

        Sentry.pushScope();
        Sentry.configureScope(scope -> {
            scope.setTag("worker", worker);
            scope.setTag("jobKind", jobKind);
            scope.setTag("correlationId", correlationId);
        });

        meters.executions(worker, jobKind).increment();
    }

    /** The id tying this pass's log lines, audit rows and Sentry events together. */
    public String correlationId() {
        return correlationId;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Rows were claimed from the queue. Zero is normal and worth recording -- an idle pass is
     *  evidence the poller is alive. */
    public void claimed(int count) {
        breadcrumb(SentryLevel.INFO, "claimed " + count + " " + jobKind + " job(s)");
    }

    /**
     * A claimed job is now being worked.
     *
     * @param queuedAt when the row was enqueued, or null if unknown. Supplying it records
     *                 queue wait time, which is the metric that distinguishes "the queue is slow"
     *                 from "the work is slow" -- two problems with entirely different fixes.
     */
    public void started(UUID jobId, Instant queuedAt) {
        if (queuedAt != null) {
            meters.queueWait(worker, jobKind).record(Duration.between(queuedAt, Instant.now()));
        }
    }

    /** The job succeeded. */
    public void completed(UUID jobId) {
        meters.completed(worker, jobKind).increment();
    }

    /**
     * The job failed and will be retried.
     *
     * <p>Counted and breadcrumbed, deliberately NOT reported as an error: a transient failure the
     * next attempt resolves is normal operation, and paging on it is how alerting gets muted.
     * Alert on the retry RATE instead. The breadcrumb means that if a later attempt does
     * dead-letter, this history arrives attached to that event.
     */
    public void retryScheduled(UUID jobId, int attempt) {
        meters.retries(worker, jobKind).increment();
        breadcrumb(SentryLevel.WARNING, "retry scheduled for " + jobKind + " attempt " + attempt);
    }

    /**
     * Retries are exhausted; the job will not run again without a human. Alerts at {@link
     * AlertSeverity#ERROR}, matching every caller before {@link #deadLettered(UUID, int, Throwable,
     * AlertSeverity)} existed.
     *
     * <p>The user's action silently did not take effect, and without an alert the only signal is a
     * log line plus a row in a screen somebody has to think to open.
     *
     * @see #deadLettered(UUID, int, Throwable, AlertSeverity)
     */
    public void deadLettered(UUID jobId, int attempts, Throwable cause) {
        deadLettered(jobId, attempts, cause, AlertSeverity.ERROR);
    }

    /**
     * Retries are exhausted; the job will not run again without a human -- reported at a severity
     * the caller chooses, because "gave up" does not always mean the same thing. Premium Import
     * Reliability v1, §5.6: a known, expected failure (a corrupt PDF, a locked document) giving up
     * immediately is not an engineering problem, and paging on every one of them buries the alerts
     * that are.
     *
     * <p>The counters below are unconditional regardless of severity -- alerting and analytics are
     * separate questions. "How often do we give up" still needs to count an {@link
     * AlertSeverity#NONE} dead-letter; "should a human be paged for this one" is the only thing
     * severity decides.
     */
    public void deadLettered(UUID jobId, int attempts, Throwable cause, AlertSeverity severity) {
        meters.deadLetters(worker, jobKind).increment();
        meters.failures(worker, jobKind).increment();
        switch (severity) {
            case NONE -> { /* expected, not actionable -- counted above, never paged */ }
            case WARNING -> capture(jobId, "apply", "dead-letter", cause, SentryLevel.WARNING);
            case ERROR -> capture(jobId, "apply", "dead-letter", cause, SentryLevel.ERROR);
        }
    }

    /**
     * Recording a failure itself failed, so the row is stranded mid-flight until recovery sweeps
     * it. A double fault, and invisible without this.
     */
    public void failureNotRecorded(UUID jobId, Throwable cause) {
        meters.failures(worker, jobKind).increment();
        capture(jobId, "record-failure", "stranded", cause);
    }

    /**
     * Rows abandoned mid-flight were returned to the queue, which means a worker process died.
     *
     * <p>Reported without a throwable: no exception exists here, only evidence that one happened
     * somewhere nobody saw. The count is the signal -- one row is a blip, a full batch means a
     * process died holding a whole claim.
     */
    public void recovered(int count) {
        if (count <= 0) return;
        meters.recovered(worker, jobKind).increment(count);
        Sentry.withScope(scope -> {
            scope.setTag("phase", "recover");
            scope.setTag("outcome", "recovered");
            scope.setLevel(SentryLevel.WARNING);
            Sentry.captureMessage("Returned " + count + " abandoned " + jobKind + " job(s) to the "
                    + "queue; a worker most likely died mid-apply");
        });
    }

    /**
     * Records the pass duration, pops the Sentry scope and restores MDC.
     *
     * <p>Idempotent: a double close would otherwise pop a scope this execution does not own, which
     * would silently strip tags from whatever is running next on this thread.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        meters.duration(worker, jobKind).record(Duration.between(startedAt, Instant.now()));
        Sentry.popScope();
        // Restored rather than cleared: an async nudge runs from a request thread that has its own
        // correlation id, and clearing would detach the rest of that request's logs.
        if (previousCorrelationId == null) {
            MDC.remove(WorkerObservability.MDC_KEY);
        } else {
            MDC.put(WorkerObservability.MDC_KEY, previousCorrelationId);
        }
    }

    // ------------------------------------------------------------------ internals

    private void capture(UUID jobId, String phase, String outcome, Throwable cause) {
        capture(jobId, phase, outcome, cause, null);
    }

    /** @param level null keeps Sentry's own default level for an exception capture -- explicit only
     *               where a caller (currently only {@link #deadLettered(UUID, int, Throwable,
     *               AlertSeverity)}) needs to say otherwise. */
    private void capture(UUID jobId, String phase, String outcome, Throwable cause, SentryLevel level) {
        Sentry.withScope(scope -> {
            scope.setTag("phase", phase);
            scope.setTag("outcome", outcome);
            // Safe under SentryScrubber's tag allowlist: a queue row's own id identifies a row in
            // our database and nothing about the person it belongs to.
            if (jobId != null) scope.setTag("jobId", jobId.toString());
            if (level != null) scope.setLevel(level);
            Sentry.captureException(cause);
        });
    }

    private void breadcrumb(SentryLevel level, String message) {
        Breadcrumb crumb = new Breadcrumb();
        crumb.setCategory("worker");
        crumb.setLevel(level);
        crumb.setMessage(message);
        Sentry.addBreadcrumb(crumb);
    }

    /** Package-private so {@link WorkerObservability} can expose a registry without leaking the
     *  Micrometer types into every worker. */
    interface WorkerMeters {
        io.micrometer.core.instrument.Counter executions(String worker, String jobKind);
        io.micrometer.core.instrument.Counter completed(String worker, String jobKind);
        io.micrometer.core.instrument.Counter retries(String worker, String jobKind);
        io.micrometer.core.instrument.Counter deadLetters(String worker, String jobKind);
        io.micrometer.core.instrument.Counter recovered(String worker, String jobKind);
        io.micrometer.core.instrument.Counter failures(String worker, String jobKind);
        Timer duration(String worker, String jobKind);
        Timer queueWait(String worker, String jobKind);
        MeterRegistry registry();
    }
}
