package com.finora.transactions;

import com.finora.entity.Category;
import com.finora.entity.CategoryRule;
import com.finora.entity.Merchant;
import com.finora.entity.Transaction;
import com.finora.exception.ApiException;
import com.finora.repository.CategoryRepository;
import com.finora.repository.CategoryRuleRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Why this category?" (Transaction Explanation panel) -- every branch reads {@link
 * Transaction#getDecisionSource()}/{@code getDecisionRuleId()}, both already written by {@code
 * CategorizationService} before this class exists; nothing here computes a new answer.
 */
class TransactionExplanationServiceTest {

    private TransactionRepository transactionRepository;
    private CategoryRuleRepository categoryRuleRepository;
    private MerchantRepository merchantRepository;
    private CategoryRepository categoryRepository;
    private TransactionExplanationService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID txnId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        categoryRuleRepository = mock(CategoryRuleRepository.class);
        merchantRepository = mock(MerchantRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        service = new TransactionExplanationService(
                transactionRepository, categoryRuleRepository, merchantRepository, categoryRepository);
    }

    @Test
    void manualDecisionNeedsNoFurtherEvidence() {
        Transaction t = transaction(Transaction.DecisionSource.MANUAL, null, Transaction.Source.MANUAL);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

        TransactionExplanationDto result = service.explain(userId, txnId);

        assertThat(result.decisionSource()).isEqualTo("MANUAL");
        assertThat(result.summary()).isEqualTo("You set this category yourself.");
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void userRuleExplainsTheActualConditionThatMatched() {
        UUID ruleId = UUID.randomUUID();
        Transaction t = transaction(Transaction.DecisionSource.USER_RULE, ruleId, Transaction.Source.CSV_IMPORT);
        CategoryRule rule = new CategoryRule();
        ReflectionTestUtils.setField(rule, "id", ruleId);
        rule.setField(CategoryRule.Field.DESCRIPTION);
        rule.setOperator(CategoryRule.Operator.CONTAINS);
        rule.setComparisonValue("AMAZON");
        rule.setActionValue("Shopping");
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));
        when(categoryRuleRepository.findById(ruleId)).thenReturn(Optional.of(rule));

        TransactionExplanationDto result = service.explain(userId, txnId);

