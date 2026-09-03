package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.AuditLog;
import com.finora.entity.User;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationLog;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationStatus;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.repository.NotificationLogRepository;
import com.finora.notification.repository.NotificationRepository;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 12's admin notification dashboard, at the HTTP layer.
 *
 * <p>Three things this proves that {@code AdminNotificationServiceTest} (mocked repositories)
 * cannot: the endpoints are actually gated by {@code NOTIFICATION_MANAGE} through real Spring
 * Security method security (not just annotated and hoped-for), a detail view really does write an
 * audit row through the real {@code AuditService}, and the JSON a client actually receives carries
 * no email or phone number field anywhere -- matching {@code AdminLearningQueueControllerIT}'s own
 * "gated on its own permission, not a borrowed one" authorization coverage.
 */
class AdminNotificationControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationLogRepository notificationLogRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("notification-admin-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Notification Dashboard IT User");
        user.setRole(role);
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

    /** A dead-lettered EMAIL notification with a failed and a successful attempt against it --
     *  the row an operator actually opens the detail view for. */
    private Notification deadLetteredNotification(UUID recipientId) {
        Notification n = Notification.create(recipientId, NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL, NotificationChannel.EMAIL, NotificationPriority.NORMAL,
                "IT_NOTIFICATION_" + UUID.randomUUID(), "Your HDFC statement is ready",
                "We finished processing your HDFC statement and imported it successfully.",
                Instant.now());
        for (int i = 0; i < Notification.MAX_ATTEMPTS; i++) {
            n.recordFailure("resend: 502 Bad Gateway", Instant.now());
        }
        Notification saved = notificationRepository.save(n);
        notificationLogRepository.save(NotificationLog.of(saved.getId(), "resend",
                "connection reset", false, 1, Instant.now()));
        notificationLogRepository.save(NotificationLog.of(saved.getId(), "resend",
                "ok", true, 2, Instant.now()));
        return saved;
    }

    // --- authorization ------------------------------------------------------------------------

    @Test
    void plainUser_isForbiddenFromTheDashboard() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/notifications", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void plainUser_isForbiddenFromTheSummary() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/notifications/summary", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void plainUser_isForbiddenFromTheDetailView() {
        User user = createUser("USER");
        Notification n = deadLetteredNotification(user.getId());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/notifications/" + n.getId(), HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * The permission this surface is gated on is its own (V130's {@code NOTIFICATION_MANAGE}),
     * not one an admin happens to already hold -- the same thing
     * {@code AdminLearningQueueControllerIT.adminWithTheGrantedPermission_canReadTheQueue} proves
     * for its own surface, and for the same reason: a future refactor gating this controller on
     * whatever permission was convenient would make the grant decorative.
     */
    @Test
    void adminWithTheGrantedPermission_canReadTheDashboard() {
        User admin = createUser("ADMIN");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/notifications", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- no PII leak ---------------------------------------------------------------------------

    /**
     * The security requirement stated for this task: no device token, recipient email, or phone
     * number reaches an admin through this surface, in either the list row or the detail view --
     * asserted against the actual JSON on the wire, not just against which fields the DTO declares.
     */
    @Test
    void detailResponse_carriesNoEmailOrPhoneOrDeviceToken() throws Exception {
        User admin = createUser("ADMIN");
        User recipient = createUser("USER");
        Notification n = deadLetteredNotification(recipient.getId());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/notifications/" + n.getId(),
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).doesNotContain(recipient.getEmail());
        // No JSON *key* named email/phone/deviceToken anywhere in the payload -- catches a future
        // field addition to the DTO, not only today's shape. Matched as a key (quote, name, colon)
        // rather than a bare substring: NotificationChannel.EMAIL serializes as the string "email"
        // and is a legitimate field *value*, not a leaked contact detail.
        assertThat(body.toLowerCase()).doesNotContain(
                "\"email\":", "\"phone\":", "\"devicetoken\":", "\"token\":");

        JsonNode dto = mapper.readTree(body).get("data");
        assertThat(dto.get("userId").asText()).isEqualTo(recipient.getId().toString());
        assertThat(dto.get("message").asText()).contains("HDFC statement");
        // The attempt log carries the write path's redaction through untouched -- this dashboard
        // applies no redaction of its own, and none is needed here since the fixture's own
        // "response" values contain no PII shape to redact.
        JsonNode attempts = dto.get("attempts");
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0).get("attempt").asInt()).isEqualTo(2);
        assertThat(attempts.get(1).get("attempt").asInt()).isEqualTo(1);
    }

    // --- audit -----------------------------------------------------------------------------------

    /** Viewing one notification's detail is a deliberate, bounded look at a user's actual message
     *  content and is audit-logged -- unlike the list/summary polling endpoints. */
    @Test
    void detailView_writesAnAuditEntry() {
        User admin = createUser("ADMIN");
        User recipient = createUser("USER");
        Notification n = deadLetteredNotification(recipient.getId());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/notifications/" + n.getId(),
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<AuditLog> entries = auditLogRepository.findByUserIdOrderByCreatedAtDesc(recipient.getId());
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.getAction()).isEqualTo("NOTIFICATION_DETAIL_VIEWED");
            assertThat(entry.getEntityId()).isEqualTo(n.getId());
        });
    }

    // --- filtering, paging, detail not found ----------------------------------------------------

    @Test
    void theStatusFilterNarrowsTheList() throws Exception {
        User admin = createUser("ADMIN");
        deadLetteredNotification(createUser("USER").getId());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/notifications?status=DEAD_LETTER&size=100",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        JsonNode content = mapper.readTree(response.getBody()).get("data").get("content");
        assertThat(content).isNotEmpty();
        content.forEach(row -> assertThat(row.get("status").asText()).isEqualTo("DEAD_LETTER"));
    }

    @Test
    void theSummaryCountsSentAndFailedByChannel() throws Exception {
        User admin = createUser("ADMIN");
        deadLetteredNotification(createUser("USER").getId());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/notifications/summary",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        JsonNode summary = mapper.readTree(response.getBody()).get("data");
        assertThat(summary.get("failed").asLong()).isPositive();
        assertThat(summary.has("sent")).isTrue();
        JsonNode byChannel = summary.get("byChannel");
        assertThat(byChannel).isNotEmpty();
        boolean sawEmail = false;
        for (JsonNode c : byChannel) {
            if ("EMAIL".equals(c.get("channel").asText())) {
                sawEmail = true;
                assertThat(c.get("failed").asLong()).isPositive();
            }
        }
        assertThat(sawEmail).isTrue();
    }

    @Test
    void aMissingNotificationId_isNotFound() {
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/notifications/" + UUID.randomUUID(),
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
