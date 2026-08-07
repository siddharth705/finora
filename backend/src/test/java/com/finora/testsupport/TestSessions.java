package com.finora.testsupport;

import com.finora.entity.RefreshToken;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.security.JwtService;
import org.springframework.http.HttpHeaders;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * An access token for an integration test, backed by a session that actually exists.
 *
 * <h2>Why this exists</h2>
 * Integration tests used to mint a bearer token with
 * {@code jwtService.generateToken(id, email, UUID.randomUUID())} — a signed token naming a session
 * that was never created. That worked because nothing checked, and it stopped working the moment
 * something did: {@link com.finora.security.SessionValidator} rejects an access token whose session
 * has no live refresh token, and a session invented at the call site has none.
 *
 * <p>The point is not that thirty test files needed a mechanical edit. It is that the old helper
 * built a state the application cannot produce — an authenticated request from a session that never
 * signed in — and every test written against it was, in that one respect, testing something that
 * could not happen. Going through the real object graph is what keeps the fixture honest as the
 * session model gains rules.
 *
 * <h2>Deliberately not RefreshTokenService.issue</h2>
 * That method captures device metadata from a request-scoped {@code HttpServletRequest}, which does
 * not exist on a test thread; it survives that by swallowing the exception, so calling it here would
 * work while quietly exercising its failure path. Writing the row directly keeps the fixture's
 * intent visible, and it is the row — not the issuing path — that the session check reads.
 */
public final class TestSessions {

    private TestSessions() {}

    /**
     * A live session for {@code user}, and a signed access token naming it.
     *
     * <p>The stored hash is not the hash of any real token, and nothing here returns a refresh
     * token: this session can authenticate requests but cannot be rotated. That is the right shape
     * for the tests that use it — they exercise endpoints, not the refresh lifecycle, and a fixture
     * that also handed out a usable refresh credential would invite a test to depend on it. Tests
     * that DO exercise rotation (RefreshTokenTransportIT, AccessTokenSessionRevocationIT) go
     * through {@code RefreshTokenService} for exactly that reason.
     *
     * <p>Expiry is a week out rather than the configured refresh lifetime, so that no test can
     * become time-sensitive: the session outlives any run.
     */
    public static String accessTokenFor(JwtService jwtService, RefreshTokenRepository refreshTokens, User user) {
        RefreshToken rt = new RefreshToken();
        rt.setUserId(user.getId());
        rt.setTokenHash("test-session-" + UUID.randomUUID());
        rt.setExpiresAt(Instant.now().plus(Duration.ofDays(7)));
        // sessionId, sessionStartedAt and createdAt come from the entity's own defaults, so this
        // fixture cannot drift from how a real sign-in initialises them.
        RefreshToken saved = refreshTokens.save(rt);
        return jwtService.generateToken(user.getId(), user.getEmail(), saved.getSessionId(),
                user.getAccountScope());
    }

    /** The same token, wrapped in the {@code Authorization} header most callers want. */
    public static HttpHeaders bearerFor(JwtService jwtService, RefreshTokenRepository refreshTokens, User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }
}
