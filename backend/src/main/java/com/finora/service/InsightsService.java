package com.finora.service;

import com.finora.dto.InsightsDto;
import com.finora.entity.Budget;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.repository.BudgetRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;
import com.finora.repository.UserRepository;
import com.finora.util.UserZone;
import java.util.Locale;

/**
 * Statistical port of the browser prototype's buildInsights() — month-over-month category
 * deltas and top-merchant spend. This is rule/statistics-based, NOT an LLM call; see the
 * README's Known Gaps for where a real AI layer would plug in instead. The one genuinely
 * actionable "recommendation" this produces (suggesting a budget for a category trending up
 * with none set) is deliberately narrow and grounded in real numbers already computed above --
 * not a fabricated general "you should spend less" suggestion, which would be advice this
 * service has no real basis to give.
 */
@Service
public class InsightsService {

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final BudgetRepository budgetRepository;
    /** Only so "this month" can be resolved against the user's own calendar -- see build(). Every
     *  other service that reports on a period already takes this dependency for the same reason
     *  (DashboardService, BudgetService, GoalService, AnalyticsService, NetWorthService); this one
     *  performed no calendar resolution at all. */
    private final UserRepository userRepository;

    public InsightsService(TransactionRepository transactionRepository, CategoryRepository categoryRepository,
                            BudgetRepository budgetRepository, UserRepository userRepository) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.budgetRepository = budgetRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public InsightsDto build(UUID userId) {
        List<Transaction> txns = transactionRepository.findByUserId(userId).stream()
                .filter(t -> t.getIsDuplicateOf() == null && !t.isTransfer() && t.getTxnType() == Transaction.Type.EXPENSE)
                .toList();

        if (txns.isEmpty()) {
            return new InsightsDto(List.of("Upload or add transactions to see spending insights."), List.of());
        }

        Map<UUID, Category> categoriesById = categoryRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(Category::getId, c -> c));

        List<String> months = txns.stream().map(t -> YearMonth.from(t.getTxnDate()).toString()).distinct().sorted().toList();
        // The newest month the user actually has data for. Still the right REPORTING period: an
        // empty "this month" would be a worse answer than last month's real figures, and for
        // someone importing statements in arrears -- the normal pattern for this product -- the
        // newest data month is routinely not the current one.
        //
        // Bug fix: what was wrong was the LABEL, not the choice. Sentences below asserted "this
        // month" over whichever month this happens to be, so a user who had not yet transacted in
        // August read July's figures as August's, and the "versus your recent average" window
        // silently shifted with it. reportingMonthIsCurrent resolves the real calendar month in
        // the USER's timezone -- this service was the only one reporting on a period that never
        // did -- and the wording follows it.
        String currentMonth = months.get(months.size() - 1);
        boolean reportingMonthIsCurrent =
                currentMonth.equals(YearMonth.now(UserZone.forUser(userRepository, userId)).toString());
        String periodLabel = reportingMonthIsCurrent ? "this month" : "in " + currentMonth;
        List<String> priorMonths = months.size() > 1
                ? months.subList(Math.max(0, months.size() - 4), months.size() - 1)
                : List.of();

        Map<String, BigDecimal> currentByCat = groupByCategory(txns, currentMonth, categoriesById);
        Map<String, BigDecimal> priorByCat = new HashMap<>();
        for (String m : priorMonths) {
            groupByCategory(txns, m, categoriesById).forEach((k, v) -> priorByCat.merge(k, v, BigDecimal::add));
        }
        int priorCount = Math.max(priorMonths.size(), 1);

        List<InsightsDto.CategoryMover> movers = currentByCat.entrySet().stream().map(e -> {
            BigDecimal current = e.getValue();
            BigDecimal priorTotal = priorByCat.getOrDefault(e.getKey(), BigDecimal.ZERO);
            BigDecimal priorAvg = priorTotal.divide(BigDecimal.valueOf(priorCount), 4, RoundingMode.HALF_UP);
            Double pct = priorAvg.compareTo(BigDecimal.ZERO) > 0
                    ? current.subtract(priorAvg).divide(priorAvg, 6, RoundingMode.HALF_UP).doubleValue() * 100
                    : null;
            return new InsightsDto.CategoryMover(e.getKey(), current, priorAvg, pct);
        }).sorted((a, b) -> Double.compare(Math.abs(b.pctChange() == null ? 0 : b.pctChange()), Math.abs(a.pctChange() == null ? 0 : a.pctChange())))
          .toList();

