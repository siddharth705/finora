package com.finora.notification.repository;

import com.finora.notification.domain.DeviceToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    Optional<DeviceToken> findByUserIdAndTokenFingerprint(UUID userId, String tokenFingerprint);

    List<DeviceToken> findByUserIdAndRevokedAtIsNull(UUID userId);
}
