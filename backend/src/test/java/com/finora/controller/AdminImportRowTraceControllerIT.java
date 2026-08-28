package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
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

/** Import Row Trace (ImportRowTraceService) -- proves PLATFORM_DIAGNOSTICS_VIEW gating and that a
 *  real import's rows come back sorted by position, only where a position is actually known. */
class AdminImportRowTraceControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-import-row-trace-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Import Row Trace IT Test User");
        user.setRole(role);
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private Account accountFor(User user) {
        Account a = new Account();
        a.setUserId(user.getId());
        a.setName("Test Account");
        a.setAccountType(Account.Type.SAVINGS);
        a.setBalance(BigDecimal.ZERO);
        return accountRepository.save(a);
    }

    private StatementImport importFor(User user, Account account) {
        StatementImport si = new StatementImport();
        si.setUserId(user.getId());
        si.setAccountId(account.getId());
        si.setFileName("statement.csv");
        si.setFileContent(new byte[0]);
        si.setTransactionsImported(2);
        si.setTransactionsSkipped(0);
        return statementImportRepository.save(si);
    }

    private Transaction txn(User user, Account account, StatementImport si, Integer sourceRowPosition) {
        Transaction t = new Transaction();
        t.setUserId(user.getId());
        t.setAccountId(account.getId());
        t.setStatementImportId(si.getId());
        t.setSourceRowPosition(sourceRowPosition);
        t.setTxnDate(LocalDate.of(2026, 7, 10));
        t.setAmount(new BigDecimal("340.00"));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription("ZOMATO ORDER");
        return transactionRepository.save(t);
    }

    @Test
    void plainUser_isForbiddenFromUsingTheTrace() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/imports/row-trace/" + UUID.randomUUID(),
                HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_getsNotFound_forAStatementImportIdThatDoesNotExist() {
        User admin = createUser("ADMIN");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/imports/row-trace/" + UUID.randomUUID(),
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void admin_seesRowsSortedByPosition_omittingTransactionsWithNoKnownPosition() throws Exception {
        User admin = createUser("ADMIN");
        User contributor = createUser("USER");
        Account account = accountFor(contributor);
        StatementImport si = importFor(contributor, account);
        Transaction second = txn(contributor, account, si, 5);
        Transaction first = txn(contributor, account, si, 2);
        txn(contributor, account, si, null); // pre-existing import, or an older client -- no position known

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/imports/row-trace/" + si.getId(),
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("statementImportId").asText()).isEqualTo(si.getId().toString());
        assertThat(data.get("rows")).hasSize(2);
        assertThat(data.get("rows").get(0).get("rowPosition").asInt()).isEqualTo(2);
        assertThat(data.get("rows").get(0).get("transactionId").asText()).isEqualTo(first.getId().toString());
        assertThat(data.get("rows").get(1).get("rowPosition").asInt()).isEqualTo(5);
        assertThat(data.get("rows").get(1).get("transactionId").asText()).isEqualTo(second.getId().toString());
    }

    @Test
    void admin_getsAnEmptyRowList_whenNoTransactionHasAKnownPosition() throws Exception {
        User admin = createUser("ADMIN");
        User contributor = createUser("USER");
        Account account = accountFor(contributor);
        StatementImport si = importFor(contributor, account);
        txn(contributor, account, si, null);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/imports/row-trace/" + si.getId(),
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("rows")).isEmpty();
    }
}
