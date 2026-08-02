package com.finora.repository;

import com.finora.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Used for the "revoke everything" response when a used/revoked token is presented again —
     *  a strong signal the token was stolen, so every session for this user gets logged out. */
    List<RefreshToken> findByUserIdAndRevokedAtIsNull(UUID userId);

    /** Backs the device-management list endpoint — unlike findByUserIdAndRevokedAtIsNull above,
     *  also excludes tokens that have simply expired without ever being explicitly revoked, since
     *  those can no longer be used to refresh and so aren't a real "active session" to show or
     *  let the user sign out of. */
    List<RefreshToken> findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastSeenAtDesc(
            UUID userId, Instant now);

    Optional<RefreshToken> findByIdAndUserId(UUID id, UUID userId);
}
