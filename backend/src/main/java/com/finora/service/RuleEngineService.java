package com.finora.service;

import com.finora.entity.CategoryRule;
import com.finora.exception.ApiException;
import com.finora.repository.CategoryRuleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Evaluates a transaction's fields against a user's category_rules (docs/
 * rule-engine-relationship-engine-eds.md §2, §4). USER rules are evaluated first, in full,
 * before any GLOBAL rule is considered -- a user can always override a system default for their
 * own data, which is exactly the precedence the EDS's pipeline diagram specifies. Within a
 * scope, lower `priority` runs first; first match wins.
 *
 * Stateless like ConfidenceEngine -- given user id + transaction fields, returns a match or
 * none. Doesn't persist, doesn't decide what to do with the match (that's CategorizationService's
 * job for ASSIGN_CATEGORY, and a fast-follow for the other action types -- see CategoryRule.ActionType).
 */
@Service
public class RuleEngineService {

    private final CategoryRuleRepository categoryRuleRepository;

    public RuleEngineService(CategoryRuleRepository categoryRuleRepository) {
        this.categoryRuleRepository = categoryRuleRepository;
    }

    public record RuleMatch(CategoryRule rule) {
        public boolean isUserScope() { return rule.getScope() == CategoryRule.Scope.USER; }
    }

    /**
     * Full evaluation across all action types -- returns the first rule (USER rules first, then
     * GLOBAL, priority order within each) whose condition matches these fields. amount/merchant/
     * accountType may each be null when the caller doesn't have that context yet; a rule whose
     * field a caller can't supply simply never matches (fails safe to "no match" rather than
     * throwing).
     */
    public Optional<RuleMatch> evaluate(UUID userId, String description, BigDecimal amount,
                                          String merchantName, String accountType) {
        for (CategoryRule rule : categoryRuleRepository.findByUserIdAndEnabledTrueOrderByPriorityAsc(userId)) {
            if (matches(rule, description, amount, merchantName, accountType)) return Optional.of(new RuleMatch(rule));
        }
        for (CategoryRule rule : categoryRuleRepository.findByScopeAndEnabledTrueOrderByPriorityAsc(CategoryRule.Scope.GLOBAL)) {
            if (matches(rule, description, amount, merchantName, accountType)) return Optional.of(new RuleMatch(rule));
        }
        return Optional.empty();
    }

    /** Same precedence as {@link #evaluate}, filtered to rules that assign a category -- what
     *  CategorizationService.suggest() needs. A higher-priority MARK_TRANSFER/ADD_TAG rule that
     *  matches first under full evaluate() does NOT block a lower-priority ASSIGN_CATEGORY rule
     *  from being found here; the two evaluations are independent passes over the same rule set,
     *  since only one of them decides this transaction's category. */
    public Optional<RuleMatch> evaluateCategoryRule(UUID userId, String description, BigDecimal amount,
                                                       String merchantName, String accountType) {
        for (CategoryRule rule : categoryRuleRepository.findByUserIdAndEnabledTrueOrderByPriorityAsc(userId)) {
            if (rule.getActionType() == CategoryRule.ActionType.ASSIGN_CATEGORY
                    && matches(rule, description, amount, merchantName, accountType)) return Optional.of(new RuleMatch(rule));
        }
        for (CategoryRule rule : categoryRuleRepository.findByScopeAndEnabledTrueOrderByPriorityAsc(CategoryRule.Scope.GLOBAL)) {
            if (rule.getActionType() == CategoryRule.ActionType.ASSIGN_CATEGORY
                    && matches(rule, description, amount, merchantName, accountType)) return Optional.of(new RuleMatch(rule));
        }
        return Optional.empty();
    }

    /** Same USER-then-GLOBAL precedence as {@link #evaluate}, but returns EVERY matching rule
     *  whose action isn't ASSIGN_CATEGORY (that one's handled by evaluateCategoryRule() above,
     *  since exactly one category can apply to a transaction). MARK_TRANSFER/MARK_INVESTMENT/
     *  MARK_SUBSCRIPTION/ADD_TAG are independent side effects a transaction can match more than
     *  one of at once (e.g. a rule tagging "streaming" and a separate rule marking the same
     *  description as a subscription), so this returns every match rather than stopping at the
     *  first -- see CategorizationService.applySideEffectRules for how each is actually applied. */
    public List<RuleMatch> evaluateSideEffectRules(UUID userId, String description, BigDecimal amount,
                                                     String merchantName, String accountType) {
        List<RuleMatch> matches = new ArrayList<>();
        for (CategoryRule rule : categoryRuleRepository.findByUserIdAndEnabledTrueOrderByPriorityAsc(userId)) {
            if (rule.getActionType() != CategoryRule.ActionType.ASSIGN_CATEGORY
                    && matches(rule, description, amount, merchantName, accountType)) {
                matches.add(new RuleMatch(rule));
            }
        }
        for (CategoryRule rule : categoryRuleRepository.findByScopeAndEnabledTrueOrderByPriorityAsc(CategoryRule.Scope.GLOBAL)) {
            if (rule.getActionType() != CategoryRule.ActionType.ASSIGN_CATEGORY
                    && matches(rule, description, amount, merchantName, accountType)) {
                matches.add(new RuleMatch(rule));
            }
        }
        return matches;
    }

