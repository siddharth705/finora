package com.finora.entity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        return new ImportJob(UUID.randomUUID(), "statement.csv", "hash", "objects/key");
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
                    .isFalse();
            assertThat(job.getStatus()).isEqualTo(ImportJob.Status.QUEUED);
        }

        job.markClaimed("worker", Instant.now());
        assertThat(job.recordFailure("boom", Instant.now())).isTrue();
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.FAILED);
    }

    @Test
    void recoveryReturnsAJobWithoutSpendingAnAttempt() {
        // A worker that died proved nothing about the job. Charging it would dead-letter perfectly
        // good work after five unlucky deploys.
        ImportJob job = job();
        job.markClaimed("worker", Instant.now());
        int afterClaim = job.getAttemptCount();

        job.returnToQueue("abandoned", Instant.now());

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.QUEUED);
        assertThat(job.getAttemptCount()).isLessThan(afterClaim);
        assertThat(job.getStartedAt()).as("no longer in flight").isNull();
    }

    @Test
    void completingClearsTheLastError() {
        // Otherwise a job that failed once and then succeeded shows an error forever, and the admin
        // queue reads as though something is still wrong.
        ImportJob job = job();
        job.markClaimed("worker", Instant.now());
        job.recordFailure("transient", Instant.now());
        job.markClaimed("worker", Instant.now());

        job.complete(UUID.randomUUID(), Instant.now());

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.COMPLETED);
        assertThat(job.getLastError()).isNull();
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
}
