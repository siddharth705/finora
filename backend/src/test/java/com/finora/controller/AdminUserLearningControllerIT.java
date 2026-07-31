package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.MerchantLearningAudit;
import com.finora.entity.User;
import com.finora.repository.MerchantLearningAuditRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Support-assisted per-user Learning Engine visibility (AdminUserLearningController) -- proves
 *  MERCHANT_MANAGE gating and that the timeline is scoped to the userId in the path, not
 *  leaking another user's learning history. */
class AdminUserLearningControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningAuditRepository auditRepository;
    @Autowired private JwtService jwtService;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-user-learning-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin User Learning IT Test User");
        user.setRole(role);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generateToken(user.getId(), user.getEmail()));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private MerchantLearningAudit auditFor(User user, UUID merchantId) {
        MerchantLearningAudit a = new MerchantLearningAudit();
        a.setUserId(user.getId());
        a.setMerchantId(merchantId);
        a.setAction(MerchantLearningAudit.Action.LEARNED);
        a.setNewCategoryId(UUID.randomUUID());
        return auditRepository.save(a);
    }

    @Test
    void plainUser_isForbiddenFromViewingAnotherUsersLearningTimeline() {
        User user = createUser("USER");
        User target = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/learning/timeline",
                HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_seesOnlyTheTargetUsersLearningTimeline_notAnotherUsers() throws Exception {
        User admin = createUser("ADMIN");
        User targetUser = createUser("USER");
        User otherUser = createUser("USER");

        auditFor(targetUser, UUID.randomUUID());
        auditFor(targetUser, UUID.randomUUID());
        auditFor(otherUser, UUID.randomUUID());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + targetUser.getId() + "/learning/timeline",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data).hasSize(2);
    }

    @Test
    void admin_getsTheTargetUsersLearningSummary() throws Exception {
        User admin = createUser("ADMIN");
        User targetUser = createUser("USER");
        auditFor(targetUser, UUID.randomUUID());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + targetUser.getId() + "/learning/summary",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("totalConfirmations").asLong()).isEqualTo(1L);
    }
}
