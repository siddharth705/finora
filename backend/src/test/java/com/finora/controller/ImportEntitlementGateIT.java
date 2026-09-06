package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.MultiAccountConfirmRequest;
import com.finora.dto.ImportDto.SectionConfirm;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.SubscriptionService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * plans.ts's "Extended financial history" Plus/Premium promise, enforced -- the second and third
 * FeatureEntitlement keys any endpoint actually checks (after ADVANCED_REPORTS): a Free-plan
 * statement's detected period may not exceed 31 days (ImportController, FeatureEntitlement
 * .EXTENDED_HISTORY) and a Free-plan account may not create a 3rd account (AccountService,
 * FeatureEntitlement.UNLIMITED_ACCOUNTS -- covered separately in AccountServiceTest, a unit test,
 * since that gate needs no HTTP layer to exercise).
 *
 * <p>{@code requireStatementPeriodWithinFreeLimit} runs BEFORE {@code ImportService.confirmSession}
 * / {@code confirmMultiSection} are ever called, so these tests use a fabricated, never-staged
 * {@code sessionId} throughout -- same shortcut {@code ImportConfirmValidationIT} takes for the
 * Bean Validation gate one layer up. A period within the Free limit (or a Plus/Premium caller)
 * must fall through to the real service and fail for an UNRELATED reason (404, the session
 * genuinely doesn't exist) -- proving the gate let the request past rather than merely not having
 * been reached yet.
 */
class ImportEntitlementGateIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private SubscriptionService subscriptionService;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("import-entitlement-gate-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Import Entitlement Gate IT Test User");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> post(String path, User user, Object body) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, bearerFor(user)), String.class);
    }

    private ConfirmedRow row() {
        return new ConfirmedRow(LocalDate.of(2026, 1, 15), "Coffee", BigDecimal.TEN, "EXPENSE",
                "Food", true, "file", null, false, null, null);
    }

    private ConfirmRequest confirmRequest(LocalDate start, LocalDate end) {
        return new ConfirmRequest(UUID.randomUUID(), List.of(row()), UUID.randomUUID(), null,
                null, null, null, start, end, null, null);
    }

    private String errorCodeOf(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody()).get("errorCode").asText();
    }

    // -- Single-account confirm ------------------------------------------------------------------

    @Test
    void csvConfirm_onFreePlan_rejectsAStatementSpanningMoreThanThirtyOneDays() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        ConfirmRequest request = confirmRequest(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("ENTITLEMENT_003");
    }

    @Test
    void csvConfirm_onFreePlan_allowsAStatementOfExactlyThirtyOneDays() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        // Jan 1 - Jan 31 inclusive is exactly 31 days -- the boundary must be let through, not
        // treated as "more than 31".
        ConfirmRequest request = confirmRequest(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, request);

        // Falls through to the real service, which then 404s on the fabricated sessionId -- proof
        // the entitlement gate itself did not block this request.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void csvConfirm_onFreePlan_rejectsAStatementOfThirtyTwoDays() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        ConfirmRequest request = confirmRequest(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("ENTITLEMENT_003");
    }

    @Test
    void csvConfirm_onPlusPlan_isNeverBlockedRegardlessOfPeriodLength() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        subscriptionService.changePlan(user.getId(), "PLUS", "test-upgrade", user.getId());
        ConfirmRequest request = confirmRequest(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1));

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void csvConfirm_withNoPeriodEchoed_isNeverBlocked() throws Exception {
        // A missing period is never itself a reason to hold -- same "carried, not dropped"
        // treatment ImportService.periodOf() already gives an older client or a format with
        // nothing printed to echo.
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        ConfirmRequest request = confirmRequest(null, null);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // -- Multi-account confirm ---------------------------------------------------------------------

    @Test
    void pdfConfirmMulti_onFreePlan_rejectsIfAnySectionExceedsThirtyOneDays() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        SectionConfirm withinLimit = new SectionConfirm(List.of(row()), UUID.randomUUID(), null,
                null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null, null);
        SectionConfirm tooLong = new SectionConfirm(List.of(row()), UUID.randomUUID(), null,
                null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1), null, null);
        MultiAccountConfirmRequest request =
                new MultiAccountConfirmRequest(UUID.randomUUID(), List.of(withinLimit, tooLong));

        ResponseEntity<String> response = post("/api/v1/import/pdf/confirm-multi", user, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("ENTITLEMENT_003");
    }

    @Test
    void pdfConfirmMulti_onFreePlan_allowsEverySectionWithinLimit() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        SectionConfirm section1 = new SectionConfirm(List.of(row()), UUID.randomUUID(), null,
                null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null, null);
        SectionConfirm section2 = new SectionConfirm(List.of(row()), UUID.randomUUID(), null,
                null, null, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), null, null);
        MultiAccountConfirmRequest request =
                new MultiAccountConfirmRequest(UUID.randomUUID(), List.of(section1, section2));

        ResponseEntity<String> response = post("/api/v1/import/pdf/confirm-multi", user, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
