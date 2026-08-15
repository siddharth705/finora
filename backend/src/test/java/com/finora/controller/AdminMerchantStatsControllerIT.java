package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Merchant;
import com.finora.entity.User;
import com.finora.integrations.google.GmailApiClient;
import com.finora.integrations.google.GmailConnection;
import com.finora.integrations.google.GmailConnectionRepository;
import com.finora.integrations.google.GmailProcessedMessage;
import com.finora.integrations.google.GmailProcessedMessageRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform-wide merchant catalog (AdminMerchantStatsService / MerchantRepository
 * .platformMerchantCounts()) -- proves MERCHANT_MANAGE gating and that the aggregate correctly
 * counts DISTINCT users vs. total rows when more than one account has independently created a
 * Merchant with the same canonicalName (the exact scenario the userCount/rowCount split exists
 * to distinguish). Also covers the same controller's Gmail parser-health endpoint (C6.2) --
 * {@code GmailMerchantStatsServiceTest}/{@code -ServiceIT} cover that endpoint's own aggregation
 * logic in depth; this only proves the HTTP-layer wiring (permission gating, the required
 * {@code since} param, real end-to-end serialization).
 */
class AdminMerchantStatsControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private GmailConnectionRepository gmailConnectionRepository;
    @Autowired private GmailProcessedMessageRepository gmailProcessedMessageRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-merchant-stats-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Merchant Stats IT Test User");
        user.setRole(role);
        // An admin is an ADMIN-PORTAL account. Since V52 the scope is what decides whether a
        // role's permissions are granted at all (AuthorizationService), so a fixture setting
        // only the role builds a state the application refuses to create -- RoleService
        // .requireScopeCanHold rejects attaching a permission-bearing role to a USER-scope row.
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private Merchant merchantFor(User user, String canonicalName) {
        Merchant m = new Merchant();
        m.setUserId(user.getId());
        m.setCanonicalName(canonicalName);
        return merchantRepository.save(m);
    }

    @Test
    void plainUser_isForbiddenFromViewingPlatformMerchantStats() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/merchants/stats", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void statsRow_countsDistinctUsersSeparatelyFromTotalRows() throws Exception {
        // ADMIN holds MERCHANT_MANAGE per V28__merchant_manage_permission.sql's seed grant.
        User admin = createUser("ADMIN");
        User userOne = createUser("USER");
        User userTwo = createUser("USER");
        String uniqueName = "Test Merchant " + UUID.randomUUID();

        // Two different users each independently created their own Merchant row with the same
        // canonicalName -- userCount must read 2, rowCount must read 2 (not 1, which would mean
        // the aggregate is deduping rows rather than counting them).
        merchantFor(userOne, uniqueName);
        merchantFor(userTwo, uniqueName);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/merchants/stats", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        JsonNode row = null;
        for (JsonNode candidate : data) {
            if (candidate.get("canonicalName").asText().equals(uniqueName)) {
                row = candidate;
                break;
            }
        }
        assertThat(row).as("stats row for " + uniqueName).isNotNull();
        assertThat(row.get("userCount").asLong()).isEqualTo(2);
        assertThat(row.get("rowCount").asLong()).isEqualTo(2);
    }

    @Test
    void plainUser_isForbiddenFromViewingGmailParserStats() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                gmailParserStatsUrl(Instant.now().minus(1, ChronoUnit.DAYS)),
                HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void gmailParserStats_reportsRealOutcomeCountsPerDomain() throws Exception {
        User admin = createUser("ADMIN");
        String domain = "unique-merchant-" + UUID.randomUUID() + ".test";
        UUID connectionId = persistGmailConnection(admin).getId();
        gmailProcessedMessageRepository.saveAndFlush(GmailProcessedMessage.trusted(
                connectionId, "msg-1", GmailProcessedMessage.Outcome.PARSED, domain));
        gmailProcessedMessageRepository.saveAndFlush(GmailProcessedMessage.trusted(
                connectionId, "msg-2", GmailProcessedMessage.Outcome.PARSE_FAILED, domain));

        ResponseEntity<String> response = restTemplate.exchange(
                gmailParserStatsUrl(Instant.now().minus(1, ChronoUnit.DAYS)),
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        JsonNode row = null;
        for (JsonNode candidate : data) {
            if (candidate.get("domain").asText().equals(domain)) {
                row = candidate;
                break;
            }
        }
        assertThat(row).as("gmail parser stats row for " + domain).isNotNull();
        assertThat(row.get("parsed").asLong()).isEqualTo(1);
        assertThat(row.get("parseFailed").asLong()).isEqualTo(1);
        assertThat(row.get("successRate").asDouble()).isEqualTo(0.5);
    }

    @Test
    void gmailParserStats_requiresTheSinceParameter() {
        User admin = createUser("ADMIN");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/merchants/gmail-parser-stats",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private URI gmailParserStatsUrl(Instant since) {
        return UriComponentsBuilder.fromPath("/api/v1/admin/merchants/gmail-parser-stats")
                .queryParam("since", since.toString())
                .build(true).toUri();
    }

    private GmailConnection persistGmailConnection(User owner) {
        GmailConnection connection = new GmailConnection();
        connection.setUserId(owner.getId());
        connection.setGoogleUserId("google-sub-" + UUID.randomUUID());
        connection.setGoogleEmail("mailbox-" + UUID.randomUUID() + "@example.test");
        connection.setGrantedScopes(GmailApiClient.GMAIL_READONLY_SCOPE);
        connection.setStatus(GmailConnection.Status.CONNECTED);
        return gmailConnectionRepository.saveAndFlush(connection);
    }
}
