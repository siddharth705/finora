package com.finora.service;

import com.finora.dto.ReportDto;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportServiceTest {

    private TransactionRepository transactionRepository;
    private AccountRepository accountRepository;
    private ReportService reportService;
    private final UUID userId = UUID.randomUUID();
    private Account liveAccount;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        accountRepository = mock(AccountRepository.class);
        CategoryRepository categoryRepository = mock(CategoryRepository.class);
        when(categoryRepository.findByUserId(any())).thenReturn(List.of());
        TransactionGraphService transactionGraphService = mock(TransactionGraphService.class);
        when(transactionGraphService.ccPaymentFromTransactionIds(any())).thenReturn(Set.of());

        liveAccount = new Account();
        ReflectionTestUtils.setField(liveAccount, "id", UUID.randomUUID());
        liveAccount.setUserId(userId);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(liveAccount));

        reportService = new ReportService(transactionRepository, accountRepository, categoryRepository, transactionGraphService);
    }

    private Transaction txn(BigDecimal amount, Transaction.Type type, Transaction.ReconciliationStatus status) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setAmount(amount);
        t.setTxnType(type);
        t.setTxnDate(LocalDate.of(2026, 7, 15));
        t.setReconciliationStatus(status);
        return t;
    }

    @Test
    void forMonth_excludesRefundStatusIncomeFromTheIncomeTotal() {
        // A REFUND-status transaction is the INCOME leg of a reconciled refund (see
        // ReconciliationService's refund pass) -- money coming back from a return isn't real
        // income and must not inflate this report's income total.
        Transaction salary = txn(new BigDecimal("50000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        Transaction refund = txn(new BigDecimal("999.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.REFUND);

        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(salary, refund));

        ReportDto report = reportService.forMonth(userId, "2026-07");

        assertThat(report.income()).isEqualByComparingTo("50000.00"); // refund's 999 excluded
    }

    @Test
    void forMonth_excludesInvestmentTransferFromTheExpenseTotal_butKeepsItInTheCategoryBreakdown() {
        // Top-line-only exclusion (RefundNetting.excludingInvestmentTransfers): a SIP is not real
        // spend and must not inflate the month's expense total, but it's still a real, budgetable
        // category -- it must not vanish from the report's own category table.
        Transaction groceries = txn(new BigDecimal("2000.00"), Transaction.Type.EXPENSE, Transaction.ReconciliationStatus.OK);
        Transaction sip = txn(new BigDecimal("3000.00"), Transaction.Type.EXPENSE, Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);

        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(groceries, sip));

        ReportDto report = reportService.forMonth(userId, "2026-07");

        assertThat(report.expense()).isEqualByComparingTo("2000.00");
        BigDecimal categoryTotal = report.categories().stream()
                .map(ReportDto.CategoryAmount::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(categoryTotal).isEqualByComparingTo("5000.00");
    }

    @Test
    void forMonth_stillIncludesOrdinaryIncome() {
        Transaction salary = txn(new BigDecimal("50000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);

        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(salary));

        ReportDto report = reportService.forMonth(userId, "2026-07");

        assertThat(report.income()).isEqualByComparingTo("50000.00");
    }

    // --- Deleted-account leak (see DashboardService.summarize for the original fix): a deleted
    // account's transactions deliberately keep deleted_at unset, so forMonth/availableMonths must
    // scope their transaction fetches to exactly the user's live account ids, not just their
    // userId. ---

    @Test
    void forMonth_scopesTransactionFetch_toExactlyTheLiveAccountIds() {
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(transactionRepository.findByUserIdAndReconciliationStatusInAndAccountIdIn(any(), any(), any()))
                .thenReturn(List.of());

        reportService.forMonth(userId, "2026-07");

        verify(transactionRepository).findByUserIdAndTxnDateBetweenAndAccountIdIn(
                eq(userId), any(), any(), eq(List.of(liveAccount.getId())));
        verify(transactionRepository).findByUserIdAndReconciliationStatusInAndAccountIdIn(
                eq(userId), any(), eq(List.of(liveAccount.getId())));
    }

    @Test
    void forMonth_withNoLiveAccounts_shortCircuits_withoutQueryingTransactions() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of());

        ReportDto report = reportService.forMonth(userId, "2026-07");

        assertThat(report.income()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(transactionRepository, never()).findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any());
        verify(transactionRepository, never()).findByUserIdAndReconciliationStatusInAndAccountIdIn(any(), any(), any());
    }

    @Test
    void availableMonths_scopesDateFetch_toExactlyTheLiveAccountIds() {
        when(transactionRepository.findDistinctTransactionDates(eq(userId), any())).thenReturn(List.of());

        reportService.availableMonths(userId);

        verify(transactionRepository).findDistinctTransactionDates(userId, List.of(liveAccount.getId()));
    }

    @Test
    void availableMonths_withNoLiveAccounts_shortCircuits_withoutQueryingTransactions() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of());

        assertThat(reportService.availableMonths(userId)).isEmpty();
        verify(transactionRepository, never()).findDistinctTransactionDates(any(), any());
    }

    /**
     * BH-042. {@code availableMonths} had no test at all, and it was the clearest case of the
     * unbounded-history pattern: load every transaction the user has ever had, map each to a
     * YearMonth, discard all but the distinct values, to fill a dropdown.
     *
     * <p>What this pins is the projection the service still owns after the query moved to the
     * database -- many dates collapsing to one month, and the ordering the dropdown depends on.
     */
    @Test
    void availableMonths_collapsesDatesToDistinctSortedMonths() {
        when(transactionRepository.findDistinctTransactionDates(eq(userId), any())).thenReturn(List.of(
                LocalDate.of(2026, 6, 30),
                LocalDate.of(2026, 5, 4),
                LocalDate.of(2026, 6, 11),   // same month as the first
                LocalDate.of(2026, 7, 1)));

        assertThat(reportService.availableMonths(userId))
                .containsExactly("2026-05", "2026-06", "2026-07");
    }

    @Test
    void availableMonths_isEmptyForAUserWithNoTransactions() {
        when(transactionRepository.findDistinctTransactionDates(eq(userId), any())).thenReturn(List.of());

        assertThat(reportService.availableMonths(userId)).isEmpty();
    }
}
