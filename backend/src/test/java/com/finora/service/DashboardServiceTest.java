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
import org.junit.jupiter.api.DisplayName;
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
    private com.finora.repository.StatementImportRepository statementImportRepository;
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
        statementImportRepository = mock(com.finora.repository.StatementImportRepository.class);

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
        when(statementImportRepository.countByUserId(any())).thenReturn(0L);

        dashboardService = new DashboardService(accountRepository, transactionRepository, categoryRepository,
                budgetRepository, userRepository, statementImportRepository);
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
    @DisplayName("categoryReviewWarning fires at/above the threshold, with the real amount/count/pct behind it")
    void summarize_flagsCategoryReviewWarning_atOrAboveTheThreshold() {
        LocalDate july = LocalDate.of(2026, 7, 15);
        // 8000 flagged / 10000 total = 80% -- comfortably above the 20% threshold, matching the
        // real "81% in Other" case this warning exists for.
        Transaction flagged1 = txn(new BigDecimal("5000.00"), Transaction.Type.EXPENSE, july, Transaction.ReconciliationStatus.OK);
        flagged1.setNeedsCategoryReview(true);
        Transaction flagged2 = txn(new BigDecimal("3000.00"), Transaction.Type.EXPENSE, july, Transaction.ReconciliationStatus.OK);
        flagged2.setNeedsCategoryReview(true);
        Transaction clean = txn(new BigDecimal("2000.00"), Transaction.Type.EXPENSE, july, Transaction.ReconciliationStatus.OK);

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(flagged1, flagged2, clean));

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.categoryReviewWarning()).isTrue();
        assertThat(summary.categoryReviewSpendAmount()).isEqualByComparingTo("8000.00");
        assertThat(summary.categoryReviewTransactionCount()).isEqualTo(2);
        assertThat(summary.categoryReviewSpendPct()).isCloseTo(80.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(summary.categoryReviewSpendWarningThresholdPct())
                .isEqualTo(DashboardService.CATEGORY_REVIEW_SPEND_WARNING_THRESHOLD_PCT);
    }

    @Test
    @DisplayName("categoryReviewWarning stays off below the threshold")
    void summarize_doesNotFlagCategoryReviewWarning_belowTheThreshold() {
        LocalDate july = LocalDate.of(2026, 7, 15);
        // 1000 flagged / 10000 total = 10% -- well under the 20% threshold.
        Transaction flagged = txn(new BigDecimal("1000.00"), Transaction.Type.EXPENSE, july, Transaction.ReconciliationStatus.OK);
        flagged.setNeedsCategoryReview(true);
        Transaction clean = txn(new BigDecimal("9000.00"), Transaction.Type.EXPENSE, july, Transaction.ReconciliationStatus.OK);

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(flagged, clean));

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.categoryReviewWarning()).isFalse();
        assertThat(summary.categoryReviewSpendPct()).isCloseTo(10.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("categoryReviewWarning ignores an INCOME transaction flagged for review -- this is a SPEND metric")
    void summarize_ignoresFlaggedIncome_whenComputingCategoryReviewSpend() {
        LocalDate july = LocalDate.of(2026, 7, 15);
        Transaction flaggedIncome = txn(new BigDecimal("50000.00"), Transaction.Type.INCOME, july, Transaction.ReconciliationStatus.OK);
        flaggedIncome.setNeedsCategoryReview(true);
        Transaction cleanExpense = txn(new BigDecimal("5000.00"), Transaction.Type.EXPENSE, july, Transaction.ReconciliationStatus.OK);

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(flaggedIncome, cleanExpense));

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.categoryReviewWarning()).isFalse();
        assertThat(summary.categoryReviewSpendAmount()).isEqualByComparingTo("0.00");
        assertThat(summary.categoryReviewSpendPct()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("categoryReviewSpendPct is 0, not NaN, when there is no expense at all this month")
    void summarize_reportsZeroCategoryReviewPct_whenThereIsNoExpense() {
        LocalDate july = LocalDate.of(2026, 7, 15);
        Transaction incomeOnly = txn(new BigDecimal("50000.00"), Transaction.Type.INCOME, july, Transaction.ReconciliationStatus.OK);

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(incomeOnly));

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.categoryReviewWarning()).isFalse();
        assertThat(summary.categoryReviewSpendPct()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("comparison gating: a partial-boundary prior month doesn't produce a wild delta percentage")
    void summarize_gatesDeltas_whenThePriorMonthIsAPartialBoundarySliver() {
        // Reproduces the real bug: one continuous ~30-day statement window (Jun 26 -- Jul 26)
        // straddles a calendar boundary, so "priorMonth" (June) is really just 5 leftover days of
        // the SAME import, not a genuine separate historical month -- a handful of stray June
        // transactions against a full July of real spending used to report as a 900%+ swing.
        List<Transaction> txns = new java.util.ArrayList<>();
        for (LocalDate d = LocalDate.of(2026, 6, 26); !d.isAfter(LocalDate.of(2026, 6, 30)); d = d.plusDays(1)) {
            txns.add(txn(new BigDecimal("500.00"), Transaction.Type.INCOME, d, Transaction.ReconciliationStatus.OK));
        }
        for (LocalDate d = LocalDate.of(2026, 7, 1); !d.isAfter(LocalDate.of(2026, 7, 26)); d = d.plusDays(1)) {
            txns.add(txn(new BigDecimal("1200.00"), Transaction.Type.INCOME, d, Transaction.ReconciliationStatus.OK));
        }
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.incomeDeltaPct()).isNull();
        assertThat(summary.expenseDeltaPct()).isNull();
        assertThat(summary.netDeltaPct()).isNull();
        assertThat(summary.comparisonGateReason()).isEqualTo("PARTIAL_PRIOR_MONTH");
    }

    @Test
    @DisplayName("comparison gating: a FULL prior month with too few transactions still doesn't get a delta")
    void summarize_gatesDeltas_whenTheFullPriorMonthHasTooFewTransactions() {
        // Isolated from the partial-boundary gate: the earlier of June's two transactions falls on
        // the 1st, so June passes the "full calendar month" boundary check cleanly -- it's gated
        // purely because only 2 transactions (below MIN_TRANSACTIONS_FOR_DELTA_COMPARISON) fall in
        // it, and one or two stray rows could still single-handedly swing the ratio.
        List<Transaction> txns = new java.util.ArrayList<>();
        txns.add(txn(new BigDecimal("100.00"), Transaction.Type.INCOME, LocalDate.of(2026, 6, 1), Transaction.ReconciliationStatus.OK));
        txns.add(txn(new BigDecimal("100.00"), Transaction.Type.INCOME, LocalDate.of(2026, 6, 20), Transaction.ReconciliationStatus.OK));
        for (LocalDate d = LocalDate.of(2026, 7, 1); !d.isAfter(LocalDate.of(2026, 7, 31)); d = d.plusDays(1)) {
            txns.add(txn(new BigDecimal("1000.00"), Transaction.Type.INCOME, d, Transaction.ReconciliationStatus.OK));
        }
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.incomeDeltaPct()).isNull();
        assertThat(summary.comparisonGateReason()).isEqualTo("TOO_FEW_PRIOR_TRANSACTIONS");
        assertThat(summary.comparisonGateMinTransactions()).isEqualTo(DashboardService.MIN_TRANSACTIONS_FOR_DELTA_COMPARISON);
    }

    @Test
    @DisplayName("comparison gating: a real, comparable prior month still gets a real delta")
    void summarize_stillComputesADelta_whenThePriorMonthIsGenuinelyComparable() {
        // Two full calendar months, both with real transaction volume -- the fix must not smother a
        // legitimate comparison, only the artifacts.
        List<Transaction> txns = new java.util.ArrayList<>();
        for (LocalDate d = LocalDate.of(2026, 6, 1); !d.isAfter(LocalDate.of(2026, 6, 30)); d = d.plusDays(1)) {
            txns.add(txn(new BigDecimal("100.00"), Transaction.Type.INCOME, d, Transaction.ReconciliationStatus.OK));
        }
        for (LocalDate d = LocalDate.of(2026, 7, 1); !d.isAfter(LocalDate.of(2026, 7, 31)); d = d.plusDays(1)) {
            txns.add(txn(new BigDecimal("200.00"), Transaction.Type.INCOME, d, Transaction.ReconciliationStatus.OK));
        }
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        // June: 30 * 100 = 3000. July: 31 * 200 = 6200. (6200-3000)/3000 * 100 ~= 106.67%.
        assertThat(summary.incomeDeltaPct()).isNotNull();
        assertThat(summary.incomeDeltaPct()).isCloseTo(106.67, org.assertj.core.data.Offset.offset(0.5));
        assertThat(summary.comparisonGateReason()).isNull();
    }

    @Test
    @DisplayName("comparison gating: an exactly-zero prior month still gates, same as before this change")
    void summarize_stillGatesDeltas_whenThePriorMonthIsExactlyZero() {
        // Pre-existing behaviour (prior == 0 -> null) must survive unchanged, isolated from the two
        // NEW gates: June is a genuine FULL month (1st -- 30th) with plenty of its own transactions
        // (clears MIN_TRANSACTIONS_FOR_DELTA_COMPARISON) -- just none of them INCOME, so incomePrior
        // is genuinely, legitimately 0, not a thin-data artifact.
        List<Transaction> txns = new java.util.ArrayList<>();
        for (LocalDate d = LocalDate.of(2026, 6, 1); !d.isAfter(LocalDate.of(2026, 6, 30)); d = d.plusDays(1)) {
            txns.add(txn(new BigDecimal("500.00"), Transaction.Type.EXPENSE, d, Transaction.ReconciliationStatus.OK));
        }
        for (LocalDate d = LocalDate.of(2026, 7, 1); !d.isAfter(LocalDate.of(2026, 7, 31)); d = d.plusDays(1)) {
            txns.add(txn(new BigDecimal("1000.00"), Transaction.Type.INCOME, d, Transaction.ReconciliationStatus.OK));
        }
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.incomeDeltaPct()).isNull();
        // The prior month itself is perfectly reliable -- it's genuinely, legitimately zero income,
        // not a thin-data artifact -- so this null shouldn't carry a gate reason a "Why?" disclosure
        // would show; the amount being zero is self-explanatory.
        assertThat(summary.comparisonGateReason()).isNull();
    }

    private UUID category(String name) {
        UUID id = UUID.randomUUID();
        Category category = new Category();
        ReflectionTestUtils.setField(category, "id", id);
        category.setUserId(userId);
        category.setName(name);
        List<Category> existing = new java.util.ArrayList<>(categoryRepository.findByUserId(userId));
        existing.add(category);
        when(categoryRepository.findByUserId(any())).thenReturn(existing);
        return id;
    }

    @Test
    @DisplayName("expense category movers: explains a real delta with the categories that moved, dropping unchanged ones")
    void summarize_explainsARealExpenseDelta_withTheCategoriesThatMoved() {
        UUID diningId = category("Dining");
        UUID groceriesId = category("Groceries");

        List<Transaction> txns = new java.util.ArrayList<>();
        // June (prior month): Dining 5000, Groceries 3000, plus an INCOME row so the prior month
        // clears MIN_TRANSACTIONS_FOR_DELTA_COMPARISON (3) and the delta isn't gated.
        Transaction diningJune = txn(new BigDecimal("5000.00"), Transaction.Type.EXPENSE, LocalDate.of(2026, 6, 1), Transaction.ReconciliationStatus.OK);
        diningJune.setCategoryId(diningId);
        Transaction groceriesJune = txn(new BigDecimal("3000.00"), Transaction.Type.EXPENSE, LocalDate.of(2026, 6, 10), Transaction.ReconciliationStatus.OK);
        groceriesJune.setCategoryId(groceriesId);
        Transaction incomeJune = txn(new BigDecimal("50000.00"), Transaction.Type.INCOME, LocalDate.of(2026, 6, 20), Transaction.ReconciliationStatus.OK);
        txns.add(diningJune);
        txns.add(groceriesJune);
        txns.add(incomeJune);

        // July (current month): Dining rose to 8000; Groceries stayed exactly 3000 -- unchanged,
        // so it must not appear in the movers even though it's a real category with real spend.
        Transaction diningJuly = txn(new BigDecimal("8000.00"), Transaction.Type.EXPENSE, LocalDate.of(2026, 7, 1), Transaction.ReconciliationStatus.OK);
        diningJuly.setCategoryId(diningId);
        Transaction groceriesJuly = txn(new BigDecimal("3000.00"), Transaction.Type.EXPENSE, LocalDate.of(2026, 7, 10), Transaction.ReconciliationStatus.OK);
        groceriesJuly.setCategoryId(groceriesId);
        txns.add(diningJuly);
        txns.add(groceriesJuly);
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.expenseDeltaPct()).isNotNull();
        assertThat(summary.expenseCategoryMovers()).hasSize(1);
        DashboardSummaryDto.CategoryMover mover = summary.expenseCategoryMovers().get(0);
        assertThat(mover.category()).isEqualTo("Dining");
        assertThat(mover.currentAmount()).isEqualByComparingTo("8000.00");
        assertThat(mover.priorAmount()).isEqualByComparingTo("5000.00");
        assertThat(mover.pctChange()).isCloseTo(60.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("expense category movers: ranks by rupee contribution, not percentage")
    void summarize_ranksCategoryMovers_byRupeeContributionNotPercentage() {
        // Small category, huge % swing (Coffee: 50 -> 500, +900%) vs. a bigger category with a
        // smaller % swing (Rent: 20000 -> 24000, +20%) -- Rent contributed more real rupees to
        // the delta and must be ranked first despite the smaller percentage.
        UUID coffeeId = category("Coffee");
        UUID rentId = category("Rent");

        List<Transaction> txns = new java.util.ArrayList<>();
        Transaction coffeeJune = txn(new BigDecimal("50.00"), Transaction.Type.EXPENSE, LocalDate.of(2026, 6, 1), Transaction.ReconciliationStatus.OK);
        coffeeJune.setCategoryId(coffeeId);
        Transaction rentJune = txn(new BigDecimal("20000.00"), Transaction.Type.EXPENSE, LocalDate.of(2026, 6, 2), Transaction.ReconciliationStatus.OK);
        rentJune.setCategoryId(rentId);
        for (LocalDate d = LocalDate.of(2026, 6, 3); !d.isAfter(LocalDate.of(2026, 6, 30)); d = d.plusDays(1)) {
            txns.add(txn(new BigDecimal("10.00"), Transaction.Type.INCOME, d, Transaction.ReconciliationStatus.OK));
        }
        txns.add(coffeeJune);
        txns.add(rentJune);

        Transaction coffeeJuly = txn(new BigDecimal("500.00"), Transaction.Type.EXPENSE, LocalDate.of(2026, 7, 1), Transaction.ReconciliationStatus.OK);
        coffeeJuly.setCategoryId(coffeeId);
        Transaction rentJuly = txn(new BigDecimal("24000.00"), Transaction.Type.EXPENSE, LocalDate.of(2026, 7, 2), Transaction.ReconciliationStatus.OK);
        rentJuly.setCategoryId(rentId);
        for (LocalDate d = LocalDate.of(2026, 7, 3); !d.isAfter(LocalDate.of(2026, 7, 31)); d = d.plusDays(1)) {
            txns.add(txn(new BigDecimal("10.00"), Transaction.Type.INCOME, d, Transaction.ReconciliationStatus.OK));
        }
        txns.add(coffeeJuly);
        txns.add(rentJuly);
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.expenseCategoryMovers()).extracting(DashboardSummaryDto.CategoryMover::category)
                .containsExactly("Rent", "Coffee");
    }

    @Test
    @DisplayName("expense category movers: caps at 3, keeping the largest rupee movers")
    void summarize_capsCategoryMoversAtThree() {
        UUID aId = category("A");
        UUID bId = category("B");
        UUID cId = category("C");
        UUID dId = category("D");
        UUID[] ids = {aId, bId, cId, dId};

        List<Transaction> txns = new java.util.ArrayList<>();
        LocalDate juneDate = LocalDate.of(2026, 6, 1);
        for (int i = 0; i < ids.length; i++) {
            Transaction t = txn(new BigDecimal("100.00"), Transaction.Type.EXPENSE, juneDate.plusDays(i), Transaction.ReconciliationStatus.OK);
            t.setCategoryId(ids[i]);
            txns.add(t);
        }
        txns.add(txn(new BigDecimal("1.00"), Transaction.Type.INCOME, LocalDate.of(2026, 6, 10), Transaction.ReconciliationStatus.OK));

        // July: D moves the most (+1000), C next (+500), B next (+200), A least (+50) -- the top 3
        // by rupee delta should be D, C, B, largest first; A dropped for being the smallest mover.
        BigDecimal[] julyAmounts = {new BigDecimal("150.00"), new BigDecimal("300.00"), new BigDecimal("600.00"), new BigDecimal("1100.00")};
        LocalDate julyDate = LocalDate.of(2026, 7, 1);
        for (int i = 0; i < ids.length; i++) {
            Transaction t = txn(julyAmounts[i], Transaction.Type.EXPENSE, julyDate.plusDays(i), Transaction.ReconciliationStatus.OK);
            t.setCategoryId(ids[i]);
            txns.add(t);
        }
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.expenseCategoryMovers()).hasSize(3);
        assertThat(summary.expenseCategoryMovers()).extracting(DashboardSummaryDto.CategoryMover::category)
                .containsExactly("D", "C", "B");
    }

    @Test
    @DisplayName("expense category movers: empty when the delta itself is gated -- nothing to explain about a hidden number")
    void summarize_hasNoCategoryMovers_whenTheExpenseDeltaIsGated() {
        // Same partial-boundary setup as the comparison-gating tests above.
        List<Transaction> txns = new java.util.ArrayList<>();
        for (LocalDate d = LocalDate.of(2026, 6, 26); !d.isAfter(LocalDate.of(2026, 6, 30)); d = d.plusDays(1)) {
            txns.add(txn(new BigDecimal("500.00"), Transaction.Type.EXPENSE, d, Transaction.ReconciliationStatus.OK));
        }
        for (LocalDate d = LocalDate.of(2026, 7, 1); !d.isAfter(LocalDate.of(2026, 7, 26)); d = d.plusDays(1)) {
            txns.add(txn(new BigDecimal("1200.00"), Transaction.Type.EXPENSE, d, Transaction.ReconciliationStatus.OK));
        }
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.expenseDeltaPct()).isNull();
        assertThat(summary.expenseCategoryMovers()).isEmpty();
    }

    @Test
    @DisplayName("detected issues: lists a transaction the reconciliation engine already flagged as a duplicate")
    void summarize_listsADetectedDuplicate() {
        Transaction canonical = txn(new BigDecimal("500.00"), Transaction.Type.EXPENSE,
                LocalDate.of(2026, 7, 10), Transaction.ReconciliationStatus.OK);
        Transaction duplicate = txn(new BigDecimal("500.00"), Transaction.Type.EXPENSE,
                LocalDate.of(2026, 7, 10), Transaction.ReconciliationStatus.DUPLICATE);
        duplicate.setIsDuplicateOf(canonical.getId());
        duplicate.setMerchant("Swiggy");
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(canonical, duplicate));

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.duplicateTransactionCount()).isEqualTo(1);
        assertThat(summary.detectedDuplicates()).hasSize(1);
        DashboardSummaryDto.DetectedDuplicate found = summary.detectedDuplicates().get(0);
        assertThat(found.merchant()).isEqualTo("Swiggy");
        assertThat(found.amount()).isEqualByComparingTo("500.00");
        assertThat(found.date()).isEqualTo(LocalDate.of(2026, 7, 10));
    }

    @Test
    @DisplayName("detected issues: a flagged duplicate is excluded from monthlyExpense, same as the ledger already treats it")
    void summarize_excludesADetectedDuplicateFromTotals() {
        Transaction real = txn(new BigDecimal("500.00"), Transaction.Type.EXPENSE,
                LocalDate.of(2026, 7, 10), Transaction.ReconciliationStatus.OK);
        Transaction duplicate = txn(new BigDecimal("500.00"), Transaction.Type.EXPENSE,
                LocalDate.of(2026, 7, 10), Transaction.ReconciliationStatus.DUPLICATE);
        duplicate.setIsDuplicateOf(UUID.randomUUID());
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(real, duplicate));

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        // 500, not 1000 -- the duplicate never counted, exactly as RefundNetting.reportable()
        // already excluded it before this feature existed. This feature only makes that visible.
        assertThat(summary.monthlyExpense()).isEqualByComparingTo("500.00");
        assertThat(summary.duplicateTransactionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("detected issues: caps the display list at DETECTED_DUPLICATES_DISPLAY_LIMIT but reports the true total count")
    void summarize_capsDetectedDuplicatesDisplay_butReportsTheTrueCount() {
        List<Transaction> txns = new java.util.ArrayList<>();
        for (int i = 0; i < DashboardService.DETECTED_DUPLICATES_DISPLAY_LIMIT + 3; i++) {
            Transaction d = txn(new BigDecimal("10.00"), Transaction.Type.EXPENSE,
                    LocalDate.of(2026, 7, 1).plusDays(i), Transaction.ReconciliationStatus.DUPLICATE);
            d.setIsDuplicateOf(UUID.randomUUID());
            txns.add(d);
        }
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.duplicateTransactionCount()).isEqualTo(DashboardService.DETECTED_DUPLICATES_DISPLAY_LIMIT + 3);
        assertThat(summary.detectedDuplicates()).hasSize(DashboardService.DETECTED_DUPLICATES_DISPLAY_LIMIT);
        // Newest first.
        assertThat(summary.detectedDuplicates().get(0).date()).isEqualTo(LocalDate.of(2026, 7, 1).plusDays(DashboardService.DETECTED_DUPLICATES_DISPLAY_LIMIT + 2));
    }

    @Test
    @DisplayName("detected issues: empty when the reconciliation engine hasn't flagged anything")
    void summarize_reportsNoDetectedDuplicates_whenNoneAreFlagged() {
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(
                txn(new BigDecimal("500.00"), Transaction.Type.EXPENSE, LocalDate.of(2026, 7, 10), Transaction.ReconciliationStatus.OK)
        ));

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.duplicateTransactionCount()).isEqualTo(0);
        assertThat(summary.detectedDuplicates()).isEmpty();
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
                budgetRepository, userRepository, statementImportRepository);
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of());

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.notifications()).noneMatch(n -> n.contains("low-balance threshold"));
    }

    @Test
    @DisplayName("limitedHistory is true below the month floor, and reports the real counts behind it")
    void summarize_flagsLimitedHistory_belowTheMonthFloor() {
        LocalDate june = LocalDate.of(2026, 6, 15);
        LocalDate july = LocalDate.of(2026, 7, 15);
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(
                txn(new BigDecimal("500.00"), Transaction.Type.EXPENSE, june, Transaction.ReconciliationStatus.OK),
                txn(new BigDecimal("500.00"), Transaction.Type.EXPENSE, july, Transaction.ReconciliationStatus.OK)));
        when(statementImportRepository.countByUserId(userId)).thenReturn(2L);

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.limitedHistory()).isTrue();
        assertThat(summary.historyMonthCount()).isEqualTo(2);
        assertThat(summary.limitedHistoryMonthFloor()).isEqualTo(DashboardService.LIMITED_HISTORY_MONTH_FLOOR);
        assertThat(summary.statementCount()).isEqualTo(2);
        assertThat(summary.accountCount()).isEqualTo(1); // setUp()'s single savings account
    }

    @Test
    @DisplayName("limitedHistory is false once the user clears the month floor")
    void summarize_doesNotFlagLimitedHistory_atOrAboveTheMonthFloor() {
        List<Transaction> txns = new java.util.ArrayList<>();
        for (int m = 5; m <= 7; m++) {
            txns.add(txn(new BigDecimal("500.00"), Transaction.Type.EXPENSE, LocalDate.of(2026, m, 10), Transaction.ReconciliationStatus.OK));
        }
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.limitedHistory()).isFalse();
        assertThat(summary.historyMonthCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("BUG 06: last month's overspend does not trigger this month's budget alert")
    void summarize_doesNotFlagABudgetFromAPreviousMonthsSpend() {
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

        // Overspent LAST month, nothing this month. The dashboard used to evaluate the budget
        // against "the newest month with data" and warn anyway -- while the Budgets page, which
        // has always used the calendar month, correctly showed this month at 0%. Two screens in
        // one app disagreeing about the same number.
        LocalDate lastMonth = LocalDate.now(com.finora.util.UserZone.DEFAULT).minusMonths(1).withDayOfMonth(15);
        Transaction dining = txn(new BigDecimal("6000.00"), Transaction.Type.EXPENSE, lastMonth, Transaction.ReconciliationStatus.OK);
        dining.setCategoryId(categoryId);
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(dining));

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.notifications())
                .as("a monthly allowance resets on a calendar boundary regardless of when the user "
                        + "last imported")
                .noneMatch(n -> n.contains("reached your monthly budget"));
    }

    @Test
    @DisplayName("BUG 05: the response names the month its figures describe")
    void summarize_reportsWhichMonthTheFiguresAreFrom() {
        LocalDate lastMonth = LocalDate.now(com.finora.util.UserZone.DEFAULT).minusMonths(1).withDayOfMonth(15);
        Transaction spend = txn(new BigDecimal("900.00"), Transaction.Type.EXPENSE, lastMonth, Transaction.ReconciliationStatus.OK);
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(spend));

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.reportingMonth())
                .as("reporting on the newest month with data is intended; saying nothing about "
                        + "which month it is, so the client renders it as 'this month', is Bug 05")
                .isEqualTo(java.time.YearMonth.from(lastMonth).toString());
        assertThat(summary.reportingMonthIsCurrent()).isFalse();
        assertThat(summary.monthlyExpense())
                .as("the figures themselves still come from that month -- an empty 'this month' "
                        + "would be a worse answer, which is why the label was the fix")
                .isEqualByComparingTo("900.00");
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

        // Bug 06: this used to be a hardcoded LocalDate.of(2026, 7, 15) and passed only because
        // the notification was evaluated against "the newest month with data" rather than the
        // calendar month. A budget is a MONTHLY ALLOWANCE, so the spend that counts against it has
        // to fall in the month the allowance belongs to -- which is what BudgetService has always
        // done and what this now asserts. Dated inside the current month in the user's own zone,
        // so the test states the rule rather than a date that happened to work when it was written.
        LocalDate thisMonth = LocalDate.now(com.finora.util.UserZone.DEFAULT).withDayOfMonth(15);
        Transaction dining = txn(new BigDecimal("6000.00"), Transaction.Type.EXPENSE, thisMonth, Transaction.ReconciliationStatus.OK);
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
    @DisplayName("D-25 PR3-A: below the floor, the health score is unavailable rather than misleadingly low")
    void summarize_healthScoreIsUnavailable_belowTheTransactionFloor() {
        // 9 transactions -- one short of MIN_TRANSACTIONS_FOR_HEALTH_SCORE (10). Deliberately an
        // expense-heavy mix that would otherwise score low (cashFlowScore hits 0% the moment one
        // month's expenses exceed income), so passing this test for the RIGHT reason (unavailable,
        // not "happens to still be a low but computed score") actually matters.
        LocalDate july = LocalDate.of(2026, 7, 15);
        List<Transaction> nineTxns = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nineTxns.add(txn(new BigDecimal("500.00"), Transaction.Type.EXPENSE, july, Transaction.ReconciliationStatus.OK));
        }
        when(transactionRepository.findByUserId(userId)).thenReturn(nineTxns);

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.healthScoreAvailable()).isFalse();
        assertThat(summary.healthScore()).isNull();
        assertThat(summary.healthLabel()).isNull();
        assertThat(summary.healthBreakdown()).isEmpty();
        assertThat(summary.healthScoreTransactionCount()).isEqualTo(9);
        assertThat(summary.healthScoreMinTransactions()).isEqualTo(10);
    }

    @Test
    @DisplayName("D-25 PR3-A: exactly the floor's transaction count computes a real score")
    void summarize_healthScoreIsAvailable_atExactlyTheTransactionFloor() {
        LocalDate july = LocalDate.of(2026, 7, 15);
        List<Transaction> tenTxns = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tenTxns.add(txn(new BigDecimal("500.00"), Transaction.Type.INCOME, july, Transaction.ReconciliationStatus.OK));
        }
        when(transactionRepository.findByUserId(userId)).thenReturn(tenTxns);

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.healthScoreAvailable()).isTrue();
        assertThat(summary.healthScore()).isNotNull();
        assertThat(summary.healthLabel()).isNotNull();
        assertThat(summary.healthBreakdown()).isNotEmpty();
        assertThat(summary.healthScoreTransactionCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("a statement window straddling a calendar-month boundary doesn't tank Spend Consistency / Cash Flow Stability")
    void summarize_partialBoundaryMonthsDontDistortConsistencyOrCashFlow() {
        // Reproduces a real user's dashboard: one continuous ~30-day statement window (Jun 26 --
        // Jul 26) that YearMonth-buckets into a near-empty 5-day June sliver and a near-full 26-day
        // July bucket. Steady, identical daily spend across the whole window -- if the two buckets
        // were compared as if both were full months, the sliver's tiny total vs. July's much larger
        // total reads as wildly inconsistent (this is exactly how the bug produced an 8% Spend
        // Consistency score for genuinely steady spending). With the partial boundary months
        // excluded from the comparison, only 0 full months remain, so both scores fall through to
        // their neutral thin-data defaults (100) rather than judge on the lopsided partial buckets.
        List<Transaction> txns = new java.util.ArrayList<>();
        LocalDate date = LocalDate.of(2026, 6, 26);
        LocalDate end = LocalDate.of(2026, 7, 26);
        while (!date.isAfter(end)) {
            txns.add(txn(new BigDecimal("1000.00"), Transaction.Type.EXPENSE, date, Transaction.ReconciliationStatus.OK));
            txns.add(txn(new BigDecimal("1200.00"), Transaction.Type.INCOME, date, Transaction.ReconciliationStatus.OK));
            date = date.plusDays(1);
        }
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.healthBreakdown().get("Spend Consistency")).isEqualTo(100.0);
        assertThat(summary.healthBreakdown().get("Cash Flow Stability")).isEqualTo(100.0);
    }

    @Test
    @DisplayName("a full prior month is still compared normally once the current month is complete")
    void summarize_fullPriorMonthStillDetectsRealInconsistency() {
        // Once there's at least one genuinely comparable full month alongside a full current month,
        // the fix must not suppress a real difference in spending between them.
        List<Transaction> txns = new java.util.ArrayList<>();
        for (LocalDate d = LocalDate.of(2026, 6, 1); !d.isAfter(LocalDate.of(2026, 6, 30)); d = d.plusDays(1)) {
            txns.add(txn(new BigDecimal("100.00"), Transaction.Type.EXPENSE, d, Transaction.ReconciliationStatus.OK));
            txns.add(txn(new BigDecimal("200.00"), Transaction.Type.INCOME, d, Transaction.ReconciliationStatus.OK));
        }
        for (LocalDate d = LocalDate.of(2026, 7, 1); !d.isAfter(LocalDate.of(2026, 7, 31)); d = d.plusDays(1)) {
            txns.add(txn(new BigDecimal("1000.00"), Transaction.Type.EXPENSE, d, Transaction.ReconciliationStatus.OK));
            txns.add(txn(new BigDecimal("200.00"), Transaction.Type.INCOME, d, Transaction.ReconciliationStatus.OK));
        }
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        // June (~3000) vs July (~31000) are both FULL months -- a real, large swing that the fix
        // must still surface, not smooth over.
        assertThat(summary.healthBreakdown().get("Spend Consistency")).isLessThan(50.0);
    }

    @Test
    @DisplayName("a single PARTIAL calendar month (all data in one bucket) scores neutral, not on the partial data")
    void summarize_onePartialMonth_scoresNeutral() {
        // All of the user's history falls inside one calendar month, but doesn't span it fully
        // (starts on the 10th, not the 1st) -- e.g. someone who signed up mid-month. There's no full
        // month to compare against, so both scores must fall through to the neutral default rather
        // than judge on a fragment.
        List<Transaction> txns = new java.util.ArrayList<>();
        for (LocalDate d = LocalDate.of(2026, 7, 10); !d.isAfter(LocalDate.of(2026, 7, 20)); d = d.plusDays(1)) {
            txns.add(txn(new BigDecimal("5000.00"), Transaction.Type.EXPENSE, d, Transaction.ReconciliationStatus.OK));
        }
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.healthBreakdown().get("Spend Consistency")).isEqualTo(100.0);
        assertThat(summary.healthBreakdown().get("Cash Flow Stability")).isEqualTo(100.0);
    }

    @Test
    @DisplayName("a single FULL calendar month gets a real (non-neutral) cash-flow reading, and a neutral consistency reading")
    void summarize_oneFullMonth_cashFlowIsRealButConsistencyIsNeutral() {
        // One data point can't say anything about MONTH-TO-MONTH consistency (that needs at least
        // two full months to compare), so consistencyScore stays at the neutral default. But cash
        // flow for that one full month is a directly known fact, not a guess -- if the whole month's
        // income covered its expenses, that's real, verified information, not a thin-data artifact.
        // So unlike consistency, cashFlowScore for a single full month is NOT forced to neutral --
        // it reports what actually happened.
        List<Transaction> txns = new java.util.ArrayList<>();
        for (LocalDate d = LocalDate.of(2026, 7, 1); !d.isAfter(LocalDate.of(2026, 7, 31)); d = d.plusDays(1)) {
            txns.add(txn(new BigDecimal("100.00"), Transaction.Type.EXPENSE, d, Transaction.ReconciliationStatus.OK));
            txns.add(txn(new BigDecimal("200.00"), Transaction.Type.INCOME, d, Transaction.ReconciliationStatus.OK));
        }
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.healthBreakdown().get("Spend Consistency")).isEqualTo(100.0);
        assertThat(summary.healthBreakdown().get("Cash Flow Stability")).isEqualTo(100.0); // income > expense all July
    }

    @Test
    @DisplayName("one full month + one partial (in-progress) month scores only off the full month")
    void summarize_oneFullMonthPlusCurrentPartialMonth_scoresOnlyTheFullMonth() {
        // June is a complete calendar month. July is still in progress (only through the 15th) --
        // a real scenario for any user who imports mid-month. July must be excluded, and June alone
        // (one full month) must drive the score, exactly as the single-full-month case above.
        List<Transaction> txns = new java.util.ArrayList<>();
        for (LocalDate d = LocalDate.of(2026, 6, 1); !d.isAfter(LocalDate.of(2026, 6, 30)); d = d.plusDays(1)) {
            txns.add(txn(new BigDecimal("100.00"), Transaction.Type.EXPENSE, d, Transaction.ReconciliationStatus.OK));
            txns.add(txn(new BigDecimal("200.00"), Transaction.Type.INCOME, d, Transaction.ReconciliationStatus.OK));
        }
        // July: heavy overspend, but only 15 days in -- must not count.
        for (LocalDate d = LocalDate.of(2026, 7, 1); !d.isAfter(LocalDate.of(2026, 7, 15)); d = d.plusDays(1)) {
            txns.add(txn(new BigDecimal("5000.00"), Transaction.Type.EXPENSE, d, Transaction.ReconciliationStatus.OK));
        }
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        // If July's partial overspend leaked in, consistency/cash-flow would collapse. It doesn't:
        // June alone (steady, income > expense) still reads as a single full, positive month.
        assertThat(summary.healthBreakdown().get("Spend Consistency")).isEqualTo(100.0);
        assertThat(summary.healthBreakdown().get("Cash Flow Stability")).isEqualTo(100.0);
    }

    @Test
    @DisplayName("three full months plus a current partial month score off the three full months")
    void summarize_threeFullMonthsPlusCurrentPartialMonth_scoresOffTheThreeFullMonths() {
        // April/May/June are complete; July is in progress. One of the three full months (May) has
        // a real, large expense spike relative to the other two -- if July's partial data (or the
        // spike) were smoothed away by the fix rather than correctly included/excluded, this
        // wouldn't show up as an inconsistency. It must.
        List<Transaction> txns = new java.util.ArrayList<>();
        addFullMonthOfDailyExpense(txns, 2026, 4, "100.00", "200.00");
        addFullMonthOfDailyExpense(txns, 2026, 5, "1000.00", "200.00"); // spike month
        addFullMonthOfDailyExpense(txns, 2026, 6, "100.00", "200.00");
        for (LocalDate d = LocalDate.of(2026, 7, 1); !d.isAfter(LocalDate.of(2026, 7, 10)); d = d.plusDays(1)) {
            txns.add(txn(new BigDecimal("50.00"), Transaction.Type.EXPENSE, d, Transaction.ReconciliationStatus.OK));
        }
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        // The spike in May, among 3 full months, is real and must be reflected -- not smoothed away.
        assertThat(summary.healthBreakdown().get("Spend Consistency")).isLessThan(60.0);
        // April and June had income > expense; May (the spike) did not -- 2 of 3 full months
        // positive. July's partial data must not be counted as a 4th month.
        assertThat(summary.healthBreakdown().get("Cash Flow Stability")).isCloseTo(66.7, org.assertj.core.data.Offset.offset(0.5));
    }

    private void addFullMonthOfDailyExpense(List<Transaction> txns, int year, int month, String dailyExpense, String dailyIncome) {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            txns.add(txn(new BigDecimal(dailyExpense), Transaction.Type.EXPENSE, d, Transaction.ReconciliationStatus.OK));
            txns.add(txn(new BigDecimal(dailyIncome), Transaction.Type.INCOME, d, Transaction.ReconciliationStatus.OK));
        }
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
