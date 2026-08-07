package com.finora.imports.jobs;

import com.finora.entity.ImportJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Writes the per-stage timing rows for an import job, in its own transaction, without ever breaking
 * an import.
 *
 * <h2>Why {@code REQUIRES_NEW} on every method, and why a separate bean</h2>
 *
 * <p>The same reasoning {@link com.finora.imports.analysis.StatementAnalysisRecorder} documents, and
 * the same reasoning {@code ImportJobWorker} follows when it records a failure outside every
 * job-store transaction. The interesting stage to record is the one that ended by throwing, and by
 * the time the worker's catch block runs the transaction that failed has already been marked
 * rollback-only. A stage row written into it would be rolled back with it — a table that records
 * successful stages perfectly and silently loses every failure, which is the opposite of why it
 * exists.
 *
 * <p>A separate {@code @Component} because Spring proxies calls between beans, not calls a bean makes
 * to itself. {@code REQUIRES_NEW} on a private helper of the worker would be silently ignored, which
 * is precisely the failure this class is written to avoid.
 *
 * <h2>Recording must never break an import</h2>
 *
 * <p>Every method swallows its own exceptions and logs at ERROR. Losing a stage row is a measurement
 * gap; failing a user's statement import because a timing insert failed is a product outage. The log
 * line is the compensating control — a burst of them means the stage timings for that window are
 * incomplete and should not be quoted.
 *
 * <p>The worker therefore still adds no instrumentation of its own: metrics, correlation, breadcrumbs
 * and exception capture continue to come from {@code WorkerObservability}, and this class adds only
 * the persistence that a per-import trace needs and a metric registry cannot provide. A timer gives
 * a p95 across every job; this answers "which stage was slow in <em>that</em> import", and those are
 * different questions.
 */
@Component
public class ImportStageRecorder {

    private static final Logger log = LoggerFactory.getLogger(ImportStageRecorder.class);

    private final ImportJobStageRepository repository;

    public ImportStageRecorder(ImportJobStageRepository repository) {
        this.repository = repository;
    }

    /**
     * Records that a stage has begun.
     *
     * <p>Written on entry rather than on exit so a worker that dies mid-stage still leaves a row
     * naming the stage it died in. A row recorded only on exit would leave nothing at all, which is
     * the case an operator most needs.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void entered(UUID jobId, int attempt, ImportJob.Status stage) {
        try {
            if (repository.findByJobIdAndAttemptAndStage(jobId, attempt, stage).isPresent()) return;
            repository.save(ImportJobStage.entered(jobId, stage, attempt, Instant.now()));
        } catch (RuntimeException e) {
            log.error("Could not record entry into stage {} of import job {} (attempt {}) -- the "
                    + "per-stage timing for this import is incomplete", stage, jobId, attempt, e);
        }
    }

    /** Closes an open stage as having succeeded. A no-op if nothing is open for it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completed(UUID jobId, int attempt, ImportJob.Status stage) {
        close(jobId, attempt, stage, ImportJobStage.Outcome.COMPLETED);
    }

    /**
     * Closes whatever stage was still open as {@code FAILED}.
     *
     * <p>Takes no stage argument on purpose. The worker's catch block is one level above the code
     * that threw and does not reliably know which stage was in flight; asking it to remember would
     * make the recorded stage a second source of truth that can disagree with what actually ran.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failedWhereverItWas(UUID jobId, int attempt) {
        try {
            Instant now = Instant.now();
            List<ImportJobStage> open =
                    repository.findByJobIdAndAttemptAndOutcome(jobId, attempt, ImportJobStage.Outcome.RUNNING);
            open.forEach(stage -> stage.close(ImportJobStage.Outcome.FAILED, now));
            repository.saveAll(open);
        } catch (RuntimeException e) {
            log.error("Could not record the failure of an in-flight stage of import job {} (attempt "
                    + "{}) -- it will read as RUNNING for a job that is not", jobId, attempt, e);
        }
    }

    /**
     * Records stages the job passed over on its way to finishing.
     *
     * <p>Only ever called for a job that got <em>past</em> them. A job that failed in {@code PARSING}
     * did not skip {@code IMPORTING}, it never reached it, and recording those as SKIPPED would turn
     * an honest absence into a false claim. Absence and SKIPPED mean different things here and the
     * distinction is the entire value of the column.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void skipped(UUID jobId, int attempt, List<ImportJob.Status> stages) {
        for (ImportJob.Status stage : stages) {
            try {
                if (repository.findByJobIdAndAttemptAndStage(jobId, attempt, stage).isPresent()) continue;
                repository.save(ImportJobStage.skipped(jobId, stage, attempt));
            } catch (RuntimeException e) {
                log.error("Could not record stage {} of import job {} (attempt {}) as skipped",
                        stage, jobId, attempt, e);
            }
        }
    }

    private void close(UUID jobId, int attempt, ImportJob.Status stage, ImportJobStage.Outcome outcome) {
        try {
            repository.findByJobIdAndAttemptAndStage(jobId, attempt, stage).ifPresent(row -> {
                row.close(outcome, Instant.now());
                repository.save(row);
            });
        } catch (RuntimeException e) {
            log.error("Could not close stage {} of import job {} (attempt {}) as {}",
                    stage, jobId, attempt, outcome, e);
        }
    }
}
