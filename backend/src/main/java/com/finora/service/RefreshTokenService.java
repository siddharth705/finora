package com.finora.service;

import com.finora.config.ClientIpResolver;
import com.finora.config.JwtProperties;
import com.finora.entity.RefreshToken;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.RefreshTokenRepository;
import com.finora.util.TokenHasher;
import com.finora.util.UserAgentParser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Refresh tokens are opaque random strings (not JWTs) stored hashed server-side — unlike the
 * short-lived JWT access token, a refresh token needs to be individually revocable, which a
 * self-contained JWT fundamentally can't be without a server-side blocklist anyway. So we just
 * store it server-side from the start, same pattern as password reset tokens.
 *
 * Rotation: every use of a refresh token immediately revokes it and issues a new one. If a
 * revoked token is presented again, that's a strong signal it was stolen and used by both the
 * legitimate holder and an attacker — the response to that is to revoke every active token for
 * the user, forcing a fresh login on every device rather than trying to guess which session is
 * the attacker's.
 */
@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;
    private final HttpServletRequest request;
    private final ClientIpResolver clientIpResolver;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, JwtProperties jwtProperties,
                                HttpServletRequest request, ClientIpResolver clientIpResolver) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProperties = jwtProperties;
        this.request = request;
        this.clientIpResolver = clientIpResolver;
    }

    public record IssuedToken(String rawToken, Instant expiresAt) {}
    public record RotationResult(UUID userId, IssuedToken newToken) {}

    /** A fresh sign-in: the session clock starts now. */
    public IssuedToken issue(UUID userId) {
        return issue(userId, Instant.now());
    }

    /**
     * @param sessionStartedAt when the user actually signed in. Rotation passes the ORIGINAL
     *        value forward rather than {@code now}, which is the whole mechanism behind the
     *        absolute cap -- resetting it here would restore the perpetual sliding session.
     */
    public IssuedToken issue(UUID userId, Instant sessionStartedAt) {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        RefreshToken rt = new RefreshToken();
        rt.setUserId(userId);
        rt.setTokenHash(TokenHasher.sha256(rawToken));
        rt.setSessionStartedAt(sessionStartedAt);
        Instant expiresAt = Instant.now().plusMillis(jwtProperties.getRefreshExpirationMs());
        rt.setExpiresAt(expiresAt);
        captureDeviceMetadata(rt);
        refreshTokenRepository.save(rt);

        return new IssuedToken(rawToken, expiresAt);
    }

    /** Best-effort device-session labels (see RefreshToken's own doc comment) from the live
     *  request that's issuing or rotating this token -- never lets a request without the usual
     *  headers (a test harness, an unusual client) fail the actual token issuance over it. */
    private void captureDeviceMetadata(RefreshToken rt) {
        try {
            String userAgent = request.getHeader("User-Agent");
            rt.setBrowser(UserAgentParser.browser(userAgent));
            rt.setDevice(UserAgentParser.device(userAgent));
            rt.setLastSeenIp(clientIpResolver.resolve(request));
            rt.setLastSeenAt(Instant.now());
        } catch (Exception e) {
            // No active request context (e.g. called outside an HTTP request) -- device metadata
            // is a nice-to-have, not something that should ever block issuing a real token.
        }
    }

    @Transactional
    public RotationResult rotate(String rawToken) {
        RefreshToken rt = refreshTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_TOKEN_EXPIRED, "Invalid refresh token"));

        if (rt.getRevokedAt() != null) {
            revokeAllForUser(rt.getUserId());
            throw new ApiException(ErrorCode.AUTH_SESSION_REVOKED,
                    "This refresh token has already been used. All sessions have been signed out as a precaution.");
        }
        Instant now = Instant.now();
        if (rt.getExpiresAt().isBefore(now)) {
            throw new ApiException(ErrorCode.AUTH_TOKEN_EXPIRED, "Refresh token expired — please sign in again.");
        }

        // Idle timeout, measured from when THIS token was created. Every rotation mints a new
        // token, so the current token's age is the time since the last refresh -- which, with a
        // 15-minute access token, is the time since the client last needed one. createdAt rather
        // than lastSeenAt deliberately: lastSeenAt is best-effort device metadata that
        // captureDeviceMetadata silently skips when there is no request context, so a null or
        // stale value would quietly disable this check. createdAt is NOT NULL with a default.
        if (jwtProperties.getIdleTimeoutMs() > 0
                && rt.getCreatedAt().plusMillis(jwtProperties.getIdleTimeoutMs()).isBefore(now)) {
            rt.setRevokedAt(now);
            refreshTokenRepository.save(rt);
            throw new ApiException(ErrorCode.AUTH_SESSION_IDLE,
                    "Signed out after a period of inactivity.");
        }

        // Absolute cap, measured from sign-in and immune to rotation. Without this, a session that
        // is merely USED often enough never ends -- which is what let a nine-hour-old browser tab
        // walk straight back into someone's bank statements.
        //
        // Revokes THIS session only, deliberately. Written first as revokeAllForUser on the
        // reasoning that a phone still refreshing would make the cap decorative -- which is simply
        // false: session_started_at is stamped at each DEVICE's sign-in, so a phone that signed in
        // on day 3 reaches its own cap on day 10 regardless of what the laptop does. Every device
        // already ages out on its own clock, so signing them all out bought nothing and cost the
        // user every other device because one of them happened to expire first.
        //
        // It also puts a routine lifecycle event in the same bucket as a compromise response.
        // revokeAllForUser belongs to the cases that genuinely imply theft -- refresh token reuse
        // above, password change, an explicit "sign out everywhere" -- and dulls that signal if it
        // fires every seven days for everybody.
        if (jwtProperties.getAbsoluteSessionMs() > 0
                && rt.getSessionStartedAt().plusMillis(jwtProperties.getAbsoluteSessionMs()).isBefore(now)) {
            rt.setRevokedAt(now);
            refreshTokenRepository.save(rt);
            throw new ApiException(ErrorCode.AUTH_SESSION_MAX_AGE,
                    "Session reached its maximum length.");
        }

        rt.setRevokedAt(now);
        refreshTokenRepository.save(rt);

        // The ORIGINAL session start, not now. This single argument is the difference between a
        // 7-day cap and no cap at all.
        IssuedToken newToken = issue(rt.getUserId(), rt.getSessionStartedAt());
        return new RotationResult(rt.getUserId(), newToken);
    }

    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken)).ifPresent(rt -> {
            rt.setRevokedAt(Instant.now());
            refreshTokenRepository.save(rt);
        });
    }

    /** Backs the device-management "your active sessions" list -- ordered most-recently-active
     *  first, since that's the order a user actually scans when looking for "which one is my
     *  phone right now" or "what's this session I don't recognize." */
    public List<RefreshToken> listActiveSessions(UUID userId) {
        return refreshTokenRepository.findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastSeenAtDesc(
                userId, Instant.now());
    }

    /** Lets a user sign a single device out remotely (e.g. a lost phone) without touching any of
     *  their other sessions -- unlike revokeAllOtherSessionsForUser, this targets exactly one
     *  session by id. Scoped to userId so a session id alone (a guessable-enough UUID from, say,
     *  a shared screenshot) can never be used to revoke a session belonging to a different user. */
    public void revokeSession(UUID userId, UUID sessionId) {
        RefreshToken rt = refreshTokenRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Session not found"));
        rt.setRevokedAt(Instant.now());
        refreshTokenRepository.save(rt);
    }

    /** Revokes every active session for the user, including whichever one is calling this --
     *  the same defense-in-depth response rotate() already applies when it detects a
     *  stolen/replayed refresh token (see this class's own doc comment). */
    public void revokeAllForUser(UUID userId) {
        List<RefreshToken> active = refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId);
        Instant now = Instant.now();
        active.forEach(t -> t.setRevokedAt(now));
        refreshTokenRepository.saveAll(active);
    }

    /** Used by PasswordChangeService's "sign out other devices" choice -- unlike
     *  revokeAllForUser(), the device that just completed the change stays signed in rather than
     *  also being forced to re-authenticate, since currentRawToken identifies it and is excluded.
     *  If currentRawToken doesn't match any active token (e.g. it was already rotated by the time
     *  this runs), nothing is excluded and every session -- including, in that edge case, this
     *  one -- ends up revoked; that fails toward the safer outcome, not a silent no-op. */
    public void revokeAllOtherSessionsForUser(UUID userId, String currentRawToken) {
        String currentHash = TokenHasher.sha256(currentRawToken);
        List<RefreshToken> others = refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId).stream()
                .filter(t -> !t.getTokenHash().equals(currentHash))
                .toList();
        Instant now = Instant.now();
        others.forEach(t -> t.setRevokedAt(now));
        refreshTokenRepository.saveAll(others);
    }
}
