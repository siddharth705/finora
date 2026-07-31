package com.finora.repository;

import com.finora.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Used for the "revoke everything" response when a used/revoked token is presented again —
     *  a strong signal the token was stolen, so every session for this user gets logged out. */
    List<RefreshToken> findByUserIdAndRevokedAtIsNull(UUID userId);
}
