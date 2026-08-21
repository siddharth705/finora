package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Budget;
import com.finora.entity.Category;
import com.finora.entity.Role;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.goals.Goal;
import com.finora.goals.GoalRepository;
import com.finora.imports.analysis.StatementAnalysisSession;
import com.finora.imports.analysis.StatementAnalysisSessionRepository;
import com.finora.integrations.google.GmailConnectionRepository;
import com.finora.integrations.google.GmailConnectionService;
import com.finora.repository.AccountReactivationTokenRepository;
import com.finora.repository.EmailVerificationTokenRepository;
import com.finora.repository.AccountRepository;
import com.finora.repository.BudgetRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.CategoryRuleRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.MerchantAliasRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantCategoryMapRepository;
import com.finora.repository.MerchantLearningAuditRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.NetWorthSnapshotRepository;
import com.finora.repository.PasswordChangeSessionRepository;
import com.finora.repository.PasswordHistoryRepository;
import com.finora.repository.PasswordResetTokenRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.RelationshipIdentifierRepository;
import com.finora.repository.RelationshipRepository;
import com.finora.repository.RoleRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.repository.UserSettingsRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AccountPurgeSweepServiceTest} proves the purge's ordering and failure handling given
 * whatever a mock hands it; this proves, against a real Postgres, the two things a mock-only suite
 * structurally cannot: that {@code hardDeleteByUserId} on {@link Transaction}/{@link Budget}/
 * {@link Goal} actually bypasses their {@code @SQLDelete}/{@code @SQLRestriction} (a plain
 * {@code deleteByUserId} would only soft-delete), and that a single native bulk {@code DELETE} on
 * {@code transactions} removes two self-referentially-paired rows (a duplicate/original pair)
 * without tripping the {@code is_duplicate_of} foreign key -- which Postgres only checks at
 * end-of-statement, not per row.
 *
 * <p>The service under test is constructed manually from autowired real beans, exactly like
 * {@code StatementStorageSweepServiceIT} -- not {@code @Autowired} directly -- so this class's own
 * {@code @Value} field overrides ({@code sweepEnabled}/{@code retentionHours}) never touch the
 * shared, context-cached singleton other test classes might autowire later.
 */
class AccountPurgeSweepServiceIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private GmailConnectionService gmailConnectionService;
    @Autowired private GmailConnectionRepository gmailConnectionRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private MerchantLearningEventRepository merchantLearningEventRepository;
    @Autowired private MerchantLearningAuditRepository merchantLearningAuditRepository;
    @Autowired private MerchantCategoryLearningRepository merchantCategoryLearningRepository;
    @Autowired private MerchantAliasRepository merchantAliasRepository;
    @Autowired private MerchantCategoryMapRepository merchantCategoryMapRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private BudgetRepository budgetRepository;
    @Autowired private GoalRepository goalRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private CategoryRuleRepository categoryRuleRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private RelationshipRepository relationshipRepository;
    @Autowired private RelationshipIdentifierRepository relationshipIdentifierRepository;
    @Autowired private NetWorthSnapshotRepository netWorthSnapshotRepository;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private ImportSessionRepository importSessionRepository;
    @Autowired private PasswordHistoryRepository passwordHistoryRepository;
    @Autowired private PasswordChangeSessionRepository passwordChangeSessionRepository;
    @Autowired private PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired private AccountReactivationTokenRepository accountReactivationTokenRepository;
    @Autowired private EmailVerificationTokenRepository emailVerificationTokenRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private UserSettingsRepository userSettingsRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private StatementImportService statementImportService;
    @Autowired private StatementAnalysisSessionRepository statementAnalysisSessionRepository;
    @Autowired private AuditService auditService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private RoleRepository roleRepository;
    @Autowired private EntityManager entityManager;

    private AccountPurgeSweepService service;
    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        service = new AccountPurgeSweepService(userRepository, gmailConnectionService, gmailConnectionRepository,
                transactionRepository, merchantLearningEventRepository, merchantLearningAuditRepository,
                merchantCategoryLearningRepository, merchantAliasRepository, merchantCategoryMapRepository,
                merchantRepository, budgetRepository, goalRepository, subscriptionRepository, categoryRuleRepository, categoryRepository,
                relationshipRepository, relationshipIdentifierRepository, netWorthSnapshotRepository,
                importJobRepository, importSessionRepository, passwordHistoryRepository,
                passwordChangeSessionRepository, passwordResetTokenRepository, accountReactivationTokenRepository,
                emailVerificationTokenRepository,
                refreshTokenRepository, userSettingsRepository, accountRepository, statementImportRepository,
                statementImportService, statementAnalysisSessionRepository, auditService, passwordEncoder,
                transactionTemplate);
        ReflectionTestUtils.setField(service, "sweepEnabled", true);
        ReflectionTestUtils.setField(service, "retentionHours", 48);
        ReflectionTestUtils.setField(service, "batchSize", 200);

        User user = new User();
        user.setEmail("purge-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Purge Test User");
        user.setStatus(User.STATUS_PENDING_DELETION);
        user.setDeletionRequestedAt(Instant.now().minus(49, ChronoUnit.HOURS));
        userId = userRepository.save(user).getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(1000));
        account.setAccountHolderName("Purge Test User");
        accountId = accountRepository.save(account).getId();
    }

    private Transaction saveTransaction(BigDecimal amount) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setTxnDate(LocalDate.now());
        t.setAmount(amount);
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription("Purge test transaction");
        return transactionRepository.save(t);
    }

    /**
     * The self-referential-FK regression this class exists to prove. Two of the user's own
     * transactions point at each other via {@code is_duplicate_of} -- a row-by-row delete loop
     * could trip that constraint depending on iteration order (delete the row still pointed to
     * first, and the FK on the other row is left dangling mid-transaction); a single bulk
     * {@code DELETE FROM transactions WHERE user_id = ?} removes both atomically in one statement,
     * which Postgres only validates FKs against at end-of-statement.
     */
    @Test
    @Transactional
    void sweep_hardDeletesSelfReferentiallyPairedTransactions_withoutTrippingTheForeignKey() {
        Transaction original = saveTransaction(BigDecimal.valueOf(500));
        Transaction duplicate = saveTransaction(BigDecimal.valueOf(500));
        duplicate.setIsDuplicateOf(original.getId());
        transactionRepository.save(duplicate);
        entityManager.flush();

        AccountPurgeSweepService.Result result = service.sweep();

        assertThat(result.purged()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        entityManager.clear();
        assertThat(transactionRepository.findByUserId(userId)).isEmpty();
    }

    /**
     * {@code hardDeleteByUserId} bypasses {@code @SQLDelete}/{@code @SQLRestriction} entirely --
     * proving this against a real Postgres, not a mocked repository whose method would "succeed"
     * either way. A plain {@code deleteByUserId} on any of these four entities would only set
     * {@code deleted_at}, leaving the row (and its data) fully intact and still discoverable via
     * the entity's own soft-delete-aware queries. Subscription also proves its own
     * {@code ON DELETE CASCADE} (V99): a real {@code subscription_events} row disappears with its
     * parent, with no separate repository call for it.
     */
    @Test
    @Transactional
    void sweep_physicallyRemovesTransactionsBudgetsGoalsAndSubscriptions_notJustSoftDeletingThem() {
        saveTransaction(BigDecimal.valueOf(250));

        Category category = new Category();
        category.setUserId(userId);
        category.setName("Groceries");
        UUID categoryId = categoryRepository.save(category).getId();

        Budget budget = new Budget();
        budget.setUserId(userId);
        budget.setCategoryId(categoryId);
        budget.setMonthlyLimit(BigDecimal.valueOf(5000));
        budgetRepository.save(budget);

        Goal goal = new Goal();
        goal.setUserId(userId);
        goal.setName("Emergency Fund");
        goal.setTargetAmount(BigDecimal.valueOf(100000));
        goal.setTargetDate(LocalDate.now().plusYears(1));
        goalRepository.save(goal);

        subscriptionService.provisionFreeSubscription(userId);
        UUID subscriptionId = subscriptionRepository.findActiveOrTrial(userId).orElseThrow().getId();
        entityManager.flush();

        service.sweep();
        // Native COUNT queries below don't trigger Hibernate's auto-flush the way a JPQL query
        // would -- an explicit flush is what makes the just-issued native hardDeleteByUserId
        // DELETEs (and any pending dirty-checked writes) actually visible to them.
        entityManager.flush();
        entityManager.clear();

        // Not just excluded by @SQLRestriction from these finder queries -- physically gone, via a
        // native count against the raw table with no restriction applied.
        Long transactionCount = (Long) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM transactions WHERE user_id = :userId")
                .setParameter("userId", userId).getSingleResult();
        Long budgetCount = (Long) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM budgets WHERE user_id = :userId")
                .setParameter("userId", userId).getSingleResult();
        Long goalCount = (Long) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM goals WHERE user_id = :userId")
                .setParameter("userId", userId).getSingleResult();
        Long subscriptionCount = (Long) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM subscriptions WHERE user_id = :userId")
                .setParameter("userId", userId).getSingleResult();
        // subscription_events has no user_id column of its own -- checked by the subscription_id
        // captured before the purge, proving V99's ON DELETE CASCADE actually fired, not just that
        // the parent row (which this same query would trivially miss anyway) is gone.
        Long subscriptionEventCount = (Long) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM subscription_events WHERE subscription_id = :subscriptionId")
                .setParameter("subscriptionId", subscriptionId).getSingleResult();
        assertThat(transactionCount).isZero();
        assertThat(budgetCount).isZero();
        assertThat(goalCount).isZero();
        assertThat(subscriptionCount).isZero();
        assertThat(subscriptionEventCount).isZero();
    }

    /**
     * End-to-end against real Postgres semantics: the {@code accounts} row survives (never hard-
     * deleted, per Finding 2 -- an {@code ON DELETE CASCADE} from a deleted account would vaporize
     * {@code statement_imports} rows outside Hibernate's {@code @SQLDelete} interceptor entirely),
     * with its plain-text-identity fields anonymized, while the {@code statement_imports} row is
     * soft-deleted (still present with {@code deleted_at} set), not physically removed.
     */
    @Test
    @Transactional
    void sweep_anonymizesTheSurvivingAccountRow_andSoftDeletesStatementImports() {
        StatementImport statement = new StatementImport();
        statement.setUserId(userId);
        statement.setAccountId(accountId);
        statement.setFileName("statement.pdf");
        statement.setSourceFormat("PDF");
        statement.setFileContent(new byte[]{1});
        statement.setContentHash("purge-it-hash-" + UUID.randomUUID());
        UUID statementId = statementImportRepository.save(statement).getId();
        entityManager.flush();

        AccountPurgeSweepService.Result result = service.sweep();

        assertThat(result.purged()).isEqualTo(1);
        // The account-anonymize and user-anonymize writes are dirty-checked entity updates, not
        // native/@Modifying statements -- Hibernate won't flush them before a subsequent NATIVE
        // query the way it would before a JPQL one, so the native reads below need this explicit.
        entityManager.flush();
        entityManager.clear();

        Account survivingAccount = accountRepository.findByUserIdIncludingDeleted(userId).get(0);
        assertThat(survivingAccount.getId()).isEqualTo(accountId);
        assertThat(survivingAccount.getAccountHolderName()).isNull();

        // Still present, not physically gone -- native query bypasses @SQLRestriction the same way
        // StatementStorageSweepService's own discovery query does.
        Object deletedAt = entityManager
                .createNativeQuery("SELECT deleted_at FROM statement_imports WHERE id = :id")
                .setParameter("id", statementId).getSingleResult();
        assertThat(deletedAt).isNotNull();

        User purgedUser = userRepository.findById(userId).orElseThrow();
        assertThat(purgedUser.getStatus()).isEqualTo(User.STATUS_DELETED);
        assertThat(purgedUser.getDeletedAt()).isNotNull();
        assertThat(purgedUser.getEmail()).isEqualTo("deleted-" + userId + "@deleted.finora.invalid");
    }

    /**
     * Regression test for a real gap a bugs-and-gaps pass on this class caught: {@code user_roles}
     * has a {@code user_id} FK like every other user-owned table (confirmed against every
     * migration that creates one), but the first version of {@code purgeOne} never touched it --
     * an explicit RBAC grant would have silently survived the purge. {@code User.roles} is managed
     * via the JPA relationship, not a repository method, so only a real Postgres run proves the
     * join-table row is actually gone, not just absent from the in-memory collection.
     */
    @Test
    @Transactional
    void sweep_clearsExplicitRoleGrants_fromTheUserRolesJoinTable() {
        Role role = new Role();
        // roles.name is VARCHAR(50) -- a full UUID suffix (36 chars) plus a descriptive prefix
        // overflows it, so this uses only the entropy a collision-avoidance need actually requires.
        role.setName("PURGE_IT_ROLE_" + UUID.randomUUID().toString().substring(0, 8));
        role.setDescription("AccountPurgeSweepServiceIT fixture role");
        UUID roleId = roleRepository.save(role).getId();

        User user = userRepository.findById(userId).orElseThrow();
        user.getRoles().add(role);
        userRepository.save(user);
        entityManager.flush();

        Long beforeCount = (Long) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM user_roles WHERE user_id = :userId")
                .setParameter("userId", userId).getSingleResult();
        assertThat(beforeCount).isEqualTo(1);

        AccountPurgeSweepService.Result result = service.sweep();

        assertThat(result.purged()).isEqualTo(1);
        entityManager.flush();
        entityManager.clear();

        Long afterCount = (Long) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM user_roles WHERE user_id = :userId")
                .setParameter("userId", userId).getSingleResult();
        assertThat(afterCount).isZero();
        // The role itself (global, not user-owned) is untouched -- only this user's grant of it.
        assertThat(roleRepository.findById(roleId)).isPresent();
    }

    /**
     * Regression test for a real security gap a Strix review caught: {@code
     * statement_analysis_sessions} was left out of the purge entirely on the theory that "the row
     * holds nothing personal to protect" (the entity's own class doc) -- but {@code file_name} is
     * literally the user's uploaded filename and {@code failure_detail} can hold a fragment of the
     * document itself. Every column on this entity is {@code updatable = false} by design (no
     * setters at all), so only a real Postgres run proves the native bulk update in {@code
     * anonymizeByUserId} actually bypasses that and reaches the database -- a mock would happily
     * "succeed" even if the query were silently wrong.
     */
    @Test
    @Transactional
    void sweep_anonymizesStatementAnalysisSessions_clearingOnlyTheThreePersonalColumns() {
        StatementAnalysisSession session = StatementAnalysisSession.failed(
                "SA-IT-" + UUID.randomUUID().toString().substring(0, 8), userId,
                StatementAnalysisSession.Source.CUSTOMER_IMPORT, "March_Statement_Jane.pdf", "PDF", 4096L,
                "hdfc-savings-v3", "IMPORT_007", "Could not anchor row 14: 'HDFC0001234 A/c 9876543210'", // synthetic-ok
                1200L, null, null);
        UUID sessionId = statementAnalysisSessionRepository.save(session).getId();
        entityManager.flush();

        AccountPurgeSweepService.Result result = service.sweep();

        assertThat(result.purged()).isEqualTo(1);
        entityManager.flush();
        entityManager.clear();

        StatementAnalysisSession anonymized = statementAnalysisSessionRepository.findById(sessionId).orElseThrow();
        assertThat(anonymized.getUserId()).isNull();
        assertThat(anonymized.getFileName()).isNull();
        assertThat(anonymized.getFailureDetail()).isNull();
        // The actual layout-intelligence signal this table exists to aggregate -- untouched.
        assertThat(anonymized.getLayoutFingerprint()).isEqualTo("hdfc-savings-v3");
        assertThat(anonymized.getFailureCode()).isEqualTo("IMPORT_007");
        assertThat(anonymized.getOutcome()).isEqualTo(StatementAnalysisSession.Outcome.FAILED);
        assertThat(anonymized.getReference()).isEqualTo(session.getReference());
    }
}
