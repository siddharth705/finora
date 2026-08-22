package com.finora.integrations.google;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The authorization boundary on the trusted sender registry — Phase C3.
 *
 * <p>Decided by {@code SecurityConfig} and method security rather than by service code, so it can
 * only be proven by driving the endpoints. This is a security-relevant registry: a row here grants
 * parse-trust to a sender, so an ordinary authenticated user must not be able to read it, add to it,
 * or disable anything in it.
 *
 * <p>The seeded rows from V82 are also checked here, because an empty registry rejects every message
 * — safe, but indistinguishable from a broken gate, and only a real database can show whether the
 * migration's seed actually landed.
 */
class AdminTrustedSenderEndpointIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/admin/trusted-senders";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private TrustedSenderDomainRepository domains;

    private User createOrdinaryUser() {
        User user = new User();
        user.setEmail("trusted-sender-it-" + UUID.randomUUID() + "@example.test");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Ordinary User");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    @Test
    void listing_requiresAuthentication() {
        ResponseEntity<String> response = restTemplate.exchange(
                BASE, HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    /**
     * The boundary that matters. A signed-in customer is authenticated but must not be able to read
     * which senders Finora trusts, let alone change them.
     */
    @Test
    @DisplayName("an ordinary authenticated user cannot read the registry")
    void listing_isRefusedToANonAdmin() {
        User user = createOrdinaryUser();

        ResponseEntity<String> response = restTemplate.exchange(
                BASE, HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** Adding a domain grants parse-trust to a new sender — the most sensitive operation here. */
    @Test
    @DisplayName("an ordinary authenticated user cannot grant trust to a new sender")
    void adding_isRefusedToANonAdmin() {
        User user = createOrdinaryUser();
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(BASE, HttpMethod.POST,
                new HttpEntity<>("{\"domain\":\"attacker.example\",\"merchantName\":\"Fake\"}", headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(domains.findByDomain("attacker.example"))
                .as("a refused request must not have written anything")
                .isEmpty();
    }

    @Test
    void disabling_isRefusedToANonAdmin() {
        User user = createOrdinaryUser();
        TrustedSenderDomain seeded = domains.findByDomain("amazon.in").orElseThrow();

        ResponseEntity<String> response = restTemplate.exchange(BASE + "/" + seeded.getId(),
                HttpMethod.DELETE, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(domains.findByDomain("amazon.in").orElseThrow().isActive())
                .as("a refused disable must leave the domain trusted")
                .isTrue();
    }

    /**
     * V82 seeds the initial merchant set deliberately: an empty registry rejects every message,
     * which is safe but indistinguishable from a broken gate on first deploy. Only a real database
     * shows whether the seed landed.
     */
    @Test
    void theMigrationSeedsTheInitialMerchantDomainsAsActive() {
        for (String domain : new String[]{"amazon.in", "myntra.com", "uber.com",
                                          "olacabs.com", "zomato.com", "booking.com"}) {
            assertThat(domains.findByDomain(domain))
                    .as("%s should be seeded by V82", domain)
                    .isPresent()
                    .get()
                    .matches(TrustedSenderDomain::isActive, "active");
        }
    }

    /** V103's readiness seed -- 50 more domains, trusted (ACTIVE) but paired with disabled
     *  templates (see AdminMerchantTemplateEndpointIT's own migration test for the template
     *  half). Only a small representative sample across the seed's category spread (food
     *  delivery, e-commerce, travel, payments, OTT, telecom, insurance), not all 50 -- proving the
     *  migration landed at all does not need enumerating every row, the same reasoning
     *  theMigrationSeedsTheInitialMerchantDomainsAsActive already applies to V82's own 6. */
    @Test
    void theReadinessSeedTrustsFiftyMoreDomains() {
        for (String domain : new String[]{"swiggy.com", "flipkart.com", "irctc.co.in",
                                          "phonepe.com", "netflix.com", "airtel.in", "hdfcergo.com"}) {
            assertThat(domains.findByDomain(domain))
                    .as("%s should be seeded ACTIVE by V103", domain)
                    .isPresent()
                    .get()
                    .matches(TrustedSenderDomain::isActive, "active");
        }
    }

    /** The unique index is what actually prevents a domain existing twice with different statuses,
     *  which would make "is this trusted?" depend on which row a query happened to read. */
    @Test
    void aDomainCannotBeStoredTwice() {
        TrustedSenderDomain duplicate = new TrustedSenderDomain();
        duplicate.setDomain("amazon.in");
        duplicate.setMerchantName("Duplicate Amazon");
        duplicate.setStatus(TrustedSenderDomain.Status.DISABLED);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> domains.saveAndFlush(duplicate)))
                .as("the partial-free unique index in V82 must reject a second row for one domain")
                .isNotNull();
    }
}
