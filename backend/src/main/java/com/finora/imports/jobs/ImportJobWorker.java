package com.finora.imports.jobs;

import com.finora.entity.ImportJob;
import com.finora.imports.ImportService;
import com.finora.imports.storage.ContentAddress;
import com.finora.imports.storage.StatementStorage;
import com.finora.observability.WorkerExecution;
import com.finora.observability.WorkerObservability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs queued statement imports off the request thread.
 *
 * <p>Phase 1 of {@code docs/engineering/enterprise-scale-milestone-design.md}. Import currently runs
 * inline: throughput is bounded by the web tier, a connection from a pool capped at 10 is held for
 * the whole parse, and a large statement gives the user a request that either takes minutes or times
 * out with the work half done.
 *
 * <h2>The observability framework is reused, not reimplemented</h2>
 *
 * <p>This worker adds no instrumentation of its own. It obtains a {@link WorkerExecution} and
 * reports lifecycle events against it, inheriting correlation, metrics, breadcrumbs, exception
 * capture, MDC management, timing and cleanup. That was an explicit design requirement for this
 * milestone, and it is the reason this class is short: everything operational already exists.
 *
 * <p>Consequently the same dashboards, alerts and runbook sections cover it. {@code worker=import}
 * appears beside {@code worker=merchant-learning} with no new panels, because the metric names are
 * fixed by the framework rather than chosen per worker.
 *
 * <h2>What this class does NOT do yet</h2>
 *
 * <p><b>Nothing enqueues jobs.</b> The upload endpoint still imports inline; switching it to return
 * 202 with a job id is the next commit, deliberately separate so the queue can be proven correct
 * before it is on a user path. Until then this worker polls an empty queue, which is exactly what
 * its metrics will show.
 *
 * <p>Retry after {@code IMPORTING} is <b>not yet idempotent</b> -- that is Phase 2's idempotency key
 * and unique constraint, and it is why {@link ImportJobStore#IN_FLIGHT_TIMEOUT} is deliberately
 * long. A job that fails after user-visible rows exist will currently retry and could duplicate
 * them. This is safe today only because nothing enqueues; it <b>blocks any multi-worker deploy</b>,
 * as the design's phase ordering says.
 */
@Component
public class ImportJobWorker {

    private static final Logger log = LoggerFactory.getLogger(ImportJobWorker.class);

    /** Low-cardinality tags, matching the merchant-learning worker's shape. */
    private static final String WORKER = "import";
    private static final String JOB_KIND = "import-job";

    private final ImportJobStore jobStore;
    private final ImportService importService;
    private final Optional<StatementStorage> storage;
    private final WorkerObservability observability;

    @Value("${app.import.queue.enabled:false}")
    private boolean enabled;

    public ImportJobWorker(ImportJobStore jobStore,
                            ImportService importService,
                            Optional<StatementStorage> storage,
                            WorkerObservability observability) {
        this.jobStore = jobStore;
        this.importService = importService;
        this.storage = storage;
        this.observability = observability;

        observability.publishQueueDepth(WORKER, JOB_KIND, jobStore::queueDepth);
        observability.publishOldestPendingAge(WORKER, JOB_KIND, jobStore::oldestQueuedAt);
    }

    /**
     * The backstop. Collects anything a nudge missed -- a process that died between commit and
     * notify, a retry whose backoff has elapsed, a job abandoned by a crashed worker.
     *
     * <p>{@code fixedDelay}, not {@code fixedRate}: the next pass starts after the previous one
     * finishes, so a slow pass cannot cause overlapping runs to pile up.
     */
    @Scheduled(fixedDelayString = "${app.import.queue.poll-interval-ms:15000}",
               initialDelayString = "${app.import.queue.initial-delay-ms:20000}")
    public void poll() {
        if (!enabled) return;
        recoverAbandoned();
        drainOnce();
    }

    /** Fire-and-forget trigger for the upload path, so an import usually starts within milliseconds
     *  rather than waiting for the next poll. Concurrent runs with the poller are safe -- that is
     *  what SKIP LOCKED is for. */
    @Async("importQueueExecutor")
    public void nudge() {
        if (!enabled) return;
        drainOnce();
    }

    /** Claims and runs one batch. Public and synchronous so tests can drive the queue
     *  deterministically rather than waiting on a scheduler. */
    public int drainOnce() {
        try (WorkerExecution execution = observability.begin(WORKER, JOB_KIND)) {
            List<UUID> claimed = jobStore.claimBatch(execution.correlationId());
            execution.claimed(claimed.size());
            for (UUID jobId : claimed) {
                runOne(execution, jobId);
            }
            return claimed.size();
        }
    }

    /** Public and synchronous for the same reason: {@link #poll} is gated by the enabled flag, so a
     *  test that switches the scheduler off cannot reach recovery through it. */
    public void recoverAbandoned() {
        try (WorkerExecution execution = observability.beginScheduled(WORKER, JOB_KIND)) {
            execution.recovered(jobStore.recoverAbandoned());
        }
    }

    /**
     * One job, start to finish.
     *
     * <p>The catch is outside every job-store transaction on purpose: by the time it runs, whatever
     * failed has already rolled back, so the failure is recorded into a clean transaction rather
     * than one already marked rollback-only.
     */
    private void runOne(WorkerExecution execution, UUID jobId) {
        ImportJob job = jobStore.find(jobId).orElse(null);
        if (job == null) {
            // The user was deleted between claim and run; the CASCADE took the job with it.
            return;
        }
        execution.started(jobId, job.getCreatedAt());
        try {
            byte[] content = readContent(job);

            jobStore.update(jobId, j -> j.advanceTo(ImportJob.Status.ANALYZING));
            var staged = importService.parseAndStageAnyFormat(
                    job.getUserId(), ImportJobService.formatOf(job.getFileName()).name(),
                    job.getFileName(), content, null);

            jobStore.update(jobId, j -> {
                // totalParsed rather than rows().size(): rows() is what staged successfully, and
                // reporting that as the total would make a statement with unparseable rows look
                // like it had fewer rows than it did.
                j.recordProgress(staged.totalParsed(), staged.rows() == null ? 0 : staged.rows().size());
                j.complete(null, Instant.now());
            });
            execution.completed(jobId);

        } catch (Exception e) {
            recordFailure(execution, jobId, e);
        }
    }

    /** Reads the uploaded bytes back from storage. A job carries an address, never the bytes -- the
     *  queue row stays small and a retry re-reads exactly the document the user uploaded. */
    private byte[] readContent(ImportJob job) {
        if (job.getContentHash() == null || job.getObjectKey() == null) {
            throw new IllegalStateException(
                    "Import job " + job.getId() + " has no content address; it cannot be retried "
                            + "without the original bytes.");
        }
        return storage
                .orElseThrow(() -> new IllegalStateException(
                        "Import job " + job.getId() + " references object storage, but no provider "
                                + "is configured."))
                .retrieve(new ContentAddress(job.getContentHash(), job.getObjectKey()));
    }

    private void recordFailure(WorkerExecution execution, UUID jobId, Exception cause) {
        try {
            boolean[] deadLettered = {false};
            int[] attempts = {0};
            jobStore.update(jobId, job -> {
                deadLettered[0] = job.recordFailure(describe(cause), Instant.now());
                attempts[0] = job.getAttemptCount();
            });
            if (deadLettered[0]) {
                log.error("Import job {} failed {} times and will not be retried automatically",
                        jobId, attempts[0], cause);
                execution.deadLettered(jobId, attempts[0], cause);
            } else {
                log.warn("Import job {} failed (attempt {}), will retry", jobId, attempts[0]);
                execution.retryScheduled(jobId, attempts[0]);
            }
        } catch (RuntimeException e) {
            // Recording the failure itself failed -- the job stays in flight and recoverAbandoned
            // will return it. Logged rather than rethrown: this runs on a scheduler, and there is
            // nobody to hand an exception to.
            log.error("Could not record the failure of import job {}", jobId, e);
            execution.failureNotRecorded(jobId, e);
        }
    }

    /** Class name plus message. The class name matters as much as the text: "storage unavailable"
     *  and "unparseable statement" need different responses from whoever reads the admin queue. */
    private static String describe(Exception e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
