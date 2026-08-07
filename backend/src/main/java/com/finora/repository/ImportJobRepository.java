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
}
