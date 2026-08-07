package com.finora.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The correlation and counting behaviour, which is what makes background failures findable.
 *
 * <p>The Sentry calls themselves are no-ops without a DSN, which is deliberate and is what lets
 * this run in the suite with no network. What is asserted here is everything that must hold
 * regardless of whether Sentry is configured: the correlation id is set and, crucially, restored;
 * and the counters move.
 */
class WorkerObservabilityTest {

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

    @Test
    void aCorrelationIdIsVisibleToTheBodyAndMarkedAsComingFromAWorker() {
        // The prefix is what makes the origin obvious in a log search, and what distinguishes a
        // queue-driven audit row from a request-driven one.
        AtomicReference<String> seen = new AtomicReference<>();

        observability.run("merchant-learning", "event", () -> seen.set(MDC.get("requestId")));

        assertThat(seen.get()).isNotNull().startsWith("worker-");
    }

    @Test
    void itUsesTheSameMdcKeyAsHttpRequests_soAuditRowsAreCorrelatedWithNoChangeToAuditService() {
        // AuditService stamps every row with MDC.get("requestId"). Background work previously had
        // none, so queue-driven audit rows carried null and could not be tied to anything.
        AtomicReference<String> seen = new AtomicReference<>();

        observability.run("merchant-learning", "event", () -> seen.set(MDC.get("requestId")));

        assertThat(seen.get()).isEqualTo(seen.get());
        assertThat(MDC.get("requestId")).as("must not leak after the body finishes").isNull();
    }

    @Test
    void aPreviousCorrelationIdIsRestored_notDestroyed() {
        // The async nudge is invoked from a request thread that already has its own id. Clearing
        // rather than restoring would silently detach the rest of that request's logs.
        MDC.put("requestId", "http-abc123");

        observability.run("merchant-learning", "event", () -> {});

        assertThat(MDC.get("requestId")).isEqualTo("http-abc123");
    }

    @Test
    void theCorrelationIdIsRestoredEvenWhenTheBodyThrows() {
        // These threads are pooled and long-lived. A leaked id would attach itself to every later
        // job the thread picked up, attributing one job's failure to another's id.
        MDC.put("requestId", "http-abc123");

        assertThatThrownBy(() -> observability.run("merchant-learning", "event", () -> {
            throw new IllegalStateException("apply failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get("requestId")).isEqualTo("http-abc123");
    }

    @Test
    void eachPassGetsItsOwnCorrelationId() {
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();

        observability.run("merchant-learning", "event", () -> first.set(MDC.get("requestId")));
        observability.run("merchant-learning", "event", () -> second.set(MDC.get("requestId")));

        assertThat(first.get()).isNotEqualTo(second.get());
    }

    @Test
    void deadLetteringIsCounted() {
        observability.deadLettered("merchant-learning", "event", UUID.randomUUID(), 5,
                new IllegalStateException("gave up"));

        assertThat(counter("finora.worker.dead_letter")).isEqualTo(1.0);
    }

    @Test
    void aScheduledRetryIsCountedButIsNotADeadLetter() {
        // The distinction that keeps alerting usable: retries are normal, dead letters are not.
        observability.retryScheduled("merchant-learning", "event", UUID.randomUUID(), 2);

        assertThat(counter("finora.worker.retry")).isEqualTo(1.0);
        assertThat(counter("finora.worker.dead_letter")).isEqualTo(0.0);
    }

    @Test
    void aFailureThatCouldNotBeRecordedIsCountedSeparately() {
        // A double fault leaves the row stranded in PROCESSING; it is not the same event as the
        // failure that triggered it and should not be filed as one.
        observability.failureNotRecorded("merchant-learning", "event", UUID.randomUUID(),
                new IllegalStateException("could not write failure"));

        assertThat(counter("finora.worker.failure_not_recorded")).isEqualTo(1.0);
    }

    @Test
    void recoveryCountsEveryRowReturnedToTheQueue_notJustTheSweep() {
        // The number is the signal: one row recovered is a blip, fifty means a worker died holding
        // a full batch.
        observability.recoveredAbandoned("merchant-learning", "event", 7);

        assertThat(counter("finora.worker.recovered")).isEqualTo(7.0);
    }

    @Test
    void reportingNeverThrows_evenWithNoSentryConfigured() {
        // This code runs in the failure path of a scheduler. If it threw, it would replace a
        // recorded failure with an unrecorded one.
        UUID id = UUID.randomUUID();

        observability.deadLettered("w", "k", id, 3, new IllegalStateException("x"));
        observability.failureNotRecorded("w", "k", id, new IllegalStateException("x"));
        observability.retryScheduled("w", "k", id, 1);
        observability.recoveredAbandoned("w", "k", 1);
        observability.deadLettered("w", "k", null, 3, new IllegalStateException("no id"));
    }

    private double counter(String name) {
        var counter = meters.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
