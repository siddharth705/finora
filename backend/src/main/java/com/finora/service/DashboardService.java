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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Server-side port of the browser prototype's KPI / health-score / insights logic —
 * kept algorithmically identical so numbers don't shift when the frontend switches
 * from the static prototype to calling this API.
 */
@Service
public class DashboardService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final BudgetRepository budgetRepository;
    private final UserRepository userRepository;
    private final com.finora.repository.StatementImportRepository statementImportRepository;

    public DashboardService(AccountRepository accountRepository, TransactionRepository transactionRepository,
                             CategoryRepository categoryRepository, BudgetRepository budgetRepository,
                             UserRepository userRepository,
                             com.finora.repository.StatementImportRepository statementImportRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.budgetRepository = budgetRepository;
        this.userRepository = userRepository;
        this.statementImportRepository = statementImportRepository;
    }

    @Transactional(readOnly = true)
    public DashboardSummaryDto summarize(UUID userId) {
        List<Account> accounts = accountRepository.findByUserId(userId);
        // BH-005. This filter used to be written out here and again, identically, in
        // ReportService -- and both copies were one-sided. A REFUND-status row is the INCOME leg of
        // a reconciled refund, so dropping it is right; the EXPENSE it reverses was left counted in
        // full, so a fully refunded purchase reported as a pure loss and Account.balance (which
        // applied both legs) disagreed with this screen by the refunded amount.
        //
        // RefundNetting owns both halves now: it drops the income leg and nets the refund off the
        // purchase, which is the only treatment correct for a PARTIAL refund too. Every amount read
        // out of `active` below goes through reportableAmount for that reason -- summing
        // Transaction::getAmount directly is what the bug was.
        List<Transaction> all = transactionRepository.findByUserId(userId);
        RefundNetting refunds = RefundNetting.from(all);
        List<Transaction> active = RefundNetting.reportable(all);
        Map<UUID, Category> categoriesById = categoryRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(Category::getId, c -> c));

        BigDecimal liquid = sumBalances(accounts, Account.Type.SAVINGS).add(sumBalances(accounts, Account.Type.WALLET));
        BigDecimal investments = sumBalances(accounts, Account.Type.INVESTMENT);
        BigDecimal liabilities = sumBalances(accounts, Account.Type.CREDIT_CARD);
        BigDecimal totalAssets = liquid.add(investments);
        BigDecimal netWorth = netWorthOf(accounts);

        // Bug 05. `currentMonth` was the newest month the user had DATA for, and every figure
        // derived from it was rendered as "this month" / "vs last month". A user who had not yet
        // transacted in August therefore read July's income, expenses, savings rate and category
        // breakdown as August's. InsightsService hit this and fixed it; this service -- the one
        // that shows the answer as headline KPIs -- never did.
        //
        // Reporting on the newest month with data is still right (see ReportingPeriod: an empty
        // "this month" is a worse answer for a product built around importing in arrears). What
        // was wrong is that nothing said which month it was, so the response now carries it and
        // the client labels the period instead of asserting one.
        ZoneId zone = com.finora.util.UserZone.forUser(userRepository, userId);
        List<String> months = active.stream().map(t -> YearMonth.from(t.getTxnDate()).toString())
                .distinct().sorted().toList();
        com.finora.util.ReportingPeriod period = com.finora.util.ReportingPeriod.resolve(months, zone);
        String currentMonth = period.month();
        // A CALENDAR step back, not "the next month down the list of months with data" -- a user
        // with a gap in their history had two non-adjacent months compared and labelled
        // "vs last month". See ReportingPeriod.priorMonth.
        String priorMonth = period.priorMonth();

        BigDecimal incomeCur = sumForMonth(active, currentMonth, Transaction.Type.INCOME, refunds);
        BigDecimal expenseCur = sumForMonth(active, currentMonth, Transaction.Type.EXPENSE, refunds);
        BigDecimal incomePrior = sumForMonth(active, priorMonth, Transaction.Type.INCOME, refunds);
        BigDecimal expensePrior = sumForMonth(active, priorMonth, Transaction.Type.EXPENSE, refunds);
        BigDecimal netCur = incomeCur.subtract(expenseCur);
        BigDecimal netPrior = incomePrior.subtract(expensePrior);

        // Comparison gating. `priorMonth` is a CALENDAR step back from `currentMonth` (see
        // ReportingPeriod.priorMonth) -- it doesn't ask whether that calendar month is actually a
        // genuine, separate slice of the user's history, or just the ragged far edge of the same
        // continuous statement window `currentMonth` itself came from. A user whose entire imported
        // history is one ~30-day window straddling Jun 26 -- Jul 26 gets a "priorMonth" of June that
        // is really 5 leftover days of the SAME import, not last month's real spending -- dividing
        // pct()'s delta against that near-empty sliver is exactly how a genuine steady spender saw
        // a reported "928.8%" income swing. isReliablePriorMonth requires prior to be both a FULL
        // calendar month (not the ragged edge of the overall imported date range) and to carry
        // enough of its own transactions that one or two stray rows can't dominate the ratio --
        // below either bar, the comparison isn't wrong, it's just not a comparison, and pct() below
        // says so with null (which MetricCard already renders as a muted "--" rather than a number).
        boolean priorMonthReliable = isReliablePriorMonth(active, priorMonth);

        BigDecimal savingsRate = incomeCur.compareTo(BigDecimal.ZERO) > 0
                ? netCur.divide(incomeCur, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        var health = computeHealthScore(accounts, active, months, liquid, refunds);

        Map<String, BigDecimal> spendByCategory = active.stream()
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE
                        && Objects.equals(YearMonth.from(t.getTxnDate()).toString(), currentMonth))
                .collect(Collectors.groupingBy(
                        t -> categoriesById.getOrDefault(t.getCategoryId(), unknownCategory()).getName(),
                        Collectors.reducing(BigDecimal.ZERO, refunds::reportableAmount, BigDecimal::add)));

        // "Other" (CategorizationService/CategoryRules's literal fallback name when nothing matched
        // a rule, keyword, or learned pattern) is a REAL, resolvable category -- not the same thing
        // as a transaction with no category at all (that's unknownCategory()'s "Uncategorized"
        // above). Neither name alone is the right signal for "does this transaction's category
        // actually tell the user anything": a transaction can be genuinely, confidently categorized
        // AS "Other" if the user's own confidence threshold accepts it. `needsCategoryReview` is
        // already the exact per-transaction answer to that question -- CategorizationService sets
        // it only for a default("Other")-sourced guess that ALSO misses the user's own
        // autoApplyConfidenceThreshold (see that method's own doc comment) -- so this reuses it
        // instead of re-deriving a parallel, less precise "is the name Other or Uncategorized"
        // heuristic that would either over- or under-count relative to what Ledger already shows
        // the user as a "needs review" badge on the exact same transactions.
        List<Transaction> currentMonthExpenses = active.stream()
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE
                        && Objects.equals(YearMonth.from(t.getTxnDate()).toString(), currentMonth))
                .toList();
        BigDecimal categoryReviewSpend = currentMonthExpenses.stream()
                .filter(Transaction::isNeedsCategoryReview)
                .map(refunds::reportableAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        int categoryReviewTransactionCount = (int) currentMonthExpenses.stream()
                .filter(Transaction::isNeedsCategoryReview).count();
        BigDecimal totalMonthlySpend = currentMonthExpenses.stream()
                .map(refunds::reportableAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        double categoryReviewSpendPct = totalMonthlySpend.compareTo(BigDecimal.ZERO) > 0
                ? categoryReviewSpend.divide(totalMonthlySpend, 6, RoundingMode.HALF_UP).doubleValue() * 100
                : 0;
        boolean categoryReviewWarning = categoryReviewSpendPct >= CATEGORY_REVIEW_SPEND_WARNING_THRESHOLD_PCT;

        // Same current-month EXPENSE figures as spendByCategory above, just keyed by category id
        // (rather than display name) so they can be joined against Budget.categoryId below --
        // mirrors BudgetService.listForUser()'s own spend-by-category computation, scoped to this
        // method's already-deduped/already-transfer-and-refund-excluded `active` list.
        //
        // Bug fix: Transaction.categoryId is nullable (an uncategorized expense has none), but
        // Collectors.groupingBy throws NullPointerException ("element cannot be mapped to a null
        // key") the moment it sees one -- this used to take down the whole dashboard for any user
        // with even a single uncategorized expense this month. Filtered out here rather than
        // given a sentinel key: buildNotifications only ever looks this map up via
        // Budget.getCategoryId(), which is never null, so an uncategorized transaction could never
        // match a budget anyway -- there's nothing to lose by excluding it from this particular map
        // (spendByCategory above, keyed by display name via unknownCategory(), still accounts for
        // it under "Uncategorized").
        //
        // Bug 06. This keys the budget-exceeded notifications below, and it used to be filtered on
        // the REPORTING month -- the newest month with data. A monthly budget resets on a calendar
        // boundary regardless of when the user last imported, so that made the dashboard warn
        // "Groceries has reached your monthly budget" from LAST month's spend while the Budgets
        // page, which has always used YearMonth.now(userZone), correctly showed this month at 0%.
        // Two screens in one app disagreeing about the same number.
        //
        // period.calendarMonth(), deliberately, even though every other figure on this response
        // uses the reporting month: an allowance is the one thing that must not be evaluated
        // against a period it does not belong to. BudgetService.listForUser is the definition this
        // now agrees with.
        Map<UUID, BigDecimal> spendByCategoryId = active.stream()
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE
                        && t.getCategoryId() != null
                        && Objects.equals(YearMonth.from(t.getTxnDate()).toString(), period.calendarMonth()))
                .collect(Collectors.groupingBy(Transaction::getCategoryId,
                        Collectors.reducing(BigDecimal.ZERO, refunds::reportableAmount, BigDecimal::add)));
        List<Budget> budgets = budgetRepository.findByUserId(userId);
        Optional<User> user = userRepository.findById(userId);
        BigDecimal lowBalanceThreshold = user.map(User::getLowBalanceThreshold).orElse(null);
        // User.timezone exists specifically so "today" (due-date countdowns below) reflects the
        // user's own day boundary, not the server's -- it was persisted and editable via Settings
        // but never actually consulted by any date computation anywhere in the codebase until now.
        // safeZoneId guards against a malformed value reaching here (timezone has no format
        // validation on the settings-update path) turning into an uncaught DateTimeException that
        // would 500 the whole dashboard for that one user.
        // `zone` was resolved once already, above, to decide the reporting period -- reusing it
        // rather than re-reading User.timezone keeps the due-date countdowns below on the same
        // clock as the period this response reports on.
        List<String> notifications = buildNotifications(accounts, budgets, spendByCategoryId, categoriesById, lowBalanceThreshold, zone);

        // A dashboard built from `months.size()` (distinct calendar months touched by any
        // transaction) < LIMITED_HISTORY_MONTH_FLOOR full months of data presents trend deltas and
        // a health score with the same confidence as a mature account, even though both are
        // dominated by thin-data artifacts at that point (see the partial-boundary-month fix to
        // Spend Consistency/Cash Flow Stability, and the pct() deltas below dividing against an
        // effectively-empty prior month). Surfacing the raw counts here lets the client show why,
        // instead of a user having to guess "why does my score look bad" on their own.
        int statementCount = (int) statementImportRepository.countByUserId(userId);
        boolean limitedHistory = months.size() < LIMITED_HISTORY_MONTH_FLOOR;

        return new DashboardSummaryDto(
                liquid, totalAssets, liabilities, netWorth,
                incomeCur, expenseCur, netCur, savingsRate,
                pct(incomeCur, incomePrior, priorMonthReliable), pct(expenseCur, expensePrior, priorMonthReliable),
                pct(netCur, netPrior, priorMonthReliable),
                health.score(), health.label(), health.breakdown(),
                health.available(), health.transactionCount(), health.minTransactions(),
                spendByCategory, notifications,
                // Which month everything above actually describes. Without these the client had no
                // choice but to guess, and it guessed "this month" -- see Bug 05.
                period.month(), period.isCurrent(),
                limitedHistory, months.size(), LIMITED_HISTORY_MONTH_FLOOR, statementCount, accounts.size(),
                categoryReviewWarning, categoryReviewSpendPct, categoryReviewSpend,
                categoryReviewTransactionCount, CATEGORY_REVIEW_SPEND_WARNING_THRESHOLD_PCT
        );
    }

    // 3 calendar months is the threshold most of this method's own thin-data guards already
    // converge on independently: the health score's Spend Consistency/Cash Flow Stability need at
    // least 2 FULL months to say anything (a 3rd guarantees at least 2 full months even when the
    // most recent one is still in progress), and the trend percentages below are most likely to
    // divide against a near-empty denominator with fewer months of history than that.
    static final int LIMITED_HISTORY_MONTH_FLOOR = 3;

    // 20% of a month's spend needing a better category is the point at which "some transactions
    // are generically categorized" becomes "categorization is broadly unreliable this month" --
    // low enough to catch the real case that motivated this (81% in "Other"), high enough that a
    // handful of genuinely ambiguous transactions in an otherwise well-categorized month doesn't
    // nag every user on every visit.
    static final double CATEGORY_REVIEW_SPEND_WARNING_THRESHOLD_PCT = 20.0;

    private Category unknownCategory() {
        Category c = new Category(); c.setName("Uncategorized"); return c;
    }

    /** Delegates to {@link com.finora.util.UserZone} -- one of four hand-copied implementations,
     *  see that class for why they were consolidated. */
    private ZoneId safeZoneId(String timezone) {
        return com.finora.util.UserZone.of(timezone);
    }

    /** Same single definition {@link com.finora.accounts.AccountBalanceConvention} gives
     *  NetWorthService -- this service used to write the assets-minus-liabilities rule out a third
     *  time by hand. */
    private BigDecimal netWorthOf(List<Account> accounts) {
        return accounts.stream()
                .map(a -> com.finora.accounts.AccountBalanceConvention
                        .netWorthContribution(a.getAccountType(), a.getBalance()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumBalances(List<Account> accounts, Account.Type type) {
        return accounts.stream().filter(a -> a.getAccountType() == type)
                .map(Account::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** BH-005: sums the REPORTABLE amount, not the raw one -- a refunded purchase contributes what
     *  it actually cost. See {@link RefundNetting}. */
    private BigDecimal sumForMonth(List<Transaction> txns, String month, Transaction.Type type,
                                    RefundNetting refunds) {
        if (month == null) return BigDecimal.ZERO;
        return txns.stream()
                .filter(t -> t.getTxnType() == type && YearMonth.from(t.getTxnDate()).toString().equals(month))
                .map(refunds::reportableAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Double pct(BigDecimal current, BigDecimal prior, boolean priorReliable) {
        if (!priorReliable || prior == null || prior.compareTo(BigDecimal.ZERO) == 0) return null;
        return current.subtract(prior).divide(prior.abs(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
    }

    // A calendar month at least MIN_TRANSACTIONS_FOR_DELTA_COMPARISON transactions is required to
    // be trusted as a delta's denominator -- low enough that a real, if quiet, month still gets a
    // real percentage, high enough that one or two stray rows can't single-handedly produce a
    // triple-digit swing the way they did for the bug this constant exists to prevent.
    static final int MIN_TRANSACTIONS_FOR_DELTA_COMPARISON = 3;

    /** True when `month` is trustworthy as a delta's denominator: a FULL calendar month (not the
     *  ragged edge of the overall imported date range -- see the comment at this method's call
     *  site) carrying at least {@link #MIN_TRANSACTIONS_FOR_DELTA_COMPARISON} transactions of its
     *  own. */
    private boolean isReliablePriorMonth(List<Transaction> active, String month) {
        if (month == null) return false;
        LocalDate earliestTxnDate = active.stream().map(Transaction::getTxnDate).min(Comparator.naturalOrder()).orElse(null);
        LocalDate latestTxnDate = active.stream().map(Transaction::getTxnDate).max(Comparator.naturalOrder()).orElse(null);
        if (earliestTxnDate == null || latestTxnDate == null) return false;
        boolean isEarliestBucket = month.equals(YearMonth.from(earliestTxnDate).toString());
        boolean isLatestBucket = month.equals(YearMonth.from(latestTxnDate).toString());
        if (isEarliestBucket && earliestTxnDate.getDayOfMonth() != 1) return false;
        if (isLatestBucket && latestTxnDate.getDayOfMonth() != YearMonth.from(latestTxnDate).lengthOfMonth()) return false;

        long monthTxnCount = active.stream()
                .filter(t -> YearMonth.from(t.getTxnDate()).toString().equals(month)).count();
        return monthTxnCount >= MIN_TRANSACTIONS_FOR_DELTA_COMPARISON;
    }

    // D-25 PR3-A. Owner's choice among the proposal's own options (transaction count vs. time
    // span vs. either): a flat transaction count, checked against `active` -- the same
    // RefundNetting-reportable list every other figure in this method is already computed from,
    // so "10 transactions" means the same 10 a user would see on the Ledger, not some other count.
    static final int MIN_TRANSACTIONS_FOR_HEALTH_SCORE = 10;

    private record HealthResult(Integer score, String label, Map<String, Double> breakdown,
                                 boolean available, int transactionCount, int minTransactions) {}

    /** Weighted composite: savings rate 25%, debt utilization 20%, emergency fund 25%,
     *  spend consistency 15%, cash flow stability 15% — identical weighting to the prototype.
     *
     *  Below {@link #MIN_TRANSACTIONS_FOR_HEALTH_SCORE}, returns unavailable rather than a
     *  computed-but-misleading score: a thin-data user can land under 40 ("Needs Attention") by
     *  construction (e.g. {@code cashFlowScore} hits 0% the moment one month's expenses exceed
     *  income, which is routine for someone who just imported one statement before any income
     *  shows up in it) -- not a true reading of their finances, just an artifact of too little
     *  data. D-19's own {@code isEmpty} gate already hides this section entirely at zero
     *  transactions; this covers the gap between zero and "enough," which that gate never did. */
    private HealthResult computeHealthScore(List<Account> accounts, List<Transaction> active,
                                             List<String> months, BigDecimal liquid,
                                             RefundNetting refunds) {
        if (active.size() < MIN_TRANSACTIONS_FOR_HEALTH_SCORE) {
            return new HealthResult(null, null, Map.of(), false, active.size(), MIN_TRANSACTIONS_FOR_HEALTH_SCORE);
        }
        List<String> last6 = months.size() > 6 ? months.subList(months.size() - 6, months.size()) : months;

        // BH-005: the same netting the headline KPIs use. The score's savings-rate and cash-flow
        // components are built from these two series, so an overstated expense month moved the
        // score as well as the tiles.
        List<BigDecimal> monthlyExpense = last6.stream().map(m -> sumForMonth(active, m, Transaction.Type.EXPENSE, refunds)).toList();
        List<BigDecimal> monthlyIncome = last6.stream().map(m -> sumForMonth(active, m, Transaction.Type.INCOME, refunds)).toList();

        // `months` buckets by exact calendar month, so a user whose entire imported history is one
        // continuous ~30-day statement window that happens to straddle a month boundary (e.g. Jun
        // 26 -- Jul 26) gets TWO buckets: a near-empty sliver (5 days of Jun) and the bulk (26 days
        // of Jul). Compared as if they were both full months, that sliver reads as wildly
        // inconsistent spending / negative cash flow -- not because the user's finances are
        // unstable, but because the bucket boundary chopped one window in half. Same class of bug
        // this method's own doc comment warns about: a thin-data artifact, not a true reading.
        //
        // Fix: only compare FULL calendar months for consistency/cash-flow -- drop the first bucket
        // if the data doesn't start on the 1st, and the last bucket if it doesn't run through
        // month-end. If that leaves nothing comparable, fall through to the existing thin-data
        // defaults below (cv=0 / cashFlowScore below) rather than judge on partial data.
        LocalDate earliestTxnDate = active.stream().map(Transaction::getTxnDate).min(Comparator.naturalOrder()).orElse(null);
        LocalDate latestTxnDate = active.stream().map(Transaction::getTxnDate).max(Comparator.naturalOrder()).orElse(null);
        List<BigDecimal> fullMonthlyExpense = new ArrayList<>();
        List<BigDecimal> fullMonthlyIncome = new ArrayList<>();
        for (int i = 0; i < last6.size(); i++) {
            String m = last6.get(i);
            boolean partialStart = i == 0 && earliestTxnDate != null
                    && m.equals(YearMonth.from(earliestTxnDate).toString()) && earliestTxnDate.getDayOfMonth() != 1;
            boolean partialEnd = i == last6.size() - 1 && latestTxnDate != null
                    && m.equals(YearMonth.from(latestTxnDate).toString())
                    && latestTxnDate.getDayOfMonth() != YearMonth.from(latestTxnDate).lengthOfMonth();
            if (partialStart || partialEnd) continue;
            fullMonthlyExpense.add(monthlyExpense.get(i));
            fullMonthlyIncome.add(monthlyIncome.get(i));
        }

        BigDecimal avgExpense = average(monthlyExpense);
        BigDecimal incomeCur = last6.isEmpty() ? BigDecimal.ZERO : monthlyIncome.get(monthlyIncome.size() - 1);
        BigDecimal expenseCur = last6.isEmpty() ? BigDecimal.ZERO : monthlyExpense.get(monthlyExpense.size() - 1);
        BigDecimal netCur = incomeCur.subtract(expenseCur);
        double savingsRate = incomeCur.compareTo(BigDecimal.ZERO) > 0
                ? netCur.divide(incomeCur, 4, RoundingMode.HALF_UP).doubleValue() * 100 : 0;
        double savingsRateScore = Math.clamp(savingsRate, 0, 30) / 30 * 100;

        List<Account> cards = accounts.stream().filter(a -> a.getAccountType() == Account.Type.CREDIT_CARD
                && a.getCreditLimit() != null && a.getCreditLimit().compareTo(BigDecimal.ZERO) > 0).toList();
        double avgUtil = cards.isEmpty() ? 0 : cards.stream()
                .mapToDouble(c -> c.getBalance().divide(c.getCreditLimit(), 6, RoundingMode.HALF_UP).doubleValue())
                .average().orElse(0);
        double debtScore = Math.max(0, 100 - avgUtil * 100);

        double monthsCovered = avgExpense.compareTo(BigDecimal.ZERO) > 0
                ? liquid.divide(avgExpense, 6, RoundingMode.HALF_UP).doubleValue()
                : (liquid.compareTo(BigDecimal.ZERO) > 0 ? 6 : 0);
        double emergencyScore = Math.min(monthsCovered / 6, 1) * 100;

        double fullMean = fullMonthlyExpense.isEmpty() ? 0
                : fullMonthlyExpense.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        double variance = fullMonthlyExpense.size() > 1
                ? fullMonthlyExpense.stream().mapToDouble(v -> Math.pow(v.doubleValue() - fullMean, 2)).average().orElse(0)
                : 0;
        double cv = fullMean > 0 ? Math.sqrt(variance) / fullMean : 0;
        double consistencyScore = Math.max(0, 100 - Math.min(cv * 100, 100));

        long positiveMonths = countNonNegativeMonths(fullMonthlyIncome, fullMonthlyExpense);
        double cashFlowScore = fullMonthlyExpense.isEmpty() ? 100 : (double) positiveMonths / fullMonthlyExpense.size() * 100;

        int overall = (int) Math.round(savingsRateScore * 0.25 + debtScore * 0.20 + emergencyScore * 0.25
                + consistencyScore * 0.15 + cashFlowScore * 0.15);
        String label = overall >= 80 ? "Excellent" : overall >= 60 ? "Good" : overall >= 40 ? "Fair" : "Needs Attention";

        // "Debt Utilization" (not "Debt Score") used to label this -- but debtScore is INVERTED
        // utilization (100 - avgUtil*100, so 100 = no debt / best), and a user with zero credit
        // cards got debtScore=100 by design (see avgUtil above). Labelled "Debt Utilization: 100%"
        // that reads as "maxed out"; it means the opposite. Every other entry in this map is
        // already a "higher = healthier" score under a name that doesn't fight that reading --
        // this was the one where the underlying real-world quantity (utilization) and the
        // displayed number run in opposite directions, so it's the one that needed renaming.
        Map<String, Double> breakdown = new LinkedHashMap<>();
        breakdown.put("Savings Rate", savingsRateScore);
        breakdown.put("Debt Score", debtScore);
        breakdown.put("Emergency Fund", emergencyScore);
        breakdown.put("Spend Consistency", consistencyScore);
        breakdown.put("Cash Flow Stability", cashFlowScore);

        return new HealthResult(overall, label, breakdown, true, active.size(), MIN_TRANSACTIONS_FOR_HEALTH_SCORE);
    }

    private long countNonNegativeMonths(List<BigDecimal> income, List<BigDecimal> expense) {
        long count = 0;
        for (int i = 0; i < income.size(); i++) {
            if (income.get(i).subtract(expense.get(i)).compareTo(BigDecimal.ZERO) >= 0) count++;
        }
        return count;
    }

    private BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private List<String> buildNotifications(List<Account> accounts, List<Budget> budgets,
                                             Map<UUID, BigDecimal> spendByCategoryId,
                                             Map<UUID, Category> categoriesById,
                                             BigDecimal lowBalanceThreshold, ZoneId zone) {
        List<String> notifs = new ArrayList<>();
        LocalDate today = LocalDate.now(zone);
        for (Account c : accounts) {
            if (c.getAccountType() == Account.Type.CREDIT_CARD && c.getDueDate() != null) {
                long days = ChronoUnit.DAYS.between(today, c.getDueDate());
                if (days >= 0 && days <= 7) {
                    notifs.add(c.getName() + " payment of " + c.getBalance() + " is due in " + days + " day(s).");
                }
            }
            // Low-balance warning: liquid (SAVINGS/WALLET) accounts only -- CREDIT_CARD/INVESTMENT
            // balances aren't "spendable cash" in the same sense, and a low CREDIT_CARD balance is
            // good news, not a warning. lowBalanceThreshold is null only if the user row couldn't be
            // found (shouldn't happen for an authenticated caller), in which case skip the check
            // rather than risk a NPE from the compareTo below.
            if ((c.getAccountType() == Account.Type.SAVINGS || c.getAccountType() == Account.Type.WALLET)
                    && lowBalanceThreshold != null && c.getBalance().compareTo(lowBalanceThreshold) < 0) {
                notifs.add(c.getName() + " balance of " + c.getBalance()
                        + " is below your low-balance threshold of " + lowBalanceThreshold + ".");
            }
        }
        // Budget-threshold warning: current-month spend has reached or passed the category's
        // monthly limit. Mirrors BudgetService.listForUser()'s own budget/spend join.
        for (Budget b : budgets) {
            if (b.getMonthlyLimit() == null || b.getMonthlyLimit().compareTo(BigDecimal.ZERO) <= 0) continue;
            BigDecimal spent = spendByCategoryId.getOrDefault(b.getCategoryId(), BigDecimal.ZERO);
            if (spent.compareTo(b.getMonthlyLimit()) >= 0) {
                Category cat = categoriesById.get(b.getCategoryId());
                String categoryName = cat != null ? cat.getName() : "Unknown";
                notifs.add(categoryName + " spending of " + spent + " has reached your monthly budget of "
                        + b.getMonthlyLimit() + ".");
            }
        }
        return notifs;
    }
}
