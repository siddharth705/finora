package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.AuditLog;
import com.finora.entity.CategoryRule;
import com.finora.entity.Merchant;
import com.finora.entity.MerchantCategoryLearning;
import com.finora.entity.Relationship;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.CategoryRuleRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.RelationshipRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Financial Intelligence Workspace, Module 1 (Dashboard). Mocked-repository unit tests, same
 *  pattern as AnalyticsServiceTest. */
class WorkspaceDashboardServiceTest {

    private TransactionRepository transactionRepository;
    private AccountRepository accountRepository;
    private MerchantRepository merchantRepository;
    private MerchantCategoryLearningRepository learningRepository;
    private CategoryRuleRepository categoryRuleRepository;
    private RelationshipRepository relationshipRepository;
    private StatementImportRepository statementImportRepository;
    private AuditLogRepository auditLogRepository;
    private WorkspaceDashboardService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        accountRepository = mock(AccountRepository.class);
        merchantRepository = mock(MerchantRepository.class);
        learningRepository = mock(MerchantCategoryLearningRepository.class);
        categoryRuleRepository = mock(CategoryRuleRepository.class);
        relationshipRepository = mock(RelationshipRepository.class);
        statementImportRepository = mock(StatementImportRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        service = new WorkspaceDashboardService(transactionRepository, accountRepository, merchantRepository,
                learningRepository, categoryRuleRepository, relationshipRepository, statementImportRepository,
                auditLogRepository, new ConfidenceEngine());

        when(accountRepository.findByUserId(userId)).thenReturn(List.of());
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of());
        when(learningRepository.findByUserId(userId)).thenReturn(List.of());
        when(categoryRuleRepository.findByUserIdAndEnabledTrueOrderByPriorityAsc(userId)).thenReturn(List.of());
        when(relationshipRepository.findByUserId(userId)).thenReturn(List.of());
        when(statementImportRepository.countByUserId(userId)).thenReturn(0L);
        when(auditLogRepository.findTop5ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of());
    }

    private Transaction transaction(Transaction.ReconciliationStatus status, boolean manuallySet, boolean recurring) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setAmount(BigDecimal.TEN);
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setReconciliationStatus(status);
        t.setCategoryManuallySet(manuallySet);
        t.setRecurring(recurring);
        return t;
    }

    private Merchant merchant() {
        Merchant m = new Merchant();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        m.setUserId(userId);
        m.setCanonicalName("Amazon");
        return m;
    }

    private MerchantCategoryLearning pair(UUID merchantId, int confidence) {
        MerchantCategoryLearning p = new MerchantCategoryLearning();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setUserId(userId);
        p.setMerchantId(merchantId);
        p.setCategoryId(UUID.randomUUID());
        p.setConfirmationCount(10);
        p.setConfidence(confidence);
        return p;
    }

    @Test
    void summarize_withNoTransactions_returnsNullCategorizationAccuracy_notDivideByZero() {
        var summary = service.summarize(userId);

        assertThat(summary.totalTransactions()).isZero();
        assertThat(summary.categorizationAccuracy()).isNull();
    }

    @Test
    void summarize_computesAutomationRate_asShareOfTransactionsNotManuallyCorrected() {
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(
                transaction(Transaction.ReconciliationStatus.OK, false, false),
                transaction(Transaction.ReconciliationStatus.OK, false, false),
                transaction(Transaction.ReconciliationStatus.OK, false, false),
                transaction(Transaction.ReconciliationStatus.OK, true, false)));

        var summary = service.summarize(userId);

        // 3 of 4 transactions were never manually corrected -> 75.0%
        assertThat(summary.categorizationAccuracy()).isEqualTo(75.0);
    }

    @Test
    void summarize_countsReconciliationStatusesIndependently() {
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(
                transaction(Transaction.ReconciliationStatus.DUPLICATE, false, false),
                transaction(Transaction.ReconciliationStatus.DUPLICATE, false, false),
                transaction(Transaction.ReconciliationStatus.TRANSFER, false, false),
                transaction(Transaction.ReconciliationStatus.REFUND, false, false),
                transaction(Transaction.ReconciliationStatus.OK, false, true)));

        var summary = service.summarize(userId);

        assertThat(summary.duplicateMatches()).isEqualTo(2);
        assertThat(summary.transferMatches()).isEqualTo(1);
        assertThat(summary.refundMatches()).isEqualTo(1);
        assertThat(summary.recurringTransactions()).isEqualTo(1);
    }

    @Test
    void summarize_bucketsMerchantsByTopCategoryConfidence_usingMerchantsTsxsExistingThresholds() {
        Merchant highConfidence = merchant();
        Merchant mediumConfidence = merchant();
        Merchant lowConfidence = merchant();
        Merchant unconfirmed = merchant();
        when(merchantRepository.findByUserId(userId)).thenReturn(
                List.of(highConfidence, mediumConfidence, lowConfidence, unconfirmed));
        when(learningRepository.findByUserId(userId)).thenReturn(List.of(
                pair(highConfidence.getId(), 95),
                pair(mediumConfidence.getId(), 75),
                pair(lowConfidence.getId(), 40)));

        var summary = service.summarize(userId);

        assertThat(summary.confidenceDistribution())
                .containsEntry("HIGH", 1L)
                .containsEntry("MEDIUM", 1L)
                .containsEntry("LOW", 1L)
                .containsEntry("UNCONFIRMED", 1L);
        assertThat(summary.totalMerchants()).isEqualTo(4);
        assertThat(summary.learnedMerchants()).isEqualTo(3); // unconfirmed has no learning pair at all
    }

    @Test
    void summarize_recentActivity_reusesTheTop5AuditLogQuery_notTheUnboundedOne() {
        AuditLog log = new AuditLog();
        ReflectionTestUtils.setField(log, "id", UUID.randomUUID());
        log.setUserId(userId);
        log.setAction("RULE_CREATED");
        when(auditLogRepository.findTop5ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(log));

        var summary = service.summarize(userId);

        assertThat(summary.recentActivity()).hasSize(1);
        assertThat(summary.recentActivity().get(0).action()).isEqualTo("RULE_CREATED");
    }

    @Test
    void summarize_activeRulesOnlyCountsEnabledOnes() {
        CategoryRule enabled = new CategoryRule();
        when(categoryRuleRepository.findByUserIdAndEnabledTrueOrderByPriorityAsc(userId)).thenReturn(List.of(enabled));

        var summary = service.summarize(userId);

        assertThat(summary.activeRules()).isEqualTo(1);
    }

    @Test
    void summarize_countsAccountsRelationshipsAndStatementImports() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(new Account(), new Account()));
        when(relationshipRepository.findByUserId(userId)).thenReturn(List.of(new Relationship()));
        when(statementImportRepository.countByUserId(userId)).thenReturn(3L);

        var summary = service.summarize(userId);

        assertThat(summary.totalAccounts()).isEqualTo(2);
        assertThat(summary.relationships()).isEqualTo(1);
        assertThat(summary.statementsImported()).isEqualTo(3);
    }

    // --- Workspace Health ---

    @Test
    void health_rulesEnabledAndMerchantLearningActive_reflectRealCounts() {
        when(categoryRuleRepository.findByUserIdAndEnabledTrueOrderByPriorityAsc(userId))
                .thenReturn(List.of(new CategoryRule()));
        Merchant m = merchant();
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(m));
        when(learningRepository.findByUserId(userId)).thenReturn(List.of(pair(m.getId(), 90)));

        var health = service.summarize(userId).health();

        assertThat(health.rulesEnabled()).isTrue();
        assertThat(health.merchantLearningActive()).isTrue();
    }

    @Test
    void health_noRulesOrLearning_bothFalse() {
        var health = service.summarize(userId).health();

        assertThat(health.rulesEnabled()).isFalse();
        assertThat(health.merchantLearningActive()).isFalse();
    }

    @Test
    void health_reconciliationHealthy_whenEveryPointerResolvesToARealTransaction() {
        Transaction original = transaction(Transaction.ReconciliationStatus.OK, false, false);
        Transaction duplicate = transaction(Transaction.ReconciliationStatus.DUPLICATE, false, false);
        duplicate.setIsDuplicateOf(original.getId());
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(original, duplicate));

        var health = service.summarize(userId).health();

        assertThat(health.reconciliationHealthy()).isTrue();
    }

    @Test
    void health_reconciliationUnhealthy_whenADuplicatePointerDanglesAtADeletedTransaction() {
        // Regression test for exactly the bug class found and fixed this session --
        // TransactionService.clearReconciliationPointersTo()/StatementImportService.delete()
        // reset these pointers on delete specifically so this can never happen; this check exists
        // to catch it live if that ever regresses, or a future write path introduces a new gap.
        Transaction duplicate = transaction(Transaction.ReconciliationStatus.DUPLICATE, false, false);
        duplicate.setIsDuplicateOf(UUID.randomUUID()); // points at a transaction that isn't in the list at all
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(duplicate));

        var health = service.summarize(userId).health();

        assertThat(health.reconciliationHealthy()).isFalse();
    }

    @Test
    void health_reconciliationUnhealthy_whenARefundPointerDangles() {
        Transaction refundIncome = transaction(Transaction.ReconciliationStatus.REFUND, false, false);
        refundIncome.setRefundOfTransactionId(UUID.randomUUID());
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(refundIncome));

        var health = service.summarize(userId).health();

        assertThat(health.reconciliationHealthy()).isFalse();
    }

    @Test
    void health_auditLoggingHealthy_whenThereIsNothingToHaveLoggedYet() {
        // Brand-new user, no transactions, no rules -- an empty activity feed here is expected,
        // not suspicious, so this must read healthy rather than false-alarm.
        var health = service.summarize(userId).health();

        assertThat(health.auditLoggingHealthy()).isTrue();
    }

    @Test
    void health_auditLoggingUnhealthy_whenThereIsRealDataButNoActivityEverLogged() {
        when(transactionRepository.findByUserId(userId))
                .thenReturn(List.of(transaction(Transaction.ReconciliationStatus.OK, false, false)));
        when(auditLogRepository.findTop5ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

        var health = service.summarize(userId).health();

        assertThat(health.auditLoggingHealthy()).isFalse();
    }

    @Test
    void health_recurringDetectionHealthy_isADocumentedPlaceholder_alwaysTrueForNow() {
        var health = service.summarize(userId).health();

        assertThat(health.recurringDetectionHealthy()).isTrue();
    }
}
