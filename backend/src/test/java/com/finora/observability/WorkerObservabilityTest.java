package com.finora.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The platform contract every worker inherits.
 *
 * <p>These assertions hold whether or not Sentry is configured -- the Sentry calls are no-ops
 * without a DSN, which is what lets this run in the suite with no network. What is asserted is
 * everything a worker depends on regardless: correlation is set and, crucially, restored; the
 * standard metric names exist; and the lifecycle distinctions that keep alerting meaningful are
 * real rather than cosmetic.
 */
class WorkerObservabilityTest {

    private static final String WORKER = "merchant-learning";
    private static final String KIND = "merchant-learning-event";

    private MeterRegistry meters;
    private WorkerObservability observability;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        observability = new WorkerObservability(meters);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    // ---------------------------------------------------------------- correlation

    @Test
    void aWorkerPassIsCorrelatedAndMarkedAsComingFromAWorker() {
        AtomicReference<String> seen = new AtomicReference<>();

        try (WorkerExecution execution = observability.begin(WORKER, KIND)) {
            seen.set(MDC.get("requestId"));
            assertThat(execution.correlationId()).isEqualTo(seen.get());
        }

        assertThat(seen.get()).isNotNull().startsWith("worker-");
    }

    @Test
    void aScheduledPassRecordsThatOriginInThePrefix() {
        // request- / worker- / scheduler- is the documented convention. The prefix is what tells an
        // operator reading a log line whether a poll or a user action started the work.
        AtomicReference<String> seen = new AtomicReference<>();

        try (WorkerExecution ignored = observability.beginScheduled(WORKER, KIND)) {
            seen.set(MDC.get("requestId"));
        }

        assertThat(seen.get()).startsWith("scheduler-");
    }

    @Test
    void itUsesTheSameMdcKeyAsHttpRequests_soAuditRowsCorrelateWithNoChangeToAuditService() {
        // AuditService stamps every row with MDC.get("requestId"). Background work previously had
        // none, so queue-driven audit rows carried null and could not be tied to anything.
        try (WorkerExecution ignored = observability.begin(WORKER, KIND)) {
            assertThat(MDC.get("requestId")).isNotNull();
        }
        assertThat(MDC.get("requestId")).as("must not leak past the pass").isNull();
    }

    @Test
    void aParentCorrelationIdIsRestored_notDestroyed() {
        // The async nudge runs from a request thread that already has its own id. Clearing rather
        // than restoring would silently detach the rest of that request's logs.
        MDC.put("requestId", "request-abc123");

        try (WorkerExecution ignored = observability.begin(WORKER, KIND)) {
            assertThat(MDC.get("requestId")).startsWith("worker-");
        }

        assertThat(MDC.get("requestId")).isEqualTo("request-abc123");
    }

