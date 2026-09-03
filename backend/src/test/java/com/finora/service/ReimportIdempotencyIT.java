package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.imports.ImportService;
import com.finora.imports.ImportSessionService;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Track B/B1. A re-import that is confirmed twice must import once.
 *
 * <p>The hole this covers: a first-time import is protected by
 * {@code ImportSessionService.claimForConfirmation}, an atomic claim taken before any importing
 * happens. A re-import has no {@code ImportSession} to claim — it replays bytes already stored on a
 * {@code StatementImport} — so {@code confirmReimport} went from an ownership check straight to
 * {@code importService.confirm}, which persists unconditionally. A double-tapped "Re-import", or a
 * client retrying a confirm whose response was lost, posted the statement's transactions a second
 * time, moved the account balance again, and left a second StatementImport row behind it.
 *
 * <p>These tests fail against the code before {@code claimReimportAttempt} existed: the second
 * confirm returned a successful response and the ledger held two copies of every row.
 */
class ReimportIdempotencyIT extends AbstractIntegrationTest {

    private static final byte[] FILE =
            "Date,Description,Amount,Type\n2026-07-01,COFFEE SHOP,150.00,DEBIT\n".getBytes(StandardCharsets.UTF_8);

    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private StatementImportService statementImportService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private UserRepository userRepository;

    @Test
    @DisplayName("the same re-import attempt, sent twice, imports once and refuses the replay")
    void aReplayedReimportIsRefused() throws Exception {
        User user = user();
        Account account = account(user);
        StatementImport original = importOnce(user, account);
        String attemptKey = UUID.randomUUID().toString();

        var first = statementImportService.confirmReimport(user.getId(), original.getId(), reimport(attemptKey));
        assertThat(first.imported()).isEqualTo(1);

        Throwable replay = catchThrowable(() ->
                statementImportService.confirmReimport(user.getId(), original.getId(), reimport(attemptKey)));

        assertThat(replay).isInstanceOf(ApiException.class);
        assertThat(((ApiException) replay).getStatus()).isEqualTo(HttpStatus.CONFLICT);
        // The original import's row plus exactly one re-imported copy -- not two.
        assertThat(rowsFor(user, account)).isEqualTo(2);
    }

    @Test
    @DisplayName("a genuinely new re-import attempt still succeeds -- the key gates the attempt, not the statement")
    void adifferentAttemptStillWorks() throws Exception {
        // The whole point of re-import is that you can run it again after correcting something. A
        // guard keyed on the DOCUMENT (a content hash, as V74/V79 use) would have made the first
        // re-import succeed and every later one fail. This pins that it does not.
        User user = user();
        Account account = account(user);
        StatementImport original = importOnce(user, account);

        statementImportService.confirmReimport(user.getId(), original.getId(), reimport(UUID.randomUUID().toString()));
        var second = statementImportService.confirmReimport(user.getId(), original.getId(), reimport(UUID.randomUUID().toString()));

        assertThat(second.imported()).isEqualTo(1);
        assertThat(rowsFor(user, account)).isEqualTo(3);
    }

    @Test
    @DisplayName("one user's key cannot collide with another's")
    void keysAreScopedPerUser() throws Exception {
        // Scoped per user deliberately: a cross-user collision would be one person's re-import
        // failing because of a stranger's request, which is never an acceptable failure mode for an
        // identifier the client chooses.
        String sharedKey = UUID.randomUUID().toString();

        User alice = user();
        Account aliceAccount = account(alice);
        StatementImport aliceImport = importOnce(alice, aliceAccount);
        statementImportService.confirmReimport(alice.getId(), aliceImport.getId(), reimport(sharedKey));

        User bob = user();
        Account bobAccount = account(bob);
        StatementImport bobImport = importOnce(bob, bobAccount);

        var bobResult = statementImportService.confirmReimport(bob.getId(), bobImport.getId(), reimport(sharedKey));

        assertThat(bobResult.imported()).isEqualTo(1);
    }

    @Test
    @DisplayName("a client that sends no key keeps the pre-existing behaviour rather than failing")
    void aMissingKeyIsNotAnError() throws Exception {
        // Shipping the server before the clients must not turn a working re-import into a broken
        // one. An un-updated client stays exactly as (un)protected as it was, and no more.
        User user = user();
        Account account = account(user);
        StatementImport original = importOnce(user, account);

        var result = statementImportService.confirmReimport(user.getId(), original.getId(), reimport(null));

        assertThat(result.imported()).isEqualTo(1);
    }

    // --- fixtures ------------------------------------------------------------------------------

    private ConfirmRequest reimport(String idempotencyKey) {
        return new ConfirmRequest(null, List.of(confirmedRow()), null, null, null, null, null,
                null, null, null, null, null, idempotencyKey);
    }

    /** Does a real first-time import, so there is a StatementImport with stored bytes to replay. */
    private StatementImport importOnce(User user, Account account) {
        ImportSession session = importSessionService.createSession(
                user.getId(), "statement.csv", FILE, List.of(stagedRow()), null);
        importService.confirmSession(user.getId(), new ConfirmRequest(
                session.getId(), List.of(confirmedRow()), account.getId(), null, null, null, null));
        return statementImportRepository.findAll().stream()
                .filter(si -> user.getId().equals(si.getUserId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the first import created no StatementImport row"));
    }

    private long rowsFor(User user, Account account) {
        return transactionRepository.findAll().stream()
                .filter(t -> user.getId().equals(t.getUserId()) && account.getId().equals(t.getAccountId()))
                .count();
    }

    private StagedRow stagedRow() {
        return new StagedRow(LocalDate.of(2026, 7, 1), "COFFEE SHOP", new BigDecimal("150.00"),
                "EXPENSE", "Other", "rule", null, false, null, null);
    }

    private ConfirmedRow confirmedRow() {
        return new ConfirmedRow(LocalDate.of(2026, 7, 1), "COFFEE SHOP", new BigDecimal("150.00"),
                "EXPENSE", "Other", true, "rule", null, false, null, null, false);
    }

    private User user() {
        User user = new User();
        user.setEmail("reimport-idem-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Reimport Idempotency User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private Account account(User owner) {
        Account account = new Account();
        account.setUserId(owner.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("10000.00"));
        return accountRepository.save(account);
    }
}
