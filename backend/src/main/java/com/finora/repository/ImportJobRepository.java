package com.finora.repository;

import com.finora.entity.ImportJob;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The durable import queue.
 *
 * <p>Queries here are deliberately PostgreSQL-shaped rather than abstracted behind a broker-neutral
 * interface. ADR-003 is explicit about this: {@code SKIP LOCKED} has no RabbitMQ equivalent and
 * update-in-place has no Kafka equivalent, so an interface shaped around them would need rewriting
 * on contact with the first real broker. Postgres is the system of record; a broker, if one is ever
 * added, distributes notifications and never owns this state.
 */
public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {

    /**
     * Claims due jobs for this worker.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes multiple workers safe without coordination:
     * each transaction locks the rows it takes and skips rows another worker already holds, so two
     * workers polling simultaneously divide the batch instead of colliding on it. Without it they
     * would either block on each other or process the same job twice.
     *
     * <p>Ordered by {@code next_attempt_at} so the longest-waiting work goes first and a retrying
     * job cannot starve behind newer arrivals. Native because {@code SKIP LOCKED} has no JPQL form.
     */
    @Query(value = """
           SELECT * FROM import_jobs
            WHERE status = 'QUEUED' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
           """, nativeQuery = true)
    List<ImportJob> claimDueJobs(@Param("now") Instant now, @Param("batchSize") int batchSize);

    /**
     * Jobs a worker claimed and abandoned -- in flight, with no progress, past the timeout.
     *
     * <p>The row lock dies with the worker's transaction but the status does not: the row reads
     * PARSING forever and no claim will see it again, because claims only look at QUEUED. Without
     * this, one crashed instance silently strands everything it had in flight.
     *
     * <p>Time-based rather than owner-based, matching the learning queue. Tracking which instance
     * holds what needs instance identity and a heartbeat, which is a lot of machinery to answer a
     * question a timestamp already answers.
     */
    @Query(value = """
           SELECT * FROM import_jobs
            WHERE status IN ('PARSING','ANALYZING','DEDUPING','IMPORTING','LEARNING')
              AND started_at < :staleBefore
            ORDER BY started_at
           """, nativeQuery = true)
    List<ImportJob> findStuckInFlight(@Param("staleBefore") Instant staleBefore, Pageable pageable);

    /** Backs the progress endpoint's ownership check -- a job id alone must never be enough to read
     *  someone else's import. */
    Optional<ImportJob> findByIdAndUserId(UUID id, UUID userId);

    /**
     * A job this user already has in flight for the same bytes, if there is one.
     *
     * <p>BH-019. The ordinary case this exists for is a double-clicked upload, or a client retrying
     * a request whose 202 never arrived -- both of which used to create a second job and a second
     * staged session for one document. Returning the existing job means the client polls the work
     * that is already happening instead of racing a duplicate of it.
     *
     * <p>Not the guarantee -- {@code idx_import_jobs_live_content} (V74) is, because this is a read
     * followed by a write and two simultaneous uploads can both miss. Terminal statuses are
     * excluded so re-uploading a statement whose earlier import finished, failed or was cancelled
     * still starts fresh work.
     */
    Optional<ImportJob> findFirstByUserIdAndContentHashAndStatusNotInOrderByCreatedAtDesc(
            UUID userId, String contentHash, java.util.Collection<ImportJob.Status> excludedStatuses);

    List<ImportJob> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /** Queue depth for the {@code finora.worker.queue_depth} gauge. */
    long countByStatus(ImportJob.Status status);

    /**
     * When the oldest claimable job was created, or empty when the queue is drained.
     *
     * <p>Scoped to the same predicate {@link #claimDueJobs} uses, so the gauge measures the set a
     * worker would actually pick up -- a job backing off between retries is waiting by design and
     * would otherwise make a healthy queue look aged.
     */
    @Query("""
           SELECT MIN(j.createdAt) FROM ImportJob j
            WHERE j.status = com.finora.entity.ImportJob.Status.QUEUED
              AND j.nextAttemptAt <= :now
           """)
    Optional<Instant> findOldestQueuedAt(@Param("now") Instant now);

    /**
     * The job that produced a staging session, if a job produced it.
     *
     * <p>The reverse of the lookup the trace already does from a job, so a trace looks the same
     * whichever handle the operator happens to hold -- an analysis reference from a support ticket
     * or a job id from the progress endpoint.
     *
     * <p><b>Never call this with null.</b> A derived query matches {@code IS NULL} and would return
     * every job that never recorded a session, which today is all of them.
     */
    List<ImportJob> findByImportSessionId(UUID importSessionId);

    /**
     * Whether a job outside {@code excludedStatuses} still references this object key. Feeds
     * {@code StatementStorageSweepService}'s reference check alongside {@code
     * StatementImportRepository.existsByObjectKey} and {@code ImportSessionRepository.existsByObjectKey}
     * -- called there with {@code StatementStorageSweepService.IMPORT_JOB_EXCLUDED_STATUSES}, i.e.
     * {@code {COMPLETED, CANCELLED}}.
     *
     * <p>Those two are excluded deliberately, not incidentally, and for the same reason as each
     * other: unlike {@code statement_imports}/{@code import_sessions}, this table has no expiry of
     * its own -- a finished job's row outlives whatever it produced (or didn't) indefinitely,
     * cascading away only if the owning user account itself is deleted (V66). Counting either here
     * would make its object permanently unsweepable, for a different reason each time:
     * <ul>
     *   <li>A COMPLETED job's object already has a legitimate reference path -- the confirmed
     *       {@code statement_imports} row, or a still-staged {@code import_sessions} row -- and
     *       counting the job too would silently override THEIR expiry, retaining the object forever
     *       after every real reference to it is already gone.</li>
     *   <li>A CANCELLED job before staging finished (see {@code ImportJob#isCancellable}) has no
     *       such path at all -- there is no session or statement-import row to expire -- so counting
     *       it would not override an expiry, it would create a reference with none.</li>
     * </ul>
     * Either way the answer is the same: neither state has a product reason to keep the object
     * reachable, so neither should hold it forever just because its row happens to.
     *
     * <p>FAILED and the in-flight statuses have no coverage in either of those tables at all -- for
     * them, this check is the only thing standing between an object and the sweep, which is the gap
     * this method exists to close: a failed import's bytes used to become unswept-safe only by
     * accident (another live reference happening to still exist), never because the failed job
     * itself was recognized as a reason to keep them. That protection is itself unbounded -- see
     * {@code StatementStorageSweepService}'s class doc, "Accepted trade-off" -- an intentional
     * choice, not an oversight matching COMPLETED's.
     */
    boolean existsByObjectKeyAndStatusNotIn(String objectKey, java.util.Collection<ImportJob.Status> excludedStatuses);
}
