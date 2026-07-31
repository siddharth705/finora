package com.finora.rules;

import com.finora.entity.CategoryRule;
import com.finora.exception.ApiException;
import com.finora.repository.CategoryRuleRepository;
import com.finora.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers RuleService's CRUD + ownership rules, and specifically the two validation gaps found
 * during a follow-up bug audit (docs/rule-engine-relationship-engine-eds.md build):
 *
 * 1. A blank comparisonValue would make RuleEngineService's CONTAINS/STARTS_WITH/EQUALS match
 *    every transaction unconditionally ("x".contains("") is always true) -- only reachable via
 *    update(), since CreateRequest.comparisonValue is @NotBlank at the API boundary but
 *    UpdateRequest's fields are deliberately unvalidated (partial update).
 * 2. An ASSIGN_CATEGORY rule with a null/blank actionValue would crash Category creation
 *    downstream (Category.name is NOT NULL) with a raw 500, far from where the bad rule was
 *    authored -- reachable via create() (defense-in-depth; CreateRequest doesn't require
 *    actionValue since non-category actions don't need one) and via update() (changing
 *    actionType to ASSIGN_CATEGORY without also supplying actionValue).
 */
class RuleServiceTest {

    private CategoryRuleRepository categoryRuleRepository;
    private RuleService ruleService;
    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        categoryRuleRepository = mock(CategoryRuleRepository.class);
        ruleService = new RuleService(categoryRuleRepository, mock(AuditService.class));
        when(categoryRuleRepository.save(any(CategoryRule.class))).thenAnswer(inv -> {
            CategoryRule r = inv.getArgument(0);
            if (r.getId() == null) ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
            return r;
        });
    }

    private CategoryRule existingUserRule(UUID id, UUID owner, CategoryRule.ActionType actionType, String actionValue) {
        CategoryRule r = new CategoryRule();
        ReflectionTestUtils.setField(r, "id", id);
        r.setUserId(owner);
        r.setScope(CategoryRule.Scope.USER);
        r.setField(CategoryRule.Field.DESCRIPTION);
        r.setOperator(CategoryRule.Operator.CONTAINS);
        r.setComparisonValue("swiggy");
        r.setActionType(actionType);
        r.setActionValue(actionValue);
        r.setPriority(100);
        r.setEnabled(true);
        return r;
    }

    private CategoryRule globalRule(UUID id) {
        CategoryRule r = new CategoryRule();
        ReflectionTestUtils.setField(r, "id", id);
        r.setScope(CategoryRule.Scope.GLOBAL);
        r.setField(CategoryRule.Field.DESCRIPTION);
        r.setOperator(CategoryRule.Operator.CONTAINS);
        r.setComparisonValue("amazon");
        r.setActionType(CategoryRule.ActionType.ASSIGN_CATEGORY);
        r.setActionValue("Shopping");
        return r;
    }

    // --- create() ---

    @Test
    void create_persistsAUserScopeRule() {
        var req = new RuleDto.CreateRequest("DESCRIPTION", "CONTAINS", "swiggy", "ASSIGN_CATEGORY", "Dining", 50);

        RuleDto result = ruleService.create(userId, req);

        assertThat(result.scope()).isEqualTo("USER");
        assertThat(result.field()).isEqualTo("DESCRIPTION");
        assertThat(result.actionValue()).isEqualTo("Dining");
        assertThat(result.priority()).isEqualTo(50);
        verify(categoryRuleRepository).save(argThat(r ->
                r.getScope() == CategoryRule.Scope.USER && userId.equals(r.getUserId())));
    }

    @Test
    void create_defaultsPriorityTo100_whenNotSupplied() {
        var req = new RuleDto.CreateRequest("DESCRIPTION", "CONTAINS", "swiggy", "ASSIGN_CATEGORY", "Dining", null);

        RuleDto result = ruleService.create(userId, req);

        assertThat(result.priority()).isEqualTo(100);
    }

    @Test
    void create_rejectsBlankComparisonValue() {
        var req = new RuleDto.CreateRequest("DESCRIPTION", "CONTAINS", "   ", "ASSIGN_CATEGORY", "Dining", null);

        assertThatThrownBy(() -> ruleService.create(userId, req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Comparison value");
        verify(categoryRuleRepository, never()).save(any());
    }

    @Test
    void create_rejectsAssignCategoryRule_withNullActionValue() {
        var req = new RuleDto.CreateRequest("DESCRIPTION", "CONTAINS", "swiggy", "ASSIGN_CATEGORY", null, null);

        assertThatThrownBy(() -> ruleService.create(userId, req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ASSIGN_CATEGORY");
        verify(categoryRuleRepository, never()).save(any());
    }

    @Test
    void create_rejectsAssignCategoryRule_withBlankActionValue() {
        var req = new RuleDto.CreateRequest("DESCRIPTION", "CONTAINS", "swiggy", "ASSIGN_CATEGORY", "   ", null);

        assertThatThrownBy(() -> ruleService.create(userId, req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ASSIGN_CATEGORY");
    }

    @Test
    void create_allowsNonCategoryActionType_withNoActionValue() {
        // MARK_TRANSFER (and the other non-category action types) genuinely don't need an
        // actionValue -- only ASSIGN_CATEGORY requires one.
        var req = new RuleDto.CreateRequest("DESCRIPTION", "CONTAINS", "self transfer", "MARK_TRANSFER", null, null);

        RuleDto result = ruleService.create(userId, req);

        assertThat(result.actionType()).isEqualTo("MARK_TRANSFER");
        assertThat(result.actionValue()).isNull();
    }

    @Test
    void create_rejectsUnknownField() {
        var req = new RuleDto.CreateRequest("NOT_A_REAL_FIELD", "CONTAINS", "swiggy", "ASSIGN_CATEGORY", "Dining", null);

        assertThatThrownBy(() -> ruleService.create(userId, req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unknown field");
    }

    // --- update() ---

    @Test
    void update_appliesOnlySuppliedFields() {
        UUID ruleId = UUID.randomUUID();
        CategoryRule existing = existingUserRule(ruleId, userId, CategoryRule.ActionType.ASSIGN_CATEGORY, "Dining");
        when(categoryRuleRepository.findById(ruleId)).thenReturn(Optional.of(existing));

        var req = new RuleDto.UpdateRequest(null, null, null, null, null, 5, null);
        RuleDto result = ruleService.update(userId, ruleId, req);

        assertThat(result.priority()).isEqualTo(5);
        assertThat(result.comparisonValue()).isEqualTo("swiggy"); // untouched
        assertThat(result.actionValue()).isEqualTo("Dining"); // untouched
    }

    @Test
    void update_rejectsBlankComparisonValue() {
        // Reachable specifically because UpdateRequest.comparisonValue has no @NotBlank --
        // an empty string is not null, so it passes the `!= null` gate in update() and would
        // otherwise reach the repository as a rule matching every transaction.
        UUID ruleId = UUID.randomUUID();
        CategoryRule existing = existingUserRule(ruleId, userId, CategoryRule.ActionType.ASSIGN_CATEGORY, "Dining");
        when(categoryRuleRepository.findById(ruleId)).thenReturn(Optional.of(existing));

        var req = new RuleDto.UpdateRequest(null, null, "", null, null, null, null);

        assertThatThrownBy(() -> ruleService.update(userId, ruleId, req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Comparison value");
    }

    @Test
    void update_rejectsChangingActionTypeToAssignCategory_withoutSupplyingActionValue() {
        // The bad state isn't reachable in one step from a fresh rule (create() blocks it), but
        // IS reachable by first creating a MARK_TRANSFER rule (actionValue legitimately null),
        // then updating only actionType -- actionValue stays null from the prior state, and
        // nothing about that individual PUT request looks invalid on its own.
        UUID ruleId = UUID.randomUUID();
        CategoryRule existing = existingUserRule(ruleId, userId, CategoryRule.ActionType.MARK_TRANSFER, null);
        when(categoryRuleRepository.findById(ruleId)).thenReturn(Optional.of(existing));

        var req = new RuleDto.UpdateRequest(null, null, null, "ASSIGN_CATEGORY", null, null, null);

        assertThatThrownBy(() -> ruleService.update(userId, ruleId, req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ASSIGN_CATEGORY");
    }

    @Test
    void update_succeeds_whenChangingActionTypeToAssignCategory_andSupplyingActionValueInTheSameRequest() {
        UUID ruleId = UUID.randomUUID();
        CategoryRule existing = existingUserRule(ruleId, userId, CategoryRule.ActionType.MARK_TRANSFER, null);
        when(categoryRuleRepository.findById(ruleId)).thenReturn(Optional.of(existing));

        var req = new RuleDto.UpdateRequest(null, null, null, "ASSIGN_CATEGORY", "Transfer", null, null);
        RuleDto result = ruleService.update(userId, ruleId, req);

        assertThat(result.actionType()).isEqualTo("ASSIGN_CATEGORY");
        assertThat(result.actionValue()).isEqualTo("Transfer");
    }

    @Test
    void update_throwsNotFound_whenRuleDoesNotExist() {
        UUID ruleId = UUID.randomUUID();
        when(categoryRuleRepository.findById(ruleId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ruleService.update(userId, ruleId, new RuleDto.UpdateRequest(null, null, null, null, null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void update_throwsForbidden_whenRuleIsGlobalScope() {
        UUID ruleId = UUID.randomUUID();
        when(categoryRuleRepository.findById(ruleId)).thenReturn(Optional.of(globalRule(ruleId)));

        assertThatThrownBy(() -> ruleService.update(userId, ruleId, new RuleDto.UpdateRequest(null, null, null, null, null, 1, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Global rules");
    }

    @Test
    void update_throwsForbidden_whenRuleBelongsToAnotherUser() {
        UUID ruleId = UUID.randomUUID();
        CategoryRule existing = existingUserRule(ruleId, otherUserId, CategoryRule.ActionType.ASSIGN_CATEGORY, "Dining");
        when(categoryRuleRepository.findById(ruleId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> ruleService.update(userId, ruleId, new RuleDto.UpdateRequest(null, null, null, null, null, 1, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("does not belong to you");
    }

    // --- delete() ---

    @Test
    void delete_removesAnOwnedUserRule() {
        UUID ruleId = UUID.randomUUID();
        CategoryRule existing = existingUserRule(ruleId, userId, CategoryRule.ActionType.ASSIGN_CATEGORY, "Dining");
        when(categoryRuleRepository.findById(ruleId)).thenReturn(Optional.of(existing));

        ruleService.delete(userId, ruleId);

        verify(categoryRuleRepository).delete(existing);
    }

    @Test
    void delete_throwsForbidden_forGlobalScopeRule() {
        UUID ruleId = UUID.randomUUID();
        when(categoryRuleRepository.findById(ruleId)).thenReturn(Optional.of(globalRule(ruleId)));

        assertThatThrownBy(() -> ruleService.delete(userId, ruleId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Global rules");
        verify(categoryRuleRepository, never()).delete(any(CategoryRule.class));
    }

    // --- listForUser() ---

    @Test
    void listForUser_mapsRepositoryResultsToDtos() {
        CategoryRule userRule = existingUserRule(UUID.randomUUID(), userId, CategoryRule.ActionType.ASSIGN_CATEGORY, "Dining");
        CategoryRule global = globalRule(UUID.randomUUID());
        when(categoryRuleRepository.findByUserIdOrScopeOrderByPriorityAsc(userId, CategoryRule.Scope.GLOBAL))
                .thenReturn(List.of(userRule, global));

        List<RuleDto> result = ruleService.listForUser(userId);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(RuleDto::scope).containsExactlyInAnyOrder("USER", "GLOBAL");
    }

    @Test
    void listForUser_surfacesMatchCountAndLastMatchedAt() {
        // Financial Intelligence Workspace, Rule Management module -- toDto() must read these off
        // the entity rather than defaulting to 0/null regardless of what's actually stored.
        CategoryRule userRule = existingUserRule(UUID.randomUUID(), userId, CategoryRule.ActionType.ASSIGN_CATEGORY, "Dining");
        userRule.setMatchCount(7);
        var lastMatched = java.time.Instant.parse("2026-07-20T10:00:00Z");
        userRule.setLastMatchedAt(lastMatched);
        when(categoryRuleRepository.findByUserIdOrScopeOrderByPriorityAsc(userId, CategoryRule.Scope.GLOBAL))
                .thenReturn(List.of(userRule));

        RuleDto result = ruleService.listForUser(userId).get(0);

        assertThat(result.matchCount()).isEqualTo(7);
        assertThat(result.lastMatchedAt()).isEqualTo(lastMatched);
    }

    @Test
    void listForUser_neverMatched_matchCountIsZero_lastMatchedAtIsNull() {
        CategoryRule freshRule = existingUserRule(UUID.randomUUID(), userId, CategoryRule.ActionType.ASSIGN_CATEGORY, "Dining");
        when(categoryRuleRepository.findByUserIdOrScopeOrderByPriorityAsc(userId, CategoryRule.Scope.GLOBAL))
                .thenReturn(List.of(freshRule));

        RuleDto result = ruleService.listForUser(userId).get(0);

        assertThat(result.matchCount()).isZero();
        assertThat(result.lastMatchedAt()).isNull();
    }
}
