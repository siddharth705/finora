package com.finora.service;

import com.finora.entity.Category;
import com.finora.entity.CategoryRule;
import com.finora.entity.Merchant;
import com.finora.entity.MerchantCategoryLearning;
import com.finora.entity.Transaction;
import com.finora.dto.WorkspaceSettingsDto;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests CategorizationService's own logic (which category to suggest, how learn() delegates)
 * with MerchantNormalizationEngine and MerchantLearningService mocked as collaborators — their
 * own internals are covered by their own dedicated test classes, not re-tested here.
 */
class CategorizationServiceTest {

    private MerchantNormalizationEngine merchantNormalizationEngine;
    private MerchantLearningService merchantLearningService;
    private MerchantLearningEventPublisher learningEventPublisher;
    private MerchantCategoryLearningRepository learningRepository;
    private CategoryRepository categoryRepository;
    private RuleEngineService ruleEngineService;
    private WorkspaceSettingsService workspaceSettingsService;
    private CategorizationService categorizationService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        merchantNormalizationEngine = mock(MerchantNormalizationEngine.class);
        merchantLearningService = mock(MerchantLearningService.class);
        // Mocked here because this class is about which collaborator learn()/queueLearning()
        // delegate to, not about whether the queue works -- the queue's own behaviour needs a real
        // transaction and a real Postgres to mean anything, which is what MerchantLearningQueueIT
        // and BulkRecategorizeLearningIT are for.
        learningEventPublisher = mock(MerchantLearningEventPublisher.class);
        learningRepository = mock(MerchantCategoryLearningRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        // Unstubbed -- Mockito's default answer returns Optional.empty() for Optional-returning
        // methods, so every test here (none of which are about the rule engine itself -- see
        // RuleEngineServiceTest) falls straight through to the learned/keyword logic being tested.
        ruleEngineService = mock(RuleEngineService.class);
        workspaceSettingsService = mock(WorkspaceSettingsService.class);
        categorizationService = new CategorizationService(
                merchantNormalizationEngine, merchantLearningService, learningEventPublisher,
                learningRepository,
                new ConfidenceEngine(), categoryRepository, ruleEngineService, workspaceSettingsService); // ConfidenceEngine is pure logic — real instance is fine
    }

    private Merchant merchantWithId(UUID id) {
        Merchant m = new Merchant();
        ReflectionTestUtils.setField(m, "id", id);
        return m;
    }

    private Merchant merchantWith(UUID id, String canonicalName) {
        Merchant m = new Merchant();
        ReflectionTestUtils.setField(m, "id", id);
        m.setCanonicalName(canonicalName);
        return m;
    }

    @Test
    void suggest_fallsBackToMerchantCanonicalName_whenRawDescriptionHasNoKeyword() {
        UUID merchantId = UUID.randomUUID();
        // Simulates a merchant whose canonical name was correctly identified from an EARLIER
        // transaction's narration (or an admin/user rename) -- this transaction's own raw text
        // carries no recognizable brand token at all.
        Merchant merchant = merchantWith(merchantId, "Swiggy Bangalore");
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchant);
        when(learningRepository.findByUserIdAndMerchantId(userId, merchantId)).thenReturn(List.of());

        var suggestion = categorizationService.suggest(userId, "UPI/REF88213764/SETTLEMENT");

