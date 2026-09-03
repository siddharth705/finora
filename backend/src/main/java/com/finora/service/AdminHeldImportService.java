package com.finora.service;

import com.finora.dto.HeldImportDto;
import com.finora.dto.PagedResponse;
import com.finora.entity.ImportJob;
import com.finora.exception.ApiException;
import com.finora.imports.jobs.ImportJobWorker;
import com.finora.repository.ImportJobRepository;
import com.finora.util.PageBounds;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The admin triage queue for imports held after an unclassified failure.
 *
 * <p>Mirrors {@link AdminLearningQueueService} deliberately rather than incidentally: the two
 * queues have the same shape (a worker owns the automatic lifecycle, a human owns retry and
 * give-up), and an operator who has learned one should not have to learn the other. The same
 * split applies here -- {@code ImportJobWorker} never resets an attempt budget, and this does,
 * because a human has asserted that the thing that was failing is now fixed.
 *
 * <p><b>Why this is its own permission.</b> Everything reachable from here concerns a real
 * customer's bank statement. {@code detail} hands back the raw parser error, which routinely quotes
 * the input that defeated it, and {@code reprocess} re-runs their document. That is a different
 * kind of access from "see how the import pipeline is doing", so it does not ride on
 * {@code PLATFORM_DIAGNOSTICS_VIEW}, and it is not folded into {@code LEARNING_QUEUE_MANAGE}
 * either -- clearing a merchant-learning backlog should not come with the ability to read
 * statements.
 */
@Service
public class AdminHeldImportService {

    /**
     * How many held jobs one Reprocess All may requeue.
     *
     * <p>Smaller than the learning queue's 500 on purpose. A requeued learning event is a cheap
     * database update; a requeued import is a full statement parse on a pool with one core thread,
     * so 500 of them is not a bulk action, it is a self-inflicted outage with a progress bar.
     */
    static final int MAX_REPROCESS_ALL = 50;

    private final ImportJobRepository repository;
    private final ImportJobWorker worker;
    private final AuditService auditService;

    public AdminHeldImportService(ImportJobRepository repository,
                                  ImportJobWorker worker,
                                  AuditService auditService) {
        this.repository = repository;
        this.worker = worker;
        this.auditService = auditService;
    }

    /** One page of held jobs, oldest first -- the longest-waiting user is the one to look at. */
    @Transactional(readOnly = true)
    public PagedResponse<HeldImportDto> list(int page, int size) {
        var result = repository.findByStatus(
                ImportJob.Status.HELD_FOR_REVIEW,
                PageRequest.of(PageBounds.safePage(page), PageBounds.safeSize(size > 0 ? size : 25),
                        Sort.by(Sort.Direction.ASC, "createdAt")));
        return PagedResponse.of(result.map(HeldImportDto::from));
    }

    /** Counts for the queue's header and the sidebar badge. Two indexed counts, not a report. */
    @Transactional(readOnly = true)
    public HeldImportDto.Summary summary() {
        return new HeldImportDto.Summary(
                repository.countByStatus(ImportJob.Status.HELD_FOR_REVIEW),
                repository.countByStatusAndWasHeldForReviewTrue(ImportJob.Status.QUEUED));
    }