        List<String> sentences = new ArrayList<>();
        BigDecimal total = currentByCat.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        // Locale.ENGLISH on every String.format below. Without it, "%,.0f" groups and separates
        // according to Locale.getDefault() on the SERVER, so a JVM defaulting to de_DE rendered
        // the rupee figure 1,23,456 as 123.456 -- inconsistent with every other number in the app,
        // and varying by deployment host rather than by anything about the user. CsvParser already
        // pins Locale.ENGLISH on the parsing side with exactly this reasoning ("doesn't depend on
        // the JVM's default locale"); it just was never applied to output.
        sentences.add(String.format(Locale.ENGLISH, "In %s, total spend was \u20b9%,.0f across %d categories.",
                currentMonth, total, currentByCat.size()));

        currentByCat.entrySet().stream().max(Map.Entry.comparingByValue())
                .ifPresent(top -> sentences.add(String.format(Locale.ENGLISH,
                        "%s was your biggest category at \u20b9%,.0f.", top.getKey(), top.getValue())));

        // The one real "recommendation" in this list: a category trending up with no budget set
        // for it yet is exactly the situation Budgets exists to help with, and it's grounded in
        // the mover data computed above rather than invented. Placed right after the headline
        // sentences (not appended at the end) so it still survives the Dashboard's condensed
        // 3-sentence preview, not just the full Insights page.
        Set<UUID> categoryIdsWithBudget = budgetRepository.findByUserId(userId).stream()
                .map(Budget::getCategoryId).collect(Collectors.toSet());
        Map<String, UUID> categoryIdByName = categoriesById.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getValue().getName(), Map.Entry::getKey, (a, b) -> a));
        movers.stream()
                .filter(m -> m.pctChange() != null && m.pctChange() >= 15)
                .filter(m -> {
                    UUID categoryId = categoryIdByName.get(m.category());
                    return categoryId == null || !categoryIdsWithBudget.contains(categoryId);
                })
                .findFirst()
                .ifPresent(m -> sentences.add(String.format(
                        "Consider setting a budget for %s — it's trending up and doesn't have one yet.", m.category())));

        movers.stream().filter(m -> m.pctChange() != null && Math.abs(m.pctChange()) >= 15).limit(3).forEach(m -> {
            String dir = m.pctChange() > 0 ? "more" : "less";
            sentences.add(String.format(Locale.ENGLISH,
                    "%s spend was %.0f%% %s than your recent average (\u20b9%,.0f vs usual \u20b9%,.0f).",
                    m.category(), Math.abs(m.pctChange()), dir, m.current(), m.priorAverage()));
        });

        // Bug fix: falling back from merchant to description still isn't guaranteed non-null --
        // description is optional on transaction creation (TransactionDto.CreateRequest has no
        // @NotBlank on it), so a manually-entered transaction with neither field set used to hit
        // the same "element cannot be mapped to a null key" NullPointerException from
        // Collectors.groupingBy as the DashboardService/BudgetService category-grouping bugs.
        Map<String, BigDecimal> merchantTotals = txns.stream()
                .filter(t -> YearMonth.from(t.getTxnDate()).toString().equals(currentMonth))
                .collect(Collectors.groupingBy(
                        t -> Optional.ofNullable(t.getMerchant()).filter(s -> !s.isBlank())
                                .or(() -> Optional.ofNullable(t.getDescription()).filter(s -> !s.isBlank()))
                                .orElse("Unknown"),
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));
        merchantTotals.entrySet().stream().max(Map.Entry.comparingByValue())
                .ifPresent(top -> sentences.add(String.format(Locale.ENGLISH,
                        "Your top merchant %s was \"%s\" at \u20b9%,.0f.", periodLabel, top.getKey(), top.getValue())));

        return new InsightsDto(sentences, movers);
    }

    private Map<String, BigDecimal> groupByCategory(List<Transaction> txns, String month, Map<UUID, Category> categoriesById) {
        return txns.stream()
                .filter(t -> YearMonth.from(t.getTxnDate()).toString().equals(month))
                .collect(Collectors.groupingBy(
                        t -> categoriesById.containsKey(t.getCategoryId()) ? categoriesById.get(t.getCategoryId()).getName() : "Uncategorized",
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));
    }
}