        assertThat(suggestion.category()).isEqualTo("Dining");
        assertThat(suggestion.source()).isEqualTo("rule");
        assertThat(suggestion.decisionSource()).isEqualTo(Transaction.DecisionSource.KEYWORD_MATCH);
    }

    @Test
    void suggestReadOnly_fallsBackToMerchantCanonicalName_whenRawDescriptionHasNoKeyword() {
        UUID merchantId = UUID.randomUUID();
        Merchant merchant = merchantWith(merchantId, "Swiggy Bangalore");
        when(merchantNormalizationEngine.resolveReadOnly(eq(userId), anyString()))
                .thenReturn(Optional.of(merchant));
        when(learningRepository.findByUserIdAndMerchantId(userId, merchantId)).thenReturn(List.of());

        var suggestion = categorizationService.suggestReadOnly(userId, "UPI/REF88213764/SETTLEMENT");

        assertThat(suggestion.category()).isEqualTo("Dining");
        assertThat(suggestion.decisionSource()).isEqualTo(Transaction.DecisionSource.KEYWORD_MATCH);
    }

    @Test
    void suggest_fallsBackToRuleEngine_whenMerchantHasNoLearnedDistribution() {
        UUID merchantId = UUID.randomUUID();
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(merchantId));
        when(learningRepository.findByUserIdAndMerchantId(userId, merchantId)).thenReturn(List.of());

        var suggestion = categorizationService.suggest(userId, "SWIGGY*ORDR9182 BLR");

        assertThat(suggestion.category()).isEqualTo("Dining");
        assertThat(suggestion.source()).isEqualTo("rule");
        assertThat(suggestion.merchantId()).isEqualTo(merchantId);
    }

    @Test
    void suggest_fallsBackToOther_whenNoRuleMatchesAndNoDistribution() {
        UUID merchantId = UUID.randomUUID();
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(merchantId));
        when(learningRepository.findByUserIdAndMerchantId(userId, merchantId)).thenReturn(List.of());

        var suggestion = categorizationService.suggest(userId, "SOME COMPLETELY UNKNOWN VENDOR");

        assertThat(suggestion.category()).isEqualTo("Other");
        assertThat(suggestion.source()).isEqualTo("default");
    }

    @Test
    void suggest_prefersLearnedDistribution_overRuleEngine() {
        // Even though "SWIGGY" would normally match the Dining rule, a merchant with real
        // confirmed history should win — this is the entire point of the self-learning system.
        UUID merchantId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();

        MerchantCategoryLearning pair = new MerchantCategoryLearning();
        pair.setMerchantId(merchantId);
        pair.setUserId(userId);
        pair.setCategoryId(categoryId);
        pair.setConfirmationCount(5);

        Category category = new Category();
        category.setUserId(userId);
        category.setName("Entertainment");

        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(merchantId));
        when(learningRepository.findByUserIdAndMerchantId(userId, merchantId)).thenReturn(List.of(pair));
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));

        var suggestion = categorizationService.suggest(userId, "SWIGGY*ORDR9182 BLR");

        assertThat(suggestion.category()).isEqualTo("Entertainment");
        assertThat(suggestion.source()).isEqualTo("learned");
        assertThat(suggestion.merchantId()).isEqualTo(merchantId);
    }

    @Test
    void suggest_userRuleMatch_reportsInitialRuleConfidence() {
        UUID merchantId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        CategoryRule rule = new CategoryRule();
        ReflectionTestUtils.setField(rule, "id", ruleId);
        rule.setActionValue("Dining");
        rule.setScope(CategoryRule.Scope.USER);

        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(merchantId));
        when(ruleEngineService.evaluateCategoryRule(eq(userId), anyString(), any(), anyString(), any()))
                .thenReturn(Optional.of(new RuleEngineService.RuleMatch(rule)));

        var suggestion = categorizationService.suggest(userId, "AMAZON PAY");

        assertThat(suggestion.confidence()).isEqualTo(ConfidenceEngine.INITIAL_RULE_CONFIDENCE);
    }

    @Test
    void suggest_learnedPattern_reportsRealConfidencePercentage_notJustHighestCount() {
        // Amazon-shaped distribution: 3 Shopping confirmations, 1 Electronics -- a genuine 75%,
        // not the flat 70 a rule match gets and not the highest-count category's raw count.
        UUID merchantId = UUID.randomUUID();
        UUID shoppingId = UUID.randomUUID();
        UUID electronicsId = UUID.randomUUID();

        MerchantCategoryLearning shopping = new MerchantCategoryLearning();
        shopping.setMerchantId(merchantId);
        shopping.setUserId(userId);
        shopping.setCategoryId(shoppingId);
        shopping.setConfirmationCount(3);
        MerchantCategoryLearning electronics = new MerchantCategoryLearning();
        electronics.setMerchantId(merchantId);
        electronics.setUserId(userId);
        electronics.setCategoryId(electronicsId);
        electronics.setConfirmationCount(1);

        Category shoppingCategory = new Category();
        shoppingCategory.setUserId(userId);
        shoppingCategory.setName("Shopping");

        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(merchantId));
        when(learningRepository.findByUserIdAndMerchantId(userId, merchantId))
                .thenReturn(List.of(shopping, electronics));
        when(categoryRepository.findById(shoppingId)).thenReturn(Optional.of(shoppingCategory));

        var suggestion = categorizationService.suggest(userId, "AMAZON PAY");

        assertThat(suggestion.category()).isEqualTo("Shopping");
        assertThat(suggestion.confidence()).isEqualTo(75); // round(3 * 100.0 / 4)
    }

    @Test
    void suggest_keywordFallbackMatch_reportsInitialRuleConfidence() {
        UUID merchantId = UUID.randomUUID();
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(merchantId));
        when(learningRepository.findByUserIdAndMerchantId(userId, merchantId)).thenReturn(List.of());

        // "SWIGGY" hits the static keyword table's Dining rule -- see
        // suggest_fallsBackToRuleEngine_whenMerchantHasNoLearnedDistribution above for the same setup.
        var suggestion = categorizationService.suggest(userId, "SWIGGY*ORDR9182 BLR");

        assertThat(suggestion.source()).isEqualTo("rule");
        assertThat(suggestion.confidence()).isEqualTo(ConfidenceEngine.INITIAL_RULE_CONFIDENCE);
    }

    @Test
    void suggest_defaultFallback_reportsInitialDefaultConfidence() {
        UUID merchantId = UUID.randomUUID();
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(merchantId));
        when(learningRepository.findByUserIdAndMerchantId(userId, merchantId)).thenReturn(List.of());

        var suggestion = categorizationService.suggest(userId, "SOME COMPLETELY UNKNOWN VENDOR");

        assertThat(suggestion.source()).isEqualTo("default");
        assertThat(suggestion.confidence()).isEqualTo(ConfidenceEngine.INITIAL_DEFAULT_CONFIDENCE);
    }

    @Test
    void learn_resolvesMerchantAndDelegatesToMerchantLearningService() {
        UUID merchantId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(merchantId));

        categorizationService.learn(userId, "SWIGGY*ORDR9182 BLR", categoryId);

        verify(merchantLearningService).confirm(userId, merchantId, categoryId);
        // The synchronous path must not ALSO queue -- a single interactive action that both applied
        // the learning and left an event behind would confirm the merchant twice.
        verifyNoInteractions(learningEventPublisher);
    }

    // --- Auto-apply confidence threshold --------------------------------------------------------

    @Test
    void needsCategoryReview_flagsADefaultSuggestion_whenConfidenceIsBelowTheUsersThreshold() {
        when(workspaceSettingsService.get(userId))
                .thenReturn(new WorkspaceSettingsDto(90, null));

        boolean result = categorizationService.needsCategoryReview(userId, true, ConfidenceEngine.INITIAL_DEFAULT_CONFIDENCE);

        assertThat(result).isTrue(); // 20 < 90
    }

    @Test
    void needsCategoryReview_clearsTheFlag_whenConfidenceMeetsALowerUserThreshold() {
        when(workspaceSettingsService.get(userId))
                .thenReturn(new WorkspaceSettingsDto(10, null));

        boolean result = categorizationService.needsCategoryReview(userId, true, ConfidenceEngine.INITIAL_DEFAULT_CONFIDENCE);

        assertThat(result).isFalse(); // 20 >= 10 -- a permissive threshold clears the default-source flag
    }

    @Test
    void needsCategoryReview_isFalse_wheneverTheSourceWasNotADefaultGuess() {
        // A rule/learned match is never flagged for review regardless of confidence or threshold --
        // this mirrors the exact pre-existing behaviour (source.equals("default")) this method replaces.
        boolean result = categorizationService.needsCategoryReview(userId, false, 20);

        assertThat(result).isFalse();
        verifyNoInteractions(workspaceSettingsService);
    }

    @Test
    void needsCategoryReview_staysTrue_whenConfidenceIsNull() {
        // A caller with no confidence to report (shouldn't happen post-Task-1, but must fail safe)
        // keeps the pre-existing "always flag a default guess" behaviour rather than silently clearing it.
        boolean result = categorizationService.needsCategoryReview(userId, true, null);

        assertThat(result).isTrue();
        verifyNoInteractions(workspaceSettingsService);
    }

    // --- WI1A: the batch counterpart ------------------------------------------------------------

    @Test
    void queueLearning_resolvesTheMerchantItselfAndQueuesTheConfirmationInsteadOfApplyingIt() {
        UUID merchantId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(merchantId));

        categorizationService.queueLearning(userId, "SWIGGY*ORDR9182 BLR", categoryId);

        // The merchant is still resolved HERE, in the caller's transaction: the worker is handed an
        // id, never a description, so it never has to create a merchant of its own after the fact.
        verify(merchantNormalizationEngine).resolve(userId, "SWIGGY*ORDR9182 BLR");
        // Null source ids because a bulk recategorization is not an import and had no staging
        // session -- see queueLearning's doc comment for why that is stated rather than invented.
        verify(learningEventPublisher).enqueue(userId, merchantId, categoryId, null, null);
        // And nothing is applied inline, which is the entire point of WI1A.
        verify(merchantLearningService, never()).confirm(any(), any(), any());
    }

    @Test
    void resolveMerchantId_delegatesToNormalizationEngine() {
        UUID merchantId = UUID.randomUUID();
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(merchantId));

        UUID result = categorizationService.resolveMerchantId(userId, "some description");

        assertThat(result).isEqualTo(merchantId);
    }

    @Test
    void resolveOrCreateCategory_returnsExisting_whenNameAlreadyExists() {
        Category existing = new Category();
        existing.setUserId(userId);
        existing.setName("Dining");
        when(categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, "Dining")).thenReturn(List.of(existing));

        Category result = categorizationService.resolveOrCreateCategory(userId, "Dining");

        assertThat(result).isSameAs(existing);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void resolveOrCreateCategory_createsNew_whenNameDoesNotExist() {
        when(categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, "Custom Category")).thenReturn(List.of());
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

        Category result = categorizationService.resolveOrCreateCategory(userId, "Custom Category");

        assertThat(result.getName()).isEqualTo("Custom Category");
        assertThat(result.isSystem()).isFalse();
        verify(categoryRepository).save(any(Category.class));
    }

    /**
     * Bug 04 (docs/quality/bug-reports/BUG_REVIEW_REPORT.md). categories.name is VARCHAR(80) NOT
     * NULL with no upstream length check on the import-confirm path -- an oversized category cell
     * used to make the INSERT fail against the column constraint, which marks the whole confirm
     * transaction rollback-only and discards the entire import. Same fix shape as
     * MerchantNormalizationEngine.fitToColumn for merchant names on the identical parser-output
     * code path: truncate before the write is ever attempted, not after it fails.
     */
    @Test
    void resolveOrCreateCategory_truncatesAnOversizedName_ratherThanFailingTheInsert() {
        String oversized = "x".repeat(100);
        String truncated = "x".repeat(80);
        when(categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, truncated)).thenReturn(List.of());
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

        Category result = categorizationService.resolveOrCreateCategory(userId, oversized);

        assertThat(result.getName()).isEqualTo(truncated);
        assertThat(result.getName()).hasSize(80);
    }

    @Test
    void resolveOrCreateCategory_trimsSurroundingWhitespace_beforeCheckingLength() {
        String padded = " ".repeat(5) + "Dining" + " ".repeat(5);
        when(categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, "Dining")).thenReturn(List.of());
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

        Category result = categorizationService.resolveOrCreateCategory(userId, padded);

        assertThat(result.getName()).isEqualTo("Dining");
    }

    /**
     * Bug 16. resolveOrCreateCategory now matches case-insensitively and trims whitespace, so
     * "dining" resolves to an existing "Dining" row instead of creating a sibling that would
     * split a budget and double-count in reports.
     */
    @Test
    void resolveOrCreateCategory_matchesCaseInsensitively_andTrimsWhitespace() {
        Category existing = new Category();
        existing.setUserId(userId);
        existing.setName("Dining");
        when(categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, "dining")).thenReturn(List.of(existing));

        Category result = categorizationService.resolveOrCreateCategory(userId, " dining ");

        assertThat(result).isSameAs(existing);
        verify(categoryRepository, never()).save(any());
    }

    /**
     * Self-review catch: a pre-existing case-variant duplicate (a user who already has both
     * "Dining" and "dining" from BEFORE the Bug 16 fix shipped) must resolve to one of them
     * deterministically, not throw. A single-result derived query
     * (findByUserIdAndNameIgnoreCase, this method's first version) would have thrown
     * IncorrectResultSizeDataAccessException the moment it matched more than one row -- turning
     * every future category action for exactly the affected users into an unhandled 500.
     */
    @Test
    void resolveOrCreateCategory_picksOneDeterministically_whenAPreExistingCaseVariantDuplicateExists() {
        Category dining = new Category();
        dining.setUserId(userId);
        dining.setName("Dining");
        Category lowercaseDining = new Category();
        lowercaseDining.setUserId(userId);
        lowercaseDining.setName("dining");
        when(categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, "Dining"))
                .thenReturn(List.of(dining, lowercaseDining));

        Category result = categorizationService.resolveOrCreateCategory(userId, "Dining");

        assertThat(result).isSameAs(dining);
        verify(categoryRepository, never()).save(any());
    }

    /**
     * Guards against a regression the Bug 16 fix above could otherwise introduce:
     * TransactionService.updateCategory passes an unvalidated Map value straight through with no
     * upstream null check, unlike every other caller (which either validates via @NotBlank or
     * checks != null first). Before trimming was added, a null name reached
     * categoryRepository.save() with a NOT NULL column and came back as a confusing 409 CONFLICT.
     * Trimming a null would instead throw an unhandled NullPointerException into the generic 500
     * handler -- worse than the bug it replaced. This must throw a clean 400 instead.
     *
     * <p>Merge-conflict resolution note (Bug 04 x Bug 16): Bug 04's own version of this method
     * defaulted null/blank to "Other" instead of throwing, for the import-confirm path's "don't
     * fail the whole import over one bad cell" reasoning. That degradation now lives at
     * ImportService.confirm's own call site instead (see
     * ImportServiceAskOnceTest#confirm_fallsBackToOther_whenTheStatementsCategoryCellWasNullOrBlank)
     * -- this method throwing for every OTHER caller is what Bug 16's fix actually needs, since a
     * blank name reaching e.g. TransactionService.updateCategory is a malformed request, not a
     * parser artifact to paper over.
     */
    @Test
    void resolveOrCreateCategory_rejectsANullOrBlankName_withA400_ratherThanNpeOrANotNullViolation() {
        assertThatThrownBy(() -> categorizationService.resolveOrCreateCategory(userId, null))
                .isInstanceOf(com.finora.exception.ApiException.class)
                .satisfies(e -> assertThat(((com.finora.exception.ApiException) e).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> categorizationService.resolveOrCreateCategory(userId, "   "))
                .isInstanceOf(com.finora.exception.ApiException.class);

        verify(categoryRepository, never()).save(any());
    }

    // --- Rule engine integration (docs/rule-engine-relationship-engine-eds.md §4: rule engine
    // runs BEFORE the learned distribution / keyword fallback) ---

    private CategoryRule userRule(String actionValue) {
        CategoryRule r = new CategoryRule();
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        r.setScope(CategoryRule.Scope.USER);
        r.setActionType(CategoryRule.ActionType.ASSIGN_CATEGORY);
        r.setActionValue(actionValue);
        return r;
    }

    private CategoryRule globalRule(String actionValue) {
        CategoryRule r = new CategoryRule();
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        r.setScope(CategoryRule.Scope.GLOBAL);
        r.setActionType(CategoryRule.ActionType.ASSIGN_CATEGORY);
        r.setActionValue(actionValue);
        return r;
    }

    @Test
    void suggest_prefersUserRuleMatch_overLearnedDistributionAndKeywordFallback() {
        UUID merchantId = UUID.randomUUID();
        CategoryRule rule = userRule("Work Expenses");
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(merchantId));
        when(ruleEngineService.evaluateCategoryRule(eq(userId), anyString(), any(), any(), any()))
                .thenReturn(Optional.of(new RuleEngineService.RuleMatch(rule)));

        var suggestion = categorizationService.suggest(userId, "AMAZON BUSINESS ORDER");

        assertThat(suggestion.category()).isEqualTo("Work Expenses");
        assertThat(suggestion.source()).isEqualTo("user_rule");
        assertThat(suggestion.decisionSource()).isEqualTo(Transaction.DecisionSource.USER_RULE);
        assertThat(suggestion.ruleId()).isEqualTo(rule.getId());
        // A rule match resolves the category without ever consulting the merchant's learned
        // distribution -- verifying that path was skipped entirely, not just that its result lost.
        verify(learningRepository, never()).findByUserIdAndMerchantId(any(), any());
    }

    @Test
    void suggest_globalRuleMatch_reportsGlobalRuleAsTheDecisionSource() {
        UUID merchantId = UUID.randomUUID();
        CategoryRule rule = globalRule("Dining");
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(merchantId));
        when(ruleEngineService.evaluateCategoryRule(eq(userId), anyString(), any(), any(), any()))
                .thenReturn(Optional.of(new RuleEngineService.RuleMatch(rule)));

        var suggestion = categorizationService.suggest(userId, "SWIGGY ORDER");

        assertThat(suggestion.source()).isEqualTo("global_rule");
        assertThat(suggestion.decisionSource()).isEqualTo(Transaction.DecisionSource.GLOBAL_RULE);
    }

    @Test
    void suggest_keywordFallbackMatch_isTaggedAsKeywordMatch_distinctFromTheNewRuleEngine() {
        // Guards against re-conflating the pre-existing static CategoryRules keyword table with
        // the new category_rules DB table -- both can produce source()=="rule"-shaped results in
        // spirit, but only the DB-table path should ever report GLOBAL_RULE/USER_RULE.
        UUID merchantId = UUID.randomUUID();
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(merchantId));
        when(learningRepository.findByUserIdAndMerchantId(userId, merchantId)).thenReturn(List.of());
        // ruleEngineService is unstubbed -- returns Optional.empty(), falls through to keyword table.

        var suggestion = categorizationService.suggest(userId, "SWIGGY*ORDR9182 BLR");

        assertThat(suggestion.source()).isEqualTo("rule");
        assertThat(suggestion.decisionSource()).isEqualTo(Transaction.DecisionSource.KEYWORD_MATCH);
        assertThat(suggestion.ruleId()).isNull();
    }

    // --- decisionSourceFor: the categorySource string -> Transaction.DecisionSource mapping
    // CsvImportService.confirm() uses at persist time (see EDS §3.2) ---

    @Test
    void decisionSourceFor_mapsEveryKnownCategorySourceString() {
        assertThat(CategorizationService.decisionSourceFor("user_rule")).isEqualTo(Transaction.DecisionSource.USER_RULE);
        assertThat(CategorizationService.decisionSourceFor("global_rule")).isEqualTo(Transaction.DecisionSource.GLOBAL_RULE);
        assertThat(CategorizationService.decisionSourceFor("learned")).isEqualTo(Transaction.DecisionSource.LEARNED_PATTERN);
        assertThat(CategorizationService.decisionSourceFor("rule")).isEqualTo(Transaction.DecisionSource.KEYWORD_MATCH);
        assertThat(CategorizationService.decisionSourceFor("file")).isEqualTo(Transaction.DecisionSource.FILE_PROVIDED);
        assertThat(CategorizationService.decisionSourceFor("default")).isEqualTo(Transaction.DecisionSource.MERCHANT_DEFAULT);
    }

    @Test
    void decisionSourceFor_defaultsToMerchantDefault_forNullOrUnrecognizedValues() {
        assertThat(CategorizationService.decisionSourceFor(null)).isEqualTo(Transaction.DecisionSource.MERCHANT_DEFAULT);
        assertThat(CategorizationService.decisionSourceFor("something_unrecognized")).isEqualTo(Transaction.DecisionSource.MERCHANT_DEFAULT);
    }

    // --- applySideEffectRules (MARK_TRANSFER/MARK_INVESTMENT/MARK_SUBSCRIPTION/ADD_TAG) ---

    private Transaction txnFor(String description) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setDescription(description);
        t.setAmount(java.math.BigDecimal.valueOf(2500));
        t.setTxnType(Transaction.Type.EXPENSE);
        return t;
    }

    private CategoryRule sideEffectRule(CategoryRule.ActionType actionType, String actionValue) {
        CategoryRule r = new CategoryRule();
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        r.setScope(CategoryRule.Scope.GLOBAL);
        r.setActionType(actionType);
        r.setActionValue(actionValue);
        return r;
    }

    @Test
    void applySideEffectRules_markTransfer_setsTransferFlagAndReconciliationStatus() {
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(UUID.randomUUID()));
        when(ruleEngineService.evaluateSideEffectRules(eq(userId), anyString(), any(), any(), any()))
                .thenReturn(List.of(new RuleEngineService.RuleMatch(sideEffectRule(CategoryRule.ActionType.MARK_TRANSFER, null))));

        Transaction t = txnFor("NEFT AUTOPAY CREDIT CARD");
        Category result = categorizationService.applySideEffectRules(userId, t);

        assertThat(t.isTransfer()).isTrue();
        assertThat(t.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.TRANSFER);
        assertThat(result).isNull(); // MARK_TRANSFER doesn't change the category
    }

    @Test
    void applySideEffectRules_markInvestment_defaultsToInvestmentsCategory_whenActionValueIsBlank() {
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(UUID.randomUUID()));
        when(ruleEngineService.evaluateSideEffectRules(eq(userId), anyString(), any(), any(), any()))
                .thenReturn(List.of(new RuleEngineService.RuleMatch(sideEffectRule(CategoryRule.ActionType.MARK_INVESTMENT, null))));
        Category investments = new Category();
        ReflectionTestUtils.setField(investments, "id", UUID.randomUUID());
        investments.setUserId(userId);
        investments.setName("Investments");
        when(categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, "Investments")).thenReturn(List.of(investments));

        Transaction t = txnFor("SIP MUTUAL FUND DEDUCTION");
        Category result = categorizationService.applySideEffectRules(userId, t);

        assertThat(t.getCategoryId()).isEqualTo(investments.getId());
        assertThat(result).isEqualTo(investments);
    }

    @Test
    void applySideEffectRules_markInvestment_usesActionValueAsCategoryName_whenProvided() {
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(UUID.randomUUID()));
        when(ruleEngineService.evaluateSideEffectRules(eq(userId), anyString(), any(), any(), any()))
                .thenReturn(List.of(new RuleEngineService.RuleMatch(sideEffectRule(CategoryRule.ActionType.MARK_INVESTMENT, "SIP - Equity"))));
        Category sipEquity = new Category();
        ReflectionTestUtils.setField(sipEquity, "id", UUID.randomUUID());
        sipEquity.setUserId(userId);
        sipEquity.setName("SIP - Equity");
        when(categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, "SIP - Equity")).thenReturn(List.of(sipEquity));

        Transaction t = txnFor("SIP MUTUAL FUND DEDUCTION");
        categorizationService.applySideEffectRules(userId, t);

        assertThat(t.getCategoryId()).isEqualTo(sipEquity.getId());
    }

    @Test
    void applySideEffectRules_addTag_appendsWithoutDuplicating() {
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(UUID.randomUUID()));
        when(ruleEngineService.evaluateSideEffectRules(eq(userId), anyString(), any(), any(), any()))
                .thenReturn(List.of(new RuleEngineService.RuleMatch(sideEffectRule(CategoryRule.ActionType.ADD_TAG, "streaming"))));

        Transaction t = txnFor("NETFLIX.COM");
        t.setTags(List.of("streaming")); // already present

        categorizationService.applySideEffectRules(userId, t);

        assertThat(t.getTags()).containsExactly("streaming"); // not duplicated
    }

    @Test
    void applySideEffectRules_addTag_createsTagListWhenNull() {
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(UUID.randomUUID()));
        when(ruleEngineService.evaluateSideEffectRules(eq(userId), anyString(), any(), any(), any()))
                .thenReturn(List.of(new RuleEngineService.RuleMatch(sideEffectRule(CategoryRule.ActionType.ADD_TAG, "streaming"))));

        Transaction t = txnFor("NETFLIX.COM"); // tags left null

        categorizationService.applySideEffectRules(userId, t);

        assertThat(t.getTags()).containsExactly("streaming");
    }

    @Test
    void applySideEffectRules_markSubscription_isANoOp_leftToRecurringService() {
        // See applySideEffectRules's own doc comment for why -- RecurringService fully
        // recomputes Transaction.recurring on every call, so setting it here would just get
        // wiped out the next time anyone loads the Recurring page.
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(UUID.randomUUID()));
        when(ruleEngineService.evaluateSideEffectRules(eq(userId), anyString(), any(), any(), any()))
                .thenReturn(List.of(new RuleEngineService.RuleMatch(sideEffectRule(CategoryRule.ActionType.MARK_SUBSCRIPTION, null))));

        Transaction t = txnFor("NETFLIX.COM");
        Category result = categorizationService.applySideEffectRules(userId, t);

        assertThat(t.isRecurring()).isFalse();
        assertThat(result).isNull();
    }

    @Test
    void applySideEffectRules_noMatches_leavesTransactionUntouched() {
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(UUID.randomUUID()));
        when(ruleEngineService.evaluateSideEffectRules(eq(userId), anyString(), any(), any(), any())).thenReturn(List.of());

        Transaction t = txnFor("REGULAR GROCERY PURCHASE");
        Category result = categorizationService.applySideEffectRules(userId, t);

        assertThat(t.isTransfer()).isFalse();
        assertThat(t.getTags()).isNull();
        assertThat(result).isNull();
    }

    // --- Rule execution tracking (Financial Intelligence Workspace, Rule Management module) ---

    @Test
    void applySideEffectRules_recordsAMatch_forEveryRuleThatFired() {
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(UUID.randomUUID()));
        CategoryRule markTransfer = sideEffectRule(CategoryRule.ActionType.MARK_TRANSFER, null);
        CategoryRule addTag = sideEffectRule(CategoryRule.ActionType.ADD_TAG, "streaming");
        when(ruleEngineService.evaluateSideEffectRules(eq(userId), anyString(), any(), any(), any())).thenReturn(List.of(
                new RuleEngineService.RuleMatch(markTransfer), new RuleEngineService.RuleMatch(addTag)));

        categorizationService.applySideEffectRules(userId, txnFor("NEFT AUTOPAY CREDIT CARD"));

        verify(ruleEngineService).recordMatch(markTransfer.getId());
        verify(ruleEngineService).recordMatch(addTag.getId());
    }

    @Test
    void applySideEffectRules_noMatches_recordsNothing() {
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(UUID.randomUUID()));
        when(ruleEngineService.evaluateSideEffectRules(eq(userId), anyString(), any(), any(), any())).thenReturn(List.of());

        categorizationService.applySideEffectRules(userId, txnFor("REGULAR GROCERY PURCHASE"));

        verify(ruleEngineService, never()).recordMatch(any());
    }

    @Test
    void recordRuleMatch_delegatesToRuleEngineService() {
        UUID ruleId = UUID.randomUUID();

        categorizationService.recordRuleMatch(ruleId);

        verify(ruleEngineService).recordMatch(ruleId);
    }
}
