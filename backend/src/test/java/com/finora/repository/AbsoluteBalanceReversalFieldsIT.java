package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres round-trip for the two new columns backing the "absolute balance reversal"
 * design (docs/superpowers/specs/2026-08-30-absolute-balance-reversal-design.md) -- proves the
 * migration and entity mappings agree before any service code depends on them.
 */
class AbsoluteBalanceReversalFieldsIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementImportRepository statementImportRepository;

    @Test
    void statementImport_balanceBeforeAbsoluteSet_roundTripsAndDefaultsToNull() {
        User user = new User();
        user.setEmail("absolute-balance-fields-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Absolute Balance Fields IT User");
        UUID userId = userRepository.save(user).getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(1000));
        UUID accountId = accountRepository.save(account).getId();

        StatementImport withoutSnapshot = new StatementImport();
        withoutSnapshot.setUserId(userId);
        withoutSnapshot.setAccountId(accountId);
        withoutSnapshot.setFileName("no-snapshot.csv");
        withoutSnapshot.setFileContent(new byte[]{1});
        withoutSnapshot.setContentHash("no-snapshot-hash-" + UUID.randomUUID());
        UUID noSnapshotId = statementImportRepository.save(withoutSnapshot).getId();
        assertThat(statementImportRepository.findById(noSnapshotId).orElseThrow()
                .getBalanceBeforeAbsoluteSet()).isNull();

        StatementImport withSnapshot = new StatementImport();
        withSnapshot.setUserId(userId);
        withSnapshot.setAccountId(accountId);
        withSnapshot.setFileName("with-snapshot.csv");
        withSnapshot.setFileContent(new byte[]{1});
        withSnapshot.setContentHash("with-snapshot-hash-" + UUID.randomUUID());
        withSnapshot.setBalanceBeforeAbsoluteSet(new BigDecimal("1234.56"));
        UUID withSnapshotId = statementImportRepository.save(withSnapshot).getId();
        assertThat(statementImportRepository.findById(withSnapshotId).orElseThrow()
                .getBalanceBeforeAbsoluteSet()).isEqualByComparingTo("1234.56");
    }

    @Test
    void account_lastAbsoluteSetStatementId_roundTripsAndDefaultsToNull() {
        User user = new User();
        user.setEmail("absolute-balance-fields-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Absolute Balance Fields IT User");
        UUID userId = userRepository.save(user).getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(1000));
        UUID accountId = accountRepository.save(account).getId();
        assertThat(accountRepository.findById(accountId).orElseThrow()
                .getLastAbsoluteSetStatementId()).isNull();

        UUID pointerTarget = UUID.randomUUID();
        Account toUpdate = accountRepository.findById(accountId).orElseThrow();
        toUpdate.setLastAbsoluteSetStatementId(pointerTarget);
        accountRepository.save(toUpdate);
        assertThat(accountRepository.findById(accountId).orElseThrow()
                .getLastAbsoluteSetStatementId()).isEqualTo(pointerTarget);
    }
}
