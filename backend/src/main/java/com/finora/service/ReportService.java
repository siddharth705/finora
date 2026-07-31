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

    public ReportService(TransactionRepository transactionRepository, CategoryRepository categoryRepository) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
    }

    @Transactional(readOnly = true)
    public ReportDto forMonth(UUID userId, String monthStr) {
        YearMonth month = YearMonth.parse(monthStr);
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();

        Map<UUID, Category> categoriesById = categoryRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(Category::getId, c -> c));

        // Same REFUND exclusion as DashboardService.summarize() -- a REFUND-status transaction
        // is the INCOME leg of a reconciled refund (see ReconciliationService's refund pass), not
        // real income, so it must not inflate this month's `income` total below.
        List<Transaction> txns = transactionRepository.findByUserIdAndTxnDateBetween(userId, from, to).stream()
                .filter(t -> t.getIsDuplicateOf() == null && !t.isTransfer()
                        && t.getReconciliationStatus() != Transaction.ReconciliationStatus.REFUND)
                .toList();

        BigDecimal income = txns.stream().filter(t -> t.getTxnType() == Transaction.Type.INCOME)
                .map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expense = txns.stream().filter(t -> t.getTxnType() == Transaction.Type.EXPENSE)
                .map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, BigDecimal> byCategory = txns.stream()
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE)
                .collect(Collectors.groupingBy(
                        t -> categoriesById.containsKey(t.getCategoryId()) ? categoriesById.get(t.getCategoryId()).getName() : "Uncategorized",
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));

        List<ReportDto.CategoryAmount> categories = byCategory.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .map(e -> new ReportDto.CategoryAmount(e.getKey(), e.getValue()))
                .toList();

        return new ReportDto(monthStr, income, expense, categories);
    }

    /** Which months have at least one transaction — backs the Reports page's month dropdown. */
    @Transactional(readOnly = true)
    public List<String> availableMonths(UUID userId) {
        return transactionRepository.findByUserId(userId).stream()
                .map(t -> YearMonth.from(t.getTxnDate()).toString())
                .distinct().sorted().toList();
    }
}
