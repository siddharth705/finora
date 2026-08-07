package com.finora.imports.jobs;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Replay safety, enforced by the database rather than by application logic.
 *
 * <p>This is the constraint that unblocks running more than one import worker. At-least-once
 * delivery means a worker that dies after confirming an import but before marking its job complete
 * has that job returned to the queue, and the next worker imports the same statement again. The
 * user gets every transaction twice, silently.
 *
 * <p><b>Asserted against a real PostgreSQL, and asserted as a rejected write.</b> A test that
 * checked "the worker skips a job that already has an import" would pass against an application-level
 * guard, and an application-level guard is a read followed by a write -- two workers can both read
 * "not present" before either writes. Only the database can decide this, so only a database
 * rejection proves it.
 */
class ImportIdempotencyIT extends AbstractIntegrationTest {

    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;

    private User user() {
        User user = new User();
        user.setEmail("import-idem-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Import Idempotency IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private UUID accountFor(UUID userId) {
        Account account = new Account();
        account.setUserId(userId);
        account.setName("Import Idempotency IT Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        return accountRepository.save(account).getId();
    }

    /**
     * account_id and file_content are both NOT NULL on this branch -- an import always belongs to
     * an account, and V55 (which drops file_content once statements live in object storage) is on
     * a parked branch, not here.
     */
    private StatementImport importFor(UUID userId, UUID jobId) {
        StatementImport si = new StatementImport();
        si.setUserId(userId);
        si.setAccountId(accountFor(userId));
        si.setFileName("statement.csv");
        si.setFileContent("Date,Description,Amount".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        si.setImportJobId(jobId);
        return statementImportRepository.save(si);
    }

    // ------------------------------------------------------------------ job level

    @Test
    void oneJobCannotProduceTwoImports() {
        // The failure this exists to stop: a worker dies after confirming, recovery re-queues the
        // job, and the retry imports the same statement a second time.
        User user = user();
        UUID jobId = UUID.randomUUID();
        importFor(user.getId(), jobId);

        assertThatThrownBy(() -> importFor(user.getId(), jobId))
                .as("the second import for one job must be rejected by the database")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void differentJobsCanEachProduceAnImport() {
        // The constraint must not be so broad that it blocks legitimate work.
        User user = user();
        importFor(user.getId(), UUID.randomUUID());
        importFor(user.getId(), UUID.randomUUID());

        assertThat(statementImportRepository.count()).isPositive();
    }

    @Test
    void theSynchronousPathIsUnconstrained() {
        // Every import created by the existing endpoint has no job. A plain UNIQUE would still
        // allow multiple NULLs in Postgres, but this asserts the behaviour rather than relying on
        // remembering that -- the synchronous path is the one in production today.
        User user = user();
        importFor(user.getId(), null);
        importFor(user.getId(), null);
        importFor(user.getId(), null);

        assertThat(statementImportRepository.count()).isPositive();
    }

    // ------------------------------------------------------------------ row level

    private Transaction row(UUID userId, UUID accountId, UUID importId, Integer ordinal) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setTxnDate(LocalDate.of(2026, 7, 10));
        t.setAmount(new BigDecimal("486.00"));
        t.setDescription("SWIGGY ORDER");
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setStatementImportId(importId);
        t.setRowOrdinal(ordinal);
        return transactionRepository.save(t);
    }

    @Test
    void oneImportCannotInsertTheSameRowTwice() {
        // Guards a different failure from the job constraint: a retry INSIDE one confirm, or a
        // future batching change that replays part of a list. The statement import is the same one,
        // so the job-level constraint cannot see it.
        User user = user();
        StatementImport si = importFor(user.getId(), UUID.randomUUID());
        UUID accountId = si.getAccountId();
        UUID importId = si.getId();
        row(user.getId(), accountId, importId, 0);

        assertThatThrownBy(() -> row(user.getId(), accountId, importId, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void distinctOrdinalsWithinOneImportAreFine() {
        // A statement legitimately contains identical-looking rows -- a commute fare twice in one
        // day. The ordinal is what distinguishes them, and it must not collapse them.
        User user = user();
        StatementImport si = importFor(user.getId(), UUID.randomUUID());
        UUID accountId = si.getAccountId();
        UUID importId = si.getId();

        row(user.getId(), accountId, importId, 0);
        row(user.getId(), accountId, importId, 1);

        assertThat(transactionRepository.findByStatementImportId(importId)).hasSize(2);
    }

    @Test
    void manuallyCreatedTransactionsAreUnconstrained() {
        // No statement, no ordinal, and the partial index excludes them. A user can enter the same
        // amount and description as often as they like.
        User user = user();
        UUID accountId = accountFor(user.getId());

        row(user.getId(), accountId, null, null);
        row(user.getId(), accountId, null, null);

        assertThat(transactionRepository.count()).isPositive();
    }

    @Test
    void theSameOrdinalInDifferentImportsIsFine() {
        // Every import starts at ordinal 0. Scoping the constraint to the import is what makes that
        // work -- a global unique ordinal would break the second import ever run.
        User user = user();
        UUID accountId = accountFor(user.getId());
        UUID first = importFor(user.getId(), UUID.randomUUID()).getId();
        UUID second = importFor(user.getId(), UUID.randomUUID()).getId();

        row(user.getId(), accountId, first, 0);
        row(user.getId(), accountId, second, 0);

        assertThat(transactionRepository.findByStatementImportId(first)).hasSize(1);
        assertThat(transactionRepository.findByStatementImportId(second)).hasSize(1);
    }
}
