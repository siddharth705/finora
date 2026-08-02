package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.PasswordChangeSession;
import com.finora.repository.PasswordChangeSessionRepository;
import com.finora.testsupport.FakePhoneVerificationProvider;
import com.finora.testsupport.TestPhoneVerificationConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the authenticated Change Password flow (start -> verify-otp -> complete)
 * over real HTTP against a real Postgres -- the security-critical paths PasswordChangeServiceTest
 * already covers at the unit level, but exercised here through the actual controller, filter
 * chain, and database round trip, the same reasoning as AuthFlowIT for the login/register flow.
 * FakePhoneVerificationProvider (wired in via @Import) stands in for Firebase, since this backend
 * never sees a real Firebase project in tests.
 *
 * Enables trust-proxy-headers and gives each test its own fake client IP via X-Forwarded-For --
 * every request in this class otherwise arrives from the same real address (TestRestTemplate on
 * localhost), and RateLimitFilter's new passwordChangeLimiter is a single shared, per-IP bucket
 * across the whole test class, not reset between test methods. Without per-test IPs, this class's
 * own request volume (several tests each doing start -> verify-otp -> complete) would trip its
 * own rate limit and produce spurious 429s -- exactly the kind of test-vs-security-control
 * collision the existing RateLimitFilterTest already isolates the same way (a distinct spoofed IP
 * per scenario), just done here at the real-HTTP level instead of a mocked HttpServletRequest.
 */
@Import(TestPhoneVerificationConfig.class)
class PasswordChangeFlowIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void enableTrustProxyHeaders(DynamicPropertyRegistry registry) {
        registry.add("app.security.trust-proxy-headers", () -> "true");
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private PasswordChangeSessionRepository sessionRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String CURRENT_PASSWORD = "OriginalPass123";
    private static final AtomicInteger CLIENT_IP_COUNTER = new AtomicInteger(1);

    private record RegisteredUser(String token, String refreshToken, String phoneNumber, String clientIp) {}

    /** Registers a fresh user, verifies their phone (password-change endpoints are gated behind
     *  phone verification, same as every other endpoint outside /auth/** and /phone/**), and
     *  returns what the rest of each test needs to drive the flow -- including a fake client IP
     *  unique to this call, so this test's own password-change rate-limit bucket never collides
     *  with another test's (see the class doc comment). */
    private RegisteredUser registerVerifiedUser() throws Exception {
        String email = "pwchange-" + UUID.randomUUID() + "@example.com";
        String phoneNumber = "+9198765" + (100000 + new java.util.Random().nextInt(900000));
        String clientIp = "203.0.113." + CLIENT_IP_COUNTER.getAndIncrement();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String registerBody = """
                {"email": "%s", "password": "%s", "fullName": "Password Change Test", "phoneNumber": "%s"}
                """.formatted(email, CURRENT_PASSWORD, phoneNumber);
        ResponseEntity<String> registerResponse = restTemplate.postForEntity(
                "/api/v1/auth/register", new HttpEntity<>(registerBody, headers), String.class);
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode registerJson = mapper.readTree(registerResponse.getBody()).get("data");
        String token = registerJson.get("token").asText();
        String refreshToken = registerJson.get("refreshToken").asText();

        HttpHeaders authHeaders = authHeaders(token, clientIp);
        String verifyBody = """
                {"firebaseIdToken": "%s"}
                """.formatted(FakePhoneVerificationProvider.tokenFor(phoneNumber));
        ResponseEntity<String> verifyResponse = restTemplate.postForEntity(
                "/api/v1/phone/verify", new HttpEntity<>(verifyBody, authHeaders), String.class);
        assertThat(verifyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        return new RegisteredUser(token, refreshToken, phoneNumber, clientIp);
    }

    private HttpHeaders authHeaders(String token, String clientIp) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.set("X-Forwarded-For", clientIp);
        return headers;
    }

    private ResponseEntity<String> start(RegisteredUser user) {
        String body = """
                {"currentPassword": "%s"}
                """.formatted(CURRENT_PASSWORD);
        return restTemplate.postForEntity("/api/v1/users/me/password-change/start",
                new HttpEntity<>(body, authHeaders(user.token(), user.clientIp())), String.class);
    }

    private ResponseEntity<String> verifyOtp(RegisteredUser user, String sessionId, String firebaseIdToken) {
        String body = """
                {"sessionId": "%s", "firebaseIdToken": "%s"}
                """.formatted(sessionId, firebaseIdToken);
        return restTemplate.postForEntity("/api/v1/users/me/password-change/verify-otp",
                new HttpEntity<>(body, authHeaders(user.token(), user.clientIp())), String.class);
    }

