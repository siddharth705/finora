package com.finora.service;

import com.finora.dto.DashboardSummaryDto;
import com.finora.entity.Account;
import com.finora.entity.Budget;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.BudgetRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardServiceTest {

    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private BudgetRepository budgetRepository;
    private CategoryRepository categoryRepository;
    private UserRepository userRepository;
    private DashboardService dashboardService;
    private final UUID userId = UUID.randomUUID();
    private Account savings;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        budgetRepository = mock(BudgetRepository.class);
        userRepository = mock(UserRepository.class);

        savings = new Account();
        ReflectionTestUtils.setField(savings, "id", UUID.randomUUID());
        savings.setUserId(userId);
        savings.setAccountType(Account.Type.SAVINGS);
        savings.setBalance(new BigDecimal("100000.00"));

        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        // Default threshold (2000) -- far below the 100000 savings balance above, so the
        // baseline tests below never trip the low-balance notification unless a test
        // explicitly overrides it.

        when(accountRepository.findByUserId(any())).thenReturn(List.of(savings));
        when(categoryRepository.findByUserId(any())).thenReturn(List.of());
        when(budgetRepository.findByUserId(any())).thenReturn(List.of());
        when(userRepository.findById(any())).thenReturn(Optional.of(user));

        dashboardService = new DashboardService(accountRepository, transactionRepository, categoryRepository,
                budgetRepository, userRepository);
    }

    private Transaction txn(BigDecimal amount, Transaction.Type type, LocalDate date, Transaction.ReconciliationStatus status) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setAmount(amount);
        t.setTxnType(type);
        t.setTxnDate(date);
        t.setReconciliationStatus(status);
        return t;
    }

    @Test
    void summarize_excludesRefundStatusIncomeFromMonthlyIncome() {
        // A REFUND-status transaction is the INCOME leg of a reconciled refund (see
        // ReconciliationService's refund pass) -- must not inflate monthlyIncome, and by
        // extension savingsRatePct and the health score's monthly-income input, all of which
        // are derived from the same filtered `active` list.
        LocalDate july = LocalDate.of(2026, 7, 15);
        Transaction salary = txn(new BigDecimal("50000.00"), Transaction.Type.INCOME, july, Transaction.ReconciliationStatus.OK);
        Transaction refund = txn(new BigDecimal("999.00"), Transaction.Type.INCOME, july, Transaction.ReconciliationStatus.REFUND);

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(salary, refund));

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.monthlyIncome()).isEqualByComparingTo("50000.00"); // refund's 999 excluded
    }

    @Test
    void summarize_stillIncludesOrdinaryIncomeAndExpense() {
        LocalDate july = LocalDate.of(2026, 7, 15);
        Transaction salary = txn(new BigDecimal("50000.00"), Transaction.Type.INCOME, july, Transaction.ReconciliationStatus.OK);
        Transaction rent = txn(new BigDecimal("15000.00"), Transaction.Type.EXPENSE, july, Transaction.ReconciliationStatus.OK);

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(salary, rent));

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.monthlyIncome()).isEqualByComparingTo("50000.00");
        assertThat(summary.monthlyExpense()).isEqualByComparingTo("15000.00");
    }

    @Test
    void summarize_flagsALiquidAccountBelowTheUsersLowBalanceThreshold() {
        // Override setUp()'s 100000 balance with one below the default 2000 threshold.
        savings.setBalance(new BigDecimal("500.00"));
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of());

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.notifications())
                .anyMatch(n -> n.contains("below your low-balance threshold"));
    }

    @Test
    void summarize_doesNotFlagACreditCardOrInvestmentAccountForLowBalance() {
        // A near-zero CREDIT_CARD balance is good news, and INVESTMENT isn't "spendable cash" --
        // neither should ever trigger the low-balance notification, regardless of threshold.
        Account card = new Account();
        ReflectionTestUtils.setField(card, "id", UUID.randomUUID());
        card.setUserId(userId);
        card.setAccountType(Account.Type.CREDIT_CARD);
        card.setBalance(BigDecimal.ZERO);

        AccountRepository accountRepository = mock(AccountRepository.class);
        when(accountRepository.findByUserId(any())).thenReturn(List.of(card));
        UserRepository userRepository = mock(UserRepository.class);
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        when(userRepository.findById(any())).thenReturn(Optional.of(user));
        dashboardService = new DashboardService(accountRepository, transactionRepository, categoryRepository,
                budgetRepository, userRepository);
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of());

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.notifications()).noneMatch(n -> n.contains("low-balance threshold"));
    }

    @Test
    void summarize_flagsACategoryThatHasReachedItsMonthlyBudget() {
        UUID categoryId = UUID.randomUUID();
        Category category = new Category();
        ReflectionTestUtils.setField(category, "id", categoryId);
        category.setUserId(userId);
        category.setName("Dining");
        when(categoryRepository.findByUserId(any())).thenReturn(List.of(category));

        Budget budget = new Budget();
        budget.setUserId(userId);
        budget.setCategoryId(categoryId);
        budget.setMonthlyLimit(new BigDecimal("5000.00"));
        when(budgetRepository.findByUserId(any())).thenReturn(List.of(budget));

        LocalDate july = LocalDate.of(2026, 7, 15);
        Transaction dining = txn(new BigDecimal("6000.00"), Transaction.Type.EXPENSE, july, Transaction.ReconciliationStatus.OK);
        dining.setCategoryId(categoryId);
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(dining));

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.notifications())
                .anyMatch(n -> n.contains("Dining") && n.contains("reached your monthly budget"));
    }

    @Test
    void summarize_doesNotFlagACategoryStillUnderItsMonthlyBudget() {
        UUID categoryId = UUID.randomUUID();
        Category category = new Category();
        ReflectionTestUtils.setField(category, "id", categoryId);
        category.setUserId(userId);
        category.setName("Groceries");
        when(categoryRepository.findByUserId(any())).thenReturn(List.of(category));

        Budget budget = new Budget();
        budget.setUserId(userId);
        budget.setCategoryId(categoryId);
        budget.setMonthlyLimit(new BigDecimal("5000.00"));
        when(budgetRepository.findByUserId(any())).thenReturn(List.of(budget));

        LocalDate july = LocalDate.of(2026, 7, 15);
        Transaction groceries = txn(new BigDecimal("1000.00"), Transaction.Type.EXPENSE, july, Transaction.ReconciliationStatus.OK);
        groceries.setCategoryId(categoryId);
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(groceries));

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.notifications()).noneMatch(n -> n.contains("reached your monthly budget"));
    }

    @Test
    void summarize_warnsWhenACreditCardPaymentIsDueWithinAWeek() {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        Account card = new Account();
        ReflectionTestUtils.setField(card, "id", UUID.randomUUID());
        card.setUserId(userId);
        card.setName("Visa Signature");
        card.setAccountType(Account.Type.CREDIT_CARD);
        card.setBalance(new BigDecimal("4500.00"));
        card.setCreditLimit(new BigDecimal("100000.00"));
        // "Today" is resolved in the user's own timezone (Asia/Kolkata, the default) -- 3 days
        // out from whatever "today" is in that zone, comfortably inside the 7-day warning window
        // regardless of what zone this test happens to run in.
        card.setDueDate(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusDays(3));
        when(accountRepository.findByUserId(any())).thenReturn(List.of(savings, card));

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.notifications()).anyMatch(n ->
                n.contains("Visa Signature") && n.contains("due in 3 day"));
    }

    @Test
    void summarize_fallsBackSafely_whenTheStoredTimezoneIsMalformed() {
        // UserSettingsService.update() now rejects a malformed timezone up front, but this proves
        // the read-time fallback here is a real backstop, not just a comment -- a row that already
        // has a bad value (however it got there) must not 500 the whole dashboard.
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setTimezone("Not/A_Real_Zone");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatCode(() -> dashboardService.summarize(userId)).doesNotThrowAnyException();
    }
}
