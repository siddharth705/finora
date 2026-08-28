package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Category;
import com.finora.entity.User;
import com.finora.repository.CategoryRepository;
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
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private CategoryRepository categoryRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private User createUser() {
        User u = new User();
        u.setEmail("cat-" + UUID.randomUUID() + "@test.com");
        u.setPasswordHash("x");
        u.setFullName("Category Controller IT User");
        u.setPhoneVerified(true);
        return userRepository.save(u);
    }

    private HttpHeaders authHeaders(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    @Test
    void optionsReturnsTheCuratedIconAndColorTokenLists() throws Exception {
        User user = createUser();
        var response = restTemplate.exchange("/api/v1/categories/options", HttpMethod.GET,
                new HttpEntity<>(authHeaders(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).get("data");
        assertThat(data.get("icons").isArray()).isTrue();
        assertThat(data.get("icons").size()).isGreaterThan(0);
        assertThat(data.get("colors").size()).isGreaterThan(0);
    }

    @Test
    void createRejectsADuplicateNameCaseInsensitively() {
        User user = createUser();
        HttpHeaders headers = authHeaders(user);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        restTemplate.postForEntity("/api/v1/categories",
                new HttpEntity<>(java.util.Map.of("name", "SIP"), headers), String.class);
        var second = restTemplate.postForEntity("/api/v1/categories",
                new HttpEntity<>(java.util.Map.of("name", "sip"), headers), String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void renamingASystemCategoryIs403() {
        User user = createUser();
        HttpHeaders headers = authHeaders(user);

        // createUser() saves the User directly, bypassing AuthService.register()'s default-category
        // seeding -- so seed a system category by hand rather than depending on the register flow.
        Category groceries = new Category();
        groceries.setUserId(user.getId());
        groceries.setName("Groceries");
        groceries.setSystem(true);
        groceries.setIcon("shopping-cart");
        groceries.setColor("green");
        Category saved = categoryRepository.save(groceries);

        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var response = restTemplate.exchange("/api/v1/categories/" + saved.getId(), HttpMethod.PATCH,
                new HttpEntity<>(java.util.Map.of("name", "Food"), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void usageStartsAtZeroForABrandNewCategory() throws Exception {
        User user = createUser();
        HttpHeaders headers = authHeaders(user);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var created = restTemplate.postForEntity("/api/v1/categories",
                new HttpEntity<>(java.util.Map.of("name", "SIP"), headers), String.class);
        String categoryId = objectMapper.readTree(created.getBody()).get("data").get("id").asText();

        var response = restTemplate.exchange("/api/v1/categories/" + categoryId + "/usage",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        JsonNode data = objectMapper.readTree(response.getBody()).get("data");
        assertThat(data.get("transactionCount").asLong()).isEqualTo(0);
        assertThat(data.get("hasBudget").asBoolean()).isFalse();
        assertThat(data.get("ruleCount").asLong()).isEqualTo(0);
    }
}