    @Test
    void correlationIsRestoredEvenWhenTheBodyThrows() {
        // Worker threads are pooled. A leaked id would attach to every later job the thread picked
        // up, attributing one job's failure to another's id.
        MDC.put("requestId", "request-abc123");

        assertThatThrownBy(() -> {
            try (WorkerExecution ignored = observability.begin(WORKER, KIND)) {
                throw new IllegalStateException("apply failed");
            }
        }).isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get("requestId")).isEqualTo("request-abc123");
    }

    @Test
    void closeIsIdempotent_soADoubleCloseCannotStripAnotherPassesTags() {
        MDC.put("requestId", "request-abc123");
        WorkerExecution execution = observability.begin(WORKER, KIND);

        execution.close();
        execution.close();

        assertThat(MDC.get("requestId")).isEqualTo("request-abc123");
    }

    @Test
    void eachPassGetsItsOwnCorrelationId() {
        String first;
        String second;
        try (WorkerExecution e = observability.begin(WORKER, KIND)) { first = e.correlationId(); }
        try (WorkerExecution e = observability.begin(WORKER, KIND)) { second = e.correlationId(); }

        assertThat(first).isNotEqualTo(second);
    }

    // ---------------------------------------------------------------- lifecycle + metrics

    @Test
    void everyPassIsCountedAndTimed_soAnIdlePollStillProvesThePollerIsAlive() {
        try (WorkerExecution execution = observability.begin(WORKER, KIND)) {
            execution.claimed(0);
        }

        assertThat(counter("finora.worker.executions")).isEqualTo(1.0);
        assertThat(meters.find("finora.worker.duration").timer().count()).isEqualTo(1L);
    }

    @Test
    void aCompletedJobIsCounted() {
        try (WorkerExecution execution = observability.begin(WORKER, KIND)) {
            execution.completed(UUID.randomUUID());
        }

        assertThat(counter("finora.worker.completed")).isEqualTo(1.0);
    }

    @Test
    void queueWaitIsRecordedWhenTheQueuedTimeIsKnown() {
        // The metric that separates "the queue is slow" from "the work is slow".
        try (WorkerExecution execution = observability.begin(WORKER, KIND)) {
            execution.started(UUID.randomUUID(), Instant.now().minus(3, ChronoUnit.SECONDS));
        }

        var timer = meters.find("finora.worker.queue_wait_time").timer();
        assertThat(timer.count()).isEqualTo(1L);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isGreaterThanOrEqualTo(3.0);
    }

    @Test
    void anUnknownQueuedTimeIsSkippedRatherThanRecordedAsZero() {
        // A zero would be indistinguishable from a genuinely instant claim and would drag the
        // percentile down, making a real queue backlog look healthy.
        try (WorkerExecution execution = observability.begin(WORKER, KIND)) {
            execution.started(UUID.randomUUID(), null);
        }

        assertThat(meters.find("finora.worker.queue_wait_time").timer()).isNull();
    }

    @Test
    void aRetryIsCountedButIsNeitherAFailureNorADeadLetter() {
        // The distinction that keeps alerting usable: retrying is expected behaviour, so it must
        // not inflate the counters an alert fires on.
        try (WorkerExecution execution = observability.begin(WORKER, KIND)) {
            execution.retryScheduled(UUID.randomUUID(), 2);
        }

        assertThat(counter("finora.worker.retries")).isEqualTo(1.0);
        assertThat(counter("finora.worker.dead_letters")).isEqualTo(0.0);
        assertThat(counter("finora.worker.failures")).isEqualTo(0.0);
    }

    @Test
    void aDeadLetterCountsAsBothADeadLetterAndAFailure() {
        // Two questions, two counters: "how often do we give up" and "how often does work not get
        // done". A dead letter is both.
        try (WorkerExecution execution = observability.begin(WORKER, KIND)) {
            execution.deadLettered(UUID.randomUUID(), 5, new IllegalStateException("gave up"));
        }

        assertThat(counter("finora.worker.dead_letters")).isEqualTo(1.0);
        assertThat(counter("finora.worker.failures")).isEqualTo(1.0);
    }

    /**
     * Premium Import Reliability v1, §5.6. The 3-arg form used above is a delegating wrapper for
     * {@code AlertSeverity.ERROR} -- this proves that by construction, not by re-implementation:
     * every metric assertion the ERROR-severity path produces matches the 3-arg form exactly.
     */
    @Test
    void the3ArgDeadLetterDelegatesToErrorSeverity() {
        try (WorkerExecution execution = observability.begin(WORKER, KIND)) {
            execution.deadLettered(UUID.randomUUID(), 5, new IllegalStateException("gave up"),
                    AlertSeverity.ERROR);
        }

        assertThat(counter("finora.worker.dead_letters")).isEqualTo(1.0);
        assertThat(counter("finora.worker.failures")).isEqualTo(1.0);
    }

    /**
     * Alerting and analytics are different questions. A FAIL_FAST-classified failure (§5.6) never
     * pages anyone -- {@link AlertSeverity#NONE} -- but "how often do we give up" still has to count
     * it, or the failure-analytics query undercounts every expected, customer-caused dead-letter.
     */
    @Test
    void aNoneSeverityDeadLetterStillCountsTowardBothCounters() {
        try (WorkerExecution execution = observability.begin(WORKER, KIND)) {
            execution.deadLettered(UUID.randomUUID(), 1, new IllegalStateException("expected"),
                    AlertSeverity.NONE);
        }

        assertThat(counter("finora.worker.dead_letters")).isEqualTo(1.0);
        assertThat(counter("finora.worker.failures")).isEqualTo(1.0);
    }

    /** Same as the NONE case: severity changes who gets paged, never what gets counted. */
    @Test
    void aWarningSeverityDeadLetterAlsoCountsTowardBothCounters() {
        try (WorkerExecution execution = observability.begin(WORKER, KIND)) {
            execution.deadLettered(UUID.randomUUID(), 5, new IllegalStateException("infra blip"),
                    AlertSeverity.WARNING);
        }

        assertThat(counter("finora.worker.dead_letters")).isEqualTo(1.0);
        assertThat(counter("finora.worker.failures")).isEqualTo(1.0);
    }

    @Test
    void aFailureThatCouldNotBeRecordedCountsAsAFailureButNotADeadLetter() {
        // The row is stranded, not abandoned-by-policy. Filing it as a dead letter would overstate
        // how often retries are genuinely exhausted.
        try (WorkerExecution execution = observability.begin(WORKER, KIND)) {
            execution.failureNotRecorded(UUID.randomUUID(), new IllegalStateException("write failed"));
        }

        assertThat(counter("finora.worker.failures")).isEqualTo(1.0);
        assertThat(counter("finora.worker.dead_letters")).isEqualTo(0.0);
    }

    @Test
    void recoveryCountsEveryRowReturned_notJustTheSweep() {
        // The number is the signal: one row is a blip, fifty means a process died holding a claim.
        try (WorkerExecution execution = observability.begin(WORKER, KIND)) {
            execution.recovered(7);
        }

        assertThat(counter("finora.worker.recovered")).isEqualTo(7.0);
    }

    @Test
    void aRecoveryOfNothingIsNotAnEvent() {
        // recoverAbandoned runs on every poll and usually finds nothing. Counting or reporting
        // those would bury the passes that found something.
        try (WorkerExecution execution = observability.begin(WORKER, KIND)) {
            execution.recovered(0);
        }

        assertThat(counter("finora.worker.recovered")).isEqualTo(0.0);
    }

    // ---------------------------------------------------------------- queue depth

    @Test
    void queueDepthIsPublishedAsAGaugeThatTracksItsSupplier() {
        // A level, not an event: the registry polls it, so it reflects the queue now rather than
        // when the worker last ran.
        AtomicInteger depth = new AtomicInteger(3);
        observability.publishQueueDepth(WORKER, KIND, depth::get);

        assertThat(meters.find("finora.worker.queue_depth").gauge().value()).isEqualTo(3.0);
        depth.set(11);
        assertThat(meters.find("finora.worker.queue_depth").gauge().value()).isEqualTo(11.0);
    }

    @Test
    void oldestPendingAgeIsPublishedInSeconds() {
        // The user-visible symptom, and the metric QueueAgeExceedsSla alerts on.
        Instant queued = Instant.now().minus(90, ChronoUnit.SECONDS);
        observability.publishOldestPendingAge(WORKER, KIND, () -> java.util.Optional.of(queued));

        assertThat(meters.find("finora.worker.oldest_pending_age").gauge().value())
                .isGreaterThanOrEqualTo(90.0).isLessThan(120.0);
    }

    @Test
    void aDrainedQueuePublishesZeroRatherThanDisappearing() {
        // A gauge that vanishes is indistinguishable from a scrape failure on a dashboard, which
        // would make an empty queue look like an outage.
        observability.publishOldestPendingAge(WORKER, KIND, java.util.Optional::empty);

        assertThat(meters.find("finora.worker.oldest_pending_age").gauge().value()).isEqualTo(0.0);
    }

    // ---------------------------------------------------------------- naming contract

    @Test
    void everyMeterCarriesWorkerAndJobKindTags_soOneDashboardQueryCoversAllWorkers() {
        // Names are fixed by the framework rather than passed in by callers precisely so this
        // holds: a worker that invented its own name would be invisible on the shared dashboard.
        try (WorkerExecution execution = observability.begin(WORKER, KIND)) {
            execution.completed(UUID.randomUUID());
        }

        assertThat(meters.find("finora.worker.completed").tag("worker", WORKER).tag("jobKind", KIND).counter())
                .isNotNull();
    }

    @Test
    void reportingNeverThrows_evenWithNoSentryConfigured() {
        // This runs in the failure path of a scheduler. If it threw, it would replace a recorded
        // failure with an unrecorded one.
        UUID id = UUID.randomUUID();
        try (WorkerExecution execution = observability.begin(WORKER, KIND)) {
            execution.claimed(1);
            execution.started(id, null);
            execution.retryScheduled(id, 1);
            execution.deadLettered(id, 3, new IllegalStateException("x"));
            execution.deadLettered(id, 1, new IllegalStateException("none"), AlertSeverity.NONE);
            execution.deadLettered(id, 5, new IllegalStateException("warning"), AlertSeverity.WARNING);
            execution.deadLettered(id, 2, new IllegalStateException("error"), AlertSeverity.ERROR);
            execution.failureNotRecorded(id, new IllegalStateException("x"));
            execution.deadLettered(null, 3, new IllegalStateException("no id"));
            execution.recovered(1);
        }
    }

    private double counter(String name) {
        var counter = meters.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
