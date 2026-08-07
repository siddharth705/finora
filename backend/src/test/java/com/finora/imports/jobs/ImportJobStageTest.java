package com.finora.imports.jobs;

import com.finora.entity.ImportJob;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stage row's own rules, with no container: what it times, what it refuses to time, and what
 * happens when it is closed twice.
 *
 * <p>Split from {@code ImportStageRecorderIT} the same way {@code ImportJobTest} is split from
 * {@code ImportJobStoreIT} — lifecycle rules are pure and belong in a test that runs in
 * milliseconds; only persistence semantics need Postgres.
 */
class ImportJobStageTest {

    private static final UUID JOB = UUID.randomUUID();

    @Test
    void aClosedStageCarriesHowLongItTook() {
        Instant start = Instant.parse("2026-08-07T10:00:00Z");
        ImportJobStage stage = ImportJobStage.entered(JOB, ImportJob.Status.PARSING, 1, start);

        stage.close(ImportJobStage.Outcome.COMPLETED, start.plus(Duration.ofMillis(1500)));

        assertThat(stage.getOutcome()).isEqualTo(ImportJobStage.Outcome.COMPLETED);
        assertThat(stage.getDurationMs()).isEqualTo(1500L);
        assertThat(stage.getEndedAt()).isEqualTo(start.plus(Duration.ofMillis(1500)));
    }

    @Test
    void anOpenStageIsRunningAndHasNoDurationYet() {
        // The state that names which stage a dead worker died in. A row that only appeared on
        // completion would leave nothing at all in exactly that case.
        ImportJobStage stage = ImportJobStage.entered(JOB, ImportJob.Status.ANALYZING, 1, Instant.now());

        assertThat(stage.getOutcome()).isEqualTo(ImportJobStage.Outcome.RUNNING);
        assertThat(stage.getEndedAt()).isNull();
        assertThat(stage.getDurationMs()).isNull();
    }

    @Test
    void aSkippedStageIsNotTimedAtAll() {
        // Zero would be a lie with consequences: it enters every average and makes a stage that
        // never ran look instantaneous, which is the opposite of the conclusion the row exists to
        // support.
        ImportJobStage stage = ImportJobStage.skipped(JOB, ImportJob.Status.IMPORTING, 1);

        assertThat(stage.getOutcome()).isEqualTo(ImportJobStage.Outcome.SKIPPED);
        assertThat(stage.getStartedAt()).isNull();
        assertThat(stage.getEndedAt()).isNull();
        assertThat(stage.getDurationMs()).isNull();
    }

    @Test
    void closingAnAlreadyClosedStageKeepsTheFirstOutcome() {
        // Recording runs in the failure path. A recorder that objected to a duplicate close, or
        // that let a later call overwrite FAILED with COMPLETED, would turn a duplicate report into
        // a wrong one.
        Instant start = Instant.parse("2026-08-07T10:00:00Z");
        ImportJobStage stage = ImportJobStage.entered(JOB, ImportJob.Status.PARSING, 2, start);
        stage.close(ImportJobStage.Outcome.FAILED, start.plus(Duration.ofMillis(40)));

        stage.close(ImportJobStage.Outcome.COMPLETED, start.plus(Duration.ofMinutes(9)));

        assertThat(stage.getOutcome()).isEqualTo(ImportJobStage.Outcome.FAILED);
        assertThat(stage.getDurationMs()).isEqualTo(40L);
    }

    @Test
    void aClockThatWentBackwardsDoesNotProduceANegativeDuration() {
        // Instant.now() on two sides of an NTP correction is enough. A negative duration reads as a
        // stage that finished before it started, which is a bug report about the wrong thing.
        Instant start = Instant.parse("2026-08-07T10:00:00Z");
        ImportJobStage stage = ImportJobStage.entered(JOB, ImportJob.Status.PARSING, 1, start);

        stage.close(ImportJobStage.Outcome.COMPLETED, start.minus(Duration.ofSeconds(5)));

        assertThat(stage.getDurationMs()).isZero();
    }
}
