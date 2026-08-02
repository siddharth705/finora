package com.finora.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Merchant;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.MerchantLearningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admin, support-assisted merchant management on behalf of a specific user
 * (AdminUserMerchantController) -- proves MERCHANT_MANAGE gating, that the admin path reaches
 * the exact same MerchantService the self-service console uses (list/rename/merge), and that it
 * stays ownership-scoped to the userId in the path (an admin can't merge a merchant belonging to
 * one user using an id that belongs to a different user).
 *
 * undo/reset-learning/confirm-category are covered here too -- these three were only ever added
 * to this controller once MerchantController (the self-service counterpart) was retired, so they
 * have no separate self-service test coverage to lean on.
 */
class AdminUserMerchantControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private MerchantLearningService merchantLearningService;
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

    private Category categoryFor(User user, String name) {
        Category c = new Category();
        c.setUserId(user.getId());
        c.setName(name);
        return categoryRepository.save(c);
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

    @Test
    void admin_canUndoAndResetLearningForATargetUsersMerchant() throws Exception {
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        HttpHeaders headers = bearerFor(admin);
        Merchant merchant = merchantFor(target, "Zomato");
        Category category = categoryFor(target, "Food");
        merchantLearningService.confirm(target.getId(), merchant.getId(), category.getId());

        ResponseEntity<String> undoResponse = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/merchants/" + merchant.getId() + "/undo",
                HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(undoResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // A second confirm gives reset-learning something to actually reset.
        merchantLearningService.confirm(target.getId(), merchant.getId(), category.getId());
        ResponseEntity<String> resetResponse = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/merchants/" + merchant.getId() + "/reset-learning",
                HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(resetResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(resetResponse.getBody()).get("data").get("topCategory").isNull()).isTrue();
    }

    @Test
    void admin_canConfirmCategoryForATargetUsersTransaction() throws Exception {
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        HttpHeaders headers = bearerFor(admin);
        Merchant merchant = merchantFor(target, "Uber");
        Category category = categoryFor(target, "Transport");

        Account account = new Account();
        account.setUserId(target.getId());
        account.setName("Target Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        accountRepository.save(account);

        Transaction txn = new Transaction();
        txn.setUserId(target.getId());
        txn.setAccountId(account.getId());
        txn.setTxnDate(LocalDate.now());
        txn.setDescription("UBER TRIP");
        txn.setAmount(BigDecimal.TEN);
        txn.setTxnType(Transaction.Type.EXPENSE);
        txn.setMerchantId(merchant.getId());
        transactionRepository.save(txn);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/merchants/" + merchant.getId() + "/confirm-category",
                HttpMethod.POST,
                new HttpEntity<>("{\"categoryId\":\"" + category.getId() + "\",\"applyToTransactionId\":\""
                        + txn.getId() + "\"}", headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(transactionRepository.findById(txn.getId()).orElseThrow().getCategoryId()).isEqualTo(category.getId());
    }
}
