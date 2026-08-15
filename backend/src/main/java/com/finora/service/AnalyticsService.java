package com.finora.service;

import com.finora.dto.AnalyticsDto;
import com.finora.entity.MerchantCategoryLearning;
import com.finora.entity.MerchantLearningAudit;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantLearningAuditRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.util.UserZone;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-only aggregations over already-persisted merchant/transaction/learning data -- never
 * mutates anything (see docs/financial-intelligence-engine-spec.md's Analytics Engine
 * responsibility table: "Any mutation" is explicitly listed as NOT this class's job).
 *
 * Originally implemented exactly the 3 views §6.5 described a UI for (top merchants ranked list,
 * spend trend line chart, category confidence horizontal bar) -- "only what has a real UI need"
 * (spec Milestone D). The Financial Intelligence Workspace's Analytics module (see
 * docs/team-message-financial-intelligence-workspace-kickoff.md) is that real UI need for three
 * more: topCategories, importStatistics, learningGrowth. Rule usage isn't among them -- it
 * depends on execution tracking CategoryRule doesn't have yet (Workspace Rule Management task),
 * not built speculatively here either.
 *
 * The spend-facing methods (topMerchants, merchantTrend, topCategories) filter out the same set
 * DashboardService/ReportService already exclude from totals: duplicates, transfers, and
 * REFUND-status income -- spend analytics shouldn't count money that didn't actually leave the
 * user's pocket as spend, any more than the dashboard should.
 */
@Service
public class AnalyticsService {

    private static final int TOP_MERCHANTS_LIMIT = 10;
    private static final int TREND_MONTHS = 6;

    private final TransactionRepository transactionRepository;
    private final MerchantRepository merchantRepository;
    private final MerchantCategoryLearningRepository learningRepository;
    private final MerchantLearningAuditRepository learningAuditRepository;
    private final CategoryRepository categoryRepository;
    private final StatementImportRepository statementImportRepository;
    private final ConfidenceEngine confidenceEngine;
    // Added so this service can answer "what month is it for THIS user" -- it previously had no
    // ZoneId access at all, which is why merchantTrend() and learningGrowth() were computing
    // months in the server's zone and in hardcoded UTC respectively. See UserZone.
    private final UserRepository userRepository;

    public AnalyticsService(TransactionRepository transactionRepository, MerchantRepository merchantRepository,
                             MerchantCategoryLearningRepository learningRepository,
                             MerchantLearningAuditRepository learningAuditRepository,
                             CategoryRepository categoryRepository, StatementImportRepository statementImportRepository,
                             ConfidenceEngine confidenceEngine, UserRepository userRepository) {
        this.transactionRepository = transactionRepository;
        this.merchantRepository = merchantRepository;
        this.learningRepository = learningRepository;
        this.learningAuditRepository = learningAuditRepository;
        this.categoryRepository = categoryRepository;
        this.statementImportRepository = statementImportRepository;
        this.confidenceEngine = confidenceEngine;
        this.userRepository = userRepository;
    }

