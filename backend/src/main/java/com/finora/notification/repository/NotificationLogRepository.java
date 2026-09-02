package com.finora.notification.repository;

import com.finora.notification.domain.NotificationLog;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

    List<NotificationLog> findByNotificationIdOrderByTimestampDesc(UUID notificationId);

    @Query("""
           SELECT COUNT(l) FROM NotificationLog l
            WHERE l.success = :success AND l.timestamp >= :since
           """)
    long countByOutcomeSince(@Param("success") boolean success, @Param("since") Instant since);
}
