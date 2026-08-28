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
    private final AuditService auditService = mock(AuditService.class);

    private CategoryService service() {
        return new CategoryService(categoryRepository, categoryRuleRepository,
                transactionRepository, budgetRepository, auditService);
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
    void usageReportsTransactionBudgetAndRuleCounts() {
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

        var usage = service().usage(userId, categoryId);

        assertThat(usage.transactionCount()).isEqualTo(12);
        assertThat(usage.hasBudget()).isTrue();
        assertThat(usage.ruleCount()).isEqualTo(1);
    }
}
