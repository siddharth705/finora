package com.finora.notification.repository;

import com.finora.notification.domain.Notification;
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

    Page<Notification> findByStatus(NotificationStatus status, Pageable pageable);

    long countByStatus(NotificationStatus status);

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
}
