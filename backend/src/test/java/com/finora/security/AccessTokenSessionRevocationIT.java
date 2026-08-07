package com.finora.security;

import com.finora.AbstractIntegrationTest;
import com.finora.config.JwtProperties;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import com.finora.service.RefreshTokenService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A revocation has to reach the access token, not just the refresh token.
 *
 * <h2>What was wrong</h2>
 * Every revocation this platform performs writes to {@code refresh_tokens}: logout, "sign out of
 * that device", the idle timeout, the absolute session cap, a password change, and the account-wide
 * revocation that reuse detection performs when it concludes a refresh token was stolen. None of it
 * could touch an access token already issued, because a JWT is valid on its signature and expiry
 * alone. So the platform could decide a token was stolen, sign the user out of every device, and
 * the attacker kept working for the remainder of the fifteen minutes.
 *
 * <h2>Why the assertions are shaped like this</h2>
 * "The request 401s after revocation" is satisfied by two completely different things: the fix
 * working, and the token having simply expired. So every rejection here is paired with an assertion
 * that the token is <em>still cryptographically valid and unexpired</em> — the state that made the
 * bug exploitable. Without that pairing, the test would keep passing if someone shortened the
 * access-token lifetime to zero, which is not the property being defended.
 *
 * <p>{@link #rotationDoesNotEndTheSession} is the inverse guard and matters just as much. Rotation
 * revokes the presented refresh token roughly every fifteen minutes of ordinary use; a check keyed
 * on the token rather than the session would sign every active user out at their first refresh,
 * and would do it while looking exactly like a working security fix.
 */
class AccessTokenSessionRevocationIT extends AbstractIntegrationTest {

    /** Any endpoint that requires nothing but authentication; the subject here is the filter. */
    private static final String PROTECTED = "/api/v1/users/me/access";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private RefreshTokenService refreshTokenService;

    private User newUser() {
        User user = new User();
        user.setEmail("session-revocation-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Session Revocation IT");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /** A real sign-in: a session row, and an access token naming it. */
    private record SignedIn(String accessToken, String refreshToken) {}

    private SignedIn signIn(User user) {
        RefreshTokenService.IssuedToken issued = refreshTokenService.issue(user.getId());
        return new SignedIn(
                jwtService.generateToken(user.getId(), user.getEmail(), issued.sessionId(),
                        user.getAccountScope()),
                issued.rawToken());
    }

    private HttpStatus callProtectedWith(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> response = restTemplate.exchange(
                PROTECTED, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        return HttpStatus.valueOf(response.getStatusCode().value());
    }

    /**
     * The token is unexpired and correctly signed — i.e. everything the old implementation checked
     * still passes. Asserting this next to a 401 is what distinguishes "revocation took effect"
     * from "the token ran out".
     */
    private void assertStillUnexpiredAndSigned(String accessToken) {
        assertThat(jwtService.isTokenValid(accessToken))
                .as("the token must still be signed and unexpired, or the 401 proves nothing about "
                        + "revocation")
                .isTrue();
    }

    @Test
    void aRevokedSessionsAccessTokenIsRejectedLongBeforeItExpires() {
        User user = newUser();
        SignedIn session = signIn(user);

        assertThat(callProtectedWith(session.accessToken())).isEqualTo(HttpStatus.OK);

        // The account-wide revocation reuse detection performs on a suspected stolen token.
        refreshTokenService.revokeAllForUser(user.getId());

        assertThat(callProtectedWith(session.accessToken()))
                .as("an access token whose session was revoked must not authenticate a request")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertStillUnexpiredAndSigned(session.accessToken());
    }

    @Test
    void signingOutOneDeviceLeavesTheOtherOneWorking() {
        User user = newUser();
        SignedIn laptop = signIn(user);
        SignedIn phone = signIn(user);

        // "Sign out my other devices" — the narrow revocation PasswordChangeService offers, which
        // deliberately spares the device that asked for it.
        refreshTokenService.revokeAllOtherSessionsForUser(user.getId(), phone.refreshToken());

        assertThat(callProtectedWith(laptop.accessToken()))
                .as("the revoked device's access token dies with its session")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertStillUnexpiredAndSigned(laptop.accessToken());

        assertThat(callProtectedWith(phone.accessToken()))
                .as("the session that was deliberately kept must keep working; a check that ended "
                        + "every session would pass the assertion above and be useless")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void logoutEndsTheAccessTokenAndNotJustTheRefreshToken() {
        User user = newUser();
        SignedIn session = signIn(user);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> logout = restTemplate.exchange(
                "/api/v1/auth/logout", HttpMethod.POST,
                new HttpEntity<>("{\"refreshToken\":\"" + session.refreshToken() + "\"}", headers),
                String.class);
        assertThat(logout.getStatusCode().value()).isEqualTo(200);

        assertThat(callProtectedWith(session.accessToken()))
                .as("logout previously left the user's access token working for up to fifteen more "
                        + "minutes, which is the whole point of signing out on a shared machine")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertStillUnexpiredAndSigned(session.accessToken());
    }

    @Test
    void rotationDoesNotEndTheSession() {
        User user = newUser();
        SignedIn session = signIn(user);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> refreshed = restTemplate.exchange(
                "/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>("{\"refreshToken\":\"" + session.refreshToken() + "\"}", headers),
                String.class);
        assertThat(refreshed.getStatusCode().value()).isEqualTo(200);

        // Rotation revoked the refresh token this access token was issued alongside, and wrote a
        // successor carrying the same session id. The session is unchanged, so the access token
        // must still work -- a revocation check keyed on the token would sign every active user out
        // roughly every fifteen minutes.
        assertThat(callProtectedWith(session.accessToken()))
                .as("ordinary rotation is not a revocation")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void anAccessTokenCarryingNoSessionClaimIsRejected() {
        User user = newUser();

        // Correctly signed with the application's own key, unexpired, naming a real user -- and with
        // no sid. This is the shape an attacker would reach for if the session check could be
        // bypassed by omitting the claim, and it is also the shape of a token minted before the
        // claim existed. Both have to fail closed: a token that names no session has no revocation
        // status that can be checked.
        String noSid = Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtProperties.getExpirationMs()))
                .signWith(Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertStillUnexpiredAndSigned(noSid);
        assertThat(callProtectedWith(noSid)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anAccessTokenNamingASessionThatNeverExistedIsRejected() {
        User user = newUser();

        // Signed, unexpired, real user, syntactically perfect sid -- for a session nothing ever
        // created. Before the check existed this authenticated exactly like any other token.
        String inventedSession = jwtService.generateToken(user.getId(), user.getEmail(), UUID.randomUUID(),
                user.getAccountScope());

        assertStillUnexpiredAndSigned(inventedSession);
        assertThat(callProtectedWith(inventedSession)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
