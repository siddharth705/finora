package com.finora.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Merchant;
import com.finora.entity.User;
import com.finora.repository.MerchantRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admin, support-assisted merchant management on behalf of a specific user
 * (AdminUserMerchantController) -- proves MERCHANT_MANAGE gating, that the admin path reaches
 * the exact same MerchantService the self-service console uses (list/rename/merge), and that it
 * stays ownership-scoped to the userId in the path (an admin can't merge a merchant belonging to
 * one user using an id that belongs to a different user).
 */
class AdminUserMerchantControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private JwtService jwtService;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-user-merchant-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin User Merchant IT Test User");
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

    private Merchant merchantFor(User user, String canonicalName) {
        Merchant m = new Merchant();
        m.setUserId(user.getId());
        m.setCanonicalName(canonicalName);
        return merchantRepository.save(m);
    }

    @Test
    void plainUser_isForbiddenFromManagingAnotherUsersMerchants() {
        User user = createUser("USER");
        User target = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/merchants", HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_canListAndRenameATargetUsersMerchant() throws Exception {
        // ADMIN holds MERCHANT_MANAGE per V28__merchant_manage_permission.sql's seed grant.
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        HttpHeaders headers = bearerFor(admin);
        Merchant merchant = merchantFor(target, "Swigy Typo");

        ResponseEntity<String> listResponse = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/merchants", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).contains("Swigy Typo");

        ResponseEntity<String> renameResponse = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/merchants/" + merchant.getId(), HttpMethod.PATCH,
                new HttpEntity<>("{\"canonicalName\":\"Swiggy\"}", headers), String.class);
        assertThat(renameResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(renameResponse.getBody()).get("data").get("canonicalName").asText())
                .isEqualTo("Swiggy");
    }

    @Test
    void admin_canMergeTwoOfATargetUsersMerchants() throws Exception {
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        HttpHeaders headers = bearerFor(admin);
        Merchant surviving = merchantFor(target, "Amazon");
        Merchant duplicate = merchantFor(target, "AMZN Mktp");

        ResponseEntity<String> mergeResponse = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/merchants/" + surviving.getId() + "/merge",
                HttpMethod.POST,
                new HttpEntity<>("{\"mergeFromMerchantId\":\"" + duplicate.getId() + "\"}", headers), String.class);

        assertThat(mergeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(merchantRepository.findById(duplicate.getId())).isEmpty();
        assertThat(merchantRepository.findById(surviving.getId())).isPresent();
    }

    @Test
    void adminActingOnUserA_cannotReachAMerchantThatBelongsToUserB() {
        User admin = createUser("ADMIN");
        User userA = createUser("USER");
        User userB = createUser("USER");
        HttpHeaders headers = bearerFor(admin);
        // Merchant actually belongs to userB, but the request path names userA -- MerchantService
        // .requireOwnedMerchant() must reject this the same way it does for the self-service path.
        Merchant othersMerchant = merchantFor(userB, "Someone Else's Merchant");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + userA.getId() + "/merchants/" + othersMerchant.getId(), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
