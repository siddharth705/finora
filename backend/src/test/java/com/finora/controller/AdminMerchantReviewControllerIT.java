package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Merchant;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.MerchantRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Merchant Review Center (WI4).
 *
 * <p>Two properties matter most and neither is about the happy path. First, that the product
 * decision holds in the code and not just in the design document: merchants are per-user, so
 * listing may cross users but every ACTION is scoped to the owner. Second, that discarding cannot
 * quietly damage a user's ledger — {@code transactions.merchant_id} is ON DELETE SET NULL, so a
 * naive delete strips the merchant from real rows without saying anything.
 */
class AdminMerchantReviewControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private com.finora.repository.AccountRepository accountRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("merchant-review-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Merchant Review IT User");
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

    private Merchant temporaryMerchant(User owner, String name) {
        Merchant merchant = new Merchant();
        merchant.setUserId(owner.getId());
        merchant.setCanonicalName(name);
        merchant.setLifecycleStatus(Merchant.Lifecycle.TEMPORARY);
        return merchantRepository.save(merchant);
    }

    private Transaction transactionFor(User owner, Merchant merchant) {
        // transactions.account_id is NOT NULL -- a transaction always belongs to an account.
        com.finora.entity.Account account = new com.finora.entity.Account();
        account.setUserId(owner.getId());
        account.setName("Review IT Account");
        account.setAccountType(com.finora.entity.Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account = accountRepository.save(account);

        Transaction txn = new Transaction();
        txn.setUserId(owner.getId());
        txn.setAccountId(account.getId());
        txn.setMerchantId(merchant.getId());
        txn.setTxnDate(LocalDate.of(2026, 7, 10));
        txn.setDescription("SWIGGY ORDER 4471");
        txn.setAmount(new BigDecimal("486.00"));
        txn.setTxnType(Transaction.Type.EXPENSE);
        return transactionRepository.save(txn);
    }

    // --- authorization ------------------------------------------------------------------------

    @Test
    void plainUser_isForbiddenFromTheReviewQueue() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/merchant-review", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminWithTheGrantedPermission_canReadTheQueue() {
        User admin = createUser("ADMIN");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/merchant-review", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- the queue ----------------------------------------------------------------------------

    /**
     * Only the engine's guesses appear. Every merchant that pre-dates V64 backfilled to APPROVED,
     * so a queue that showed them would have dumped the entire existing merchant table into an
     * operator's lap on the first deploy.
     */
    @Test
    void theQueueShowsOnlyMerchantsAwaitingReview() throws Exception {
        User admin = createUser("ADMIN");
        User owner = createUser("USER");
        Merchant temporary = temporaryMerchant(owner, "SWIGGY GUESS " + UUID.randomUUID());

        Merchant approved = new Merchant();
        approved.setUserId(owner.getId());
        approved.setCanonicalName("ALREADY APPROVED " + UUID.randomUUID());
        merchantRepository.save(approved);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/merchant-review?size=200", HttpMethod.GET,
                new HttpEntity<>(bearerFor(admin)), String.class);

        JsonNode content = mapper.readTree(response.getBody()).get("data").get("content");
        assertThat(content.toString()).contains(temporary.getId().toString());
        assertThat(content.toString()).doesNotContain(approved.getId().toString());
    }

    /** The owner's identity travels with the row -- an operator deciding on a merchant needs to
     *  know whose account it landed in without a second lookup. */
    @Test
    void aQueueRowCarriesTheOwningUserAndItsTransactionCount() throws Exception {
        User admin = createUser("ADMIN");
        User owner = createUser("USER");
        Merchant merchant = temporaryMerchant(owner, "CORRELATED " + UUID.randomUUID());
        transactionFor(owner, merchant);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/merchant-review?size=200", HttpMethod.GET,
                new HttpEntity<>(bearerFor(admin)), String.class);

        JsonNode row = null;
        for (JsonNode candidate : mapper.readTree(response.getBody()).get("data").get("content")) {
            if (candidate.get("id").asText().equals(merchant.getId().toString())) row = candidate;
        }
        assertThat(row).isNotNull();
        assertThat(row.get("userEmail").asText()).isEqualTo(owner.getEmail());
        assertThat(row.get("transactionCount").asLong()).isEqualTo(1);
        assertThat(row.get("lifecycleStatus").asText()).isEqualTo("TEMPORARY");
    }

    // --- actions ------------------------------------------------------------------------------

    @Test
    void approve_confirmsTheGuessAndRemovesItFromTheQueue() {
        User admin = createUser("ADMIN");
        User owner = createUser("USER");
        Merchant merchant = temporaryMerchant(owner, "APPROVE ME " + UUID.randomUUID());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/merchant-review/users/" + owner.getId() + "/merchants/" + merchant.getId() + "/approve",
                HttpMethod.POST, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(merchantRepository.findById(merchant.getId()).orElseThrow().getLifecycleStatus())
                .isEqualTo(Merchant.Lifecycle.APPROVED);
    }

    /** Acting on something already handled is a conflict naming the real state, so an operator can
     *  tell "a colleague got there first" from "this cannot be done". */
    @Test
    void approve_onAnAlreadyApprovedMerchant_isRejectedWithTheActualState() {
        User admin = createUser("ADMIN");
        User owner = createUser("USER");
        Merchant merchant = temporaryMerchant(owner, "TWICE " + UUID.randomUUID());
        merchant.setLifecycleStatus(Merchant.Lifecycle.APPROVED);
        merchantRepository.save(merchant);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/merchant-review/users/" + owner.getId() + "/merchants/" + merchant.getId() + "/approve",
                HttpMethod.POST, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("APPROVED");
    }

    /**
     * The product decision, enforced rather than merely documented: a merchant belongs to exactly
     * one user, so an action addressed to the wrong owner must not resolve.
     */
    @Test
    void anActionScopedToTheWrongUserCannotReachTheMerchant() {
        User admin = createUser("ADMIN");
        User owner = createUser("USER");
        User someoneElse = createUser("USER");
        Merchant merchant = temporaryMerchant(owner, "SCOPED " + UUID.randomUUID());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/merchant-review/users/" + someoneElse.getId()
                        + "/merchants/" + merchant.getId() + "/approve",
                HttpMethod.POST, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(merchantRepository.findById(merchant.getId()).orElseThrow().getLifecycleStatus())
                .isEqualTo(Merchant.Lifecycle.TEMPORARY);
    }

    /** Merge candidates come only from the owner's own approved merchants. There is no canonical
     *  registry, so another user's merchant is not a thing this one could be merged into. */
    @Test
    void mergeCandidatesNeverIncludeAnotherUsersMerchants() throws Exception {
        User admin = createUser("ADMIN");
        User owner = createUser("USER");
        User someoneElse = createUser("USER");
        Merchant guess = temporaryMerchant(owner, "GUESS " + UUID.randomUUID());

        Merchant ownersApproved = new Merchant();
        ownersApproved.setUserId(owner.getId());
        ownersApproved.setCanonicalName("OWNERS REAL " + UUID.randomUUID());
        merchantRepository.save(ownersApproved);

        Merchant strangersApproved = new Merchant();
        strangersApproved.setUserId(someoneElse.getId());
        strangersApproved.setCanonicalName("STRANGERS " + UUID.randomUUID());
        merchantRepository.save(strangersApproved);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/merchant-review/users/" + owner.getId()
                        + "/merchants/" + guess.getId() + "/merge-candidates",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        String body = mapper.readTree(response.getBody()).get("data").toString();
        assertThat(body).contains(ownersApproved.getId().toString());
        assertThat(body).doesNotContain(strangersApproved.getId().toString());
    }

    // --- the dangerous one --------------------------------------------------------------------

    /**
     * Discarding a merchant with history is refused, and the refusal explains itself.
     *
     * <p>{@code transactions.merchant_id} is ON DELETE SET NULL, so a delete here would silently
     * strip the merchant from real ledger rows — the user's history would quietly lose an
     * attribution nobody asked to remove. Merge is the operation for that case.
     */
    @Test
    void discard_isRefusedWhenTransactionsAreAttributedToTheMerchant() {
        User admin = createUser("ADMIN");
        User owner = createUser("USER");
        Merchant merchant = temporaryMerchant(owner, "HAS HISTORY " + UUID.randomUUID());
        Transaction txn = transactionFor(owner, merchant);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/merchant-review/users/" + owner.getId() + "/merchants/" + merchant.getId(),
                HttpMethod.DELETE, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("Merge");
        // Both survive, and crucially the transaction still points at the merchant.
        assertThat(merchantRepository.findById(merchant.getId())).isPresent();
        assertThat(transactionRepository.findById(txn.getId()).orElseThrow().getMerchantId())
                .isEqualTo(merchant.getId());
    }

    /** With nothing attributed to it, a guess that was never real can simply go. */
    @Test
    void discard_removesAMerchantNothingPointsAt() {
        User admin = createUser("ADMIN");
        User owner = createUser("USER");
        Merchant merchant = temporaryMerchant(owner, "NEVER REAL " + UUID.randomUUID());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/merchant-review/users/" + owner.getId() + "/merchants/" + merchant.getId(),
                HttpMethod.DELETE, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(merchantRepository.findById(merchant.getId())).isEmpty();
    }
}
