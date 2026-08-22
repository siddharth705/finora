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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RefreshTokenService.class);

    /** Same reasoning as ImportSessionService.CLEANUP_BATCH_SIZE -- bounds the cost of one sweep;
     *  a backlog drains across subsequent runs rather than in one unbounded delete. */
    private static final int CLEANUP_BATCH_SIZE = 200;

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;
    private final HttpServletRequest request;
    private final ClientIpResolver clientIpResolver;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.security.refresh-token-cleanup.enabled:true}")
    private boolean cleanupEnabled;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, JwtProperties jwtProperties,
                                HttpServletRequest request, ClientIpResolver clientIpResolver) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProperties = jwtProperties;
        this.request = request;
        this.clientIpResolver = clientIpResolver;
    }

    public record IssuedToken(String rawToken, Instant expiresAt, UUID sessionId) {}
    public record RotationResult(UUID userId, IssuedToken newToken) {}

    /** A fresh sign-in: a new session, whose clock starts now. */
    public IssuedToken issue(UUID userId) {
        return issue(userId, Instant.now(), UUID.randomUUID());
    }

    /**
     * @param sessionStartedAt when the user actually signed in. Rotation passes the ORIGINAL
     *        value forward rather than {@code now}, which is the whole mechanism behind the
     *        absolute cap -- resetting it here would restore the perpetual sliding session.
     */
    public IssuedToken issue(UUID userId, Instant sessionStartedAt, UUID sessionId) {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        RefreshToken rt = new RefreshToken();
        rt.setUserId(userId);
        rt.setTokenHash(TokenHasher.sha256(rawToken));
        rt.setSessionStartedAt(sessionStartedAt);
        rt.setSessionId(sessionId);
        Instant expiresAt = Instant.now().plusMillis(jwtProperties.getRefreshExpirationMs());
        rt.setExpiresAt(expiresAt);
        captureDeviceMetadata(rt);
        refreshTokenRepository.save(rt);

        return new IssuedToken(rawToken, expiresAt, sessionId);
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

    /**
     * {@code noRollbackFor} is load-bearing, not a style choice.
     *
     * <p>Three paths below deliberately WRITE a revocation and then throw to reject the request:
     * reuse detection revokes every session, and the idle and absolute limits revoke the presented
     * one. {@link ApiException} is a RuntimeException, so the default rollback rule discarded all
     * three the instant they were reported — the caller got a correct 401 saying "all sessions have
     * been signed out as a precaution" while, in the database, nothing had been signed out at all.
     * On the reuse path that is the failure that matters most: the whole response to a suspected
     * stolen token is to invalidate the copy the attacker holds, and it silently did not.
     *
     * <p>Invisible to every unit test here, because those mock the repository: {@code saveAll} was
     * called, the verification passed, and no transaction existed to undo it. It took an
     * end-to-end test that replayed a used cookie and then checked an untouched second device.
     */
    /**
     * Read-only peek at which user a presented raw refresh token belongs to. Exists so
     * {@link com.finora.service.AuthService#refresh} can run its account-status checks (suspended,
     * deactivated, pending deletion) BEFORE calling {@link #rotate}, instead of after -- rotate()
     * revokes the presented token and mints a new one as its very first mutations, and those writes
     * join the caller's transaction rather than opening their own, so a suspension check that ran
     * only after rotate() had already returned was gating a response the database had already
     * committed to, not the mutation itself.
     *
     * <p>Deliberately does not validate revocation, expiry, idle timeout, or the absolute cap --
     * rotate() remains the sole source of truth for whether the token itself is still usable. This
     * only resolves ownership, using the same "invalid token" error rotate() throws for the same
     * not-found case, so an unrecognized token behaves identically either way.
     */
    public UUID resolveUserId(String rawToken) {
        return refreshTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_TOKEN_EXPIRED, "Invalid refresh token"))
                .getUserId();
    }

    @Transactional(noRollbackFor = ApiException.class)
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
            // No custom message: ErrorCode's own copy is written for the user and ends with
            // "Please sign in again", and this string is what Login.tsx renders verbatim after
            // client.ts carries it through SESSION_ENDED_REASON_KEY.
            throw new ApiException(ErrorCode.AUTH_SESSION_IDLE);
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
            throw new ApiException(ErrorCode.AUTH_SESSION_MAX_AGE);
        }

        rt.setRevokedAt(now);
        refreshTokenRepository.save(rt);

        // The ORIGINAL session start, not now. This single argument is the difference between a
        // 7-day cap and no cap at all.
        IssuedToken newToken = issue(rt.getUserId(), rt.getSessionStartedAt(), rt.getSessionId());
        return new RotationResult(rt.getUserId(), newToken);
    }

    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken)).ifPresent(rt -> {
            rt.setRevokedAt(Instant.now());
            refreshTokenRepository.save(rt);
        });
    }

    /**
     * Bug 14 (docs/quality/bug-reports/BUG_REVIEW_REPORT.md). Nothing deleted a
     * {@code refresh_tokens} row, ever -- every sign-in creates one and every rotation creates
     * another, only ever setting {@code revokedAt} on the old one. An actively used session
     * rotates roughly every 15 minutes (the access token's own lifetime), so the table grew
     * without bound: a permanently retained, ever-growing store of device-tracking PII
     * ({@code tokenHash}, {@code lastSeenIp}, {@code browser}, {@code device}) with no retention
     * policy at all.
     *
     * <p>Same opportunistic-sweep-turned-scheduled-job shape as
     * {@code ImportSessionService.sweepExpiredSessions}/{@code scheduledSweep} (BH-047) --
     * {@link com.finora.config.BackgroundWorkConfig} already enables scheduling unconditionally,
     * so there is no reason for this to be reactive/opportunistic the way the import-session sweep
     * originally, mistakenly was.
     *
     * <p>See {@link RefreshTokenRepository#findByExpiresAtBeforeOrderByExpiresAtAsc} for why this
     * keys on {@code expiresAt} alone, not {@code revokedAt IS NOT NULL} -- deleting a revoked row
     * before its own natural expiry would defeat {@link #rotate}'s reuse-detection for however much
     * of the stolen token's original lifetime remained.
     *
     * @return how many rows were removed, so a caller or a test can see the sweep did something
     */
    @Transactional
    public int sweepExpiredTokens() {
        List<RefreshToken> expired = refreshTokenRepository.findByExpiresAtBeforeOrderByExpiresAtAsc(
                Instant.now(), PageRequest.of(0, CLEANUP_BATCH_SIZE));
        if (expired.isEmpty()) return 0;
        refreshTokenRepository.deleteAll(expired);
        return expired.size();
    }

    /**
     * The scheduled trigger. Gated by a flag for the same reason every other scheduler in this
     * codebase is (see application-test.yml's own comments on the pattern): an integration suite
     * needs the sweep to be deterministic, and a background thread deleting rows mid-test is
     * exactly the cross-test pollution BH-058 was about. {@code application-test.yml} turns it
     * off; tests drive {@link #sweepExpiredTokens()} directly.
     *
     * <p>{@code fixedDelay}, not {@code fixedRate}: the next sweep starts after the previous one
     * finishes, so a slow sweep cannot pile up overlapping runs.
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${app.security.refresh-token-cleanup.interval-ms:3600000}",
            initialDelayString = "${app.security.refresh-token-cleanup.initial-delay-ms:120000}")
    public void scheduledCleanup() {
        if (!cleanupEnabled) return;
        int removed = sweepExpiredTokens();
        if (removed > 0) {
            log.info("Removed {} expired refresh token row(s).", removed);
        }
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

    /**
     * Used by PasswordChangeService's "sign out other devices" choice -- unlike
     * {@link #revokeAllForUser}, the device that just completed the change stays signed in rather
     * than also being forced to re-authenticate.
     *
     * <p><b>Keyed on the SESSION, not on the raw token.</b> This used to take the caller's own
     * refresh token and exclude the row whose hash matched. That worked only because the web app
     * kept a copy of that token in {@code localStorage} where script could read it -- which is
     * exactly the exposure BH-012 removed. With the token held only in an HttpOnly cookie the
     * client cannot read it, and the cookie is path-scoped to {@code /api/v1/auth} so it does not
     * reach this endpoint either.
     *
     * <p>The session id is the better key regardless, and was available all along. ADR-002 makes
     * the session the unit rather than the token precisely because a token rotates roughly every
     * fifteen minutes while the session does not -- so the old form also had a live failure mode
     * of its own: a token that rotated between the client reading it and this running matched
     * nothing, and "this device" was revoked along with the others. The {@code sid} claim is on
     * every access token, so the request that is asking already proves which session it is.
     *
     * @param currentSessionId the session to spare. Null spares nothing and revokes every session
     *        including the caller's -- the same fail-safe direction the previous implementation
     *        took for an unmatched token, and reachable only for a token minted before {@code sid}
     *        existed.
     */
    public void revokeAllOtherSessionsForUser(UUID userId, UUID currentSessionId) {
        List<RefreshToken> others = refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId).stream()
                .filter(t -> currentSessionId == null || !currentSessionId.equals(t.getSessionId()))
                .toList();
        Instant now = Instant.now();
        others.forEach(t -> t.setRevokedAt(now));
        refreshTokenRepository.saveAll(others);
    }
}
