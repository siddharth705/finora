package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres coverage for {@link StatementImportRepository#countByUserId} and {@link
 * StatementImportRepository#findCapabilityDataByUserId}, the two brand-new fileContent-free
 * queries added closing the last 5 callers of the entity-returning finder (see {@link
 * StatementImportRepository.StatementMetadata}'s own doc comment for the full history) --
 * {@code countByUserId} is a Spring Data derived query with no {@code @Query} at all, and {@code
 * findCapabilityDataByUserId} is a JPQL {@code AS}-aliased projection using the same mechanism as
 * {@code StatementMetadata}'s own query. Every caller of either is otherwise only proven against a
 * mocked repository ({@code WorkspaceDashboardServiceTest}, {@code CapabilityCoverageServiceTest})
 * -- a mock proves the service calls the repository correctly, not that the query itself is
 * syntactically and semantically correct against real Postgres. This is that proof.
 */
class StatementImportRepositoryIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private EntityManager entityManager;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("statement-repo-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Statement Repository IT User");
        userId = userRepository.save(user).getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(1000));
        accountId = accountRepository.save(account).getId();
    }

    private StatementImport saveStatement(String activatedCapabilitiesJson, String unparseableSummaryJson) {
        StatementImport s = new StatementImport();
        s.setUserId(userId);
        s.setAccountId(accountId);
        s.setFileName("statement.pdf");
        s.setSourceFormat("PDF");
        s.setFileContent(new byte[]{1});
        s.setContentHash("statement-repo-it-hash-" + UUID.randomUUID());
        s.setActivatedCapabilitiesJson(activatedCapabilitiesJson);
        s.setUnparseableSummaryJson(unparseableSummaryJson);
        return statementImportRepository.save(s);
    }

    @Test
    @Transactional
    void countByUserId_countsOnlyThisUsersLiveRows() {
        saveStatement(null, null);
        saveStatement(null, null);
        entityManager.flush();
        entityManager.clear();

        assertThat(statementImportRepository.countByUserId(userId)).isEqualTo(2L);
        assertThat(statementImportRepository.countByUserId(UUID.randomUUID())).isZero();
    }

    @Test
    @Transactional
    void countByUserId_excludesSoftDeletedRows() {
        StatementImport statement = saveStatement(null, null);
        entityManager.flush();
        assertThat(statementImportRepository.countByUserId(userId)).isEqualTo(1L);

        statementImportRepository.delete(statement); // soft delete via @SQLDelete
        entityManager.flush();
        entityManager.clear();

        assertThat(statementImportRepository.countByUserId(userId)).isZero();
    }

    @Test
    @Transactional
    void findCapabilityDataByUserId_projectsTheRightColumnsAndExcludesFileContent() {
        String capabilities = "[{\"capability\":\"WRAPPED_DESCRIPTION\",\"status\":\"SUCCESS\"}]";
        StatementImport statement = saveStatement(capabilities, null);
        entityManager.flush();
        entityManager.clear();

        List<StatementImportRepository.CapabilityData> rows =
                statementImportRepository.findCapabilityDataByUserId(userId);

        assertThat(rows).hasSize(1);
        StatementImportRepository.CapabilityData row = rows.get(0);
        assertThat(row.getId()).isEqualTo(statement.getId());
        assertThat(row.getActivatedCapabilitiesJson()).isEqualTo(capabilities);
        assertThat(row.getUnparseableSummaryJson()).isNull();
    }

    @Test
    @Transactional
    void findCapabilityDataByUserId_scopedToOneUser_notGlobal() {
        saveStatement(null, null);

        User otherUser = new User();
        otherUser.setEmail("statement-repo-it-other-" + UUID.randomUUID() + "@example.com");
        otherUser.setPasswordHash("irrelevant-for-this-test");
        otherUser.setFullName("Other User");
        UUID otherUserId = userRepository.save(otherUser).getId();
        Account otherAccount = new Account();
        otherAccount.setUserId(otherUserId);
        otherAccount.setName("Other Account");
        otherAccount.setAccountType(Account.Type.SAVINGS);
        otherAccount.setBalance(BigDecimal.ZERO);
        UUID otherAccountId = accountRepository.save(otherAccount).getId();
        StatementImport otherStatement = new StatementImport();
        otherStatement.setUserId(otherUserId);
        otherStatement.setAccountId(otherAccountId);
        otherStatement.setFileName("other.pdf");
        otherStatement.setSourceFormat("PDF");
        otherStatement.setFileContent(new byte[]{1});
        otherStatement.setContentHash("statement-repo-it-hash-" + UUID.randomUUID());
        statementImportRepository.save(otherStatement);
        entityManager.flush();
        entityManager.clear();

        assertThat(statementImportRepository.findCapabilityDataByUserId(userId)).hasSize(1);
        assertThat(statementImportRepository.findCapabilityDataByUserId(otherUserId)).hasSize(1);
    }
}