    /**
     * Financial Intelligence Workspace, Rule Management module -- records that `ruleId` actually
     * decided something on a PERSISTED transaction. Deliberately NOT called from evaluate()/
     * evaluateCategoryRule()/evaluateSideEffectRules() above: CategorizationService.suggest()
     * (which calls evaluateCategoryRule()) also runs at CSV staging/preview time
     * (CsvImportService.parseRow), before the user has reviewed or confirmed anything -- counting
     * there would inflate a rule's match count every time someone re-uploads a file to preview
     * it, including uploads that are never confirmed. Callers instead invoke this explicitly at
     * the actual write points: CategorizationService.applySideEffectRules() (always called at
     * confirm()/create() time, never at staging) for the non-ASSIGN_CATEGORY action types, and
     * TransactionService.create()/CsvImportService.confirm() directly for ASSIGN_CATEGORY, using
     * the ruleId already resolved by suggest() (ImportService carries it through staging as
     * StagedRow/ConfirmedRow.ruleId rather than re-evaluating at confirm time).
     */
    @Transactional
    public void recordMatch(UUID ruleId) {
        if (ruleId == null) return;
        categoryRuleRepository.recordMatch(ruleId, Instant.now());
    }

    /**
     * Admin Rule Engine module -- "would this rule match?" against sample fields, without
     * creating or persisting anything (AdminRuleController's POST /admin/rules/test). Builds a
     * transient (never-saved) CategoryRule from the given field/operator/comparisonValue and
     * reuses the exact same matches() logic every real evaluation goes through below -- there's
     * only one evaluation code path, not a parallel test-only one, so a "yes, this matches"
     * answer here is never wrong once the rule is actually saved. field/operator parsing mirrors
     * RuleService's own parseField()/parseOperator() (same error messages) since a rule being
     * tested hasn't necessarily been saved as a real CategoryRule yet.
     */
    public boolean testMatch(String field, String operator, String comparisonValue,
                              String description, BigDecimal amount, String merchantName, String accountType) {
        CategoryRule probe = new CategoryRule();
        probe.setField(parseField(field));
        probe.setOperator(parseOperator(operator));
        probe.setComparisonValue(comparisonValue);
        return matches(probe, description, amount, merchantName, accountType);
    }

    private CategoryRule.Field parseField(String v) {
        try { return CategoryRule.Field.valueOf(v.toUpperCase()); }
        catch (IllegalArgumentException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown field: " + v); }
    }

    private CategoryRule.Operator parseOperator(String v) {
        try { return CategoryRule.Operator.valueOf(v.toUpperCase()); }
        catch (IllegalArgumentException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown operator: " + v); }
    }

    private boolean matches(CategoryRule rule, String description, BigDecimal amount, String merchantName, String accountType) {
        String actual = switch (rule.getField()) {
            case DESCRIPTION -> description;
            case MERCHANT -> merchantName;
            case ACCOUNT_TYPE -> accountType;
            case AMOUNT -> amount != null ? amount.toPlainString() : null;
        };
        if (actual == null) return false;

        return switch (rule.getOperator()) {
            case CONTAINS -> actual.toLowerCase().contains(rule.getComparisonValue().toLowerCase());
            case EQUALS -> actual.equalsIgnoreCase(rule.getComparisonValue());
            case STARTS_WITH -> actual.toLowerCase().startsWith(rule.getComparisonValue().toLowerCase());
            case GT -> compareAmount(amount, rule.getComparisonValue()) > 0;
            case LT -> compareAmount(amount, rule.getComparisonValue()) < 0;
            case BETWEEN -> matchesBetween(amount, rule.getComparisonValue());
        };
    }

    private int compareAmount(BigDecimal amount, String comparisonValue) {
        if (amount == null) return 0; // GT/LT against a null amount can't be satisfied -- see the null actual == null return false above, this path is unreachable for AMOUNT field but kept defensive for other fields misconfigured with a numeric operator
        try {
            return amount.compareTo(new BigDecimal(comparisonValue.trim()));
        } catch (NumberFormatException e) {
            return 0; // malformed rule data -- fail closed (no match) rather than throw mid-import
        }
    }

    /** comparisonValue for BETWEEN is "low,high" -- e.g. "1000,5000". */
    private boolean matchesBetween(BigDecimal amount, String comparisonValue) {
        if (amount == null) return false;
        String[] parts = comparisonValue.split(",");
        if (parts.length != 2) return false;
        try {
            BigDecimal low = new BigDecimal(parts[0].trim());
            BigDecimal high = new BigDecimal(parts[1].trim());
            return amount.compareTo(low) >= 0 && amount.compareTo(high) <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
