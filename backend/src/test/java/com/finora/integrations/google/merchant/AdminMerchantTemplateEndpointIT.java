package com.finora.integrations.google.merchant;

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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The authorization boundary and a real end-to-end pass, mirroring
 * {@code AdminTrustedSenderEndpointIT}'s own shape for the sibling registry. Gated on
 * {@code MERCHANT_MANAGE}, not {@code SYSTEM_SETTINGS} -- see
 * {@code MerchantTemplateAdminService}'s class doc for why -- so the boundary here is "an ordinary
 * user", not specifically "a non-SYSTEM_SETTINGS admin".
 */
class AdminMerchantTemplateEndpointIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/admin/merchant-templates";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private MerchantTemplateRepository templates;

    private User createOrdinaryUser() {
        return createUser("USER");
    }

    /** Mirrors {@code AdminMerchantStatsControllerIT.createUser}'s own pattern: since V52 the
     *  account SCOPE (not just the role string) is what RoleService.requireScopeCanHold checks
     *  before a role's permissions are actually granted, so an ADMIN-role row still gets no
     *  authorities at all unless its scope is also SCOPE_ADMIN. */
    private User createUser(String role) {
        User user = new User();
        user.setEmail("merchant-template-it-" + UUID.randomUUID() + "@example.test");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Merchant Template IT Test User");
        user.setRole(role);
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
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

    @Test
    @DisplayName("an ordinary authenticated user cannot read the template list")
    void listing_isRefusedToANonAdmin() {
        User user = createOrdinaryUser();

        ResponseEntity<String> response = restTemplate.exchange(
                BASE, HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("an ordinary authenticated user cannot create a template")
    void creating_isRefusedToANonAdmin() {
        User user = createOrdinaryUser();
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(BASE, HttpMethod.POST,
                new HttpEntity<>(Map.of("merchantDomain", "attacker.example", "merchantName", "Fake",
                        "receiptMarker", "Total", "amountPattern", "Rs. {amount}", "datePattern", "{date}"),
                        headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(templates.findByMerchantDomain("attacker.example"))
                .as("a refused request must not have written anything")
                .isEmpty();
    }

    /**
     * V85/V86 seed Uber and Zomato with no admin actor -- checked here for the same reason
     * {@code AdminTrustedSenderEndpointIT.theMigrationSeedsTheInitialMerchantDomainsAsActive}
     * checks its own seeds: only a real database shows whether the migration's seed actually
     * landed, and both migration-seeded templates should list correctly through this new endpoint.
     */
    @Test
    @DisplayName("the migration-seeded Uber and Zomato templates are visible and enabled")
    void theMigrationSeedsAreVisibleAndEnabled() {
        for (String domain : new String[]{"uber.com", "zomato.com"}) {
            assertThat(templates.findByMerchantDomain(domain))
                    .as("%s should be seeded by V85/V86", domain)
                    .isPresent()
                    .get()
                    .matches(MerchantTemplate::isEnabled, "enabled");
        }
    }

    /** V103's readiness seed -- 50 more templates, unlike V85/V86's Uber/Zomato rows these are
     *  seeded DISABLED on purpose (see that migration's own comment: every pattern is a best
     *  guess, none verified against a real sample email). Checked here specifically because that
     *  disabled-by-default state is the entire reason a 50-row bulk seed of unverified patterns is
     *  safe at all -- if this regressed to enabled, wrong guesses could mis-stage real amounts. */
    @Test
    void theReadinessSeedTemplatesAreDisabledByDefault() {
        for (String domain : new String[]{"swiggy.com", "flipkart.com", "irctc.co.in",
                                          "netflix.com", "airtel.in", "hdfcergo.com"}) {
            assertThat(templates.findByMerchantDomain(domain))
                    .as("%s should be seeded by V103, disabled pending a real test", domain)
                    .isPresent()
                    .get()
                    .matches(t -> !t.isEnabled(), "disabled");
        }
    }

    /** V109 removed phonepe.com/paytm.com/cred.club from this table entirely -- the declarative
     *  template model cannot represent a counterparty distinct from the domain, so these three now
     *  have no merchant_templates row at all (PhonePeEmailParser/CredEmailParser cover two of them
     *  as hand-written, config-gated parsers instead; paytm.com is intentionally unparsed). Their
     *  gmail_trusted_sender_domains rows are untouched -- this checks only the template half. */
    @Test
    @DisplayName("phonepe/paytm/cred have no merchant_templates row -- the declarative model was wrong for them")
    void theP2PDomainsHaveNoTemplateRow() {
        for (String domain : new String[]{"phonepe.com", "paytm.com", "cred.club"}) {
            assertThat(templates.findByMerchantDomain(domain))
                    .as("%s should have been removed by V109", domain)
                    .isEmpty();
        }
    }

    /** V104 corrects V103's dominos.co.in guess to a pattern verified against two real
     *  "Order Successful" emails (see that migration's own comment for what the original guess got
     *  wrong). Checked here so a future migration cannot silently regress the corrected values back
     *  toward the old, unverified guess without a test noticing. Still disabled -- being
     *  pattern-verified is not the same as being activated; see V104's own comment. */
    @Test
    void theDominosTemplateIsCorrectedByV104() {
        assertThat(templates.findByMerchantDomain("dominos.co.in"))
                .isPresent()
                .get()
                .satisfies(t -> {
                    assertThat(t.getReceiptMarker()).isEqualTo("Order Confirmed");
                    assertThat(t.getAmountPattern()).isEqualTo("Grand Total : Rs.{amount}");
                    assertThat(t.getDatePattern()).isEqualTo("|{date}|");
                    assertThat(t.isEnabled()).isFalse();
                });
    }

    @Test
    @DisplayName("create -> test -> activate happy path")
    void createTestActivateHappyPath() {
        // Login as an ADMIN-role user -- ADMIN holds MERCHANT_MANAGE per V16.
        User admin = createUser("ADMIN");
        HttpHeaders headers = bearerFor(admin);
        headers.setContentType(MediaType.APPLICATION_JSON);

        String domain = "it-swiggy-" + UUID.randomUUID().toString().substring(0, 8) + ".example";
        ResponseEntity<Map> createResponse = restTemplate.exchange(BASE, HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "merchantDomain", domain, "merchantName", "Swiggy",
                        "receiptMarker", "Order Summary",
                        "amountPattern", "Grand Total: Rs. {amount}",
                        "datePattern", "Order Date: {date}"),
                        headers),
                Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> created = (Map<String, Object>) createResponse.getBody().get("data");
        assertThat(created.get("enabled"))
                .as("disabled by default, regardless of what was sent")
                .isEqualTo(false);
        String id = (String) created.get("id");

        ResponseEntity<Map> testResponse = restTemplate.exchange(BASE + "/test", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "merchantDomain", domain, "receiptMarker", "Order Summary",
                        "amountPattern", "Grand Total: Rs. {amount}", "datePattern", "Order Date: {date}",
                        "sampleHtml", "<html><body>Order Summary<br>Grand Total: Rs. 499.00<br>"
                                + "Order Date: August 12, 2026</body></html>"),
                        headers),
                Map.class);
        assertThat(testResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> testResult = (Map<String, Object>) testResponse.getBody().get("data");
        assertThat(testResult.get("status")).isEqualTo("PARSED");
        assertThat(testResult.get("amount")).isEqualTo(499.0);

        ResponseEntity<Map> activateResponse = restTemplate.exchange(BASE + "/" + id + "/activate",
                HttpMethod.POST, new HttpEntity<>(headers), Map.class);
        assertThat(activateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> activated = (Map<String, Object>) activateResponse.getBody().get("data");
        assertThat(activated.get("enabled")).isEqualTo(true);

        assertThat(templates.findByMerchantDomain(domain))
                .isPresent()
                .get()
                .matches(MerchantTemplate::isEnabled, "enabled");
    }

    @Test
    @DisplayName("a non-receipt marker excludes a refund-shaped sample but not a genuine receipt, end to end")
    void testEndpoint_nonReceiptMarkerExcludesARefundButNotAGenuineReceipt() {
        User admin = createUser("ADMIN");
        HttpHeaders headers = bearerFor(admin);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String domain = "it-refund-" + UUID.randomUUID().toString().substring(0, 8) + ".example";

        ResponseEntity<Map> refundResponse = restTemplate.exchange(BASE + "/test", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "merchantDomain", domain, "receiptMarker", "Order Summary",
                        "nonReceiptMarker", "Refund Processed|Order Cancelled",
                        "amountPattern", "Grand Total: Rs. {amount}", "datePattern", "Order Date: {date}",
                        "sampleHtml", "<html><body>Order Summary<br>Refund Processed<br>"
                                + "Grand Total: Rs. 499.00<br>Order Date: August 12, 2026</body></html>"),
                        headers),
                Map.class);
        assertThat(refundResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> refundResult = (Map<String, Object>) refundResponse.getBody().get("data");
        assertThat(refundResult.get("status")).isEqualTo("NOT_A_RECEIPT");
        assertThat((String) refundResult.get("reason")).contains("non-receipt marker");

        ResponseEntity<Map> receiptResponse = restTemplate.exchange(BASE + "/test", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "merchantDomain", domain, "receiptMarker", "Order Summary",
                        "nonReceiptMarker", "Refund Processed|Order Cancelled",
                        "amountPattern", "Grand Total: Rs. {amount}", "datePattern", "Order Date: {date}",
                        "sampleHtml", "<html><body>Order Summary<br>Grand Total: Rs. 499.00<br>"
                                + "Order Date: August 12, 2026</body></html>"),
                        headers),
                Map.class);
        assertThat(receiptResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> receiptResult = (Map<String, Object>) receiptResponse.getBody().get("data");
        assertThat(receiptResult.get("status")).isEqualTo("PARSED");
        assertThat(receiptResult.get("amount")).isEqualTo(499.0);
    }

    @Test
    @DisplayName("creating a template for amazon.in (already a hand-written parser) is refused")
    void creating_refusesADomainAHandWrittenParserAlreadyClaims() {
        User admin = createUser("ADMIN");
        HttpHeaders headers = bearerFor(admin);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(BASE, HttpMethod.POST,
                new HttpEntity<>(Map.of("merchantDomain", "amazon.in", "merchantName", "Amazon",
                        "receiptMarker", "Order #", "amountPattern", "Total: Rs. {amount}",
                        "datePattern", "Date: {date}"), headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
