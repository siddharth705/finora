package com.finora.observability;

import com.finora.config.CorrelationIdFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * The observability contract every background worker implements.
 *
 * <p>Workers do not instrument themselves. They obtain a {@link WorkerExecution} from here and
 * report lifecycle events against it, so correlation, metrics, breadcrumbs, exception capture, MDC
 * management, timing and cleanup are inherited rather than reimplemented. A new worker adds
 * instrumentation by using this class, not by writing any.
 *
 * <h2>The standard lifecycle</h2>
 *
 * <pre>
 *   queued -&gt; claimed -&gt; started -&gt; completed
 *                          |
 *                          +-&gt; retry scheduled -&gt; (back to claimed)
 *                          +-&gt; dead letter
 *                          +-&gt; failure recording failed
 *                          +-&gt; recovered (a worker died mid-flight)
 * </pre>
 *
 * <p>These names are the operational vocabulary: they are the metric names, the Sentry
 * {@code outcome} tags and the runbook headings, deliberately identical in all three so that an
 * alert, a dashboard panel and a document all say the same word.
 *
 * <h2>Correlation convention</h2>
 *
 * <p>Every execution path generates an id under the same MDC key {@link CorrelationIdFilter} uses,
 * distinguished by prefix:
 *
 * <ul>
 *   <li>{@code request-} — an HTTP request, set by {@link CorrelationIdFilter}</li>
 *   <li>{@code worker-} — an async or on-demand worker pass</li>
 *   <li>{@code scheduler-} — a pass initiated by a scheduled trigger</li>
 * </ul>
 *
 * <p>One key, not three, is what makes this work: {@code AuditService} stamps every audit row with
 * whatever it finds there, so a queue-driven write is correlated with no change to that class. The
 * prefix preserves origin without needing a second lookup.
 *
 * @see WorkerExecution
 * @see <a href="file:../../../../../../../docs/engineering/observability.md">observability.md</a>
 */
@Component
public class WorkerObservability {

    /** Deliberately the same key {@link CorrelationIdFilter} uses -- see the correlation section. */
    static final String MDC_KEY = CorrelationIdFilter.MDC_KEY;

    /** Prefixes for the correlation convention. */
    public static final String WORKER_PREFIX = "worker";
    public static final String SCHEDULER_PREFIX = "scheduler";

    private final Meters meters;

    public WorkerObservability(MeterRegistry registry) {
        this.meters = new Meters(registry);
    }

    /** Begins a worker-initiated pass. Use with try-with-resources. */
    public WorkerExecution begin(String worker, String jobKind) {
        return new WorkerExecution(meters, worker, jobKind, WORKER_PREFIX);
    }

    /** Begins a pass initiated by a scheduled trigger, so the correlation id records that origin. */
    public WorkerExecution beginScheduled(String worker, String jobKind) {
        return new WorkerExecution(meters, worker, jobKind, SCHEDULER_PREFIX);
    }

    /**
     * Publishes queue depth for a worker.
     *
     * <p>A gauge rather than a counter because depth is a level, not an event, and it is the one
     * signal that distinguishes "slow" from "stuck": a rising depth with a healthy completion rate
     * is load, a rising depth with a flat completion rate is a stall. Call once at startup; the
     * supplier is polled by the registry.
     *
     * <p>The supplier must be cheap and must not throw -- it runs on the metrics scrape path, not
     * on the worker's own thread. A {@code SELECT count(*)} against an indexed status column is the
     * intended shape.
     */
    public void publishQueueDepth(String worker, String jobKind, Supplier<Number> depth) {
        Gauge.builder("finora.worker.queue_depth", depth)
                .tag("worker", worker)
                .tag("jobKind", jobKind)
                .description("Jobs waiting to be claimed")
                .register(meters.registry());
    }

    /**
     * The standard metric set, so every worker reports the same names with the same tags.
     *
     * <p>Names are fixed here rather than passed in by callers on purpose: a dashboard querying
     * {@code finora.worker.dead_letters} must match every worker, and a worker that invented its
     * own name would be invisible on it.
     */
    private record Meters(MeterRegistry registry) implements WorkerExecution.WorkerMeters {

        @Override public Counter executions(String w, String k) { return counter("executions", w, k, "Worker passes started"); }
        @Override public Counter completed(String w, String k)  { return counter("completed", w, k, "Jobs that succeeded"); }
        @Override public Counter retries(String w, String k)    { return counter("retries", w, k, "Jobs scheduled for another attempt"); }
        @Override public Counter deadLetters(String w, String k){ return counter("dead_letters", w, k, "Jobs that exhausted their retries"); }
        @Override public Counter recovered(String w, String k)  { return counter("recovered", w, k, "Jobs returned to the queue after a worker died"); }
        @Override public Counter failures(String w, String k)   { return counter("failures", w, k, "Job failures that were not retried away"); }

        @Override public Timer duration(String w, String k) {
            return Timer.builder("finora.worker.duration")
                    .tag("worker", w).tag("jobKind", k)
                    .description("How long one worker pass took")
                    .publishPercentiles(0.5, 0.95)   // p95 is the dashboard number; the mean hides stalls
                    .register(registry);
        }

        @Override public Timer queueWait(String w, String k) {
            return Timer.builder("finora.worker.queue_wait_time")
                    .tag("worker", w).tag("jobKind", k)
                    .description("How long a job waited between being queued and being started")
                    .publishPercentiles(0.5, 0.95)
                    .register(registry);
        }

        @Override public MeterRegistry registry() { return registry; }

        private Counter counter(String name, String worker, String jobKind, String description) {
            return Counter.builder("finora.worker." + name)
                    .tag("worker", worker).tag("jobKind", jobKind)
                    .description(description)
                    .register(registry);
        }
    }
}
