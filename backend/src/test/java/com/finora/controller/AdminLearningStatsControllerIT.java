package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.MerchantCategoryLearning;
import com.finora.entity.MerchantLearningAudit;
import com.finora.entity.User;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantLearningAuditRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Platform-wide Learning Engine stats (AdminLearningStatsService) -- proves MERCHANT_MANAGE
 *  gating (reused, not a new permission -- see V28's grant) and that the endpoint reflects real
 *  rows rather than always returning an empty/zero shape. */
class AdminLearningStatsControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantCategoryLearningRepository learningRepository;
    @Autowired private MerchantLearningAuditRepository auditRepository;
    @Autowired private JwtService jwtService;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-learning-stats-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Learning Stats IT Test User");
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

    @Test
    void plainUser_isForbiddenFromViewingPlatformLearningStats() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/learning/stats", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void stats_reflectRealLearningRowsAcrossThePlatform() throws Exception {
        User admin = createUser("ADMIN");
        User contributor = createUser("USER");

        MerchantCategoryLearning pair = new MerchantCategoryLearning();
        pair.setUserId(contributor.getId());
        pair.setMerchantId(UUID.randomUUID());
        pair.setCategoryId(UUID.randomUUID());
        pair.setConfirmationCount(1);
        pair.setConfidence(100);
        learningRepository.save(pair);

        MerchantLearningAudit audit = new MerchantLearningAudit();
        audit.setUserId(contributor.getId());
        audit.setMerchantId(pair.getMerchantId());
        audit.setAction(MerchantLearningAudit.Action.LEARNED);
        audit.setNewCategoryId(pair.getCategoryId());
        auditRepository.save(audit);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/learning/stats", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("learnedMerchantPairs").asLong()).isGreaterThanOrEqualTo(1L);
        assertThat(data.get("totalConfirmations").asLong()).isGreaterThanOrEqualTo(1L);
    }
}
