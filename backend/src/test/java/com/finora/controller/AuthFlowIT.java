package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.testsupport.FakePhoneVerificationProvider;
import com.finora.testsupport.TestPhoneVerificationConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real JWT flow end-to-end over HTTP: register a user, log in, use the token
 * to hit a protected endpoint, and confirm the same endpoint rejects requests with no token
 * and with a garbage token. This is the kind of thing that's easy to break silently by editing
 * SecurityConfig's matcher order or the JWT filter and not notice until a real deployment.
 */
@Import(TestPhoneVerificationConfig.class)
class AuthFlowIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void register_thenLogin_thenAccessProtectedEndpoint_succeeds() throws Exception {
        String email = "flow-" + System.currentTimeMillis() + "@example.com";
        String phoneNumber = "+919876543210";

        String registerBody = """
                {"email": "%s", "password": "SecurePass123", "fullName": "Flow Test User", "phoneNumber": "%s"}
                """.formatted(email, phoneNumber);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> registerResponse = restTemplate.postForEntity(
                "/api/v1/auth/register", new HttpEntity<>(registerBody, headers), String.class);
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode registerJson = mapper.readTree(registerResponse.getBody());
        assertThat(registerJson.get("success").asBoolean()).isTrue();
        String token = registerJson.get("data").get("token").asText();
        assertThat(token).isNotBlank();

        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth(token);
        // Bug fix: the Content-Type was missing, so RestTemplate defaulted a String body to
        // text/plain and POST /phone/verify answered 415 -> the flow blew up with a 500 well before
        // the assertion it was written for. Never caught because this class had never run: *IT did
        // not match surefire's default includes (see pom.xml).
        authHeaders.setContentType(MediaType.APPLICATION_JSON);

        // A freshly registered user is not phone-verified yet -- PhoneVerificationFilter must
        // reject a protected endpoint until verification completes, and must still allow the
        // verify call itself (otherwise nobody could ever get verified in the first place).
        ResponseEntity<String> beforeVerification = restTemplate.exchange(
                "/api/v1/accounts", HttpMethod.GET, new HttpEntity<>(authHeaders), String.class);
        assertThat(beforeVerification.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode beforeJson = mapper.readTree(beforeVerification.getBody());
        assertThat(beforeJson.get("errorCode").asText()).isEqualTo("PHONE_VERIFICATION_REQUIRED");

        // Firebase's own client SDK would normally send/confirm the OTP and hand back an ID token
        // -- FakePhoneVerificationProvider (wired in via @Import above) stands in for that, since
        // this backend never sees a real Firebase project in tests.
        String verifyBody = """
                {"firebaseIdToken": "%s"}
                """.formatted(FakePhoneVerificationProvider.tokenFor(phoneNumber));
        ResponseEntity<String> verifyResponse = restTemplate.postForEntity(
                "/api/v1/phone/verify", new HttpEntity<>(verifyBody, authHeaders), String.class);
        assertThat(verifyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Now that the phone is verified, the same token can reach a protected endpoint.
        ResponseEntity<String> accountsResponse = restTemplate.exchange(
                "/api/v1/accounts", HttpMethod.GET, new HttpEntity<>(authHeaders), String.class);

        assertThat(accountsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode accountsJson = mapper.readTree(accountsResponse.getBody());
        assertThat(accountsJson.get("success").asBoolean()).isTrue();
    }

    @Test
    void protectedEndpoint_rejectsRequestWithNoToken() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/accounts", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpoint_rejectsGarbageToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("not-a-real-jwt");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/accounts", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void login_withWrongPassword_returnsUnauthorized() throws Exception {
        String email = "wrongpass-" + System.currentTimeMillis() + "@example.com";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String registerBody = """
                {"email": "%s", "password": "CorrectPass123", "fullName": "Test", "phoneNumber": "+919876543211"}
                """.formatted(email);
        restTemplate.postForEntity("/api/v1/auth/register", new HttpEntity<>(registerBody, headers), String.class);

        String loginBody = """
                {"identifier": "%s", "password": "WrongPassword999"}
                """.formatted(email);
        ResponseEntity<String> loginResponse = restTemplate.postForEntity(
                "/api/v1/auth/login", new HttpEntity<>(loginBody, headers), String.class);

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void register_withDuplicateEmail_returnsConflict() {
        String email = "dupe-" + System.currentTimeMillis() + "@example.com";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"email": "%s", "password": "SecurePass123", "fullName": "First", "phoneNumber": "+919876543212"}
                """.formatted(email);

        restTemplate.postForEntity("/api/v1/auth/register", new HttpEntity<>(body, headers), String.class);
        ResponseEntity<String> secondAttempt = restTemplate.postForEntity(
                "/api/v1/auth/register", new HttpEntity<>(body, headers), String.class);

        assertThat(secondAttempt.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * This is the test that would have caught the real CORS bug found via manual testing: a
     * standalone CorsFilter bean (instead of wiring CORS through HttpSecurity.cors(...)) let a
     * permitAll endpoint still reject real browser requests, because curl/TestRestTemplate-style
     * clients don't send an Origin header by default and so never exercised the broken CORS path
     * at all. Explicitly sending Origin here is what makes this test meaningful — without it,
     * this test would have passed against the broken code too.
     */
    @Test
    void login_withOriginHeader_succeedsAndReturnsCorsAllowOriginHeader() throws Exception {
        String email = "cors-" + System.currentTimeMillis() + "@example.com";
        HttpHeaders registerHeaders = new HttpHeaders();
        registerHeaders.setContentType(MediaType.APPLICATION_JSON);
        String registerBody = """
                {"email": "%s", "password": "SecurePass123", "fullName": "CORS Test", "phoneNumber": "+919876543213"}
                """.formatted(email);
        ResponseEntity<String> registerResponse = restTemplate.postForEntity(
                "/api/v1/auth/register", new HttpEntity<>(registerBody, registerHeaders), String.class);

        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        loginHeaders.set("Origin", "http://localhost:5173"); // the actual header a real browser sends
        String loginBody = """
                {"identifier": "%s", "password": "SecurePass123"}
                """.formatted(email);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/login", new HttpEntity<>(loginBody, loginHeaders), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");

        // Subscription billing V4 (design spec §2): the mobile app needs the real Fynora user id
        // from the auth response itself to call RevenueCat's configureRevenueCat() at sign-in --
        // AuthResponse carried no id field before this. Piggybacks on the register/login round
        // trip already above rather than adding a dedicated test with its own register() call --
        // this file's register rate-limit budget is already fully spent (rate-limit.register.max
        // is 5 per 5 minutes; see application.yml's own comment on why that ceiling is tight on
        // purpose, and this class was already tuned to exactly that many register() calls).
        JsonNode registerJson = mapper.readTree(registerResponse.getBody());
        JsonNode loginJson = mapper.readTree(response.getBody());
        String registeredId = registerJson.get("data").get("id").asText();
        assertThat(registeredId).isNotBlank();
        assertThat(loginJson.get("data").get("id").asText()).isEqualTo(registeredId);
    }
}
