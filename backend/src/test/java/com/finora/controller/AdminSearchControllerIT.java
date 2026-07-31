package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Bank;
import com.finora.entity.User;
import com.finora.repository.BankRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Global Search (AdminSearchController) -- proves the endpoint is reachable by any authenticated
 *  account (no narrow @PreAuthorize gate, see the controller's class comment for why), rejects
 *  requests with no token at all, and that a real created User and a real created custom Bank
 *  both surface in the fanned-out results for a query that matches them. */
class AdminSearchControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private BankRepository bankRepository;
    @Autowired private JwtService jwtService;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String fullName) {
        User user = new User();
        user.setEmail("admin-search-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName(fullName);
        user.setRole("USER");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generateToken(user.getId(), user.getEmail()));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> search(String q, HttpHeaders headers) {
        String url = UriComponentsBuilder.fromPath("/api/v1/admin/search").queryParam("q", q).toUriString();
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    void search_withNoTokenAtAll_isUnauthorized() {
        ResponseEntity<String> response = search("anything", new HttpHeaders());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void search_anyAuthenticatedAccount_canReachIt_evenWithNoAdminPermissions() throws Exception {
        String uniqueName = "Zephyr Global Search Target " + UUID.randomUUID();
        User plainUser = createUser("Plain User");
        User target = createUser(uniqueName);

        ResponseEntity<String> response = search(uniqueName, bearerFor(plainUser));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        boolean found = false;
        for (JsonNode row : data) {
            if (row.get("type").asText().equals("user") && row.get("id").asText().equals(target.getId().toString())) {
                found = true;
                assertThat(row.get("title").asText()).isEqualTo(uniqueName);
                assertThat(row.get("link").asText()).isEqualTo("/users/" + target.getId());
            }
        }
        assertThat(found).as("expected user result for " + uniqueName).isTrue();
    }

    @Test
    void search_findsARealCustomBankByShortName() throws Exception {
        User admin = createUser("Search Admin");
        String uniqueShortName = "ZBANK" + UUID.randomUUID().toString().substring(0, 8);

        Bank bank = new Bank();
        bank.setId("zbank-" + UUID.randomUUID());
        bank.setOfficialName("Zephyr Test Bank Ltd");
        bank.setShortName(uniqueShortName);
        bankRepository.save(bank);

        ResponseEntity<String> response = search(uniqueShortName, bearerFor(admin));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        boolean found = false;
        for (JsonNode row : data) {
            if (row.get("type").asText().equals("bank") && row.get("id").asText().equals(bank.getId())) {
                found = true;
                assertThat(row.get("title").asText()).isEqualTo("Zephyr Test Bank Ltd");
                assertThat(row.get("link").asText()).isEqualTo("/banks");
            }
        }
        assertThat(found).as("expected bank result for " + uniqueShortName).isTrue();
    }

    @Test
    void search_blankQuery_returnsEmptyList() throws Exception {
        User admin = createUser("Search Admin Two");
        ResponseEntity<String> response = search("", bearerFor(admin));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data).isEmpty();
    }
}
