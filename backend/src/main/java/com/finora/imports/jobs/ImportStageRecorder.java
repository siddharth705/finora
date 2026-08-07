package com.finora.imports.jobs;

import com.finora.entity.ImportJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Writes the per-stage timing rows for an import job, in its own transaction, without ever breaking
 * an import.
 *
 * <h2>Its own transaction, for the reason the worker documents</h2>
 *
 * <p>The stage worth recording is the one that ended by throwing, and by the time the worker's catch
 * block runs the transaction that failed has already been marked rollback-only. A stage row written
 * into it would be rolled back with it — a table that records successful stages perfectly and
 * silently loses every failure, which is the opposite of why it exists. This is the same rule
 * {@link com.finora.imports.analysis.StatementAnalysisRecorder} follows and the same one
 * {@code ImportJobWorker} keeps by catching outside every job-store transaction.
 *
 * <h2>Why a TransactionTemplate and not {@code @Transactional(REQUIRES_NEW)}</h2>
 *
 * <p><b>Because the catch has to be outside the commit, and an annotation puts it inside.</b> With
 * {@code @Transactional} the proxy commits <em>after</em> the method body returns, so a constraint
 * violation — a job whose user was deleted between claim and run, which CASCADE really does produce
 * — leaves the transaction rollback-only and surfaces as an {@code UnexpectedRollbackException} at
 * commit time, long after the in-method {@code catch} has run and reported success. The exception
 * then reaches the worker, which records it as an import failure: a telemetry problem misreported as
 * a customer's statement failing to import.
 *
 * <p>That is not hypothetical here. It is what {@code ImportStageRecorderIT.recordingAgainstAJobThat
 * NoLongerExistsIsAMeasurementGapAndNotAnOutage} caught, and it is why that test asserts on the call
 * not throwing rather than on the row not existing — the second assertion passes under both designs.
 * Running the unit of work through a template makes the catch wrap the commit, which is the only
 * place the promise "recording never breaks an import" can actually be kept.
 *
 * <h2>Recording must never break an import</h2>
 *
 * <p>Every method swallows and logs at ERROR. Losing a stage row is a measurement gap; failing a
 * user's statement import because a timing insert failed is a product outage. The log line is the
 * compensating control — a burst of them means the stage timings for that window are incomplete and
 * should not be quoted.
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
    private final TransactionTemplate transactions;

    public ImportStageRecorder(ImportJobStageRepository repository,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Records that a stage has begun.
     *
     * <p>Written on entry rather than on exit so a worker that dies mid-stage still leaves a row
     * naming the stage it died in. A row recorded only on exit would leave nothing at all, which is
     * the case an operator most needs.
     */
    public void entered(UUID jobId, int attempt, ImportJob.Status stage) {
        record(() -> {
            if (repository.findByJobIdAndAttemptAndStage(jobId, attempt, stage).isPresent()) return;
            repository.save(ImportJobStage.entered(jobId, stage, attempt, Instant.now()));
        }, "Could not record entry into stage {} of import job {} (attempt {}) -- the per-stage "
                + "timing for this import is incomplete", stage, jobId, attempt);
    }

    /** Closes an open stage as having succeeded. A no-op if nothing is open for it. */
    public void completed(UUID jobId, int attempt, ImportJob.Status stage) {
        record(() -> close(jobId, attempt, stage, ImportJobStage.Outcome.COMPLETED),
                "Could not close stage {} of import job {} (attempt {}) as completed",
                stage, jobId, attempt);
    }

    /**
     * Closes whatever stage was still open as {@code FAILED}.
     *
     * <p>Takes no stage argument on purpose. The worker's catch block is one level above the code
     * that threw and does not reliably know which stage was in flight; asking it to remember would
     * make the recorded stage a second source of truth that can disagree with what actually ran.
     */
    public void failedWhereverItWas(UUID jobId, int attempt) {
        record(() -> {
            Instant now = Instant.now();
            List<ImportJobStage> open = repository.findByJobIdAndAttemptAndOutcome(
                    jobId, attempt, ImportJobStage.Outcome.RUNNING);
            open.forEach(stage -> stage.close(ImportJobStage.Outcome.FAILED, now));
            repository.saveAll(open);
        }, "Could not record the failure of an in-flight stage of import job {} (attempt {}) -- it "
                + "will read as RUNNING for a job that is not", jobId, attempt);
    }

    /**
     * Records stages the job passed over on its way to finishing.
     *
     * <p>Only ever called for a job that got <em>past</em> them. A job that failed in {@code PARSING}
     * did not skip {@code IMPORTING}, it never reached it, and recording those as SKIPPED would turn
     * an honest absence into a false claim. Absence and SKIPPED mean different things here and the
     * distinction is the entire value of the column.
     *
     * <p>One transaction per stage rather than one for all of them: a collision on any single stage
     * would otherwise discard the rest, and these rows are independent facts.
     */
    public void skipped(UUID jobId, int attempt, List<ImportJob.Status> stages) {
        for (ImportJob.Status stage : stages) {
            record(() -> {
                if (repository.findByJobIdAndAttemptAndStage(jobId, attempt, stage).isPresent()) return;
                repository.save(ImportJobStage.skipped(jobId, stage, attempt));
            }, "Could not record stage {} of import job {} (attempt {}) as skipped",
                    stage, jobId, attempt);
        }
    }

    private void close(UUID jobId, int attempt, ImportJob.Status stage, ImportJobStage.Outcome outcome) {
        repository.findByJobIdAndAttemptAndStage(jobId, attempt, stage).ifPresent(row -> {
            row.close(outcome, Instant.now());
            repository.save(row);
        });
    }

    /**
     * Runs one unit of recording in its own transaction, and absorbs anything it throws.
     *
     * <p>The {@code catch} deliberately encloses {@link TransactionTemplate#executeWithoutResult},
     * which performs the commit — see the class comment for why an in-method catch under
     * {@code @Transactional} would not.
     */
    private void record(Runnable work, String failureMessage, Object... context) {
        try {
            transactions.executeWithoutResult(status -> work.run());
        } catch (RuntimeException e) {
            Object[] withCause = java.util.Arrays.copyOf(context, context.length + 1);
            withCause[context.length] = e;
            log.error(failureMessage, withCause);
        }
    }
}
