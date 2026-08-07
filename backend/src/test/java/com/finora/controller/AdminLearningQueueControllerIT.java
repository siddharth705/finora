package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Category;
import com.finora.entity.Merchant;
import com.finora.entity.MerchantLearningEvent;
import com.finora.entity.User;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WI2's operator surface.
 *
 * <p>Two things are being proved. First, that the endpoints are gated on
 * {@code LEARNING_QUEUE_MANAGE} rather than on whatever permission the caller happens to hold — a
 * new admin surface that quietly answers to an unrelated permission is the failure mode V34 and
 * V61 both exist to prevent. Second, that one response carries everything an operator needs, which
 * is the requirement the whole work item was scoped around: if the page has to make a second call
 * (or a database query) to name the user or the statement, it has failed.
 */
@TestPropertySource(properties = "app.learning.queue.enabled=false")
class AdminLearningQueueControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private MerchantLearningEventRepository eventRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("learning-queue-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Learning Queue IT User");
        user.setRole(role);
        // An admin is an ADMIN-PORTAL account. Since V52 the scope is what decides whether a
        // role's permissions are granted at all (AuthorizationService), so a fixture setting
        // only the role builds a state the application refuses to create -- RoleService
        // .requireScopeCanHold rejects attaching a permission-bearing role to a USER-scope row.
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** A FAILED event with full correlation, i.e. the row an operator actually opens this page for. */
    private MerchantLearningEvent failedEvent(User owner) {
        Merchant merchant = new Merchant();
        merchant.setUserId(owner.getId());
        merchant.setCanonicalName("Queue IT Merchant " + UUID.randomUUID());
        Merchant savedMerchant = merchantRepository.save(merchant);

        Category category = new Category();
        category.setUserId(owner.getId());
        category.setName("Queue IT Category " + UUID.randomUUID());
        Category savedCategory = categoryRepository.save(category);

        MerchantLearningEvent event = MerchantLearningEvent.pending(
                owner.getId(), savedMerchant.getId(), savedCategory.getId(), null, null);
        for (int i = 0; i < MerchantLearningEvent.MAX_ATTEMPTS; i++) {
            event.recordFailure("constraint violation on (user, merchant, category)", Instant.now());
        }
        return eventRepository.save(event);
    }

    // --- authorization ------------------------------------------------------------------------

    @Test
    void plainUser_isForbiddenFromTheQueue() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/learning-queue", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * The permission this surface is gated on is its OWN, not one it borrows.
     *
     * <p>A legacy ADMIN holds a wide set of permissions but not LEARNING_QUEUE_MANAGE unless V63
     * granted it — which it does, so this asserts the grant landed. The failure this guards against
     * is a future refactor gating the controller on something an admin happens to hold anyway,
     * making the permission decorative.
     */
    @Test
    void adminWithTheGrantedPermission_canReadTheQueue() {
        User admin = createUser("ADMIN");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/learning-queue", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void plainUser_isForbiddenFromRetrying() {
        User user = createUser("USER");
        MerchantLearningEvent event = failedEvent(user);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/learning-queue/" + event.getId() + "/retry",
                HttpMethod.POST, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(eventRepository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(MerchantLearningEvent.Status.FAILED);
    }

    // --- the operator requirement -------------------------------------------------------------

    /**
     * Every question an operator has, answered by one response.
     *
     * <p>This is the acceptance test for the work item's stated goal. Ids alone would pass a naive
     * "does the endpoint return the event" test and fail the actual requirement, because the
     * operator would then go to the database to find out whose account it was and which file it
     * came from.
     */
    @Test
    void oneResponseAnswersWhatFailedWhyForWhomAndFromWhere() throws Exception {
        User admin = createUser("ADMIN");
        User affected = createUser("USER");
        MerchantLearningEvent event = failedEvent(affected);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/learning-queue/" + event.getId(),
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode dto = mapper.readTree(response.getBody()).get("data");

        assertThat(dto.get("status").asText()).isEqualTo("FAILED");
        // Why did it fail, and how many times.
        assertThat(dto.get("lastError").asText()).contains("constraint violation");
        assertThat(dto.get("attemptCount").asInt()).isEqualTo(MerchantLearningEvent.MAX_ATTEMPTS);
        assertThat(dto.get("maxAttempts").asInt()).isEqualTo(MerchantLearningEvent.MAX_ATTEMPTS);
        assertThat(dto.get("firstFailedAt").isNull()).isFalse();
        // Who was affected -- by name, not only by id.
        assertThat(dto.get("userId").asText()).isEqualTo(affected.getId().toString());
        assertThat(dto.get("userEmail").asText()).isEqualTo(affected.getEmail());
        // What the confirmation actually was.
        assertThat(dto.get("merchantName").asText()).startsWith("Queue IT Merchant");
        assertThat(dto.get("categoryName").asText()).startsWith("Queue IT Category");
        // Can it be retried -- computed server-side so the UI cannot drift from the state machine.
        assertThat(dto.get("retryable").asBoolean()).isTrue();
    }

    /** An import with no staging session must read as "no session", never as a fabricated id. */
    @Test
    void anEventWithNoImportSessionReportsNullRatherThanAPlaceholder() throws Exception {
        User admin = createUser("ADMIN");
        MerchantLearningEvent event = failedEvent(createUser("USER"));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/learning-queue/" + event.getId(),
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        JsonNode dto = mapper.readTree(response.getBody()).get("data");
        assertThat(dto.get("importSessionId").isNull()).isTrue();
    }

    // --- actions ------------------------------------------------------------------------------

    @Test
    void retry_requeuesAFailedEventWithAFreshAttemptBudget() throws Exception {
        User admin = createUser("ADMIN");
        MerchantLearningEvent event = failedEvent(createUser("USER"));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/learning-queue/" + event.getId() + "/retry",
                HttpMethod.POST, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        MerchantLearningEvent reloaded = eventRepository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MerchantLearningEvent.Status.PENDING);
        assertThat(reloaded.getAttemptCount()).isZero();
        // The error an admin has already responded to is cleared, so a PENDING row does not read
        // as "failed again" before anything has run.
        assertThat(reloaded.getLastError()).isNull();
        assertThat(reloaded.getFirstFailedAt()).as("history survives the retry").isNotNull();
    }

    /** Retrying something that is not FAILED is a conflict, and the message names the real state —
     *  that is what distinguishes "someone already retried this" from "this cannot be retried". */
    @Test
    void retry_onANonFailedEvent_isRejectedWithTheActualState() throws Exception {
        User admin = createUser("ADMIN");
        MerchantLearningEvent event = failedEvent(createUser("USER"));
        ReflectionTestUtils.setField(event, "status", MerchantLearningEvent.Status.COMPLETED);
        eventRepository.save(event);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/learning-queue/" + event.getId() + "/retry",
                HttpMethod.POST, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("COMPLETED");
    }

    @Test
    void retryAll_requeuesEveryFailedEventAndReportsHowMany() throws Exception {
        User admin = createUser("ADMIN");
        MerchantLearningEvent first = failedEvent(createUser("USER"));
        MerchantLearningEvent second = failedEvent(createUser("USER"));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/learning-queue/retry-all",
                HttpMethod.POST, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(response.getBody()).get("data").get("retried").asInt())
                .isGreaterThanOrEqualTo(2);
        assertThat(eventRepository.findById(first.getId()).orElseThrow().getStatus())
                .isEqualTo(MerchantLearningEvent.Status.PENDING);
        assertThat(eventRepository.findById(second.getId()).orElseThrow().getStatus())
                .isEqualTo(MerchantLearningEvent.Status.PENDING);
    }

    /**
     * RESOLVED is not COMPLETED, and the queue's history depends on the difference.
     *
     * <p>COMPLETED means the learning was applied. RESOLVED means it never will be and a person
     * accepted that. Collapsing them would make the queue lie about what the engine actually
     * learned.
     */
    @Test
    void resolve_closesAFailedEventWithoutApplyingTheLearning() throws Exception {
        User admin = createUser("ADMIN");
        MerchantLearningEvent event = failedEvent(createUser("USER"));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/learning-queue/" + event.getId() + "/resolve", HttpMethod.POST,
                new HttpEntity<>("{\"reason\":\"merchant was merged away\"}", bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        MerchantLearningEvent reloaded = eventRepository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MerchantLearningEvent.Status.RESOLVED);
        assertThat(reloaded.getStatus()).isNotEqualTo(MerchantLearningEvent.Status.COMPLETED);
    }

    // --- filtering, paging, sorting -----------------------------------------------------------

    @Test
    void theStatusFilterNarrowsTheQueue() throws Exception {
        User admin = createUser("ADMIN");
        failedEvent(createUser("USER"));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/learning-queue?status=FAILED&size=100",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        JsonNode content = mapper.readTree(response.getBody()).get("data").get("content");
        assertThat(content).isNotEmpty();
        content.forEach(row -> assertThat(row.get("status").asText()).isEqualTo("FAILED"));
    }

    /** An unknown sort field falls back rather than 500ing. Passing the parameter straight to
     *  Sort.by would reach Hibernate as an unmapped property, which is bad input answered with a
     *  server error. */
    @Test
    void anUnknownSortFieldFallsBackInsteadOfFailing() {
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/learning-queue?sortField=drop%20table&sortDir=desc",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theSummaryCountsEachStatusSeparately() throws Exception {
        User admin = createUser("ADMIN");
        failedEvent(createUser("USER"));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/learning-queue/summary",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        JsonNode summary = mapper.readTree(response.getBody()).get("data");
        assertThat(summary.get("failed").asLong()).isPositive();
        assertThat(summary.has("pending")).isTrue();
        assertThat(summary.has("resolved")).isTrue();
    }
}
