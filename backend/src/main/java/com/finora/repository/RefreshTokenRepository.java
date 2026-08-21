package com.finora.repository;

import com.finora.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Whether a session still has a live refresh token, and therefore still exists at all.
     *
     * <p>Read on every authenticated request by {@link com.finora.security.SessionValidator}, which
     * is what stops an access token outliving the revocation of the session that minted it. A
     * session is alive exactly while ONE of its rows is unrevoked and unexpired: rotation revokes
     * the presented row and writes a successor carrying the same {@code session_id}, so a session
     * accumulates one revoked row per refresh and this must not be written as "no revoked rows
     * exist" — that reading would end every session at its first rotation.
     *
     * <p>{@code expires_at} is checked as well as {@code revoked_at} because expiry is silent:
     * nothing writes {@code revoked_at} when a refresh token simply ages out, so a session whose
     * only row expired would otherwise still count as live.
     *
     * <p>Backed by {@code idx_refresh_tokens_live_session} (V71), a partial index on exactly the
     * rows this predicate keeps.
     */
    boolean existsBySessionIdAndRevokedAtIsNullAndExpiresAtAfter(UUID sessionId, Instant now);

    /** AccountPurgeSweepService -- every row already revoked by requestDeletion() by this point;
     *  this removes the residual device/IP labels too. Hard delete, no soft-delete concern. */
    void deleteByUserId(UUID userId);

    /** D-28 PR4-C: the reuse proposal §4 asks for -- "check device/IP overlap between
     *  referrer_user_id and referred_user_id's sessions before crediting a reward, rather than
     *  building a parallel fingerprinting system." Distinct, non-null IPs only: a null
     *  {@code last_seen_ip} (never populated, e.g. a row created before this column existed) must
     *  never be treated as a shared signal between two accounts. */
    @Query("SELECT DISTINCT r.lastSeenIp FROM RefreshToken r WHERE r.userId = :userId AND r.lastSeenIp IS NOT NULL")
    List<String> findDistinctLastSeenIpsByUserId(@Param("userId") UUID userId);
}
