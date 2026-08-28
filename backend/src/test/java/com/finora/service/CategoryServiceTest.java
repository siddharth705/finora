package com.finora.service;

import com.finora.entity.Category;
import com.finora.exception.ApiException;
import com.finora.repository.CategoryRepository;
import com.finora.repository.CategoryRuleRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.BudgetRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CategoryServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final CategoryRepository categoryRepository = mock(CategoryRepository.class);
    private final CategoryRuleRepository categoryRuleRepository = mock(CategoryRuleRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final BudgetRepository budgetRepository = mock(BudgetRepository.class);
    private final MerchantLearningService merchantLearningService = mock(MerchantLearningService.class);
    private final AuditService auditService = mock(AuditService.class);

    private CategoryService service() {
        return new CategoryService(categoryRepository, categoryRuleRepository,
                transactionRepository, budgetRepository, merchantLearningService, auditService);
    }

    @Test
    void createsACategoryWithDefaultIconAndColorWhenOmitted() {
        when(categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, "SIP"))
                .thenReturn(List.of());
        when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Category created = service().create(userId, "SIP", null, null);

        assertThat(created.getName()).isEqualTo("SIP");
        assertThat(created.isSystem()).isFalse();
        assertThat(created.getIcon()).isEqualTo("tag");
        assertThat(created.getColor()).isEqualTo("gray");
    }

    @Test
    void rejectsACaseInsensitiveDuplicateForTheSameUser() {
        Category existing = new Category();
        existing.setUserId(userId);
        existing.setName("SIP");
        when(categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, "sip"))
                .thenReturn(List.of(existing));

        assertThatThrownBy(() -> service().create(userId, "sip", null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already have a category named");
    }

    @Test
    void rejectsAnIconTokenOutsideTheCuratedAllowList() {
        when(categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, "SIP"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service().create(userId, "SIP", "not-a-real-icon", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("icon");
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> service().create(userId, "  ", null, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void renameCascadesToMatchingPersonalRulesButLeavesGlobalRulesAlone() {
        UUID categoryId = UUID.randomUUID();
        Category existing = new Category();
        existing.setUserId(userId);
        existing.setName("Mutual Fund SIP");
        existing.setSystem(false);
        org.springframework.test.util.ReflectionTestUtils.setField(existing, "id", categoryId);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(existing));
        when(categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, "SIP"))
                .thenReturn(List.of());
        when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        com.finora.entity.CategoryRule rule = new com.finora.entity.CategoryRule();
        rule.setUserId(userId);
        rule.setActionType(com.finora.entity.CategoryRule.ActionType.ASSIGN_CATEGORY);
        rule.setActionValue("Mutual Fund SIP");
        when(categoryRuleRepository.findByUserIdAndActionTypeInAndActionValueIgnoreCase(
                eq(userId), any(), eq("Mutual Fund SIP"))).thenReturn(List.of(rule));

        Category renamed = service().rename(userId, categoryId, "SIP", null, null);

        assertThat(renamed.getName()).isEqualTo("SIP");
        assertThat(rule.getActionValue()).isEqualTo("SIP");
        verify(categoryRuleRepository).save(rule);
    }

    @Test
    void renameRejectsAnAttemptOnASystemCategory() {
        UUID categoryId = UUID.randomUUID();
        Category system = new Category();
        system.setUserId(userId);
        system.setName("Groceries");
        system.setSystem(true);
        org.springframework.test.util.ReflectionTestUtils.setField(system, "id", categoryId);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(system));

        assertThatThrownBy(() -> service().rename(userId, categoryId, "Food", null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("system categor");
    }

    @Test
    void usageReportsTransactionBudgetRuleAndLearningCounts() {
        UUID categoryId = UUID.randomUUID();
        Category existing = new Category();
        existing.setUserId(userId);
        existing.setSystem(false);
        org.springframework.test.util.ReflectionTestUtils.setField(existing, "id", categoryId);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(existing));
        when(transactionRepository.countByUserIdAndCategoryId(userId, categoryId)).thenReturn(12L);
        when(budgetRepository.findByUserIdAndCategoryId(userId, categoryId))
                .thenReturn(Optional.of(new com.finora.entity.Budget()));
        when(categoryRuleRepository.findByUserIdAndActionTypeInAndActionValueIgnoreCase(
                eq(userId), any(), any())).thenReturn(List.of(new com.finora.entity.CategoryRule()));
        when(merchantLearningService.learningRowCount(userId, categoryId)).thenReturn(7L);

        var usage = service().usage(userId, categoryId);

        assertThat(usage.transactionCount()).isEqualTo(12);
        assertThat(usage.hasBudget()).isTrue();
        assertThat(usage.ruleCount()).isEqualTo(1);
        assertThat(usage.learningRowCount()).isEqualTo(7);
    }

    @Test
    void deleteReassignsTransactionsRewritesRulesRemovesBudgetThenDeletesTheCategory() {
        UUID categoryId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Category toDelete = new Category();
        toDelete.setUserId(userId);
        toDelete.setName("Mutual Fund SIP");
        toDelete.setSystem(false);
        org.springframework.test.util.ReflectionTestUtils.setField(toDelete, "id", categoryId);
        Category target = new Category();
        target.setUserId(userId);
        target.setName("SIP");
        org.springframework.test.util.ReflectionTestUtils.setField(target, "id", targetId);

        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(toDelete));
        when(categoryRepository.findById(targetId)).thenReturn(Optional.of(target));

        com.finora.entity.Budget budget = new com.finora.entity.Budget();
        when(budgetRepository.findByUserIdAndCategoryId(userId, categoryId)).thenReturn(Optional.of(budget));

        com.finora.entity.CategoryRule rule = new com.finora.entity.CategoryRule();
        rule.setActionValue("Mutual Fund SIP");
        when(categoryRuleRepository.findByUserIdAndActionTypeInAndActionValueIgnoreCase(
                eq(userId), any(), eq("Mutual Fund SIP"))).thenReturn(List.of(rule));

        service().delete(userId, categoryId, targetId);

        verify(transactionRepository).reassignCategory(userId, categoryId, targetId);
        verify(budgetRepository).delete(budget);
        assertThat(rule.getActionValue()).isEqualTo("SIP");
        verify(categoryRuleRepository).save(rule);
        verify(categoryRepository).delete(toDelete);
        verify(merchantLearningService).onCategoryDeleted(userId, categoryId, targetId);
    }

    // Final-branch review, finding 3. requireOwned passes for reassignTo == categoryId (it is
    // still the caller's own category at that moment), so without an explicit guard the delete
    // reassigned every transaction onto the row it was about to remove and
    // transactions.category_id ON DELETE SET NULL then nulled all of them -- total category loss,
    // reported as success.
    @Test
    void deleteRejectsReassigningACategoryToItself() {
        UUID categoryId = UUID.randomUUID();
        Category toDelete = new Category();
        toDelete.setUserId(userId);
        toDelete.setName("Mutual Fund SIP");
        toDelete.setSystem(false);
        org.springframework.test.util.ReflectionTestUtils.setField(toDelete, "id", categoryId);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(toDelete));

        assertThatThrownBy(() -> service().delete(userId, categoryId, categoryId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("reassign a category to itself");

        verify(transactionRepository, never()).reassignCategory(any(), any(), any());
        verify(categoryRepository, never()).delete(any(Category.class));
    }

    @Test
    void deleteRequiresAReassignTargetWhenTheCategoryHasDependents() {
        UUID categoryId = UUID.randomUUID();
        Category toDelete = new Category();
        toDelete.setUserId(userId);
        toDelete.setSystem(false);
        org.springframework.test.util.ReflectionTestUtils.setField(toDelete, "id", categoryId);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(toDelete));
        when(transactionRepository.countByUserIdAndCategoryId(userId, categoryId)).thenReturn(3L);

        assertThatThrownBy(() -> service().delete(userId, categoryId, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("reassign");
    }

    // Final-branch review, parked finding 1. onCategoryDeleted (learning-data cleanup) runs
    // unconditionally, outside the hasDependents branch that validates reassignTo -- so a
    // category with zero transactions/budget/rules but stray merchant-learning data could be
    // deleted with an unvalidated reassignTo, letting a bogus or someone-else's-category UUID
    // through to the merchant learning service instead of failing with 404/403.
    @Test
    void deleteValidatesReassignToEvenWhenTheOnlyDependentIsMerchantLearningData() {
        UUID categoryId = UUID.randomUUID();
        UUID otherUsersCategoryId = UUID.randomUUID();
        Category toDelete = new Category();
        toDelete.setUserId(userId);
        toDelete.setName("Mutual Fund SIP");
        toDelete.setSystem(false);
        org.springframework.test.util.ReflectionTestUtils.setField(toDelete, "id", categoryId);

        Category othersCategory = new Category();
        othersCategory.setUserId(UUID.randomUUID());
        othersCategory.setName("Not Yours");
        org.springframework.test.util.ReflectionTestUtils.setField(othersCategory, "id", otherUsersCategoryId);

        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(toDelete));
        when(categoryRepository.findById(otherUsersCategoryId)).thenReturn(Optional.of(othersCategory));
        // Zero transactions/budget/rules, merchant-learning data present. That now counts as a
        // dependent in its own right (adversarial review, finding 2), but the point under test is
        // unchanged and independent of that: reassignTo is validated for ownership before it can
        // reach the merchant learning service, whatever the dependent mix.
        when(transactionRepository.countByUserIdAndCategoryId(userId, categoryId)).thenReturn(0L);
        when(merchantLearningService.learningRowCount(userId, categoryId)).thenReturn(2L);
        when(budgetRepository.findByUserIdAndCategoryId(userId, categoryId)).thenReturn(Optional.empty());
        when(categoryRuleRepository.findByUserIdAndActionTypeInAndActionValueIgnoreCase(
                eq(userId), any(), eq("Mutual Fund SIP"))).thenReturn(List.of());

        assertThatThrownBy(() -> service().delete(userId, categoryId, otherUsersCategoryId))
                .isInstanceOf(ApiException.class);

        verify(merchantLearningService, never()).onCategoryDeleted(any(), any(), any());
        verify(categoryRepository, never()).delete(any(Category.class));
    }

    // Adversarial review, finding 2. merchant_category_learning rows were not part of
    // hasDependents, so a category whose ONLY dependent was the Learning Engine's per-merchant
    // training data could be deleted with no reassignment target: repointCategory is gated on
    // there being one, so V7's ON DELETE CASCADE destroyed that training data silently.
    @Test
    void deleteRequiresAReassignTargetWhenTheOnlyDependentIsMerchantLearningData() {
        UUID categoryId = UUID.randomUUID();
        Category toDelete = new Category();
        toDelete.setUserId(userId);
        toDelete.setName("Mutual Fund SIP");
        toDelete.setSystem(false);
        org.springframework.test.util.ReflectionTestUtils.setField(toDelete, "id", categoryId);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(toDelete));
        when(transactionRepository.countByUserIdAndCategoryId(userId, categoryId)).thenReturn(0L);
        when(budgetRepository.findByUserIdAndCategoryId(userId, categoryId)).thenReturn(Optional.empty());
        when(categoryRuleRepository.findByUserIdAndActionTypeInAndActionValueIgnoreCase(
                eq(userId), any(), eq("Mutual Fund SIP"))).thenReturn(List.of());
        when(merchantLearningService.learningRowCount(userId, categoryId)).thenReturn(4L);

        assertThatThrownBy(() -> service().delete(userId, categoryId, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("reassign");

        verify(merchantLearningService, never()).onCategoryDeleted(any(), any(), any());
        verify(categoryRepository, never()).delete(any(Category.class));
    }

    @Test
    void deleteWithNoDependentsAtAllStillNeedsNoTarget() {
        UUID categoryId = UUID.randomUUID();
        Category toDelete = new Category();
        toDelete.setUserId(userId);
        toDelete.setName("Mutual Fund SIP");
        toDelete.setSystem(false);
        org.springframework.test.util.ReflectionTestUtils.setField(toDelete, "id", categoryId);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(toDelete));
        when(transactionRepository.countByUserIdAndCategoryId(userId, categoryId)).thenReturn(0L);
        when(budgetRepository.findByUserIdAndCategoryId(userId, categoryId)).thenReturn(Optional.empty());
        when(categoryRuleRepository.findByUserIdAndActionTypeInAndActionValueIgnoreCase(
                eq(userId), any(), eq("Mutual Fund SIP"))).thenReturn(List.of());
        when(merchantLearningService.learningRowCount(userId, categoryId)).thenReturn(0L);

        service().delete(userId, categoryId, null);

        verify(categoryRepository).delete(toDelete);
    }

    @Test
    void deleteRejectsAnAttemptOnASystemCategory() {
        UUID categoryId = UUID.randomUUID();
        Category system = new Category();
        system.setUserId(userId);
        system.setSystem(true);
        org.springframework.test.util.ReflectionTestUtils.setField(system, "id", categoryId);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(system));

        assertThatThrownBy(() -> service().delete(userId, categoryId, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("system categor");
    }
}
