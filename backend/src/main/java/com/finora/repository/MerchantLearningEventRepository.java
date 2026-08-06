package com.finora.repository;

import com.finora.entity.MerchantLearningEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MerchantLearningEventRepository extends JpaRepository<MerchantLearningEvent, UUID> {

    /**
     * Claims a batch of due events, taking a row lock and skipping anything another worker already
     * holds.
     *
     * <p><b>{@code FOR UPDATE SKIP LOCKED} is the load-bearing part.</b> Railway can run more than
     * one instance, so without it two workers select the same row, both apply the learning, and the
     * merchant's {@code confirmation_count} increments twice. That is not wasted work — confirmation
     * counts are what {@code ConfidenceEngine.topCategory} uses to decide which category is
     * auto-applied, so double-processing silently changes the answer the engine gives. Plain
     * {@code FOR UPDATE} would serialise the workers instead, which is correct but turns a second
     * instance into a queue of one; {@code SKIP LOCKED} lets them work in parallel on disjoint rows,
     * which is the point of having them.
     *
     * <p>Native rather than JPQL because JPA's {@code @Lock(PESSIMISTIC_WRITE)} has no portable way
     * to express {@code SKIP LOCKED}. This application is PostgreSQL-only (see docker-compose.yml
     * and every migration), so a native query costs nothing here.
     *
     * <p>Must be called inside a transaction, and the caller must finish processing within it — the
     * lock lives for the transaction's lifetime and is what stops a second worker taking the row.
     * {@code MerchantLearningEventWorker} is the only caller and does exactly that.
     */
    @Query(value = """
           SELECT * FROM merchant_learning_events
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
           """, nativeQuery = true)
    List<MerchantLearningEvent> claimDueEvents(@Param("now") Instant now,
                                                @Param("batchSize") int batchSize);

    /**
     * Events stuck in PROCESSING past {@code staleBefore} — a worker claimed them and died before
     * finishing.
     *
     * <p>The row lock is released when that worker's transaction dies, but the status is not: the
     * row reads PROCESSING forever and no claim query will ever see it again, because claims only
     * look at PENDING. Without this, a single crashed worker silently strands however many events
     * it had in flight. Recovery is deliberately time-based rather than owner-based — tracking
     * which instance holds what would need instance identity and a heartbeat, which is a lot of
     * machinery to answer a question a timestamp already answers.
     *
     * <p>The enum literal is written in the SOURCE form ({@code MerchantLearningEvent.Status.PROCESSING}),
     * not the JVM binary form with a {@code $}. Hibernate accepts the binary form today, which is
     * exactly why it is worth pinning: it is not JPQL, and a provider upgrade is free to stop
     * accepting it.
     */
    @Query("""
           SELECT e FROM MerchantLearningEvent e
            WHERE e.status = com.finora.entity.MerchantLearningEvent.Status.PROCESSING
              AND e.updatedAt < :staleBefore
           """)
    List<MerchantLearningEvent> findStuckInProcessing(@Param("staleBefore") Instant staleBefore,
                                                       Pageable limit);

    /** Backs the admin queue page (WI2). Newest failure first — an admin opening this page is
     *  looking at what just broke, not at the backlog's oldest resident. */
    List<MerchantLearningEvent> findByStatusOrderByLastRetryAtDesc(MerchantLearningEvent.Status status,
                                                                    Pageable pageable);

    long countByStatus(MerchantLearningEvent.Status status);
}