    private ResponseEntity<String> complete(RegisteredUser user, String sessionId, String newPassword,
                                             boolean signOutOtherDevices, String currentRefreshToken) {
        String body = """
                {"sessionId": "%s", "newPassword": "%s", "signOutOtherDevices": %s, "currentRefreshToken": "%s"}
                """.formatted(sessionId, newPassword, signOutOtherDevices, currentRefreshToken);
        return restTemplate.postForEntity("/api/v1/users/me/password-change/complete",
                new HttpEntity<>(body, authHeaders(user.token(), user.clientIp())), String.class);
    }

    private ResponseEntity<String> refresh(String refreshToken) {
        String body = """
                {"refreshToken": "%s"}
                """.formatted(refreshToken);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity("/api/v1/auth/refresh", new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void successfulPasswordChange_thenLoginWithNewPassword_succeeds() throws Exception {
        RegisteredUser user = registerVerifiedUser();

        ResponseEntity<String> startResponse = start(user);
        assertThat(startResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String sessionId = mapper.readTree(startResponse.getBody()).get("data").get("sessionId").asText();

        ResponseEntity<String> verifyResponse = verifyOtp(user, sessionId, FakePhoneVerificationProvider.tokenFor(user.phoneNumber()));
        assertThat(verifyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> completeResponse = complete(user, sessionId, "BrandNewPass456", false, user.refreshToken());
        assertThat(completeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The new password actually took effect against the real, persisted user row.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String email = mapper.readTree(restTemplate.exchange("/api/v1/users/me", HttpMethod.GET,
                new HttpEntity<>(authHeaders(user.token(), user.clientIp())), String.class).getBody()).get("data").get("email").asText();
        String loginBody = """
                {"identifier": "%s", "password": "BrandNewPass456"}
                """.formatted(email);
        ResponseEntity<String> loginResponse = restTemplate.postForEntity(
                "/api/v1/auth/login", new HttpEntity<>(loginBody, headers), String.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void verifyOtp_withInvalidFirebaseToken_isRejected() throws Exception {
        RegisteredUser user = registerVerifiedUser();
        ResponseEntity<String> startResponse = start(user);
        String sessionId = mapper.readTree(startResponse.getBody()).get("data").get("sessionId").asText();

        ResponseEntity<String> verifyResponse = verifyOtp(user, sessionId, FakePhoneVerificationProvider.INVALID_TOKEN);

        assertThat(verifyResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void verifyOtp_withMismatchedPhoneNumber_isRejected() throws Exception {
        RegisteredUser user = registerVerifiedUser();
        ResponseEntity<String> startResponse = start(user);
        String sessionId = mapper.readTree(startResponse.getBody()).get("data").get("sessionId").asText();

        ResponseEntity<String> verifyResponse = verifyOtp(user, sessionId, FakePhoneVerificationProvider.tokenFor("+919999999999"));

        assertThat(verifyResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(verifyResponse.getBody()).get("message").asText()).contains("doesn't match");
    }

    @Test
    void verifyOtp_againstAnExpiredSession_isRejected() throws Exception {
        RegisteredUser user = registerVerifiedUser();
        ResponseEntity<String> startResponse = start(user);
        String sessionId = mapper.readTree(startResponse.getBody()).get("data").get("sessionId").asText();

        // Force the session into the past rather than waiting out the real TTL -- directly
        // manipulating the persisted row is the standard way to test time-based expiry
        // deterministically.
        PasswordChangeSession session = sessionRepository.findById(UUID.fromString(sessionId)).orElseThrow();
        session.setExpiresAt(Instant.now().minusSeconds(60));
        sessionRepository.save(session);

        ResponseEntity<String> verifyResponse = verifyOtp(user, sessionId, FakePhoneVerificationProvider.tokenFor(user.phoneNumber()));

        assertThat(verifyResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(verifyResponse.getBody()).get("message").asText()).contains("expired");
    }

    @Test
    void verifyOtp_calledTwiceForTheSameSession_rejectsTheReplay() throws Exception {
        RegisteredUser user = registerVerifiedUser();
        ResponseEntity<String> startResponse = start(user);
        String sessionId = mapper.readTree(startResponse.getBody()).get("data").get("sessionId").asText();

        ResponseEntity<String> first = verifyOtp(user, sessionId, FakePhoneVerificationProvider.tokenFor(user.phoneNumber()));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> replay = verifyOtp(user, sessionId, FakePhoneVerificationProvider.tokenFor(user.phoneNumber()));

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void complete_calledTwiceForTheSameSession_isIdempotentNotAnError() throws Exception {
        RegisteredUser user = registerVerifiedUser();
        String sessionId = mapper.readTree(start(user).getBody()).get("data").get("sessionId").asText();
        verifyOtp(user, sessionId, FakePhoneVerificationProvider.tokenFor(user.phoneNumber()));

        ResponseEntity<String> firstComplete = complete(user, sessionId, "BrandNewPass456", false, user.refreshToken());
        assertThat(firstComplete.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode firstData = mapper.readTree(firstComplete.getBody()).get("data");

        // A retried request (e.g. the frontend timed out waiting for the first response and
        // resent it) must return the SAME outcome, not throw "verify the code again" -- and must
        // not attempt to hash+save the password or revoke sessions a second time.
        ResponseEntity<String> duplicateComplete = complete(user, sessionId, "BrandNewPass456", false, user.refreshToken());

        assertThat(duplicateComplete.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode secondData = mapper.readTree(duplicateComplete.getBody()).get("data");
        assertThat(secondData.get("message").asText()).isEqualTo(firstData.get("message").asText());
        assertThat(secondData.get("otherDevicesSignedOut").asBoolean()).isEqualTo(firstData.get("otherDevicesSignedOut").asBoolean());
    }

    @Test
    void complete_withSignOutOtherDevicesTrue_revokesOtherSessionsButKeepsThisOneAndTheAccountLoginWorking() throws Exception {
        RegisteredUser user = registerVerifiedUser();

        // A second "device" -- log in again to mint a second, independent refresh token for the
        // same account.
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        String email = mapper.readTree(restTemplate.exchange("/api/v1/users/me", HttpMethod.GET,
                new HttpEntity<>(authHeaders(user.token(), user.clientIp())), String.class).getBody()).get("data").get("email").asText();
        String loginBody = """
                {"identifier": "%s", "password": "%s"}
                """.formatted(email, CURRENT_PASSWORD);
        ResponseEntity<String> secondLogin = restTemplate.postForEntity(
                "/api/v1/auth/login", new HttpEntity<>(loginBody, loginHeaders), String.class);
        String otherDeviceRefreshToken = mapper.readTree(secondLogin.getBody()).get("data").get("refreshToken").asText();

        String sessionId = mapper.readTree(start(user).getBody()).get("data").get("sessionId").asText();
        verifyOtp(user, sessionId, FakePhoneVerificationProvider.tokenFor(user.phoneNumber()));
        ResponseEntity<String> completeResponse = complete(user, sessionId, "BrandNewPass456", true, user.refreshToken());
        assertThat(completeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(completeResponse.getBody()).get("data").get("otherDevicesSignedOut").asBoolean()).isTrue();

        // This device's own refresh token still works.
        assertThat(refresh(user.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        // The other device's does not -- it was revoked.
        assertThat(refresh(otherDeviceRefreshToken).getStatusCode()).isNotEqualTo(HttpStatus.OK);
    }

    @Test
    void complete_withSignOutOtherDevicesFalse_preservesEveryOtherSession() throws Exception {
        RegisteredUser user = registerVerifiedUser();

        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        String email = mapper.readTree(restTemplate.exchange("/api/v1/users/me", HttpMethod.GET,
                new HttpEntity<>(authHeaders(user.token(), user.clientIp())), String.class).getBody()).get("data").get("email").asText();
        String loginBody = """
                {"identifier": "%s", "password": "%s"}
                """.formatted(email, CURRENT_PASSWORD);
        ResponseEntity<String> secondLogin = restTemplate.postForEntity(
                "/api/v1/auth/login", new HttpEntity<>(loginBody, loginHeaders), String.class);
        String otherDeviceRefreshToken = mapper.readTree(secondLogin.getBody()).get("data").get("refreshToken").asText();

        String sessionId = mapper.readTree(start(user).getBody()).get("data").get("sessionId").asText();
        verifyOtp(user, sessionId, FakePhoneVerificationProvider.tokenFor(user.phoneNumber()));
        ResponseEntity<String> completeResponse = complete(user, sessionId, "BrandNewPass456", false, user.refreshToken());
        assertThat(completeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(completeResponse.getBody()).get("data").get("otherDevicesSignedOut").asBoolean()).isFalse();

        assertThat(refresh(user.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refresh(otherDeviceRefreshToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
