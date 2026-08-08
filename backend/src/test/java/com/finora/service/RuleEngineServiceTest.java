package com.finora.service;

import com.finora.entity.CategoryRule;
import com.finora.exception.ApiException;
import com.finora.repository.CategoryRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers RuleEngineService's evaluation order (USER rules before GLOBAL, priority within a
 * scope, first-match-wins) and each Operator's matching logic — see
 * docs/rule-engine-relationship-engine-eds.md §2, §4.
 */
class RuleEngineServiceTest {

    private CategoryRuleRepository categoryRuleRepository;
    private RuleEngineService ruleEngineService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        categoryRuleRepository = mock(CategoryRuleRepository.class);
        ruleEngineService = new RuleEngineService(categoryRuleRepository);
    }

    private CategoryRule rule(CategoryRule.Scope scope, CategoryRule.Field field, CategoryRule.Operator operator,
                               String comparisonValue, CategoryRule.ActionType actionType, String actionValue, int priority) {
        CategoryRule r = new CategoryRule();
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        r.setScope(scope);
        r.setField(field);
        r.setOperator(operator);
        r.setComparisonValue(comparisonValue);
        r.setActionType(actionType);
        r.setActionValue(actionValue);
        r.setPriority(priority);
        r.setEnabled(true);
        return r;
    }

    private void stub(List<CategoryRule> userRules, List<CategoryRule> globalRules) {
        when(categoryRuleRepository.findByUserIdAndEnabledTrueOrderByPriorityAsc(userId)).thenReturn(userRules);
        when(categoryRuleRepository.findByScopeAndEnabledTrueOrderByPriorityAsc(CategoryRule.Scope.GLOBAL)).thenReturn(globalRules);
    }

    @Test
    void evaluate_prefersUserRule_overGlobalRule_whenBothMatch() {
        CategoryRule userRule = rule(CategoryRule.Scope.USER, CategoryRule.Field.DESCRIPTION, CategoryRule.Operator.CONTAINS,
                "swiggy", CategoryRule.ActionType.ASSIGN_CATEGORY, "Food Delivery", 100);
        CategoryRule globalRule = rule(CategoryRule.Scope.GLOBAL, CategoryRule.Field.DESCRIPTION, CategoryRule.Operator.CONTAINS,
                "swiggy", CategoryRule.ActionType.ASSIGN_CATEGORY, "Dining", 50); // lower priority number, but GLOBAL loses regardless
        stub(List.of(userRule), List.of(globalRule));

        var match = ruleEngineService.evaluateCategoryRule(userId, "SWIGGY ORDER 123", null, "Swiggy", null);

        assertThat(match).isPresent();
        assertThat(match.get().rule().getActionValue()).isEqualTo("Food Delivery");
        assertThat(match.get().isUserScope()).isTrue();
    }

    @Test
    void evaluate_fallsThroughToGlobalRule_whenNoUserRuleMatches() {
        CategoryRule userRule = rule(CategoryRule.Scope.USER, CategoryRule.Field.DESCRIPTION, CategoryRule.Operator.CONTAINS,
                "zomato", CategoryRule.ActionType.ASSIGN_CATEGORY, "Food Delivery", 100);
        CategoryRule globalRule = rule(CategoryRule.Scope.GLOBAL, CategoryRule.Field.DESCRIPTION, CategoryRule.Operator.CONTAINS,
                "swiggy", CategoryRule.ActionType.ASSIGN_CATEGORY, "Dining", 100);
        stub(List.of(userRule), List.of(globalRule));

        var match = ruleEngineService.evaluateCategoryRule(userId, "SWIGGY ORDER 123", null, "Swiggy", null);

        assertThat(match).isPresent();
        assertThat(match.get().rule().getActionValue()).isEqualTo("Dining");
        assertThat(match.get().isUserScope()).isFalse();
    }

    @Test
    void evaluate_respectsPriorityOrdering_withinTheSameScope() {
        // Repository is stubbed as already priority-ordered (that ordering is the repository's
        // job via ...OrderByPriorityAsc — this test locks in that evaluate() takes the FIRST
        // match in whatever order it's given, not e.g. the lowest actionValue alphabetically).
        CategoryRule higherPriority = rule(CategoryRule.Scope.USER, CategoryRule.Field.DESCRIPTION, CategoryRule.Operator.CONTAINS,
                "amazon", CategoryRule.ActionType.ASSIGN_CATEGORY, "Work Expenses", 10);
        CategoryRule lowerPriority = rule(CategoryRule.Scope.USER, CategoryRule.Field.DESCRIPTION, CategoryRule.Operator.CONTAINS,
                "amazon", CategoryRule.ActionType.ASSIGN_CATEGORY, "Shopping", 200);
        stub(List.of(higherPriority, lowerPriority), List.of());

        var match = ruleEngineService.evaluateCategoryRule(userId, "AMAZON.IN ORDER", null, "Amazon", null);

        assertThat(match).isPresent();
        assertThat(match.get().rule().getActionValue()).isEqualTo("Work Expenses");
    }

    @Test
    void evaluate_returnsEmpty_whenNothingMatches() {
        stub(List.of(), List.of());

        var match = ruleEngineService.evaluateCategoryRule(userId, "SOME UNKNOWN VENDOR", null, "Unknown", null);

        assertThat(match).isEmpty();
    }

    @Test
    void evaluateCategoryRule_skipsNonCategoryActionTypes_evenAtHigherPriority() {
        // A higher-priority (lower number) MARK_TRANSFER rule that matches must NOT block a
        // lower-priority ASSIGN_CATEGORY rule from being found — the two are independent passes
        // over the same rule set (see RuleEngineService.evaluateCategoryRule's own doc comment).
        CategoryRule transferRule = rule(CategoryRule.Scope.USER, CategoryRule.Field.DESCRIPTION, CategoryRule.Operator.CONTAINS,
                "self transfer", CategoryRule.ActionType.MARK_TRANSFER, null, 10);
        CategoryRule categoryRule = rule(CategoryRule.Scope.USER, CategoryRule.Field.DESCRIPTION, CategoryRule.Operator.CONTAINS,
                "self transfer", CategoryRule.ActionType.ASSIGN_CATEGORY, "Transfer", 200);
        stub(List.of(transferRule, categoryRule), List.of());

        var match = ruleEngineService.evaluateCategoryRule(userId, "SELF TRANSFER TO SAVINGS", null, null, null);

        assertThat(match).isPresent();
        assertThat(match.get().rule().getActionType()).isEqualTo(CategoryRule.ActionType.ASSIGN_CATEGORY);
    }

    @Test
    void evaluate_fullEvaluation_returnsFirstMatchRegardlessOfActionType() {
        CategoryRule transferRule = rule(CategoryRule.Scope.USER, CategoryRule.Field.DESCRIPTION, CategoryRule.Operator.CONTAINS,
                "self transfer", CategoryRule.ActionType.MARK_TRANSFER, null, 10);
        stub(List.of(transferRule), List.of());

        var match = ruleEngineService.evaluate(userId, "SELF TRANSFER TO SAVINGS", null, null, null);

        assertThat(match).isPresent();
        assertThat(match.get().rule().getActionType()).isEqualTo(CategoryRule.ActionType.MARK_TRANSFER);
    }

    @Test
    void matches_equals_isCaseInsensitiveButRequiresFullMatch() {
        CategoryRule r = rule(CategoryRule.Scope.GLOBAL, CategoryRule.Field.ACCOUNT_TYPE, CategoryRule.Operator.EQUALS,
                "CREDIT_CARD", CategoryRule.ActionType.ASSIGN_CATEGORY, "Card Spend", 100);
        stub(List.of(), List.of(r));

        assertThat(ruleEngineService.evaluateCategoryRule(userId, "desc", null, null, "credit_card")).isPresent();
        assertThat(ruleEngineService.evaluateCategoryRule(userId, "desc", null, null, "credit_card_extra")).isEmpty();
    }

    @Test
    void matches_startsWith() {
        CategoryRule r = rule(CategoryRule.Scope.GLOBAL, CategoryRule.Field.DESCRIPTION, CategoryRule.Operator.STARTS_WITH,
                "UPI/DR/", CategoryRule.ActionType.ASSIGN_CATEGORY, "UPI Debit", 100);
        stub(List.of(), List.of(r));

        assertThat(ruleEngineService.evaluateCategoryRule(userId, "UPI/DR/900077778888/MERCHANT", null, null, null)).isPresent();
        assertThat(ruleEngineService.evaluateCategoryRule(userId, "SOMETHING UPI/DR/ IN THE MIDDLE", null, null, null)).isEmpty();
    }

    @Test
    void matches_gt_and_lt_onAmount() {
        CategoryRule bigTicket = rule(CategoryRule.Scope.GLOBAL, CategoryRule.Field.AMOUNT, CategoryRule.Operator.GT,
                "50000", CategoryRule.ActionType.ASSIGN_CATEGORY, "Big Ticket", 100);
        stub(List.of(), List.of(bigTicket));

        assertThat(ruleEngineService.evaluateCategoryRule(userId, "desc", BigDecimal.valueOf(75000), null, null)).isPresent();
        assertThat(ruleEngineService.evaluateCategoryRule(userId, "desc", BigDecimal.valueOf(1000), null, null)).isEmpty();
        assertThat(ruleEngineService.evaluateCategoryRule(userId, "desc", null, null, null)).isEmpty(); // no amount context at all
    }

    @Test
    void matches_between_onAmount() {
        CategoryRule r = rule(CategoryRule.Scope.GLOBAL, CategoryRule.Field.AMOUNT, CategoryRule.Operator.BETWEEN,
                "1000,5000", CategoryRule.ActionType.ASSIGN_CATEGORY, "Mid Range", 100);
        stub(List.of(), List.of(r));

        assertThat(ruleEngineService.evaluateCategoryRule(userId, "desc", BigDecimal.valueOf(2500), null, null)).isPresent();
        assertThat(ruleEngineService.evaluateCategoryRule(userId, "desc", BigDecimal.valueOf(1000), null, null)).isPresent(); // inclusive
        assertThat(ruleEngineService.evaluateCategoryRule(userId, "desc", BigDecimal.valueOf(5000), null, null)).isPresent(); // inclusive
        assertThat(ruleEngineService.evaluateCategoryRule(userId, "desc", BigDecimal.valueOf(9000), null, null)).isEmpty();
    }

    // Bug fix: AMOUNT+EQUALS used to compare amount.toPlainString() (e.g. "2500.00", DB-column
    // scale) against the raw comparisonValue string (e.g. "2500" as typed) -- a scale-2 stored
    // amount essentially never string-equals a plainly-typed integer, so this rule shape silently
    // never matched anything real.
    @Test
    void matches_equals_onAmount_comparesNumericValue_notFormattedString() {
        CategoryRule r = rule(CategoryRule.Scope.GLOBAL, CategoryRule.Field.AMOUNT, CategoryRule.Operator.EQUALS,
                "2500", CategoryRule.ActionType.ASSIGN_CATEGORY, "Exact Amount", 100);
        stub(List.of(), List.of(r));

        assertThat(ruleEngineService.evaluateCategoryRule(userId, "desc", BigDecimal.valueOf(2500), null, null)).isPresent();
        assertThat(ruleEngineService.evaluateCategoryRule(userId, "desc", new BigDecimal("2500.00"), null, null)).isPresent();
        assertThat(ruleEngineService.evaluateCategoryRule(userId, "desc", BigDecimal.valueOf(2501), null, null)).isEmpty();
    }

    @Test
    void matches_equals_onAmount_failsClosed_whenComparisonValueIsMalformed() {
        CategoryRule r = rule(CategoryRule.Scope.GLOBAL, CategoryRule.Field.AMOUNT, CategoryRule.Operator.EQUALS,
                "not-a-number", CategoryRule.ActionType.ASSIGN_CATEGORY, "Broken Rule", 100);
        stub(List.of(), List.of(r));

        assertThat(ruleEngineService.evaluateCategoryRule(userId, "desc", BigDecimal.valueOf(2500), null, null)).isEmpty();
    }

    @Test
    void disabledRules_areNeverConsidered() {
        // Repository methods used by RuleEngineService are the ...EnabledTrue... variants, so a
        // disabled rule simply never appears in what's stubbed here -- this test documents that
        // contract rather than re-testing Spring Data's query derivation.
        stub(List.of(), List.of());

        assertThat(ruleEngineService.evaluateCategoryRule(userId, "SWIGGY ORDER", null, null, null)).isEmpty();
    }

    // --- evaluateSideEffectRules (MARK_TRANSFER/MARK_INVESTMENT/MARK_SUBSCRIPTION/ADD_TAG) ---

    @Test
    void evaluateSideEffectRules_returnsEveryMatch_notJustTheFirst() {
        CategoryRule markSubscription = rule(CategoryRule.Scope.GLOBAL, CategoryRule.Field.DESCRIPTION,
                CategoryRule.Operator.CONTAINS, "netflix", CategoryRule.ActionType.MARK_SUBSCRIPTION, null, 100);
        CategoryRule addTag = rule(CategoryRule.Scope.GLOBAL, CategoryRule.Field.DESCRIPTION,
                CategoryRule.Operator.CONTAINS, "netflix", CategoryRule.ActionType.ADD_TAG, "streaming", 200);
        stub(List.of(), List.of(markSubscription, addTag));

        var matches = ruleEngineService.evaluateSideEffectRules(userId, "NETFLIX.COM SUBSCRIPTION", null, null, null);

        assertThat(matches).hasSize(2);
        assertThat(matches).extracting(m -> m.rule().getActionType())
                .containsExactlyInAnyOrder(CategoryRule.ActionType.MARK_SUBSCRIPTION, CategoryRule.ActionType.ADD_TAG);
    }

    @Test
    void evaluateSideEffectRules_excludesAssignCategoryRules() {
        CategoryRule assignCategory = rule(CategoryRule.Scope.GLOBAL, CategoryRule.Field.DESCRIPTION,
                CategoryRule.Operator.CONTAINS, "swiggy", CategoryRule.ActionType.ASSIGN_CATEGORY, "Food", 100);
        CategoryRule markTransfer = rule(CategoryRule.Scope.GLOBAL, CategoryRule.Field.DESCRIPTION,
                CategoryRule.Operator.CONTAINS, "swiggy", CategoryRule.ActionType.MARK_TRANSFER, null, 200);
        stub(List.of(), List.of(assignCategory, markTransfer));

        var matches = ruleEngineService.evaluateSideEffectRules(userId, "SWIGGY ORDER", null, null, null);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).rule().getActionType()).isEqualTo(CategoryRule.ActionType.MARK_TRANSFER);
    }

    @Test
    void evaluateSideEffectRules_userRulesConsideredAlongsideGlobalRules() {
        CategoryRule userMarkTransfer = rule(CategoryRule.Scope.USER, CategoryRule.Field.DESCRIPTION,
                CategoryRule.Operator.CONTAINS, "credit card autopay", CategoryRule.ActionType.MARK_TRANSFER, null, 100);
        CategoryRule globalAddTag = rule(CategoryRule.Scope.GLOBAL, CategoryRule.Field.DESCRIPTION,
                CategoryRule.Operator.CONTAINS, "credit card autopay", CategoryRule.ActionType.ADD_TAG, "autopay", 100);
        stub(List.of(userMarkTransfer), List.of(globalAddTag));

        var matches = ruleEngineService.evaluateSideEffectRules(userId, "CREDIT CARD AUTOPAY", null, null, null);

        // Unlike evaluate()/evaluateCategoryRule() (first-match-wins), side effects aren't
        // mutually exclusive -- both the user's own MARK_TRANSFER rule and the global ADD_TAG
        // rule should apply to the same transaction.
        assertThat(matches).hasSize(2);
    }

    @Test
    void evaluateSideEffectRules_noMatches_returnsEmptyListNotNull() {
        stub(List.of(), List.of());

        assertThat(ruleEngineService.evaluateSideEffectRules(userId, "anything", null, null, null)).isEmpty();
    }

    // --- recordMatch (Financial Intelligence Workspace, Rule Management execution tracking) ---

    @Test
    void recordMatch_delegatesToTheRepositorysBulkUpdate() {
        UUID ruleId = UUID.randomUUID();

        ruleEngineService.recordMatch(ruleId);

        verify(categoryRuleRepository).recordMatch(eq(ruleId), any());
    }

    @Test
    void recordMatch_nullRuleId_isANoOp_ratherThanHittingTheRepository() {
        // Every evaluate*() caller may pass a null ruleId (no ASSIGN_CATEGORY rule matched) --
        // see this method's own doc comment for why callers pass the raw ruleId through instead
        // of checking null themselves.
        ruleEngineService.recordMatch(null);

        verify(categoryRuleRepository, never()).recordMatch(any(), any());
    }

    // --- testMatch (Admin Rule Engine module -- dry-run against sample fields) ---

    @Test
    void testMatch_neverTouchesTheRepository_sinceItEvaluatesATransientRule() {
        boolean result = ruleEngineService.testMatch("MERCHANT", "CONTAINS", "Swiggy",
                null, null, "Swiggy Bangalore", null);

        assertThat(result).isTrue();
        verify(categoryRuleRepository, never()).findByUserIdAndEnabledTrueOrderByPriorityAsc(any());
        verify(categoryRuleRepository, never()).findByScopeAndEnabledTrueOrderByPriorityAsc(any());
    }

    @Test
    void testMatch_returnsFalse_whenTheSampleFieldsDontMatch() {
        boolean result = ruleEngineService.testMatch("MERCHANT", "CONTAINS", "Swiggy",
                null, null, "Zomato Bangalore", null);

        assertThat(result).isFalse();
    }

    @Test
    void testMatch_evaluatesAmountOperators_justLikeARealRuleWould() {
        assertThat(ruleEngineService.testMatch("AMOUNT", "GT", "5000", null, new BigDecimal("6000"), null, null))
                .isTrue();
        assertThat(ruleEngineService.testMatch("AMOUNT", "LT", "5000", null, new BigDecimal("6000"), null, null))
                .isFalse();
    }

    @Test
    void testMatch_rejectsAnUnrecognizedField() {
        assertThatThrownBy(() -> ruleEngineService.testMatch("NOT_A_REAL_FIELD", "CONTAINS", "x", null, null, null, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void testMatch_rejectsAnUnrecognizedOperator() {
        assertThatThrownBy(() -> ruleEngineService.testMatch("DESCRIPTION", "NOT_A_REAL_OPERATOR", "x", null, null, null, null))
                .isInstanceOf(ApiException.class);
    }
}
