package com.finora.security;

import com.finora.repository.RefreshTokenRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Answers the one question a self-contained access token cannot: is the session that minted this
 * token still alive?
 *
 * <h2>The gap this closes</h2>
 * A JWT is valid because it is signed and unexpired, and nothing else. Every revocation this
 * platform performs — voluntary logout, "sign out of that device", the idle timeout, the absolute
 * session cap, a password change, and the account-wide revocation that refresh-token reuse
 * detection performs when it concludes a token was stolen — writes {@code revoked_at} on
 * {@code refresh_tokens} rows. None of it could touch an access token already in circulation. So
 * the platform decided a token was stolen, signed the user out everywhere, and the attacker's
 * access token kept working for the remainder of its fifteen minutes.
 *
 * <p>Fifteen minutes is a short window and it is the wrong thing to be measuring. The relevant
 * quantity is that a revocation the platform performed did not take effect, which is a property of
 * the design rather than of the number: shortening the access token's life narrows the window and
 * never closes it, and lengthening it (a reasonable thing to want on mobile) widens it silently.
 *
 * <h2>Why the session and not the token</h2>
 * The access token carries {@code sid}, the session id — stable across every rotation
 * (ADR-002: "the session is the unit, not the token"). Rotation revokes the presented refresh
 * token and issues a successor carrying the same {@code sid}, so a session that is merely being
 * used normally always has exactly one live row, and a session that ended has none. Keying on
 * {@code refresh_tokens.id} instead would reject every access token the moment its own refresh
 * token rotated, which is roughly every fifteen minutes of ordinary use.
 *
 * <h2>Cost</h2>
 * One indexed existence check per authenticated request, against
 * {@code idx_refresh_tokens_live_session} (V71) — a partial index over exactly the unrevoked rows,
 * so it holds one row per live session rather than one per rotation. This sits alongside the two
 * reads the authenticated path already performs ({@code CurrentUserDetailsService}'s user load and
 * {@code PhoneVerificationFilter}'s flag read) rather than adding a new class of work. If the read
 * ever needs to be avoided, the answer is a cache with an explicit staleness budget, which is a
 * decision to make against a measurement rather than in advance.
 */
@Component
public class SessionValidator {

    private final RefreshTokenRepository refreshTokenRepository;

    public SessionValidator(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /**
     * @param sessionId the token's {@code sid} claim, or null when the token carries none
     * @return whether that session still has a live refresh token
     *
     * <p><b>A null {@code sid} is not live.</b> It means the token cannot be tied to any session,
     * so its revocation status is unknowable — and "unknowable" has to fail closed here, or the
     * whole check is bypassable by presenting a token without the claim. Every token this
     * application has ever minted carries {@code sid} ({@code JwtService.generateToken} requires
     * it), so the only tokens this rejects are ones forged or minted before the claim existed; the
     * latter can be at most fifteen minutes old at deploy time and all three clients recover from
     * the resulting 401 by refreshing. This is deliberately stricter than the reading
     * {@code DeviceController} applies to the same claim, where a null means "cannot tell which
     * device is asking" and the right answer is to badge nothing rather than to reject the request.
     */
    public boolean isSessionLive(UUID sessionId) {
        if (sessionId == null) {
            return false;
        }
        return refreshTokenRepository.existsBySessionIdAndRevokedAtIsNullAndExpiresAtAfter(
                sessionId, Instant.now());
    }
}