    /** Top merchants by total EXPENSE spend for the given month (all-time if month is null). */
    public List<AnalyticsDto.TopMerchant> topMerchants(UUID userId, YearMonth month) {
        Map<UUID, String> merchantNames = merchantNamesFor(userId);

        RefundNetting refunds = refundsFor(userId);
        Map<UUID, List<Transaction>> byMerchant = activeExpenseTransactions(userId, month).stream()
                .filter(t -> t.getMerchantId() != null)
                .collect(Collectors.groupingBy(Transaction::getMerchantId));

        return byMerchant.entrySet().stream()
                .map(e -> new AnalyticsDto.TopMerchant(
                        e.getKey(),
                        merchantNames.getOrDefault(e.getKey(), "Unknown"),
                        e.getValue().stream().map(refunds::reportableAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                        e.getValue().size()))
                .sorted(Comparator.comparing(AnalyticsDto.TopMerchant::totalSpend).reversed())
                .limit(TOP_MERCHANTS_LIMIT)
                .toList();
    }

    /** Total merchant-attributed EXPENSE spend per month, for the TREND_MONTHS trailing the given
     *  anchor month (current month if null). One point per month including zero-spend months, so
     *  the line chart's x-axis doesn't silently skip a month with no purchases. */
    public List<AnalyticsDto.TrendPoint> merchantTrend(UUID userId, YearMonth anchorMonth) {
        // Bug fix: this was a bare YearMonth.now(), i.e. the SERVER's zone, not the user's. The
        // anchor is optional on the live path (AdminUserAnalyticsController passes
        // parseMonth(month) for an optional query parameter), so the null branch is reached in
        // normal use -- and for the first 5.5 hours of an IST month it names the previous month,
        // silently shifting the whole 6-month window. The same class of bug was already fixed in
        // NetWorthService, GoalService and BudgetService; this service was missed because it had
        // no ZoneId access to fix it with. See UserZone.
        YearMonth end = anchorMonth != null ? anchorMonth : YearMonth.now(UserZone.forUser(userRepository, userId));
        YearMonth start = end.minusMonths(TREND_MONTHS - 1L);

        Map<YearMonth, BigDecimal> byMonth = new HashMap<>();
        RefundNetting refunds = refundsFor(userId);
        // BH-042: this used to call activeExpenseTransactions(userId, null) -- the ALL-TIME
        // overload -- loading the user's entire expense history via findByUserId, and only
        // discarded everything outside [start, end] afterward, in memory. start/end above are
        // already the exact window this method needs, so querying them directly is the fix; the
        // refund netting above is unaffected -- refundsFor() already runs its own small, always-
        // unbounded-by-design query (see its javadoc) that isn't part of the fetch being narrowed
        // here.
        for (Transaction t : activeExpenseTransactions(userId, start.atDay(1), end.atEndOfMonth())) {
            if (t.getMerchantId() == null) continue;
            YearMonth m = YearMonth.from(t.getTxnDate());
            if (m.isBefore(start) || m.isAfter(end)) continue;
            byMonth.merge(m, refunds.reportableAmount(t), BigDecimal::add);
        }

        List<AnalyticsDto.TrendPoint> points = new ArrayList<>();
        for (YearMonth m = start; !m.isAfter(end); m = m.plusMonths(1)) {
            points.add(new AnalyticsDto.TrendPoint(m.toString(), byMonth.getOrDefault(m, BigDecimal.ZERO)));
        }
        return points;
    }

    /** One point per category, averaging confidence across every merchant whose CURRENT top
     *  category is that one -- "how confident is the engine, on average, about merchants filed
     *  under Category X." A merchant with no learned distribution yet has no top category and
     *  contributes to nothing here. */
    public List<AnalyticsDto.CategoryConfidencePoint> categoryConfidence(UUID userId) {
        Map<UUID, String> categoryNames = new HashMap<>();
        categoryRepository.findByUserId(userId).forEach(c -> categoryNames.put(c.getId(), c.getName()));

        // One bulk query grouped in-memory, not one findByUserIdAndMerchantId call per merchant
        // -- same N+1 discipline as ReconciliationService's duplicate-detection fix earlier.
        Map<UUID, List<MerchantCategoryLearning>> pairsByMerchant = learningRepository.findByUserId(userId).stream()
                .collect(Collectors.groupingBy(MerchantCategoryLearning::getMerchantId));

        Map<UUID, List<Integer>> confidencesByCategory = new HashMap<>();
        for (List<MerchantCategoryLearning> pairs : pairsByMerchant.values()) {
            MerchantCategoryLearning top = confidenceEngine.topCategory(pairs);
            if (top == null) continue;
            confidencesByCategory.computeIfAbsent(top.getCategoryId(), k -> new ArrayList<>()).add(top.getConfidence());
        }

        return confidencesByCategory.entrySet().stream()
                .map(e -> new AnalyticsDto.CategoryConfidencePoint(
                        categoryNames.getOrDefault(e.getKey(), "Unknown"),
                        (int) Math.round(e.getValue().stream().mapToInt(Integer::intValue).average().orElse(0)),
                        e.getValue().size()))
                .sorted(Comparator.comparingInt(AnalyticsDto.CategoryConfidencePoint::avgConfidence).reversed())
                .toList();
    }

    /** Same shape and exclusion rules as topMerchants(), grouped by category instead --
     *  Workspace Analytics' "Top Categories" view. */
    public List<AnalyticsDto.TopCategory> topCategories(UUID userId, YearMonth month) {
        Map<UUID, String> categoryNames = new HashMap<>();
        categoryRepository.findByUserId(userId).forEach(c -> categoryNames.put(c.getId(), c.getName()));

        RefundNetting refunds = refundsFor(userId);
        Map<UUID, List<Transaction>> byCategory = activeExpenseTransactions(userId, month).stream()
                .filter(t -> t.getCategoryId() != null)
                .collect(Collectors.groupingBy(Transaction::getCategoryId));

        return byCategory.entrySet().stream()
                .map(e -> new AnalyticsDto.TopCategory(
                        e.getKey(),
                        categoryNames.getOrDefault(e.getKey(), "Uncategorized"),
                        e.getValue().stream().map(refunds::reportableAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                        e.getValue().size()))
                .sorted(Comparator.comparing(AnalyticsDto.TopCategory::totalSpend).reversed())
                .limit(TOP_MERCHANTS_LIMIT)
                .toList();
    }

    /** Aggregated over StatementImport -- no new table. lastImportedAt is null for a user who's
     *  never imported anything, not epoch-zero or some other silent stand-in. */
    public AnalyticsDto.ImportStatistics importStatistics(UUID userId) {
        List<StatementImport> imports = statementImportRepository.findByUserIdOrderByImportedAtDesc(userId);
        int totalTransactionsImported = imports.stream().mapToInt(StatementImport::getTransactionsImported).sum();
        int totalTransactionsSkipped = imports.stream().mapToInt(StatementImport::getTransactionsSkipped).sum();
        var lastImportedAt = imports.isEmpty() ? null : imports.get(0).getImportedAt(); // already ordered desc
        return new AnalyticsDto.ImportStatistics(imports.size(), totalTransactionsImported, totalTransactionsSkipped, lastImportedAt);
    }

    /** LEARNED vs CORRECTED counts per month, oldest first -- same x-axis convention as
     *  merchantTrend(). Unlike merchantTrend()/topMerchants(), this isn't capped to a trailing
     *  window: learning history is small (one row per confirmation, not per transaction) and a
     *  user's whole learning timeline is exactly what "growth" means here, not just recent months. */
    public List<AnalyticsDto.LearningGrowthPoint> learningGrowth(UUID userId) {
        List<MerchantLearningAudit> entries = learningAuditRepository.findByUserId(userId);
        if (entries.isEmpty()) return List.of();

        // Bug fix: months were bucketed in hardcoded UTC, so activity in the first 5.5 hours of an
        // IST month landed in the previous month's bucket -- the user sees their own actions
        // attributed to a month they didn't happen in. Same root cause as the merchantTrend fix
        // above: this service had no access to the user's zone. Resolved once here rather than
        // per entry, since every row belongs to the same user.
        ZoneId zone = UserZone.forUser(userRepository, userId);

        Map<YearMonth, long[]> byMonth = new HashMap<>(); // [0]=learned, [1]=corrected
        for (MerchantLearningAudit entry : entries) {
            if (entry.getAction() != MerchantLearningAudit.Action.LEARNED
                    && entry.getAction() != MerchantLearningAudit.Action.CORRECTED) continue;
            YearMonth m = YearMonth.from(entry.getCreatedAt().atZone(zone));
            long[] counts = byMonth.computeIfAbsent(m, k -> new long[2]);
            if (entry.getAction() == MerchantLearningAudit.Action.LEARNED) counts[0]++;
            else counts[1]++;
        }
        if (byMonth.isEmpty()) return List.of();

        YearMonth start = byMonth.keySet().stream().min(YearMonth::compareTo).orElseThrow();
        YearMonth end = byMonth.keySet().stream().max(YearMonth::compareTo).orElseThrow();

        List<AnalyticsDto.LearningGrowthPoint> points = new ArrayList<>();
        for (YearMonth m = start; !m.isAfter(end); m = m.plusMonths(1)) {
            long[] counts = byMonth.getOrDefault(m, new long[2]);
            points.add(new AnalyticsDto.LearningGrowthPoint(m.toString(), counts[0], counts[1]));
        }
        return points;
    }

    /**
     * BH-005, third copy. The REFUND clause here was doing nothing useful and hiding that: a refund
     * leg is INCOME, so it was already excluded by the EXPENSE filter one line down, while the
     * PURCHASE it reverses stayed counted in full. Every spend figure on this page therefore
     * included money that had come back.
     *
     * <p>{@link RefundNetting} owns the rule; {@link #refundsFor} supplies the amounts. Filtering
     * and amounts have to come from the same place, which is why the netting is returned alongside
     * rather than being applied here.
     */
    private List<Transaction> activeExpenseTransactions(UUID userId, YearMonth month) {
        return RefundNetting.reportable(transactionRepository.findByUserId(userId)).stream()
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE
                        && (month == null || YearMonth.from(t.getTxnDate()).equals(month)))
                .toList();
    }

    /**
     * BH-042: date-bounded twin of {@link #activeExpenseTransactions(UUID, YearMonth)}, used only
     * by {@link #merchantTrend}, which already knows its exact [start, end] window up front -- no
     * need to load anything outside it, unlike the {@code YearMonth}-or-null overload above (kept
     * exactly as it was: {@link #topMerchants} and {@link #topCategories} still call it, and their
     * {@code month == null} "all-time" case is a real, intentional query shape this pass leaves
     * alone).
     */
    private List<Transaction> activeExpenseTransactions(UUID userId, LocalDate from, LocalDate to) {
        return RefundNetting.reportable(transactionRepository.findByUserIdAndTxnDateBetween(userId, from, to)).stream()
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE)
                .toList();
    }

    /** The offsets for the same user, so a refunded purchase contributes what it actually cost. */
    private RefundNetting refundsFor(UUID userId) {
        return RefundNetting.from(transactionRepository.findByUserIdAndReconciliationStatus(
                userId, Transaction.ReconciliationStatus.REFUND));
    }

    private Map<UUID, String> merchantNamesFor(UUID userId) {
        Map<UUID, String> names = new HashMap<>();
        merchantRepository.findByUserId(userId).forEach(m -> names.put(m.getId(), m.getCanonicalName()));
        return names;
    }
}
