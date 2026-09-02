package com.finora.notification.repository;

import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationTemplate;
import com.finora.notification.domain.NotificationType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {

    Optional<NotificationTemplate> findByTypeAndChannelAndActiveTrue(NotificationType type,
            NotificationChannel channel);
}
