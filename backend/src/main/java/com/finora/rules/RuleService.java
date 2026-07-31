package com.finora.rules;

import com.finora.entity.CategoryRule;
import com.finora.exception.ApiException;
import com.finora.repository.CategoryRuleRepository;
import com.finora.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD for USER-scope category_rules -- the management API behind RuleEngineService's
 * evaluation. Deliberately thin, matching this codebase's convention (RoleService,
 * BudgetService, ...): repository access, ownership checks, and DTO mapping live here;
 * RuleController stays a pass-through.
 *
 * GLOBAL rules are read-only through the USER-facing methods above (list() includes them,
 * create/update/delete reject them) -- authoring them is handled by the separate
 * listGlobal/createGlobal/updateGlobal/deleteGlobal methods below instead, gated by RULE_MANAGE
 * (see V25__rule_manage_permission.sql and AdminRuleController). This was seed-data-only through
 * v48; the fast-follow EDS §6 called for has now landed.
 */
@Service
public class RuleService {

    private final CategoryRuleRepository categoryRuleRepository;
    private final AuditService auditService;

    public RuleService(CategoryRuleRepository categoryRuleRepository, AuditService auditService) {
        this.categoryRuleRepository = categoryRuleRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<RuleDto> listForUser(UUID userId) {
        return categoryRuleRepository.findByUserIdOrScopeOrderByPriorityAsc(userId, CategoryRule.Scope.GLOBAL)
                .stream().map(this::toDto).toList();
    }

    @Transactional
    public RuleDto create(UUID userId, RuleDto.CreateRequest req) {
        CategoryRule rule = new CategoryRule();
        rule.setUserId(userId);
        rule.setScope(CategoryRule.Scope.USER);
        rule.setField(parseField(req.field()));
        rule.setOperator(parseOperator(req.operator()));
        rule.setComparisonValue(req.comparisonValue());
        rule.setActionType(parseActionType(req.actionType()));
        rule.setActionValue(req.actionValue());
        rule.setPriority(req.priority() != null ? req.priority() : 100);

        validateRule(rule);
        CategoryRule saved = categoryRuleRepository.save(rule);
        auditService.record(userId, "RULE_CREATED", "CategoryRule", saved.getId(),
                Map.of("field", saved.getField().name(), "actionType", saved.getActionType().name()));
        return toDto(saved);
    }

    @Transactional
    public RuleDto update(UUID userId, UUID ruleId, RuleDto.UpdateRequest req) {
        CategoryRule rule = getOwnedUserRule(userId, ruleId);
        if (req.field() != null) rule.setField(parseField(req.field()));
        if (req.operator() != null) rule.setOperator(parseOperator(req.operator()));
        if (req.comparisonValue() != null) rule.setComparisonValue(req.comparisonValue());
        if (req.actionType() != null) rule.setActionType(parseActionType(req.actionType()));
        if (req.actionValue() != null) rule.setActionValue(req.actionValue());
        if (req.priority() != null) rule.setPriority(req.priority());
        if (req.enabled() != null) rule.setEnabled(req.enabled());
        rule.setUpdatedAt(Instant.now());

        validateRule(rule);
        CategoryRule saved = categoryRuleRepository.save(rule);
        auditService.record(userId, "RULE_UPDATED", "CategoryRule", ruleId);
        return toDto(saved);
    }

    /**
     * Two crash/footgun risks this closes, both only reachable via update() (create()'s
     * CreateRequest already rejects them at the API boundary via @NotBlank -- this is
     * defense-in-depth for create() and the ONLY guard for update(), whose UpdateRequest fields
     * are deliberately unvalidated strings to support partial updates):
     *
     * 1. A blank comparisonValue: RuleEngineService's CONTAINS/STARTS_WITH/EQUALS operators call
     *    String methods like "".contains(x) which are trivially true for an empty needle --
     *    "actual.contains(\"\")" is always true, so a rule with a blank comparisonValue would
     *    silently match every single transaction.
     * 2. An ASSIGN_CATEGORY rule with a null/blank actionValue: CategorizationService.suggest()
     *    would return that null/blank string as the suggested category, which
     *    resolveOrCreateCategory() then tries to persist as a Category with a null/blank name --
     *    Category.name is NOT NULL, so this fails as an unhandled DataIntegrityViolationException
     *    (a raw 500) at CSV confirm / transaction create time, far from where the bad rule was
     *    actually authored.
     */
    private void validateRule(CategoryRule rule) {
        if (rule.getComparisonValue() == null || rule.getComparisonValue().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Comparison value can't be blank.");
        }
        if (rule.getActionType() == CategoryRule.ActionType.ASSIGN_CATEGORY
                && (rule.getActionValue() == null || rule.getActionValue().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "An ASSIGN_CATEGORY rule needs a non-blank actionValue (the category name).");
        }
    }

    @Transactional
    public void delete(UUID userId, UUID ruleId) {
        CategoryRule rule = getOwnedUserRule(userId, ruleId);
        categoryRuleRepository.delete(rule);
        auditService.record(userId, "RULE_DELETED", "CategoryRule", ruleId);
    }

    /** 404 if the rule doesn't exist at all, 403 if it's GLOBAL scope or owned by a different
     *  user -- same not-found-vs-forbidden distinction RoleService/StatementImportService use
     *  elsewhere (see their getOwned() methods). */
    private CategoryRule getOwnedUserRule(UUID userId, UUID ruleId) {
        CategoryRule rule = categoryRuleRepository.findById(ruleId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Rule not found"));
        if (rule.getScope() == CategoryRule.Scope.GLOBAL) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Global rules can't be modified by users.");
        }
        if (!userId.equals(rule.getUserId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This rule does not belong to you");
        }
        return rule;
    }

    private CategoryRule.Field parseField(String v) {
        try { return CategoryRule.Field.valueOf(v.toUpperCase()); }
        catch (IllegalArgumentException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown field: " + v); }
    }

    private CategoryRule.Operator parseOperator(String v) {
        try { return CategoryRule.Operator.valueOf(v.toUpperCase()); }
        catch (IllegalArgumentException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown operator: " + v); }
    }

    private CategoryRule.ActionType parseActionType(String v) {
        try { return CategoryRule.ActionType.valueOf(v.toUpperCase()); }
        catch (IllegalArgumentException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown action type: " + v); }
    }

    // --- Admin: GLOBAL rule management (RULE_MANAGE) -- see AdminRuleController ---

    @Transactional(readOnly = true)
    public List<RuleDto> listGlobal() {
        return categoryRuleRepository.findByScopeOrderByPriorityAsc(CategoryRule.Scope.GLOBAL)
                .stream().map(this::toDto).toList();
    }

    /** actingAdminId is who the audit entry is attributed to -- a GLOBAL rule has no owning user
     *  to record it against (CategoryRule.userId is null for GLOBAL scope by design, see V17's
     *  chk_category_rules_scope_user), so the acting admin is the closest thing to "whose audit
     *  trail this belongs on." */
    @Transactional
    public RuleDto createGlobal(UUID actingAdminId, RuleDto.CreateRequest req) {
        CategoryRule rule = new CategoryRule();
        rule.setUserId(null);
        rule.setScope(CategoryRule.Scope.GLOBAL);
        rule.setField(parseField(req.field()));
        rule.setOperator(parseOperator(req.operator()));
        rule.setComparisonValue(req.comparisonValue());
        rule.setActionType(parseActionType(req.actionType()));
        rule.setActionValue(req.actionValue());
        rule.setPriority(req.priority() != null ? req.priority() : 100);

        validateRule(rule);
        CategoryRule saved = categoryRuleRepository.save(rule);
        auditService.record(actingAdminId, "GLOBAL_RULE_CREATED", "CategoryRule", saved.getId(),
                Map.of("field", saved.getField().name(), "actionType", saved.getActionType().name()));
        return toDto(saved);
    }

    @Transactional
    public RuleDto updateGlobal(UUID actingAdminId, UUID ruleId, RuleDto.UpdateRequest req) {
        CategoryRule rule = getGlobalRule(ruleId);
        if (req.field() != null) rule.setField(parseField(req.field()));
        if (req.operator() != null) rule.setOperator(parseOperator(req.operator()));
        if (req.comparisonValue() != null) rule.setComparisonValue(req.comparisonValue());
        if (req.actionType() != null) rule.setActionType(parseActionType(req.actionType()));
        if (req.actionValue() != null) rule.setActionValue(req.actionValue());
        if (req.priority() != null) rule.setPriority(req.priority());
        if (req.enabled() != null) rule.setEnabled(req.enabled());
        rule.setUpdatedAt(Instant.now());

        validateRule(rule);
        CategoryRule saved = categoryRuleRepository.save(rule);
        auditService.record(actingAdminId, "GLOBAL_RULE_UPDATED", "CategoryRule", ruleId);
        return toDto(saved);
    }

    @Transactional
    public void deleteGlobal(UUID actingAdminId, UUID ruleId) {
        CategoryRule rule = getGlobalRule(ruleId);
        categoryRuleRepository.delete(rule);
        auditService.record(actingAdminId, "GLOBAL_RULE_DELETED", "CategoryRule", ruleId);
    }

    private CategoryRule getGlobalRule(UUID ruleId) {
        CategoryRule rule = categoryRuleRepository.findById(ruleId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Rule not found"));
        if (rule.getScope() != CategoryRule.Scope.GLOBAL) {
            // Guards against an admin passing a USER-scope rule's id to this endpoint by mistake
            // (or by URL-guessing) -- this path must never touch another user's own rule.
            throw new ApiException(HttpStatus.BAD_REQUEST, "This rule is not a global rule.");
        }
        return rule;
    }

    private RuleDto toDto(CategoryRule r) {
        return new RuleDto(r.getId(), r.getScope().name(), r.getField().name(), r.getOperator().name(),
                r.getComparisonValue(), r.getActionType().name(), r.getActionValue(), r.getPriority(), r.isEnabled(),
                r.getMatchCount(), r.getLastMatchedAt());
    }
}
