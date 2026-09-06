package com.finora.observability;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the two reconciliation counters are actually scrapeable, the same distinction
 * {@link WorkerMetricsExportIT} exists to prove for worker metrics: a meter registered but never
 * exported is indistinguishable from no meter at all in a mocked unit test, and this project has
 * already been burned by that gap once (see that class's own doc comment).
 *
 * <p>Calls {@link ReconciliationMetrics} directly rather than driving a full reconciliation
 * scenario or a real {@code confirmNotDuplicate} call through real data -- this test's job is "does
 * incrementing through this component reach the scrape," not "does {@code ReconciliationService}
 * correctly decide when to call it," which {@code ReconciliationServiceTest} and
 * {@code TransactionServiceTest} already cover with mocks.
 */
class ReconciliationMetricsExportIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ReconciliationMetrics reconciliationMetrics;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

    private HttpHeaders adminBearer() {
        User user = new User();
        user.setEmail("reconciliation-metrics-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Reconciliation Metrics IT User");
        user.setRole("ADMIN");
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        user = userRepository.save(user);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private String scrape() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(adminBearer()), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("an authenticated scrape must work; without it these counters are invisible")
                .isTrue();
        return response.getBody();
    }

    @Test
    void transferMatchedAppearsInTheScrape_withItsRelationshipMatchLabel() {
        reconciliationMetrics.transferMatched(true);

        // Prometheus renders dots as underscores and appends _total to a counter -- the exact
        // translation scripts/check-dashboard-metrics.py exists to police for any future Grafana
        // panel built against this metric.
        assertThat(scrape())
                .contains("finora_reconciliation_transfers_matched_total")
                .contains("relationshipMatch=\"true\"");
    }

    @Test
    void duplicateOverridesAppearInTheScrape_withItsSourceLabel() {
        reconciliationMetrics.duplicateOverridden(Transaction.Source.GMAIL_IMPORT);

        assertThat(scrape())
                .contains("finora_reconciliation_duplicate_overrides_total")
                .contains("source=\"GMAIL_IMPORT\"");
    }
}
