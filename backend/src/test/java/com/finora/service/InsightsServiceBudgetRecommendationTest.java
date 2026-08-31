package com.finora.service;

import com.finora.entity.Budget;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.BudgetRepository;
import com.finora.repository.UserRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.StatementImportRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the one genuinely actionable line in AI Insights: "Consider setting a budget for X" is
 * only shown when a category is trending up with no budget already covering it -- suppressed the
 * moment a budget exists, so Insights never nags about something the user already acted on.
 */
class InsightsServiceBudgetRecommendationTest {

    private TransactionRepository transactionRepository;
    private AccountRepository accountRepository;
    private CategoryRepository categoryRepository;
    private BudgetRepository budgetRepository;
    private InsightsService insightsService;
    private final UUID userId = UUID.randomUUID();
    private Category dining;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        accountRepository = mock(AccountRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        budgetRepository = mock(BudgetRepository.class);
        StatementImportRepository statementImportRepository = mock(StatementImportRepository.class);
        when(statementImportRepository.findMetadataWithPeriodByUserIdAndAccountId(any(), any())).thenReturn(List.of());
        TransactionGraphService transactionGraphService = mock(TransactionGraphService.class);
        when(transactionGraphService.ccPaymentFromTransactionIds(any())).thenReturn(Set.of());

        Account liveAccount = new Account();
        ReflectionTestUtils.setField(liveAccount, "id", UUID.randomUUID());
        liveAccount.setUserId(userId);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(liveAccount));

        insightsService = new InsightsService(transactionRepository, accountRepository, categoryRepository, budgetRepository,
                mock(UserRepository.class), transactionGraphService, statementImportRepository);

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
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(risingDiningSpendAcrossTwoMonths());
        when(budgetRepository.findByUserId(userId)).thenReturn(List.of()); // no budgets at all

        var result = insightsService.build(userId);

        assertThat(result.sentences()).anyMatch(s -> s.contains("Consider setting a budget for Dining"));
    }

    @Test
    void suppressesTheRecommendation_whenABudgetAlreadyCoversThatCategory() {
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(risingDiningSpendAcrossTwoMonths());

        Budget diningBudget = new Budget();
        diningBudget.setUserId(userId);
        diningBudget.setCategoryId(dining.getId());
        when(budgetRepository.findByUserId(userId)).thenReturn(List.of(diningBudget));

        var result = insightsService.build(userId);

        assertThat(result.sentences()).noneMatch(s -> s.contains("Consider setting a budget"));
    }

    // Deleted-account leak (see DashboardService.summarize for the original fix): a deleted
    // account's transactions deliberately keep deleted_at unset, so build()'s pipeline must scope
    // its transaction fetch to exactly the live account ids, not just userId.
    @Test
    void build_withNoLiveAccounts_shortCircuits_withoutQueryingTransactions() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of());

        var result = insightsService.build(userId);

        assertThat(result.sentences()).containsExactly("Upload or add transactions to see spending insights.");
        org.mockito.Mockito.verifyNoInteractions(transactionRepository);
    }

    @Test
    void build_scopesTransactionFetch_toExactlyTheLiveAccountIds() {
        Account onlyLiveAccount = new Account();
        ReflectionTestUtils.setField(onlyLiveAccount, "id", UUID.randomUUID());
        onlyLiveAccount.setUserId(userId);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(onlyLiveAccount));
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(risingDiningSpendAcrossTwoMonths());
        when(budgetRepository.findByUserId(userId)).thenReturn(List.of());

        insightsService.build(userId);

        org.mockito.Mockito.verify(transactionRepository)
                .findByUserIdAndAccountIdIn(userId, List.of(onlyLiveAccount.getId()));
    }
}
