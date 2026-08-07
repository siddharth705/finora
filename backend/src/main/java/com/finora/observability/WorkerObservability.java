package com.finora.observability;

import com.finora.config.CorrelationIdFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Makes background work visible.
 *
 * <p>The Sentry starter reports what fails on a request thread. Nothing reported what failed on a
 * scheduler or an async executor, and that is where the work that matters most happens: the
 * merchant-learning queue applies a user's confirmed categorisation, retries it with backoff, and
 * gives up into a dead-letter state that only surfaces if someone opens the admin queue. A worker
 * that died mid-apply left rows stranded in PROCESSING and said so in a log line nobody tails.
 *
 * <h2>Correlation, and why it reuses the HTTP key</h2>
 *
 * <p>{@link CorrelationIdFilter} sets {@code requestId} in MDC for HTTP requests, and
 * {@code AuditService} stamps every audit row with whatever it finds there. Background work had
 * none, so a queue-driven audit row carried a null request id and could not be tied to anything.
 *
 * <p>Setting the <em>same</em> MDC key here means three things line up for free, with no change to
 * either class: the worker's log lines, the audit rows it writes, and the Sentry events it reports
 * all carry one id. The value is prefixed {@code worker-} so its origin stays obvious in a log
 * search.
 *
 * <h2>What is reported, and what deliberately is not</h2>
 *
 * <p>A retryable failure is <b>not</b> reported as an error. A transient constraint violation that
 * the next attempt resolves is normal operation, and paging on it is how alerting gets muted. It is
 * recorded as a breadcrumb and counted, so the rate is visible without being noisy.
 *
 * <p>Three things ARE reported, because each means something is wrong and nobody is watching:
 * a dead-lettered job (retries exhausted, work silently not done), a failure while recording a
 * failure (the row is now stranded), and recovery of abandoned rows (a worker process died).
 */
@Component
public class WorkerObservability {

    /** Deliberately the same key {@link CorrelationIdFilter} uses -- see the class comment. */
    private static final String MDC_KEY = CorrelationIdFilter.MDC_KEY;

    private final MeterRegistry meters;

    public WorkerObservability(MeterRegistry meters) {
        this.meters = meters;
    }

    /**
     * Runs a unit of background work under a fresh correlation id and Sentry scope.
     *
     * <p>The scope is popped and MDC restored in a finally block: these threads are pooled and
     * long-lived, so a leaked tag or MDC entry would attach itself to every later job the thread
     * picked up, which is worse than having none -- it would attribute one job's failure to
     * another's id.
     *
     * <p>Any previous MDC value is restored rather than cleared, so a worker invoked from a
     * request thread (the async nudge) does not destroy the request's own correlation id.
     */
    public void run(String worker, String jobKind, Runnable body) {
        String previous = MDC.get(MDC_KEY);
        String correlationId = "worker-" + UUID.randomUUID();
        MDC.put(MDC_KEY, correlationId);
        Sentry.pushScope();
        try {
            Sentry.configureScope(scope -> {
                scope.setTag("worker", worker);
                scope.setTag("jobKind", jobKind);
                scope.setTag("correlationId", correlationId);
            });
            body.run();
        } finally {
            Sentry.popScope();
            if (previous == null) MDC.remove(MDC_KEY); else MDC.put(MDC_KEY, previous);
        }
    }

    /**
     * A job failed but will be retried. Recorded, counted, not reported as an error.
     *
     * <p>Deliberately a breadcrumb: if a later attempt of the same job dead-letters, this is the
     * history an operator wants attached to that event. On its own it is not worth waking anyone.
     */
    public void retryScheduled(String worker, String jobKind, UUID jobId, int attempt) {
        counter("finora.worker.retry", worker, jobKind).increment();
        io.sentry.Breadcrumb crumb = new io.sentry.Breadcrumb();
        crumb.setCategory("worker");
        crumb.setLevel(SentryLevel.WARNING);
        crumb.setMessage("retry scheduled for " + jobKind + " attempt " + attempt);
        Sentry.addBreadcrumb(crumb);
    }

    /**
     * Retries are exhausted and the job will not run again without a human.
     *
     * <p>The one worker outcome that always deserves an event: the user's action silently did not
     * take effect, and the only existing signal was a log line plus a row in an admin screen
     * somebody has to think to open.
     */
    public void deadLettered(String worker, String jobKind, UUID jobId, int attempts, Throwable cause) {
        counter("finora.worker.dead_letter", worker, jobKind).increment();
        capture(worker, jobKind, jobId, "apply", "dead-letter", cause);
    }

    /**
     * Recording a failure itself failed, so the row is stranded in PROCESSING until recovery
     * sweeps it up. A double fault, and invisible without this.
     */
    public void failureNotRecorded(String worker, String jobKind, UUID jobId, Throwable cause) {
        counter("finora.worker.failure_not_recorded", worker, jobKind).increment();
        capture(worker, jobKind, jobId, "record-failure", "stranded", cause);
    }

    /**
     * Rows were found abandoned in PROCESSING and returned to the queue, which means a worker
     * process died mid-apply. Reported without a throwable -- there is no exception here, only
     * evidence that one happened somewhere nobody saw.
     */
    public void recoveredAbandoned(String worker, String jobKind, int count) {
        counter("finora.worker.recovered", worker, jobKind).increment(count);
        Sentry.withScope(scope -> {
            scope.setTag("worker", worker);
            scope.setTag("jobKind", jobKind);
            scope.setTag("phase", "recover");
            scope.setTag("outcome", "recovered");
            scope.setLevel(SentryLevel.WARNING);
            Sentry.captureMessage(
                    "Returned " + count + " abandoned " + jobKind + " job(s) to the queue; "
                            + "a worker most likely died mid-apply");
        });
    }

    private void capture(String worker, String jobKind, UUID jobId,
                         String phase, String outcome, Throwable cause) {
        Sentry.withScope(scope -> {
            scope.setTag("worker", worker);
            scope.setTag("jobKind", jobKind);
            scope.setTag("phase", phase);
            scope.setTag("outcome", outcome);
            // Safe under SentryScrubber's tag allowlist: a queue row's own id identifies a row in
            // our database and nothing about the person it belongs to.
            if (jobId != null) scope.setTag("jobId", jobId.toString());
            Sentry.captureException(cause);
        });
    }

    private Counter counter(String name, String worker, String jobKind) {
        return Counter.builder(name).tag("worker", worker).tag("jobKind", jobKind).register(meters);
    }
}
