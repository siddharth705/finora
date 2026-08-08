package com.finora.service;

import com.finora.dto.ReportDto;
import com.finora.entity.Transaction;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportServiceTest {

    private TransactionRepository transactionRepository;
    private ReportService reportService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        CategoryRepository categoryRepository = mock(CategoryRepository.class);
        when(categoryRepository.findByUserId(any())).thenReturn(List.of());
        reportService = new ReportService(transactionRepository, categoryRepository);
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

        when(transactionRepository.findByUserIdAndTxnDateBetween(any(), any(), any()))
                .thenReturn(List.of(salary, refund));

        ReportDto report = reportService.forMonth(userId, "2026-07");

        assertThat(report.income()).isEqualByComparingTo("50000.00"); // refund's 999 excluded
    }

    @Test
    void forMonth_stillIncludesOrdinaryIncome() {
        Transaction salary = txn(new BigDecimal("50000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);

        when(transactionRepository.findByUserIdAndTxnDateBetween(any(), any(), any()))
                .thenReturn(List.of(salary));

        ReportDto report = reportService.forMonth(userId, "2026-07");

        assertThat(report.income()).isEqualByComparingTo("50000.00");
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
        when(transactionRepository.findDistinctTransactionDates(userId)).thenReturn(List.of(
                LocalDate.of(2026, 6, 30),
                LocalDate.of(2026, 5, 4),
                LocalDate.of(2026, 6, 11),   // same month as the first
                LocalDate.of(2026, 7, 1)));

        assertThat(reportService.availableMonths(userId))
                .containsExactly("2026-05", "2026-06", "2026-07");
    }

    @Test
    void availableMonths_isEmptyForAUserWithNoTransactions() {
        when(transactionRepository.findDistinctTransactionDates(userId)).thenReturn(List.of());

        assertThat(reportService.availableMonths(userId)).isEmpty();
    }
}