    /**
     * One held job's full diagnostics, including the raw parser error.
     *
     * <p><b>Audited on every call, and that is the point of the method existing separately from
     * {@link #list}.</b> This is where an admin reads text drawn from a real person's bank
     * statement. Browsing the queue stays unaudited because the row form carries no customer
     * content; opening one does not, so it is recorded against the admin who opened it.
     *
     * <p>Not {@code readOnly}: it writes the audit entry.
     */
    @Transactional
    public HeldImportDto.Detail detail(UUID actingAdminId, UUID jobId) {
        ImportJob job = require(jobId);
        auditService.record(actingAdminId, "HELD_IMPORT_VIEWED", "ImportJob", jobId,
                Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", job.getUserId().toString(),
                        "failureCode", String.valueOf(job.getFailureCode())));
        return HeldImportDto.Detail.from(job);
    }

    /**
     * Sends one held job back to the queue with a fresh attempt budget.
     *
     * <p>Safe to use speculatively: if the parser bug is not actually fixed, the job fails the same
     * way and lands back in this queue. Nothing is lost by trying.
     */
    @Transactional
    public HeldImportDto reprocess(UUID actingAdminId, UUID jobId) {
        ImportJob job = require(jobId);
        requireHeld(job, "reprocessed");
        requireNoLiveDuplicate(job);

        String originalFailureCode = job.getFailureCode();
        job.returnToQueueForReprocess(Instant.now());
        repository.save(job);

        // The failure code is cleared off the entity by the transition (a stale code would describe
        // the wrong attempt on the customer's timeline), so the audit entry is the only place the
        // original reason survives. Recording it here is what makes "did fixing X actually clear
        // these?" answerable later.
        auditService.record(actingAdminId, "HELD_IMPORT_REPROCESSED", "ImportJob", jobId,
                Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", job.getUserId().toString(),
                        "originalFailureCode", String.valueOf(originalFailureCode)));

        nudgeAfterCommit();
        return HeldImportDto.from(job);
    }

    /**
     * Sends every held job back to the queue, up to {@link #MAX_REPROCESS_ALL}.
     *
     * @return how many were requeued, so the UI can say "12 reprocessed" rather than "done"
     */
    @Transactional
    public int reprocessAll(UUID actingAdminId) {
        // Counted BEFORE anything is mutated. Taking it afterwards would be wrong in a way that is
        // invisible to a mocked test: Hibernate auto-flushes the pending UPDATEs before running a
        // query against the same table, so the count would already exclude everything this call
        // just requeued, and subtracting the batch size again would double-count it away.
        long heldBefore = repository.countByStatus(ImportJob.Status.HELD_FOR_REVIEW);

        List<ImportJob> held = repository.findByStatus(
                ImportJob.Status.HELD_FOR_REVIEW,
                PageRequest.of(0, MAX_REPROCESS_ALL, Sort.by(Sort.Direction.ASC, "createdAt")))
                .getContent();
        if (held.isEmpty()) return 0;

        Instant now = Instant.now();
        // A job whose bytes are already live under another job cannot go back on the queue without
        // violating idx_import_jobs_live_content. Skipped rather than aborting the whole batch, and
        // counted, so the caller is never told "reprocessed everything" when it did not.
        List<ImportJob> eligible = held.stream().filter(this::hasNoLiveDuplicate).toList();
        eligible.forEach(job -> job.returnToQueueForReprocess(now));
        repository.saveAll(eligible);

        // One entry for the bulk action, not one per job: the thing the admin did was "reprocess
        // everything". The counts are what make it reconstructable -- including what was left
        // behind, because a silently truncated batch reads as a complete one. stillHeld covers both
        // reasons something was left: skipped as a duplicate, or beyond MAX_REPROCESS_ALL.
        auditService.record(actingAdminId, "HELD_IMPORTS_REPROCESSED_BULK", "ImportJob", null,
                Map.of("actorId", actingAdminId.toString(),
                        "count", eligible.size(),
                        "skippedDuplicates", held.size() - eligible.size(),
                        "stillHeld", Math.max(heldBefore - eligible.size(), 0)));

        nudgeAfterCommit();
        return eligible.size();
    }

    /**
     * Gives up on a held job, landing it in the plain FAILED it would have reached today.
     *
     * <p>The escape hatch the queue needs to stay useful, for the reason the learning queue's own
     * resolve gives: some statements will never parse -- a bank that publishes an image with no
     * text layer at all -- and with no way to close them the page fills with permanent noise until
     * operators stop reading it.
     *
     * <p>The user then sees the ordinary failure they would have seen without this feature. That is
     * the honest outcome: we said we would run additional checks, we ran them, and they did not
     * work.
     */
    @Transactional
    public HeldImportDto resolve(UUID actingAdminId, UUID jobId, String reason) {
        ImportJob job = require(jobId);
        requireHeld(job, "resolved");

        job.resolveWithoutFix(Instant.now());
        repository.save(job);
        auditService.record(actingAdminId, "HELD_IMPORT_RESOLVED", "ImportJob", jobId,
                Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", job.getUserId().toString(),
                        // Map.of rejects nulls, and an operator is not required to explain
                        // themselves -- the empty string keeps the entry writable either way.
                        "reason", reason == null ? "" : reason));
        return HeldImportDto.from(job);
    }

    // --- internals ----------------------------------------------------------------------------

    private ImportJob require(UUID jobId) {
        return repository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such import job."));
    }

    private static void requireHeld(ImportJob job, String verb) {
        if (job.getStatus() != ImportJob.Status.HELD_FOR_REVIEW) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only a held import can be " + verb + "; this one is " + job.getStatus() + ".");
        }
    }

    /**
     * Refuses a reprocess whose bytes are already live under a different job.
     *
     * <p>A held job is excluded from {@code idx_import_jobs_live_content} (V134), precisely so the
     * user can re-upload the statement they were told to stop worrying about. That leaves one
     * window: if they did re-upload, moving the held job back to QUEUED makes two live jobs for the
     * same (user, document) and the unique index rejects it. Caught here so the operator gets a 409
     * that explains itself, rather than a constraint violation surfacing as a 500.
     */
    private void requireNoLiveDuplicate(ImportJob job) {
        if (!hasNoLiveDuplicate(job)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This user has already re-uploaded the same statement and that import is still "
                            + "live, so this one cannot be reprocessed. Resolve it instead.");
        }
    }

    private boolean hasNoLiveDuplicate(ImportJob job) {
        if (job.getContentHash() == null) return true;
        return repository.findFirstByUserIdAndContentHashAndStatusNotInOrderByCreatedAtDesc(
                        job.getUserId(), job.getContentHash(), ImportJob.Status.TERMINAL)
                .isEmpty();
    }

    /** Wakes the worker once the requeue has committed -- the same afterCommit discipline
     *  {@code AdminLearningQueueService} and {@code ImportJobService.accept} both use. Nudging
     *  inside the transaction would hand the worker a job id it cannot yet see as QUEUED. */
    private void nudgeAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            worker.nudge();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                worker.nudge();
            }
        });
    }
}
