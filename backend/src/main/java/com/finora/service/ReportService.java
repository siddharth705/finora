package com.finora.service;

import com.finora.dto.ReportDto;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ReportService {

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionGraphService transactionGraphService;

    public ReportService(TransactionRepository transactionRepository, CategoryRepository categoryRepository,
                          TransactionGraphService transactionGraphService) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.transactionGraphService = transactionGraphService;
    }

    @Transactional(readOnly = true)
    public ReportDto forMonth(UUID userId, String monthStr) {
        YearMonth month = YearMonth.parse(monthStr);
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();

        Map<UUID, Category> categoriesById = categoryRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(Category::getId, c -> c));

        // BH-005. This was a hand-written copy of DashboardService's filter, and both copies were
        // one-sided: the refund's INCOME leg was dropped and the EXPENSE it reverses was left
        // counted in full, so a refunded purchase reported as a pure loss. RefundNetting owns the
        // rule for both readers now -- it drops the income leg AND nets the refund off the
        // purchase, which is the only treatment also correct for a partial refund.
        //
        // The refund legs come from a SEPARATE query, not from the month window. A refund
        // routinely arrives in a later month than its purchase, so the rows that offset this
        // month's expenses are frequently outside the range this month was queried with -- netting
        // against only the in-window ones would leave every cross-month refund uncorrected, which
        // is most of them.
        RefundNetting refunds = RefundNetting.from(transactionRepository.findByUserIdAndReconciliationStatusIn(
                userId, java.util.List.of(Transaction.ReconciliationStatus.REFUND, Transaction.ReconciliationStatus.REVERSAL)));
        List<Transaction> monthTxns = transactionRepository.findByUserIdAndTxnDateBetween(userId, from, to);
        List<Transaction> txns = RefundNetting.reportable(
                monthTxns, transactionGraphService.ccPaymentFromTransactionIds(monthTxns));

        BigDecimal income = txns.stream().filter(t -> t.getTxnType() == Transaction.Type.INCOME)
                .map(refunds::reportableAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expense = txns.stream().filter(t -> t.getTxnType() == Transaction.Type.EXPENSE)
                .map(refunds::reportableAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, BigDecimal> byCategory = txns.stream()
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE)
                .collect(Collectors.groupingBy(
                        t -> categoriesById.containsKey(t.getCategoryId()) ? categoriesById.get(t.getCategoryId()).getName() : "Uncategorized",
                        Collectors.reducing(BigDecimal.ZERO, refunds::reportableAmount, BigDecimal::add)));

        List<ReportDto.CategoryAmount> categories = byCategory.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .map(e -> new ReportDto.CategoryAmount(e.getKey(), e.getValue()))
                .toList();

        return new ReportDto(monthStr, income, expense, categories);
    }

    /**
     * Which months have at least one transaction — backs the Reports page's month dropdown.
     *
     * <p>BH-042: this used to load the user's ENTIRE transaction history as JPA entities, map each
     * row to a {@code YearMonth}, and throw away everything but the distinct values. The result is
     * a dozen strings; the query was proportional to the whole ledger, on a page load. Distinct
     * dates come from the database now, and only the month projection happens here.
     *
     * <p>Distinct DATES rather than distinct months, because {@code date_trunc} has no portable
     * JPQL form and pushing a native query down for this would trade one small cost for a
     * dialect lock-in. The row count is bounded by days-with-activity, not by transactions.
     */
    @Transactional(readOnly = true)
    public List<String> availableMonths(UUID userId) {
        return transactionRepository.findDistinctTransactionDates(userId).stream()
                .map(date -> YearMonth.from(date).toString())
                .distinct().sorted().toList();
    }
}