        assertThat(result.decisionSource()).isEqualTo("USER_RULE");
        assertThat(result.summary()).contains("description contains \"AMAZON\"").contains("Shopping");
        assertThat(result.evidence()).contains("Rule condition: description contains \"AMAZON\"");
    }

    @Test
    void aBetweenRuleShowsReadableNumbersNotTheRawStorageEncoding() {
        UUID ruleId = UUID.randomUUID();
        Transaction t = transaction(Transaction.DecisionSource.USER_RULE, ruleId, Transaction.Source.CSV_IMPORT);
        CategoryRule rule = new CategoryRule();
        ReflectionTestUtils.setField(rule, "id", ruleId);
        rule.setField(CategoryRule.Field.AMOUNT);
        rule.setOperator(CategoryRule.Operator.BETWEEN);
        rule.setComparisonValue("1000,5000"); // RuleEngineService's own storage format, "low,high"
        rule.setActionValue("Shopping");
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));
        when(categoryRuleRepository.findById(ruleId)).thenReturn(Optional.of(rule));

        TransactionExplanationDto result = service.explain(userId, txnId);

        assertThat(result.summary()).contains("amount is between 1000 and 5000");
        assertThat(result.summary()).doesNotContain("1000,5000");
        assertThat(result.evidence()).contains("Rule condition: amount is between 1000 and 5000");
    }

    @Test
    void aDeletedRuleStillGetsAnHonestAnswerNotAnError() {
        UUID ruleId = UUID.randomUUID();
        Transaction t = transaction(Transaction.DecisionSource.GLOBAL_RULE, ruleId, Transaction.Source.CSV_IMPORT);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));
        when(categoryRuleRepository.findById(ruleId)).thenReturn(Optional.empty());

        TransactionExplanationDto result = service.explain(userId, txnId);

        assertThat(result.decisionSource()).isEqualTo("GLOBAL_RULE");
        assertThat(result.summary()).isEqualTo("Matched one of Finora's built-in rules.");
        assertThat(result.evidence()).anyMatch(s -> s.contains("no longer available"));
    }

    @Test
    void learnedPatternNamesTheMerchant() {
        UUID merchantId = UUID.randomUUID();
        Transaction t = transaction(Transaction.DecisionSource.LEARNED_PATTERN, null, Transaction.Source.CSV_IMPORT);
        t.setMerchantId(merchantId);
        Merchant merchant = new Merchant();
        merchant.setCanonicalName("Swiggy");
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));
        when(merchantRepository.findById(merchantId)).thenReturn(Optional.of(merchant));

        TransactionExplanationDto result = service.explain(userId, txnId);

        assertThat(result.summary()).contains("\"Swiggy\"");
    }

    @Test
    void keywordMatchNeedsNoRuleLookup() {
        Transaction t = transaction(Transaction.DecisionSource.KEYWORD_MATCH, null, Transaction.Source.CSV_IMPORT);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

        TransactionExplanationDto result = service.explain(userId, txnId);

        assertThat(result.summary()).isEqualTo("Matched a keyword Finora recognizes in the description.");
    }

    @Test
    void fileProvidedIsTakenAtFaceValue() {
        Transaction t = transaction(Transaction.DecisionSource.FILE_PROVIDED, null, Transaction.Source.CSV_IMPORT);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

        TransactionExplanationDto result = service.explain(userId, txnId);

        assertThat(result.summary()).isEqualTo("The imported file specified this category directly.");
    }

    @Test
    void defaultForAGmailImportNamesTheMissingCoverageHonestly() {
        Transaction t = transaction(Transaction.DecisionSource.MERCHANT_DEFAULT, null, Transaction.Source.GMAIL_IMPORT);
        t.setMerchant("amazon.in");
        Category category = new Category();
        category.setName("Other");
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));
        when(categoryRepository.findById(t.getCategoryId())).thenReturn(Optional.of(category));

        TransactionExplanationDto result = service.explain(userId, txnId);

        assertThat(result.summary())
                .contains("Gmail receipt")
                .contains("amazon.in")
                .contains("doesn't auto-detect a category for this merchant yet");
    }

    @Test
    void defaultForABankImportIsPlainerAboutTheSameFact() {
        Transaction t = transaction(Transaction.DecisionSource.MERCHANT_DEFAULT, null, Transaction.Source.CSV_IMPORT);
        Category category = new Category();
        category.setName("Other");
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));
        when(categoryRepository.findById(t.getCategoryId())).thenReturn(Optional.of(category));

        TransactionExplanationDto result = service.explain(userId, txnId);

        assertThat(result.summary()).doesNotContain("Gmail").contains("No rule, learned pattern, or keyword matched");
    }

    @Test
    void aMissingCategoryRowStillProducesAnAnswer() {
        Transaction t = transaction(Transaction.DecisionSource.MERCHANT_DEFAULT, null, Transaction.Source.CSV_IMPORT);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));
        when(categoryRepository.findById(t.getCategoryId())).thenReturn(Optional.empty());

        TransactionExplanationDto result = service.explain(userId, txnId);

        assertThat(result.summary()).contains("this category");
    }

    @Test
    void explain_includesTheDecisionConfidence_whenPresent() {
        Transaction t = transaction(Transaction.DecisionSource.LEARNED_PATTERN, null, Transaction.Source.CSV_IMPORT);
        t.setDecisionConfidence(82);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

        TransactionExplanationDto result = service.explain(userId, txnId);

        assertThat(result.confidence()).isEqualTo(82);
    }

    @Test
    void explain_omitsConfidence_forAManualDecision() {
        Transaction t = transaction(Transaction.DecisionSource.MANUAL, null, Transaction.Source.MANUAL);
        // decisionConfidence deliberately left null -- TransactionService never sets it for MANUAL.
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

        TransactionExplanationDto result = service.explain(userId, txnId);

        assertThat(result.confidence()).isNull();
    }

    /**
     * "Why this match?" -- Phase 1, docs/proposals/reconciliation-evolution-roadmap-proposal.md.
     * The overwhelming common case: nothing matched this row, so there's nothing to explain.
     */
    @Test
    void reconciliationIsNull_forAnOkTransaction() {
        Transaction t = transaction(Transaction.DecisionSource.MANUAL, null, Transaction.Source.MANUAL);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

        TransactionExplanationDto result = service.explain(userId, txnId);

        assertThat(result.reconciliation()).isNull();
    }

    @Test
    void reconciliationExplainsARefund_withTheMatchedExpenseIdFromTheEntity_notTheJson() {
        UUID expenseId = UUID.randomUUID();
        Transaction t = transaction(Transaction.DecisionSource.MANUAL, null, Transaction.Source.CSV_IMPORT);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
        t.setRefundOfTransactionId(expenseId);
        t.setReconciliationExplanation(java.util.Map.of("type", "REFUND", "reason", java.util.Map.of(
                "refundKeyword", true, "sameMerchant", false,
                "refundAmount", "340.00", "purchaseAmount", "340.00", "partialRefund", false)));
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

        var reconciliation = service.explain(userId, txnId).reconciliation();

        assertThat(reconciliation.status()).isEqualTo("REFUND");
        assertThat(reconciliation.matchedTransactionId()).isEqualTo(expenseId);
        assertThat(reconciliation.summary()).contains("refund").contains("based on its wording");
        assertThat(reconciliation.evidence()).contains("Full refund");
    }

    @Test
    void reconciliationExplainsAReversal_distinctlyFromARefund() {
        UUID expenseId = UUID.randomUUID();
        Transaction t = transaction(Transaction.DecisionSource.MANUAL, null, Transaction.Source.CSV_IMPORT);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.REVERSAL);
        t.setRefundOfTransactionId(expenseId);
        t.setReconciliationExplanation(java.util.Map.of("type", "REVERSAL", "reason", java.util.Map.of(
                "reversalKeyword", true, "sameMerchant", false,
                "reversalAmount", "1200.00", "purchaseAmount", "1200.00", "partialReversal", false)));
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

        var reconciliation = service.explain(userId, txnId).reconciliation();

        assertThat(reconciliation.status()).isEqualTo("REVERSAL");
        assertThat(reconciliation.summary()).contains("reversal").doesNotContain("refund");
        assertThat(reconciliation.evidence()).contains("Full reversal");
    }

    @Test
    void reconciliationExplainsATransfer_withTheTransferPairId() {
        UUID pairId = UUID.randomUUID();
        Transaction t = transaction(Transaction.DecisionSource.MANUAL, null, Transaction.Source.CSV_IMPORT);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.TRANSFER);
        t.setTransfer(true);
        t.setTransferPairId(pairId);
        t.setReconciliationExplanation(java.util.Map.of("type", "TRANSFER", "reason", java.util.Map.of(
                "differentAccount", true, "oppositeDirection", true,
                "amountDifference", "0.00", "dateDifferenceDays", 2L, "dayWindowApplied", 4L,
                "relationshipIdentifierMatched", false)));
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

        var reconciliation = service.explain(userId, txnId).reconciliation();

        assertThat(reconciliation.status()).isEqualTo("TRANSFER");
        assertThat(reconciliation.matchedTransactionId()).isEqualTo(pairId);
        assertThat(reconciliation.summary()).contains("2 day(s) apart");
    }

    @Test
    void reconciliationExplainsADuplicate_withTheOriginalTransactionId() {
        UUID originalId = UUID.randomUUID();
        Transaction t = transaction(Transaction.DecisionSource.MANUAL, null, Transaction.Source.CSV_IMPORT);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.DUPLICATE);
        t.setIsDuplicateOf(originalId);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

        var reconciliation = service.explain(userId, txnId).reconciliation();

        assertThat(reconciliation.status()).isEqualTo("DUPLICATE");
        assertThat(reconciliation.matchedTransactionId()).isEqualTo(originalId);
        assertThat(reconciliation.summary()).contains("duplicate");
    }

    /** A row that predates V55 (reconciliation_explanation) has a status but no recorded JSON --
     *  still a real, honest answer, not an error. */
    @Test
    void reconciliationStillAnswers_whenTheExplanationJsonPredatesV55() {
        UUID originalId = UUID.randomUUID();
        Transaction t = transaction(Transaction.DecisionSource.MANUAL, null, Transaction.Source.CSV_IMPORT);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.DUPLICATE);
        t.setIsDuplicateOf(originalId);
        // reconciliationExplanation deliberately left null
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

        var reconciliation = service.explain(userId, txnId).reconciliation();

        assertThat(reconciliation.status()).isEqualTo("DUPLICATE");
        assertThat(reconciliation.evidence()).isEmpty();
    }

    @Test
    void someoneElsesTransactionIsRejected() {
        Transaction t = transaction(Transaction.DecisionSource.MANUAL, null, Transaction.Source.MANUAL);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.explain(UUID.randomUUID(), txnId))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aNonexistentTransactionIsNotFound() {
        when(transactionRepository.findById(txnId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.explain(userId, txnId))
                .isInstanceOf(ApiException.class);
    }

    private Transaction transaction(Transaction.DecisionSource decisionSource, UUID ruleId, Transaction.Source source) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", txnId);
        t.setUserId(userId);
        t.setCategoryId(UUID.randomUUID());
        t.setSource(source);
        t.setDecisionSource(decisionSource);
        t.setDecisionRuleId(ruleId);
        return t;
    }
}
