package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POST /api/v1/auth/identify -- the identifier-first entry step (auth/security review §2.2,
 * docs/proposals/authentication-account-security-review.md). AuthServiceIdentifyTest already
 * covers AuthService.identify()'s branching against mocks; this exercises the real HTTP path
 * (controller wiring, SecurityConfig permitAll, real DB lookup).
 *
 * <p>Seeds test users directly via UserRepository, not through POST /auth/register, the same way
 * LoginExistenceOracleIT does -- AbstractIntegrationTest's own doc names a shared RateLimiter
 * singleton drawn down across unrelated *IT classes in the same run as a previously diagnosed
 * failure mode. Going through /auth/register here would spend that same shared registerLimiter
 * budget for no reason: identify() never calls register(), so there is nothing to prove by
 * routing through it.
 */
class AuthIdentifyFlowIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    private User registeredPasswordUser() {
        User user = new User();
        user.setEmail("identify-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("unused-in-this-test");
        user.setFullName("Identify Test User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private ResponseEntity<String> identify(String identifier) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"identifier": "%s"}
                """.formatted(identifier);
        return restTemplate.postForEntity("/api/v1/auth/identify", new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void identify_forRegisteredEmail_returnsExists() throws Exception {
        User user = registeredPasswordUser();

        ResponseEntity<String> response = identify(user.getEmail());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = mapper.readTree(response.getBody());
        assertThat(json.get("data").get("nextAction").asText()).isEqualTo("EXISTS");
    }

    @Test
    void identify_forUnregisteredIdentifier_returnsContinue_notARawExistsBoolean() throws Exception {
        ResponseEntity<String> response = identify("nobody-" + UUID.randomUUID() + "@example.com");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = mapper.readTree(response.getBody());
        assertThat(json.get("data").get("nextAction").asText()).isEqualTo("CONTINUE");
        // The whole point of §2.2's nextAction design: no field on this response is a directly
        // machine-readable existence boolean.
        assertThat(json.get("data").has("exists")).isFalse();
    }

    @Test
    void identify_rejectsBlankIdentifier() {
        ResponseEntity<String> response = identify("");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
