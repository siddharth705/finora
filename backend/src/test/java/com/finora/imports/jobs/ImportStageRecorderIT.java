package com.finora.imports.jobs;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.imports.StatementUpload;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Per-stage timing against a real Postgres.
 *
 * <p>Two classes of property, both of which fail silently if they break. The <b>persistence</b> half
 * is the unique key on {@code (job_id, attempt, stage)}: without it a retry's PARSING overwrites the
 * first attempt's and the table reports the last attempt's timing as the job's, which looks like a
 * working table. The <b>fail-safe</b> half is that no recorder call can throw — it runs beside a
 * customer's import, and an exception here would fail an upload over a measurement.
 *
 * <p>Testcontainers rather than a mock repository, for the reason {@code StatementAnalysisRecorderIT}
 * gives: a mocked repository cannot violate a constraint, so a test built on one proves the recorder
 * calls {@code save} and nothing about what the database does with it.
 *
 * <p><b>The scheduler is off for this class</b>, matching {@code ImportJobStoreIT}: a poller firing
 * mid-test would claim these jobs and record stages of its own.
 */
@TestPropertySource(properties = "app.import.queue.enabled=false")
class ImportStageRecorderIT extends AbstractIntegrationTest {

    @Autowired private ImportStageRecorder recorder;
    @Autowired private ImportJobStageRepository stageRepository;
    @Autowired private ImportJobStore jobStore;
    @Autowired private UserRepository userRepository;

    private ImportJob job() {
        User user = new User();
        user.setEmail("stage-recorder-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Stage Recorder IT User");
        user.setPhoneVerified(true);
        User saved = userRepository.save(user);
        return jobStore.enqueue(saved.getId(), "statement.csv", "hash-" + UUID.randomUUID(), "objects/x",
                StatementUpload.Format.CSV);
    }

    @Test
    void aStageIsOpenedOnEntryAndCarriesADurationOnceClosed() {
        ImportJob job = job();

        recorder.entered(job.getId(), 1, ImportJob.Status.PARSING);
        var whileRunning = stageRepository.findByJobIdOrderByRecordedAtAsc(job.getId());
        recorder.completed(job.getId(), 1, ImportJob.Status.PARSING);

        assertThat(whileRunning).singleElement()
                .as("the row exists before the stage ends -- that is what names the stage a dead "
                    + "worker died in")
                .satisfies(stage -> assertThat(stage.getOutcome()).isEqualTo(ImportJobStage.Outcome.RUNNING));

        assertThat(stageRepository.findByJobIdOrderByRecordedAtAsc(job.getId())).singleElement()
                .satisfies(stage -> {
                    assertThat(stage.getOutcome()).isEqualTo(ImportJobStage.Outcome.COMPLETED);
                    assertThat(stage.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0L);
                    assertThat(stage.getEndedAt()).isNotNull();
                });
    }

    @Test
    void aRetryGetsItsOwnRowRatherThanOverwritingTheFirstAttempt() {
        // The property the unique key exists for. Merged attempts would report the last one's
        // timing under the job's name, and "attempt 3 was slower than attempt 1" -- a degrading
        // dependency rather than a blip -- would be unobservable.
        ImportJob job = job();

        recorder.entered(job.getId(), 1, ImportJob.Status.PARSING);
        recorder.failedWhereverItWas(job.getId(), 1);
        recorder.entered(job.getId(), 2, ImportJob.Status.PARSING);
        recorder.completed(job.getId(), 2, ImportJob.Status.PARSING);

        List<ImportJobStage> stages = stageRepository.findByJobIdOrderByRecordedAtAsc(job.getId());
        assertThat(stages).hasSize(2);
        assertThat(stages).extracting(ImportJobStage::getAttempt).containsExactly(1, 2);
        assertThat(stages).extracting(ImportJobStage::getOutcome)
                .containsExactly(ImportJobStage.Outcome.FAILED, ImportJobStage.Outcome.COMPLETED);
    }

    @Test
    void aFailureClosesWhicheverStageWasOpenAndLeavesFinishedOnesAlone() {
        // The worker's catch block is a level above the code that threw and does not reliably know
        // which stage was in flight. Asking it to remember would make the recorded stage a second
        // source of truth that can disagree with what actually ran.
        ImportJob job = job();
        recorder.entered(job.getId(), 1, ImportJob.Status.PARSING);
        recorder.completed(job.getId(), 1, ImportJob.Status.PARSING);
        recorder.entered(job.getId(), 1, ImportJob.Status.ANALYZING);

        recorder.failedWhereverItWas(job.getId(), 1);

        List<ImportJobStage> stages = stageRepository.findByJobIdOrderByRecordedAtAsc(job.getId());
        assertThat(stages).extracting(s -> s.getStage() + ":" + s.getOutcome())
                .containsExactly("PARSING:COMPLETED", "ANALYZING:FAILED");
    }

    @Test
    void aSkippedStageIsRecordedWithNoTimingAtAll() {
        // The Evidence Rule: a stage that did not run has to be distinguishable from a stage nobody
        // instrumented, and only a row can say so. This is also the observation that can prove an
        // optimisation unnecessary -- DEDUPING is not slow on this path because it never runs.
        ImportJob job = job();

        recorder.skipped(job.getId(), 1, List.of(ImportJob.Status.DEDUPING, ImportJob.Status.IMPORTING));

        assertThat(stageRepository.findByJobIdOrderByRecordedAtAsc(job.getId()))
                .hasSize(2)
                .allSatisfy(stage -> {
                    assertThat(stage.getOutcome()).isEqualTo(ImportJobStage.Outcome.SKIPPED);
                    assertThat(stage.getStartedAt()).isNull();
                    assertThat(stage.getDurationMs())
                            .as("zero would enter every average and make a stage that never ran "
                                + "look instantaneous")
                            .isNull();
                });
    }

    @Test
    void enteringTheSameStageTwiceDoesNotDoubleTheTiming() {
        ImportJob job = job();

        recorder.entered(job.getId(), 1, ImportJob.Status.PARSING);
        recorder.entered(job.getId(), 1, ImportJob.Status.PARSING);

        assertThat(stageRepository.findByJobIdOrderByRecordedAtAsc(job.getId())).hasSize(1);
    }

    @Test
    void recordingAgainstAJobThatNoLongerExistsIsAMeasurementGapAndNotAnOutage() {
        // A user deleted between claim and run takes their jobs with them (CASCADE), so this is a
        // real production sequence rather than a contrived one. The foreign key rejects the insert;
        // what must not happen is that rejection reaching the worker and failing the pass.
        //
        // This is the assertion the flush inside the recorder exists for: REQUIRES_NEW commits
        // after the method returns, so without it the violation would surface outside the catch.
        UUID ghost = UUID.randomUUID();

        assertThatCode(() -> {
            recorder.entered(ghost, 1, ImportJob.Status.PARSING);
            recorder.completed(ghost, 1, ImportJob.Status.PARSING);
            recorder.failedWhereverItWas(ghost, 1);
            recorder.skipped(ghost, 1, List.of(ImportJob.Status.LEARNING));
        }).doesNotThrowAnyException();

        assertThat(stageRepository.findByJobIdOrderByRecordedAtAsc(ghost)).isEmpty();
    }
}
