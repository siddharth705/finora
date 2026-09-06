package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.MultiAccountConfirmRequest;
import com.finora.dto.ImportDto.SectionConfirm;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.ImportSession;
import com.finora.entity.User;
import com.finora.imports.ImportSessionService;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * plans.ts's "Extended financial history" Plus/Premium promise, enforced -- the second and third
 * FeatureEntitlement keys any endpoint actually checks (after ADVANCED_REPORTS): a Free-plan
 * statement's detected period may not exceed 31 days (ImportService, FeatureEntitlement
 * .EXTENDED_HISTORY) and a Free-plan account may not create a 3rd account (AccountService,
 * FeatureEntitlement.UNLIMITED_ACCOUNTS -- covered separately in AccountServiceTest, a unit test,
 * since that gate needs no HTTP layer to exercise).
 *
 * <p>Bug fix: this class used to fabricate a never-staged {@code sessionId} throughout, on the
 * premise that {@code requireStatementPeriodWithinFreeLimit} ran in the controller, against
 * {@code request.statementPeriodStart()}/{@code End()}, before {@code ImportService.confirmSession}
 * /{@code confirmMultiSection} were ever reached. That premise made every one of these tests pass
 * against a gate that trusted the client's own echoed period -- exactly the gap it existed to
 * catch, undetected, because the tests only ever sent an HONEST echo. The gate has since moved
 * into {@code ImportService}, reading the period back from THIS session's own server-computed
 * {@code detectedAccountJson}/{@code sectionsJson} instead (see
 * {@code ImportService.requireStatementPeriodWithinFreeLimit}'s own doc comment) -- so these tests
 * now stage a REAL session via {@link ImportSessionService#createSession}/
 * {@link ImportSessionService#createMultiSection} (same shortcut {@code ImportControllerSessionsIT}
 * takes -- a session of a given kind sitting in the STAGED state, not real CSV/PDF parsing) and
 * confirm against its real {@code sessionId}. A period within the Free limit (or a Plus/Premium
 * caller) still falls through to the real service and fails for an UNRELATED reason (404, the
 * {@code existingAccountId} genuinely doesn't exist) -- proving the gate let the request past
 * rather than merely not having been reached yet.
 *
 * <p>The {@code *EvenWhenTheRequestClaims...} tests are the regression coverage for the bug itself:
 * they stage a session whose REAL detected period is over the Free limit, then confirm with a
 * request that claims a shorter (or null) period -- proving the server's decision no longer moves
 * when the request body does.
 */
class ImportEntitlementGateIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private ImportSessionService importSessionService;
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

    // Same date/description/amount/type on both sides -- ConfirmedRowIntegrity.requireSameRows
    // compares exactly those four fields between the staged and confirmed row lists, and this
    // class's single-account confirm tests need to clear that check to ever reach the period gate.
    private StagedRow stagedRow() {
        return new StagedRow(LocalDate.of(2026, 1, 15), "Coffee", BigDecimal.TEN, "EXPENSE",
                "Food", "file", null, false, null, null);
    }

    private ConfirmedRow confirmedRow() {
        return new ConfirmedRow(LocalDate.of(2026, 1, 15), "Coffee", BigDecimal.TEN, "EXPENSE",
                "Food", true, "file", null, false, null, null);
    }

    // Only start/end vary across these tests -- everything else about the detected account is
    // irrelevant to the gate being tested.
    private DetectedAccountInfo detectedAccountWithPeriod(LocalDate start, LocalDate end) {
        return new DetectedAccountInfo("Test Bank", "SAVINGS", new BigDecimal("1000"), new BigDecimal("900"),
                start, end, null, null, null, null, null, null, null, null,
                "SAVINGS", 0.85, false, List.of(), null,
                null, null, null, null, null, null, null);
    }

    /** Stages a real single-account session whose server-side detected period is exactly the one
     *  given -- the value {@code ImportService} now reads the gate's decision from. */
    private UUID stageSingleAccountSession(User user, LocalDate detectedStart, LocalDate detectedEnd) {
        ImportSession session = importSessionService.createSession(user.getId(), "statement.csv",
                "Date,Description,Amount\n2026-01-15,COFFEE,10.00\n".getBytes(StandardCharsets.UTF_8),
                List.of(stagedRow()), detectedAccountWithPeriod(detectedStart, detectedEnd));
        return session.getId();
    }

    /** Stages a real multi-account session whose sections carry the given detected periods, one
     *  section per period, same one-staged-row-per-section shape throughout. */
    private UUID stageMultiAccountSession(User user, List<LocalDate[]> detectedPeriods) {
        List<StagedAccountSection> sections = detectedPeriods.stream()
                .map(p -> new StagedAccountSection(detectedAccountWithPeriod(p[0], p[1]), List.of(stagedRow()), 1, 0, List.of()))
                .toList();
        ImportSession session = importSessionService.createMultiSection(user.getId(), "composite-statement.pdf",
                "composite pdf bytes".getBytes(StandardCharsets.UTF_8), sections);
        return session.getId();
    }

    // claimedStart/End are whatever the REQUEST says the period is -- deliberately independent of
    // the session's real detected period above, since the gate must not read these at all anymore.
    private ConfirmRequest confirmRequest(UUID sessionId, LocalDate claimedStart, LocalDate claimedEnd) {
        return new ConfirmRequest(sessionId, List.of(confirmedRow()), UUID.randomUUID(), null,
                null, null, null, claimedStart, claimedEnd, null, null);
    }

    private String errorCodeOf(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody()).get("errorCode").asText();
    }

    // -- Single-account confirm ------------------------------------------------------------------

    @Test
    void csvConfirm_onFreePlan_rejectsAStatementSpanningMoreThanThirtyOneDays() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        LocalDate start = LocalDate.of(2026, 1, 1), end = LocalDate.of(2026, 3, 31);
        UUID sessionId = stageSingleAccountSession(user, start, end);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, confirmRequest(sessionId, start, end));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("ENTITLEMENT_003");
    }

    @Test
    void csvConfirm_onFreePlan_allowsAStatementOfExactlyThirtyOneDays() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        // Jan 1 - Jan 31 inclusive is exactly 31 days -- the boundary must be let through, not
        // treated as "more than 31".
        LocalDate start = LocalDate.of(2026, 1, 1), end = LocalDate.of(2026, 1, 31);
        UUID sessionId = stageSingleAccountSession(user, start, end);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, confirmRequest(sessionId, start, end));

        // Falls through to the real service, which then 404s on the fabricated existingAccountId
        // -- proof the entitlement gate itself did not block this request.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void csvConfirm_onFreePlan_rejectsAReversedDetectedPeriodOfTheSameRealLength() throws Exception {
        // A reversed (end before start) DETECTED period must reject exactly as its correctly-
        // ordered equivalent would -- a naive `end - start` day count goes negative for this input
        // and always slips under the limit, silently defeating the whole check. Real parsers can
        // genuinely produce this (a mislabelled statement footer, an OCR misread), so this has to
        // hold against the session's own data, not just against a request field.
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        UUID sessionId = stageSingleAccountSession(user, LocalDate.of(2026, 3, 31), LocalDate.of(2026, 1, 1));

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user,
                confirmRequest(sessionId, LocalDate.of(2026, 3, 31), LocalDate.of(2026, 1, 1)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("ENTITLEMENT_003");
    }

    @Test
    void csvConfirm_onFreePlan_rejectsAStatementOfThirtyTwoDays() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        LocalDate start = LocalDate.of(2026, 1, 1), end = LocalDate.of(2026, 2, 1);
        UUID sessionId = stageSingleAccountSession(user, start, end);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, confirmRequest(sessionId, start, end));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("ENTITLEMENT_003");
    }

    @Test
    void csvConfirm_onPlusPlan_isNeverBlockedRegardlessOfPeriodLength() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        subscriptionService.changePlan(user.getId(), "PLUS", "test-upgrade", user.getId());
        LocalDate start = LocalDate.of(2025, 1, 1), end = LocalDate.of(2026, 1, 1);
        UUID sessionId = stageSingleAccountSession(user, start, end);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, confirmRequest(sessionId, start, end));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void csvConfirm_withNoPeriodDetected_isNeverBlocked() throws Exception {
        // A missing period is never itself a reason to hold -- same "carried, not dropped"
        // treatment ImportService.periodOf() already gives an older client or a format with
        // nothing printed to detect.
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        UUID sessionId = stageSingleAccountSession(user, null, null);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, confirmRequest(sessionId, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** The regression test for the actual bug: the request claims a period well within the Free
     *  limit (indeed, none at all) for a session whose real, server-detected period is nearly
     *  three months. Before the fix, the gate read {@code request.statementPeriodStart()}/{@code
     *  End()} directly -- this exact request would have sailed through, no race or malice-
     *  detection needed, just an edited or honestly-stale request body. */
    @Test
    void csvConfirm_onFreePlan_isRejectedEvenWhenTheRequestClaimsAShorterPeriodThanWhatWasActuallyStaged() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        UUID sessionId = stageSingleAccountSession(user, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user,
                confirmRequest(sessionId, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("ENTITLEMENT_003");
    }

    // -- Multi-account confirm ---------------------------------------------------------------------

    private MultiAccountConfirmRequest multiRequest(UUID sessionId, LocalDate... claimedPeriodPairs) {
        List<SectionConfirm> sections = new java.util.ArrayList<>();
        for (int i = 0; i < claimedPeriodPairs.length; i += 2) {
            sections.add(new SectionConfirm(List.of(confirmedRow()), UUID.randomUUID(), null,
                    null, null, claimedPeriodPairs[i], claimedPeriodPairs[i + 1], null, null));
        }
        return new MultiAccountConfirmRequest(sessionId, sections);
    }

    @Test
    void pdfConfirmMulti_onFreePlan_rejectsIfAnySectionExceedsThirtyOneDays() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        LocalDate[] withinLimit = {LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)};
        LocalDate[] tooLong = {LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1)};
        UUID sessionId = stageMultiAccountSession(user, List.of(withinLimit, tooLong));

        ResponseEntity<String> response = post("/api/v1/import/pdf/confirm-multi", user,
                multiRequest(sessionId, withinLimit[0], withinLimit[1], tooLong[0], tooLong[1]));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("ENTITLEMENT_003");
    }

    @Test
    void pdfConfirmMulti_onFreePlan_allowsEverySectionWithinLimit() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        LocalDate[] section1 = {LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)};
        LocalDate[] section2 = {LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)};
        UUID sessionId = stageMultiAccountSession(user, List.of(section1, section2));

        ResponseEntity<String> response = post("/api/v1/import/pdf/confirm-multi", user,
                multiRequest(sessionId, section1[0], section1[1], section2[0], section2[1]));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** Multi-account equivalent of {@code csvConfirm_..._isRejectedEvenWhenTheRequestClaims...}
     *  above: both sections' REQUEST-claimed periods are within the Free limit, but the second
     *  section's real, staged detection is not -- and that is what must decide the outcome. */
    @Test
    void pdfConfirmMulti_onFreePlan_isRejectedEvenWhenTheRequestClaimsShorterPeriodsThanWhatWasActuallyStaged() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        LocalDate[] detectedWithinLimit = {LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)};
        LocalDate[] detectedTooLong = {LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1)};
        UUID sessionId = stageMultiAccountSession(user, List.of(detectedWithinLimit, detectedTooLong));
        LocalDate[] claimedShort = {LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)};

        ResponseEntity<String> response = post("/api/v1/import/pdf/confirm-multi", user,
                multiRequest(sessionId, claimedShort[0], claimedShort[1], claimedShort[0], claimedShort[1]));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("ENTITLEMENT_003");
    }
}
