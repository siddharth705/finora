package com.finora.imports.jobs;

import com.finora.entity.ImportJob;
import com.finora.repository.ImportJobRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The durable import queue's write side.
 *
 * <h2>enqueue joins the caller's transaction, and that is the whole point</h2>
 *
 * <p>{@link #enqueue} deliberately has no {@code @Transactional} of its own, so it participates in
 * whatever transaction the upload endpoint has open. ADR-003 records why this matters: if the job
 * row committed independently of the work that created it, the two orderings both break.
 *
 * <table>
 *   <tr><th>Order</th><th>Failure</th></tr>
 *   <tr><td>Enqueue, then commit</td>
 *       <td>Upload rolls back, job is already queued, a worker imports a statement the user never
 *           successfully uploaded</td></tr>
 *   <tr><td>Commit, then enqueue</td>
 *       <td>Process dies between the two and the upload is accepted with no record of work to
 *           do</td></tr>
 * </table>
 *
 * <p>Neither is fixable with retries, because the failure is the absence of atomicity rather than a
 * transient error. Writing the row inside the caller's transaction makes both impossible:
 * <b>the upload and the work to do commit together or neither does.</b> This is the same guarantee
 * {@code MerchantLearningEventPublisher.enqueue()} already provides for learning events.
 *
 * <h2>Claiming runs in its own transaction</h2>
 *
 * <p>{@link #claimBatch} is {@code REQUIRES_NEW} and short: it holds row locks only long enough to
 * flip status, then commits. The work itself happens outside that transaction, so a slow import
 * never holds a lock -- what keeps other workers off the rows afterwards is the status, not the
 * lock.
 */
@Service
public class ImportJobStore {

    /** How many jobs one poll claims. Bounded so a backlog drains steadily rather than one worker
     *  holding a connection from a pool capped at 10 for an unbounded stretch. */
    static final int BATCH_SIZE = 10;

    static final int RECOVERY_BATCH_SIZE = 50;

    /**
     * How long a job may sit in flight before it is assumed abandoned.
     *
     * <p>Generous, and asymmetric on purpose. Recovering too early re-runs an import that is
     * actually still going, which before Phase 2's idempotency key means duplicated financial data.
     * Recovering too late means a delay. Those costs are not comparable, so this waits far longer
     * than any real import should take.
     */
    static final Duration IN_FLIGHT_TIMEOUT = Duration.ofMinutes(30);

    private final ImportJobRepository repository;

    public ImportJobStore(ImportJobRepository repository) {
        this.repository = repository;
    }

    /**
     * Records work to do, inside the caller's transaction.
     *
     * <p><b>Do not add {@code @Transactional} here.</b> See the class comment: independence is
     * precisely the property that breaks it.
     */
    public ImportJob enqueue(UUID userId, String fileName, String contentHash, String objectKey) {
        return repository.save(new ImportJob(userId, fileName, contentHash, objectKey));
    }

    /**
     * Claims a batch and marks it in flight.
     *
     * <p>Returns the ids rather than the entities: the work happens outside this transaction, and
     * handing back managed entities from a transaction that has closed is how detached-entity bugs
     * start. The worker re-reads each job in its own transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> claimBatch(String correlationId) {
        Instant now = Instant.now();
        List<ImportJob> claimed = repository.claimDueJobs(now, BATCH_SIZE);
        claimed.forEach(job -> job.markClaimed(correlationId, now));
        repository.saveAll(claimed);
        return claimed.stream().map(ImportJob::getId).toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ImportJob> find(UUID jobId) {
        return repository.findById(jobId);
    }

    /**
     * Applies a change to a job in its own transaction.
     *
     * <p>Every mutation the worker makes goes through here so that a failure while recording
     * progress cannot roll back the import that produced it -- the two are separate concerns and
     * separate transactions.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void update(UUID jobId, java.util.function.Consumer<ImportJob> change) {
        repository.findById(jobId).ifPresent(job -> {
            change.accept(job);
            repository.save(job);
        });
    }

    /**
     * Returns abandoned jobs to the queue.
     *
     * @return how many were returned -- the number is the signal, since one is a blip and a full
     *         batch means a worker died holding an entire claim
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverAbandoned() {
        Instant now = Instant.now();
        List<ImportJob> stuck = repository.findStuckInFlight(
                now.minus(IN_FLIGHT_TIMEOUT), PageRequest.of(0, RECOVERY_BATCH_SIZE));
        if (stuck.isEmpty()) return 0;
        stuck.forEach(job -> job.returnToQueue(
                "Abandoned in " + job.getStatus() + " for longer than " + IN_FLIGHT_TIMEOUT, now));
        repository.saveAll(stuck);
        return stuck.size();
    }

    public long queueDepth() {
        return repository.countByStatus(ImportJob.Status.QUEUED);
    }

    public Optional<Instant> oldestQueuedAt() {
        return repository.findOldestQueuedAt(Instant.now());
    }
}
