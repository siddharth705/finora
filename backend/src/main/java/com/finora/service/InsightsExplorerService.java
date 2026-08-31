package com.finora.service;

import com.finora.dto.InsightsExplorerDto;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Assembles the Insight Explorer's trace (docs/proposals/reconciliation-evolution-roadmap-
 * proposal.md, Part 9) for one user. Reuses {@link InsightsService#pipeline} for the part that
 * decides which transactions count -- the part with a real bug history (BH-005) -- and adds only
 * the per-number, per-transaction breakdown that {@link InsightsService#build} doesn't need to
 * keep around once it has its sums.
 */
@Service
public class InsightsExplorerService {

    private final InsightsService insightsService;
    private final UserRepository userRepository;

    public InsightsExplorerService(InsightsService insightsService, UserRepository userRepository) {
        this.insightsService = insightsService;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public Optional<InsightsExplorerDto.Trace> trace(UUID userId) {
        if (!userRepository.existsById(userId)) {
            return Optional.empty();
        }

        Optional<InsightsService.Pipeline> maybePipeline = insightsService.pipeline(userId);
        if (maybePipeline.isEmpty()) {
            return Optional.of(new InsightsExplorerDto.Trace(userId, null, false, null, null, null));
        }
        InsightsService.Pipeline pipeline = maybePipeline.get();

        List<Transaction> currentMonthTxns = pipeline.txns().stream()
                .filter(t -> YearMonth.from(t.getTxnDate()).toString().equals(pipeline.currentMonth()))
                .toList();

        return Optional.of(new InsightsExplorerDto.Trace(
                userId,
                pipeline.currentMonth(),
                pipeline.reportingMonthIsCurrent(),
                totalSpend(currentMonthTxns, pipeline.categoriesById(), pipeline.refunds()),
                topCategory(currentMonthTxns, pipeline.categoriesById(), pipeline.refunds()),
                topMerchant(currentMonthTxns, pipeline.refunds())));
    }

    /** {@code currentMonthTxns} always has at least one row -- {@code currentMonth} is by
     *  construction the month of the newest transaction in the pipeline's reportable set. */
    private InsightsExplorerDto.TotalSpend totalSpend(List<Transaction> currentMonthTxns,
                                                        Map<UUID, Category> categoriesById, RefundNetting refunds) {
        List<InsightsExplorerDto.TracedTransaction> traced = currentMonthTxns.stream()
                .map(t -> traceOf(t, refunds)).toList();
        BigDecimal total = sumReportable(currentMonthTxns, refunds);
        long categoryCount = currentMonthTxns.stream().map(t -> categoryNameOf(t, categoriesById)).distinct().count();
        return new InsightsExplorerDto.TotalSpend(total, (int) categoryCount, traced);
    }

    private InsightsExplorerDto.TopCategory topCategory(List<Transaction> currentMonthTxns,
                                                          Map<UUID, Category> categoriesById, RefundNetting refunds) {
        Map<String, List<Transaction>> byCategory = currentMonthTxns.stream()
                .collect(Collectors.groupingBy(t -> categoryNameOf(t, categoriesById)));
        Map.Entry<String, List<Transaction>> top = byCategory.entrySet().stream()
                .max(Comparator.comparing(e -> sumReportable(e.getValue(), refunds)))
                .orElseThrow();
        return new InsightsExplorerDto.TopCategory(top.getKey(), sumReportable(top.getValue(), refunds),
                top.getValue().stream().map(t -> traceOf(t, refunds)).toList());
    }

    private InsightsExplorerDto.TopMerchant topMerchant(List<Transaction> currentMonthTxns, RefundNetting refunds) {
        Map<String, List<Transaction>> byMerchant = currentMonthTxns.stream()
                .collect(Collectors.groupingBy(this::merchantNameOf));
        Map.Entry<String, List<Transaction>> top = byMerchant.entrySet().stream()
                .max(Comparator.comparing(e -> sumReportable(e.getValue(), refunds)))
                .orElseThrow();
        return new InsightsExplorerDto.TopMerchant(top.getKey(), sumReportable(top.getValue(), refunds),
                top.getValue().stream().map(t -> traceOf(t, refunds)).toList());
    }

    private BigDecimal sumReportable(List<Transaction> txns, RefundNetting refunds) {
        return txns.stream().map(refunds::reportableAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String categoryNameOf(Transaction t, Map<UUID, Category> categoriesById) {
        Category category = categoriesById.get(t.getCategoryId());
        return category != null ? category.getName() : "Uncategorized";
    }

    /** Mirrors {@link InsightsService}'s own merchant-total grouping exactly, including the
     *  description fallback -- see that method's comment for why neither field is guaranteed. */
    private String merchantNameOf(Transaction t) {
        return Optional.ofNullable(t.getMerchant()).filter(s -> !s.isBlank())
                .or(() -> Optional.ofNullable(t.getDescription()).filter(s -> !s.isBlank()))
                .orElse("Unknown");
    }

    private InsightsExplorerDto.TracedTransaction traceOf(Transaction t, RefundNetting refunds) {
        return new InsightsExplorerDto.TracedTransaction(t.getId(), t.getDescription(), t.getAmount(),
                refunds.reportableAmount(t), t.getTxnDate());
    }
}
