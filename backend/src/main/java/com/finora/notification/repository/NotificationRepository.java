package com.finora.notification.repository;

import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    boolean existsByNotificationKey(String notificationKey);

    Optional<Notification> findByNotificationKey(String notificationKey);

    /** AccountPurgeSweepService.purgeOne's own cleanup -- notifications belong to the user they
     *  were sent to, and never survive a purge on their own via ON DELETE CASCADE alone, since
     *  purgeOne never issues a raw DELETE FROM users. See V137's own comment. */
    void deleteByUserId(UUID userId);

    Page<Notification> findByStatus(NotificationStatus status, Pageable pageable);

    long countByStatus(NotificationStatus status);

    /** Backs the admin dashboard's per-channel send-outcome breakdown (Task 12) -- six cheap
     *  indexed-enough counts (3 channels x {SENT, DEAD_LETTER}) rather than a GROUP BY, matching
     *  the "simple indexed counts, not a reporting subsystem" discipline AdminStatsService and
     *  AdminLearningQueueService.summary() already use for their own filter-chip counts. */
    long countByChannelAndStatus(NotificationChannel channel, NotificationStatus status);

    /**
     * Claims a batch of due notifications for this worker only.
     *
     * <p>Native because JPQL has no portable FOR UPDATE SKIP LOCKED. This app is Postgres-only by
     * design (ADR-003), so a native query is the right tool here -- the same choice
     * MerchantLearningEventRepository.claimDueEvents made.
     */
    @Query(value = """
           SELECT * FROM notifications
            WHERE status IN ('CREATED', 'QUEUED', 'RETRYING')
              AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
           """, nativeQuery = true)
    List<Notification> claimDue(@Param("now") Instant now, @Param("batchSize") int batchSize);

    /** PROCESSING rows that outlived the worker that claimed them. */
    @Query("""
           SELECT n FROM Notification n
            WHERE n.status = com.finora.notification.domain.NotificationStatus.PROCESSING
              AND n.nextAttemptAt < :cutoff
           """)
    List<Notification> findAbandoned(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Query("""
           SELECT MIN(n.nextAttemptAt) FROM Notification n
            WHERE n.status IN (com.finora.notification.domain.NotificationStatus.CREATED,
                               com.finora.notification.domain.NotificationStatus.QUEUED,
                               com.finora.notification.domain.NotificationStatus.RETRYING)
           """)
    Optional<Instant> findOldestPendingAt();

    /**
     * Inserts a freshly-queued notification row, or does nothing if {@code notification_key} is
     * already taken -- see {@code NotificationService.request}'s own doc comment for the TOCTOU
     * race this exists to close, and {@code MerchantAliasRepository#insertIfAbsent} /
     * {@code MerchantNormalizationEngine.addAlias}'s "Bug fix, second" for why a plain
     * {@code saveAndFlush()} + {@code catch(DataIntegrityViolationException)} was tried first in
     * this codebase and replaced: once any statement in an open Postgres transaction fails, every
     * later statement on it -- a plain SELECT included -- fails until COMMIT or ROLLBACK, so
     * catching the exception in Java does not, by itself, keep the rest of the caller's transaction
     * usable. {@code ON CONFLICT DO NOTHING} resolves a benign lost race atomically and silently
     * instead, so no exception is ever raised for it and the ambient transaction is never poisoned.
     *
     * <p>Deliberately stays in the CALLER's transaction, not {@code REQUIRES_NEW}: it is the
     * caller's own transaction committing that is what makes this row part of the outbox (see
     * {@code NotificationService.request}'s class-level Javadoc) -- a suspended-and-resumed inner
     * transaction would not un-poison the ambient one on failure anyway (Postgres's COMMIT against
     * an aborted transaction silently downgrades to ROLLBACK, discarding the caller's other work
     * with no exception raised at all), and on success it would let a notification commit and
     * become dispatchable even if the caller's own business write later rolls back for an unrelated
     * reason -- exactly what "the caller's transaction is what makes this an outbox" forbids.
     *
     * <h2>Kept in sync by hand with {@code Notification.create()} + {@code markQueued()}</h2>
     *
     * <p>This statement re-derives, in raw SQL, exactly the row {@link Notification#create} followed
     * by {@link Notification#markQueued} would produce: {@code status = 'QUEUED'} (never the
     * column's own {@code DEFAULT 'CREATED'} -- a row written by this path must land in the same
     * state that sequence produces, not the state before {@code markQueued} ran),
     * {@code attempt_count = 0}, {@code next_attempt_at} and {@code created_at} both the same
     * {@code now} instant, and {@code last_error}/{@code sent_at}/{@code read_at} left NULL by
     * omission (all three are nullable with no default). There is deliberately no single source of
     * truth shared between the entity and this query -- the same tradeoff
     * {@code MerchantAliasRepository#insertIfAbsent} accepted, and its doc comment's warning applies
     * here too: if {@link Notification}'s constructor or {@code markQueued} ever change what a
     * freshly-queued row looks like, this statement must be updated by hand to match.
     *
     * @return the id of the row this call inserted, or empty if {@code notificationKey} already
     *     existed -- from an earlier call, a retried caller, or a concurrent writer that got there
     *     first. Either way, by the time this returns, a row for {@code notificationKey} is
     *     guaranteed to exist.
     */
    @Query(value = """
           INSERT INTO notifications
               (id, user_id, notification_key, type, category, channel, priority, status, title,
                message, attempt_count, next_attempt_at, created_at)
           VALUES
               (gen_random_uuid(), :userId, :notificationKey, :type, :category, :channel, :priority,
                'QUEUED', :title, :message, 0, :now, :now)
           ON CONFLICT (notification_key) DO NOTHING
           RETURNING id
           """, nativeQuery = true)
    Optional<UUID> insertIfAbsent(@Param("userId") UUID userId,
            @Param("notificationKey") String notificationKey, @Param("type") String type,
            @Param("category") String category, @Param("channel") String channel,
            @Param("priority") String priority, @Param("title") String title,
            @Param("message") String message, @Param("now") Instant now);
}
