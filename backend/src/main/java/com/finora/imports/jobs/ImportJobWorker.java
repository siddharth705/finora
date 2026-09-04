package com.finora.imports.jobs;

import com.finora.entity.ImportJob;
import com.finora.exception.ErrorCode;
import com.finora.imports.ImportService;
import com.finora.imports.analysis.ImportVerificationRecorder;
import com.finora.imports.StatementUpload;
import com.finora.imports.storage.StatementContentService;
import com.finora.imports.storage.StatementIntegrityException;
import com.finora.observability.AlertSeverity;
import com.finora.observability.WorkerExecution;
import com.finora.observability.WorkerObservability;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.finora.imports.trust.HoldDecision;
import com.finora.imports.trust.TrustPredicate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p>{@link ImportStageRecorder} is not an exception to that. Metrics answer "how slow is PARSING
 * across every job", which a timer does well and a table does badly; the recorder answers "which
 * stage was slow in <b>that</b> import", which a timer cannot answer at all. The framework keeps
 * every question it was built for, and the recorder writes rows nothing in it was ever going to
 * hold.
 *
 * <h2>Where this worker stops, and what that means for replay</h2>
 *
 * <p><b>It stages; it does not confirm.</b> {@code POST /api/v1/import/jobs} enqueues, and a claimed
 * job runs {@code PARSING} then {@code ANALYZING} and completes there — see
 * {@link #LAST_STAGE_THIS_WORKER_RUNS}, with everything beyond it recorded {@code SKIPPED} rather
 * than left absent. The user still reviews the staged session and confirms it on a synchronous
 * request. So no job this worker runs has ever written a user-visible transaction row.
 *
 * <p><b>Replay is safe.</b> Phase 2 landed in {@code V67}: {@code statement_imports.import_job_id}
 * is UNIQUE, so a job returned to the queue after confirming cannot import the same statement
 * twice, and {@code transactions (statement_import_id, row_ordinal)} is UNIQUE, so a retry inside a
 * single confirm cannot insert a row twice. Both are database constraints rather than worker-side
 * checks, because a check is a read followed by a write and two workers can both read "not present"
 * before either writes. {@code ImportIdempotencyIT} asserts the rejected write against real
 * Postgres.
 *
 * <p>The first of those two is <b>armed but not yet exercised</b>, and that is worth stating rather
 * than leaving to be discovered: nothing in production sets {@code import_job_id}, because the path
 * that would — a worker that carries a job through {@code IMPORTING} — is the scope above and does
 * not exist. The constraint is in place ahead of the code that needs it, which is the ordering the
 * design asked for; it means a multi-worker deploy is no longer blocked on idempotency, not that
 * the guarantee has been observed doing anything yet. The second constraint is live for every
 * import: {@code ImportService} assigns {@code rowOrdinal} on both paths.
 *
 * <p>{@link ImportJobStore#IN_FLIGHT_TIMEOUT} stays deliberately long regardless. Its own doc gives
 * the reason that outlives Phase 2: recovering early and recovering late do not cost the same
 * amount, so it waits far longer than any real import should take.
 */
@Component
public class ImportJobWorker {

    private static final Logger log = LoggerFactory.getLogger(ImportJobWorker.class);

    /** Low-cardinality tags, matching the merchant-learning worker's shape. */
    private static final String WORKER = "import";
    private static final String JOB_KIND = "import-job";

    /**
     * How many batches one nudge drains before leaving the rest to the poller.
     *
     * <p>{@code MAX_NUDGE_PASSES * ImportJobStore.BATCH_SIZE} is exactly
     * {@link ImportJobStore#RECOVERY_BATCH_SIZE} -- one nudge clears a full recovery batch and
     * stops. Deliberately not larger: each pass is up to ten statement parses on a pool with one
     * core thread, so an unbounded loop would let one caller hold that thread for minutes while
     * other users' uploads waited behind it.
     */
    private static final int MAX_NUDGE_PASSES = 5;

    /** The furthest this worker takes a job. Everything after staging is still the user's review. */
    private static final ImportJob.Status LAST_STAGE_THIS_WORKER_RUNS = ImportJob.Status.ANALYZING;

    /**
     * The stages a completed job passed over without entering.
     *
     * <p>Recorded as {@code SKIPPED} rather than left absent, because "this stage did not run" and
     * "nobody instrumented this stage" are different facts and only one of them is actionable. It is
     * also the observation that can prove an optimisation unnecessary: someone about to speed up
     * {@code DEDUPING} on the async path can see, per job, that it never runs there.
     *
     * <p>Derived from the enum rather than listed, so it cannot name a stage that does not exist or
     * silently miss one that is added.
     */
    private static final List<ImportJob.Status> STAGES_THIS_WORKER_PASSES_OVER =
            ImportJob.Status.IN_FLIGHT.stream()
                    .filter(stage -> stage.ordinal() > LAST_STAGE_THIS_WORKER_RUNS.ordinal())
                    .sorted(java.util.Comparator.comparingInt(Enum::ordinal))
                    .toList();

    private final ImportJobStore jobStore;
    private final ImportService importService;
    private final StatementContentService statementContentService;
    private final WorkerObservability observability;
    private final ImportStageRecorder stageRecorder;
    private final ExceptionClassifier exceptionClassifier;
    private final NotificationService notificationService;
    private final ImportVerificationRecorder verificationRecorder;
    private final com.finora.service.HeldStatementService heldStatementService;

    /**
     * The deploy that parsed a statement, recorded on every job.
     *
     * <p>Railway injects RAILWAY_GIT_COMMIT_SHA on every deploy, the same source
     * {@code sentry.release} already uses, so this needs no dashboard configuration. Blank
     * locally and in tests, which is honest -- there is no deploy to name.
     */
    @Value("${app.parser-version:${RAILWAY_GIT_COMMIT_SHA:}}")
    private String parserVersion;

    @Value("${app.import.queue.enabled:false}")
    private boolean enabled;

    public ImportJobWorker(ImportJobStore jobStore,
                            ImportService importService,
                            StatementContentService statementContentService,
                            WorkerObservability observability,
                            ImportStageRecorder stageRecorder,
                            ExceptionClassifier exceptionClassifier,
                            NotificationService notificationService,
                            ImportVerificationRecorder verificationRecorder,
                            com.finora.service.HeldStatementService heldStatementService) {
        this.jobStore = jobStore;
        this.importService = importService;
        this.statementContentService = statementContentService;
        this.observability = observability;
        this.stageRecorder = stageRecorder;
        this.exceptionClassifier = exceptionClassifier;
        this.notificationService = notificationService;
        this.verificationRecorder = verificationRecorder;
        this.heldStatementService = heldStatementService;

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

    /**
     * Fire-and-forget trigger for the upload path, so an import usually starts within milliseconds
     * rather than waiting for the next poll. Concurrent runs with the poller are safe -- that is
     * what SKIP LOCKED is for.
     *
     * <p><b>Drains until a pass comes back short, rather than exactly once.</b> A pass that claims
     * a full {@link ImportJobStore#BATCH_SIZE} is evidence more work is waiting, and stopping there
     * left the remainder for the next poll even though a worker had just been woken and the queue
     * was demonstrably not empty. The path that makes this matter is
     * {@link ImportJobStore#recoverAbandoned()}: it returns up to
     * {@link ImportJobStore#RECOVERY_BATCH_SIZE} jobs to QUEUED at once, and a single drain of ten
     * left the rest waiting out a poll interval each. Ordinary uploads were never the problem --
     * each one registers its own nudge, so a burst of them already drains itself.
     *
     * <p>Bounded far more tightly than {@code MerchantLearningEventWorker}'s equivalent, and for a
     * concrete reason: a pass there is fifty small writes, whereas a pass here is up to ten whole
     * statement parses on a pool with one core thread. {@link #MAX_NUDGE_PASSES} is sized to clear
     * exactly one full recovery batch and no more; a genuine backlog beyond that is the poller's
     * job, which is what it is for.
     */
    @Async("importQueueExecutor")
    public void nudge() {
        if (!enabled) return;
        int passes = 1;
        while (drainOnce() == ImportJobStore.BATCH_SIZE && passes++ < MAX_NUDGE_PASSES) {
            // A full batch means the queue had at least one more job than this pass could take.
        }
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
     * than one already marked rollback-only. The stage and verification recorders sit under the same
     * rule -- each opens its own {@code REQUIRES_NEW} transaction and swallows its own exceptions,
     * so a telemetry write can neither be undone by an import's rollback nor cause one.
     *
     * <p>Stages are opened before their work and closed after it. Opening first is what makes a
     * worker that dies mid-stage leave a row naming the stage it died in; closing on exit is what
     * gives the row a duration. A stage recorded only on completion would say nothing about exactly
     * the case an operator is looking for.
     */
    private void runOne(WorkerExecution execution, UUID jobId) {
        ImportJob job = jobStore.find(jobId).orElse(null);
        if (job == null) {
            // The user was deleted between claim and run; the CASCADE took the job with it.
            return;
        }
        // Read once, after markClaimed has already incremented it, so every stage row for this pass
        // carries the same attempt number and a retry's stages sit beside the first attempt's
        // rather than overwriting them.
        int attempt = job.getAttemptCount();
        execution.started(jobId, job.getCreatedAt());
        try {
            // The job is already PARSING -- markClaimed put it there -- so this opens the stage the
            // status column has been asserting all along and nothing has ever timed.
            stageRecorder.entered(jobId, attempt, ImportJob.Status.PARSING);
            byte[] content = readContent(job);
            stageRecorder.completed(jobId, attempt, ImportJob.Status.PARSING);

            // Between stages, so a cancel that lands during a long parse takes effect at the next
            // boundary rather than at the end. Cancelling cannot interrupt PDFBox mid-document --
            // that needs cooperative cancellation plumbed through the parser -- so what this
            // guarantees is the part the user cares about: a cancelled job never produces a staged
            // session, and never turns up as something waiting to be reviewed.
            abortIfCancelled(jobId);

            jobStore.update(jobId, j -> j.advanceTo(ImportJob.Status.ANALYZING));
            stageRecorder.entered(jobId, attempt, ImportJob.Status.ANALYZING);
            // The session-creating staging path, NOT parseAndStageAnyFormat. That one returns a
            // response and persists nothing, so a job used to complete with a row count and no
            // session: the progress endpoint had nowhere to send the user and every staged row was
            // discarded. Recording verification belongs to that path too now, against the analysis
            // row it writes -- and ImportTraceService reads the analysis anchor in preference to the
            // job one, so recording here as well would write rows nothing ever reads.
            StagedForJob staged = stage(job, content);
            stageRecorder.completed(jobId, attempt, ImportJob.Status.ANALYZING);

            // Checked again before completing, because complete() sets COMPLETED unconditionally and
            // would otherwise overwrite a cancel that arrived while the parse was running.
            abortIfCancelled(jobId);

            VerificationTelemetry telemetry =
                    VerificationTelemetry.from(staged.verificationReports());

            // The trust gate. Everything above this line was about extraction failing; this is
            // about extraction succeeding and being distrusted anyway, on evidence the pipeline
            // already computed. UTC rather than the server's zone so the future-period rule cannot
            // depend on where this runs.
            HoldDecision decision = TrustPredicate.evaluate(
                    staged.verificationReports(), staged.statementPeriods(),
                    LocalDate.now(ZoneOffset.UTC));

            // Created before the job transition so the job row can carry the hold's id.
            //
            // Failure here must not fail the import -- the same rule V62 states for merchant
            // learning -- but it must fail CLOSED: a failed hold still holds, with no review
            // record, rather than completing. The database being unavailable is not evidence the
            // extraction was fine, and completing would silently release exactly the import this
            // exists to stop. createHold is idempotent on the job id, so a retried pass reuses the
            // review that already exists instead of colliding with it.
            UUID heldStatementId = null;
            if (decision.hold()) {
                try {
                    heldStatementId = heldStatementService
                            .createHold(job, staged, decision, parserVersion).getId();
                } catch (RuntimeException e) {
                    log.error("Could not create the hold record for import job {}; holding the "
                            + "import anyway, with no review record to work from", jobId, e);
                }
            }
            final UUID heldId = heldStatementId;

            jobStore.update(jobId, j -> {
                // totalParsed rather than the staged row count: the latter is what staged
                // successfully, and reporting it as the total would make a statement with
                // unparseable rows look like it had fewer rows than it did.
                j.recordProgress(staged.totalParsed(), staged.stagedRows());
                if (decision.hold()) {
                    // Keeps the session: the rows are real, and comparing them against the
                    // document is the entire review.
                    j.holdForTrustReview(staged.sessionId(), heldId, Instant.now());
                } else {
                    j.complete(staged.sessionId(), Instant.now());
                }
                // Evidence only, and recorded either way -- it is what a reviewer works from, and
                // the telemetry readout's denominator would otherwise quietly exclude exactly the
                // imports worth looking at.
                j.recordVerificationTelemetry(
                        telemetry.reliabilityStatus(), telemetry.textSource(),
                        telemetry.isEmpty() ? null : telemetry.headerReconstructionUncertain(),
                        telemetry.isEmpty() ? null : telemetry.findingsCount(),
                        telemetry.isEmpty() ? null : telemetry.failedCount(),
                        telemetry.isEmpty() ? null : telemetry.warningCount(),
                        parserVersion);
                if (!decision.hold()) {
                    // A held import is not finished, so it announces nothing -- the same rule the
                    // other hold follows. Telling someone their statement is ready and then
                    // withholding it would be worse than saying nothing.
                    notifyIfPreviouslyHeld(j, staged.bankName());
                }
            });
            recordVerificationFindings(jobId, staged);
            // Only on the success path. A job that failed in PARSING did not skip IMPORTING, it
            // never reached it, and recording that as SKIPPED would turn an honest absence into a
            // false claim.
            stageRecorder.skipped(jobId, attempt, STAGES_THIS_WORKER_PASSES_OVER);
            execution.completed(jobId);

        } catch (ImportJobCancelledException e) {
            // Not a failure, and deliberately ahead of the catch below: recordFailure would put the
            // job back to QUEUED for a retry, so falling through to it would resurrect the very work
            // the user asked to stop. The row is already CANCELLED -- the endpoint set it -- so
            // there is nothing to write here except closing the stage this pass was in.
            stageRecorder.failedWhereverItWas(jobId, attempt);
            log.info("Import job {} abandoned mid-pass: cancelled by its owner.", jobId);
            execution.completed(jobId);

        } catch (Exception e) {
            // Before recordFailure, so the stage row is closed even if recording the job's own
            // failure then fails -- the double-fault case that leaves the job stranded. A stranded
            // job whose stage says FAILED at ANALYZING is diagnosable; one whose stage still says
            // RUNNING is indistinguishable from a worker that is still working.
            stageRecorder.failedWhereverItWas(jobId, attempt);
            recordFailure(execution, jobId, e);
        }
    }

    /**
     * Stages the document, by the same two paths the synchronous endpoints use.
     *
     * <p>BH-029: read from {@code import_jobs.source_format}, which the upload endpoint wrote after
     * validating the bytes against it — so the parser that runs here is the one the file was
     * accepted for, as a recorded fact rather than as two call sites evaluating
     * {@link ImportJobService#formatOf} against the same filename and agreeing. PDF gets a null
     * password: a protected document cannot be queued, because there is nobody to ask for the
     * password minutes later.
     */
    private StagedForJob stage(ImportJob job, byte[] content) throws java.io.IOException {
        return StatementUpload.Format.PDF.name().equals(job.getSourceFormat())
                ? StagedForJob.of(importService.parseAndStagePdfWithSession(
                        job.getUserId(), job.getFileName(), content, null))
                : StagedForJob.of(importService.parseAndStageWithSession(
                        job.getUserId(), job.getFileName(), content));
    }

    /**
     * Ends this pass if the job's owner cancelled it while the worker held it.
     *
     * <p>A re-read rather than a flag on the entity this method already has: the cancel happened on
     * another thread, in another transaction, after that entity was loaded. One extra query per
     * stage boundary, not per row.
     */
    private void abortIfCancelled(UUID jobId) {
        boolean cancelled = jobStore.find(jobId)
                .map(j -> j.getStatus() == ImportJob.Status.CANCELLED)
                .orElse(false);
        if (cancelled) throw new ImportJobCancelledException(jobId);
    }

    /**
     * Reads the uploaded bytes back from storage. A job carries an address, never the bytes -- the
     * queue row stays small and a retry re-reads exactly the document the user uploaded.
     *
     * <p>BH-045: routes through {@link StatementContentService#read}, the same verified path
     * {@code ImportService}/{@code StatementImportService} already use for every other statement
     * read, rather than calling {@code StatementStorage.retrieve} directly. This used to skip
     * {@link com.finora.imports.storage.ContentAddress#requireMatches} entirely -- the SHA-256
     * check every other read goes through -- so a corrupted or tampered object in R2 would have
     * been silently parsed and imported with no detection on this specific path. {@code ImportJob}
     * now implements {@code StoredStatement} for exactly this reason (see that class's own doc).
     * The no-address check below stays first and separate: it's a job-specific, more actionable
     * error ("cannot be retried") than {@code StatementContentService}'s own generic message for
     * the same underlying condition.
     *
     * <p>A hash mismatch surfaces as {@link com.finora.imports.storage.StatementIntegrityException}
     * -- see {@link ExceptionClassifier#classify} for why that is deliberately not treated the same
     * as an ordinary {@code StatementStorageException}.
     */
    private byte[] readContent(ImportJob job) {
        if (job.getContentHash() == null || job.getObjectKey() == null) {
            throw new IllegalStateException(
                    "Import job " + job.getId() + " has no content address; it cannot be retried "
                            + "without the original bytes.");
        }
        return statementContentService.read(job);
    }

    /**
     * Whether a dead-lettered failure belongs in the admin triage queue.
     *
     * <p>The queue exists for one thing: a parser gap on a statement layout this codebase has not
     * seen. Fix the parser, reprocess, done. Two conditions establish that -- the classification
     * was RETRY_ONCE_THEN_ALERT (nothing recognised the exception) and the attempt budget is spent.
     *
     * <p><b>StatementIntegrityException is excluded, despite satisfying both.</b> Those two
     * conditions quietly encode a third one -- that a human can fix something and reprocess -- and
     * this exception is the counterexample. It means storage returned bytes that do not hash to
     * what the row claims: a wrong object for a key, bit-rot, a bad restore.
     *
     * <p>Every other part of the system already treats that as a storage correctness incident
     * rather than remediable import work: the classifier refuses to give it the five-attempt RETRY
     * budget a plain outage gets, the exception's own doc says to investigate the provider, and the
     * alert fires at ERROR. This routing was the one place still treating it as parser triage.
     *
     * <ul>
     *   <li><b>Its only action cannot work.</b> Reprocess re-reads the same key and gets the same
     *       wrong bytes. The exception's own message says "investigate the storage provider before
     *       retrying" -- the queue would render a button doing precisely that.</li>
     *   <li><b>The user message would be a promise we cannot keep.</b> "We'll notify you once it's
     *       ready" is true of a parser gap. Here the stored document may be gone for good, and the
     *       honest outcome is the ordinary failure, which is what FAILED already gives them.</li>
     *   <li><b>It is usually not one statement.</b> A bad migration corrupts objects in bulk. Five
     *       thousand rows in a parser-remediation queue disguise one storage incident as a backlog
     *       of unrelated tickets.</li>
     *   <li><b>Excluding it costs no visibility.</b> The DEAD_LETTERED branch below still alerts at
     *       {@link AlertSeverity#ERROR} either way -- that path does not depend on the hold.</li>
     * </ul>
     *
     * <p>Nothing here can self-heal, which is what makes exclusion safe rather than merely tidier.
     * Encryption is AES-256-GCM, so a wrong key throws rather than yielding wrong plaintext;
     * async job objects are never compressed, so no decode step can corrupt them; and keys are
     * content-addressed, so a stale or missing replica raises NoSuchKeyException (a plain RETRY),
     * never a different document under the same key. The one speculative retry this policy allows
     * has also already been spent by the time this is asked.
     *
     * <p>The exclusion itself is expressed as {@link #isOperatorRemediable}, not as an inline
     * {@code instanceof}: the rule is "only operator-remediable failures enter triage", and a
     * negated type check states the exception rather than the rule. The next counterexample of
     * this class then has an obvious home.
     */
    private static boolean holdsForTriage(ErrorCode.RetryPolicy policy,
                                          ImportJob.FailureOutcome outcome, Exception cause) {
        return outcome == ImportJob.FailureOutcome.DEAD_LETTERED
                && policy == ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT
                && isOperatorRemediable(cause);
    }

    /**
     * Whether a human could plausibly fix the cause and have a reprocess succeed.
     *
     * <p>This is the invariant the triage queue actually depends on, stated once instead of being
     * implied by a list of exclusions. Hold-for-review assumes an operator can take corrective
     * action and then reprocess the import successfully. A parser gap satisfies that: fix the
     * parser, reprocess, done.
     *
     * <p>{@link StatementIntegrityException} does not. It is a storage correctness incident --
     * storage returned bytes that do not hash to what the row claims -- and reprocessing the same
     * object cannot succeed until the underlying storage problem is corrected, which is not
     * something the queue's Reprocess button does. Its own message says so: "investigate the
     * storage provider before retrying".
     *
     * <p><b>Asks {@link ExceptionClassifier#isIntegrityFailure} rather than testing the type
     * here.</b> That method is the codebase's single definition of an integrity failure, cause
     * chain and all; this one used to keep its own, and two independent answers to the same
     * question agree only by luck. The failure that discipline prevents is not hypothetical --
     * wrapping is idiomatic in this pipeline, and an integrity failure wrapped in its own parent
     * type would otherwise read as a plain storage outage to the classifier while still reading as
     * non-remediable here.
     */
    private static boolean isOperatorRemediable(Throwable cause) {
        return !ExceptionClassifier.isIntegrityFailure(cause);
    }

    /**
     * Persists the per-rule findings this import produced, so a trust rule can be measured before
     * it is ever enforced.
     *
     * <p>{@code recordForJob} has existed unused since the observability work: only
     * {@code recordForAnalysis} was ever wired, which keys on an analysis session, so the admin
     * analysis path accumulated history and the real user import path accumulated none. That is why
     * "how often would this gate fire?" is currently unanswerable.
     *
     * <p><b>Outside the job's own transaction, and swallowing its own failures.</b> Recording
     * telemetry must never be able to fail an import -- the same rule V62 states for merchant
     * learning, that "failure can never roll back an import". A statement that parsed correctly
     * being rejected because a diagnostic write failed would be a strictly worse outcome than the
     * blindness this fixes.
     */
    private void recordVerificationFindings(UUID jobId, StagedForJob staged) {
        if (staged.verificationReports().isEmpty()) {
            return;
        }
        try {
            verificationRecorder.recordForJob(jobId, staged.verificationReports());
        } catch (RuntimeException e) {
            log.warn("Could not record verification findings for import job {}; the import itself "
                    + "is unaffected", jobId, e);
        }
    }

    /**
     * Closes the loop with a user who was told we were running additional checks.
     *
     * <p>Only a job that was previously held notifies. An ordinary import that succeeds first time
     * sends nothing -- we never asked that user to wait, so there is nothing to follow up on, and a
     * notification per successful import would be noise on the one path that already shows its own
     * result on screen.
     *
     * <p>Called inside {@code jobStore.update}'s transaction on purpose. {@code
     * NotificationService.request} is a transactional-outbox write, so sharing the transaction that
     * marks the job COMPLETED is what makes "the import landed" and "the user gets told" atomic:
     * neither can happen without the other. The dispatcher sends it afterwards, off this thread.
     *
     * <p>The notification key is derived from the job id, so a redelivery -- a retried pass, a
     * recovered worker -- collides on the outbox's idempotency key rather than sending twice.
     *
     * <p>NORMAL rather than HIGH or CRITICAL: those are reserved for security events per the
     * notification platform's frozen design, and an import finishing is not one.
     */
    private void notifyIfPreviouslyHeld(ImportJob job, String bankName) {
        if (!job.wasHeldForReview()) {
            return;
        }
        notificationService.request(NotificationRequest.of(
                job.getUserId(),
                NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL,
                NotificationPriority.NORMAL,
                "IMPORT_READY_" + job.getId(),
                Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                // The template reads "Your {{bank}} statement is ready", so this is the parser's
                // own detected bank name -- the only moment it is in hand, since the job itself
                // never learns it. "bank" is the fallback when the parser could not name one,
                // giving "Your bank statement is ready"; the fallback lives here rather than in
                // StagedForJob because it is a property of this template, not of staging. A
                // missing param would render "{{bank}}" literally to the customer.
                Map.of("bank", bankName == null || bankName.isBlank() ? "bank" : bankName)));
    }

    private void recordFailure(WorkerExecution execution, UUID jobId, Exception cause) {
        try {
            // Classified once, outside the update lambda: classification reads nothing from the
            // database and doesn't need the job's own transaction, and the lambda may not even run
            // (jobStore.update is a no-op if the job was deleted between claim and failure).
            ErrorCode.RetryPolicy policy = exceptionClassifier.classify(cause);
            // The same rule ImportService.recordParseFailure uses for
            // StatementAnalysisSession.failureCode -- shared via ErrorCode.failureCodeOf rather
            // than duplicated, so the two write sites cannot drift the day only one of them
            // changes (Premium Import Reliability v1, §3.1).
            String failureCode = ErrorCode.failureCodeOf(cause);
            ImportJob.FailureOutcome[] outcome = {ImportJob.FailureOutcome.RETRY_SCHEDULED};
            int[] attempts = {0};
            jobStore.update(jobId, job -> {
                outcome[0] = job.recordFailure(describe(cause), failureCode, policy, Instant.now());
                attempts[0] = job.getAttemptCount();
                // A dead-lettered unclassified failure is the one case that is plausibly a genuine
                // parser gap rather than a user error or an infrastructure blip. Hold it for triage
                // instead of handing the user a bare FAILED they can do nothing about.
                //
                // Inside the update lambda, deliberately: this is where the managed entity is, and
                // the surrounding REQUIRES_NEW transaction is what persists it. Mutating the job
                // after this block returns would change a detached object and write nothing.
                //
                // outcome[0] keeps its DEAD_LETTERED value, so the switch below still fires the
                // alert. Holding for triage adds a destination; it does not replace engineering
                // visibility.
                if (holdsForTriage(policy, outcome[0], cause)) {
                    job.holdForReview(failureCode, Instant.now());
                }
            });
            switch (outcome[0]) {
                case DEAD_LETTERED -> {
                    log.error("Import job {} failed {} times and will not be retried automatically",
                            jobId, attempts[0], cause);
                    execution.deadLettered(jobId, attempts[0], cause, severityFor(policy));
                }
                case RETRY_SCHEDULED -> {
                    log.warn("Import job {} failed (attempt {}), will retry", jobId, attempts[0]);
                    execution.retryScheduled(jobId, attempts[0]);
                }
                // The owner cancelled the job while this pass was inside it, and the pass then hit
                // an exception on its way out -- most often the IllegalStateException complete()
                // throws precisely to refuse a cancelled job. Not a failure, and emphatically not a
                // retry: this is the case that used to resurrect the cancellation. Reported as a
                // completed pass, because the pass did exactly what it should have.
                case ALREADY_FINISHED -> {
                    log.info("Import job {} reached a terminal state while this pass was running; "
                            + "leaving it alone. The pass ended with: {}", jobId, describe(cause));
                    execution.completed(jobId);
                }
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

    /**
     * How loudly a dead-letter of this policy should reach a human -- Premium Import Reliability
     * v1, §5.6. The translation from {@code ErrorCode.RetryPolicy} (the import domain) to {@link
     * AlertSeverity} (the platform observability contract every worker shares) belongs here, at the
     * one place that already knows both: {@code WorkerExecution}/{@code WorkerObservability} stay
     * generic rather than depending on this worker's own failure vocabulary.
     */
    private static AlertSeverity severityFor(ErrorCode.RetryPolicy policy) {
        return switch (policy) {
            // A known, permanent, expected failure -- a corrupt PDF, a locked document. Already
            // visible to the customer (Sprint 1's failure UX) and to support (the failure-analytics
            // query); paging an engineer for every one of them buries the alerts that are actually
            // theirs to act on.
            case FAIL_FAST -> AlertSeverity.NONE;
            // An infrastructure dependency failed for the full backoff window. Usually not Finora's
            // own code -- check the dependency's health -- so worth knowing, not worth waking
            // someone.
            case RETRY -> AlertSeverity.WARNING;
            // Nothing recognized this exception, it was retried once anyway, and it failed again.
            // The one case that is plausibly a genuine, unclassified Finora bug.
            case RETRY_ONCE_THEN_ALERT -> AlertSeverity.ERROR;
        };
    }
}
