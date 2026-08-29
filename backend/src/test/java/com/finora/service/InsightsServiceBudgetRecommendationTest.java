package com.finora.service;

import com.finora.entity.Budget;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.repository.BudgetRepository;
import com.finora.repository.UserRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the one genuinely actionable line in AI Insights: "Consider setting a budget for X" is
 * only shown when a category is trending up with no budget already covering it -- suppressed the
 * moment a budget exists, so Insights never nags about something the user already acted on.
 */
class InsightsServiceBudgetRecommendationTest {

    private TransactionRepository transactionRepository;
    private CategoryRepository categoryRepository;
    private BudgetRepository budgetRepository;
    private InsightsService insightsService;
    private final UUID userId = UUID.randomUUID();
    private Category dining;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        budgetRepository = mock(BudgetRepository.class);
        TransactionGraphService transactionGraphService = mock(TransactionGraphService.class);
        when(transactionGraphService.ccPaymentFromTransactionIds(any())).thenReturn(Set.of());
        insightsService = new InsightsService(transactionRepository, categoryRepository, budgetRepository,
                mock(UserRepository.class), transactionGraphService);

        dining = new Category();
        ReflectionTestUtils.setField(dining, "id", UUID.randomUUID());
        dining.setUserId(userId);
        dining.setName("Dining");
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(dining));
    }

    private Transaction expense(LocalDate date, BigDecimal amount) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setCategoryId(dining.getId());
        t.setTxnDate(date);
        t.setAmount(amount);
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription("Restaurant");
        t.setMerchant("Restaurant");
        return t;
    }

    private List<Transaction> risingDiningSpendAcrossTwoMonths() {
        // Prior month: 1000 total. Current month: 2000 total -- a 100% jump, comfortably past
        // the 15% mover threshold that gates both the mover sentence and the recommendation.
        return List.of(
                expense(LocalDate.of(2026, 6, 15), BigDecimal.valueOf(1000)),
                expense(LocalDate.of(2026, 7, 15), BigDecimal.valueOf(2000))
        );
    }

    @Test
    void recommendsABudget_whenACategoryIsTrendingUp_andNoneExistsYet() {
        when(transactionRepository.findByUserId(userId)).thenReturn(risingDiningSpendAcrossTwoMonths());
        when(budgetRepository.findByUserId(userId)).thenReturn(List.of()); // no budgets at all

        var result = insightsService.build(userId);

        assertThat(result.sentences()).anyMatch(s -> s.contains("Consider setting a budget for Dining"));
    }

    @Test
    void suppressesTheRecommendation_whenABudgetAlreadyCoversThatCategory() {
        when(transactionRepository.findByUserId(userId)).thenReturn(risingDiningSpendAcrossTwoMonths());

        Budget diningBudget = new Budget();
        diningBudget.setUserId(userId);
        diningBudget.setCategoryId(dining.getId());
        when(budgetRepository.findByUserId(userId)).thenReturn(List.of(diningBudget));

        var result = insightsService.build(userId);

        assertThat(result.sentences()).noneMatch(s -> s.contains("Consider setting a budget"));
    }
}
