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

    public DashboardService(AccountRepository accountRepository, TransactionRepository transactionRepository,
                             CategoryRepository categoryRepository, BudgetRepository budgetRepository,
                             UserRepository userRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.budgetRepository = budgetRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public DashboardSummaryDto summarize(UUID userId) {
        List<Account> accounts = accountRepository.findByUserId(userId);
        // REFUND-status transactions are the INCOME leg of a reconciled refund (see
        // ReconciliationService's refund pass) -- excluded here the same way TRANSFER/DUPLICATE
        // already are, so a refunded purchase's money coming back doesn't get counted as real
        // income (which would also silently inflate incomeCur, health score monthly income, and
        // savingsRate, since all three are derived from this same `active` list below).
        List<Transaction> active = transactionRepository.findByUserId(userId).stream()
                .filter(t -> t.getIsDuplicateOf() == null && !t.isTransfer()
                        && t.getReconciliationStatus() != Transaction.ReconciliationStatus.REFUND)
                .toList();
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

        BigDecimal incomeCur = sumForMonth(active, currentMonth, Transaction.Type.INCOME);
        BigDecimal expenseCur = sumForMonth(active, currentMonth, Transaction.Type.EXPENSE);
        BigDecimal incomePrior = sumForMonth(active, priorMonth, Transaction.Type.INCOME);
        BigDecimal expensePrior = sumForMonth(active, priorMonth, Transaction.Type.EXPENSE);
        BigDecimal netCur = incomeCur.subtract(expenseCur);
        BigDecimal netPrior = incomePrior.subtract(expensePrior);

        BigDecimal savingsRate = incomeCur.compareTo(BigDecimal.ZERO) > 0
                ? netCur.divide(incomeCur, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        var health = computeHealthScore(accounts, active, months, liquid);

        Map<String, BigDecimal> spendByCategory = active.stream()
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE
                        && Objects.equals(YearMonth.from(t.getTxnDate()).toString(), currentMonth))
                .collect(Collectors.groupingBy(
                        t -> categoriesById.getOrDefault(t.getCategoryId(), unknownCategory()).getName(),
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));

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
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));
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

        return new DashboardSummaryDto(
                liquid, totalAssets, liabilities, netWorth,
                incomeCur, expenseCur, netCur, savingsRate,
                pct(incomeCur, incomePrior), pct(expenseCur, expensePrior), pct(netCur, netPrior),
                health.score(), health.label(), health.breakdown(),
                spendByCategory, notifications,
                // Which month everything above actually describes. Without these the client had no
                // choice but to guess, and it guessed "this month" -- see Bug 05.
                period.month(), period.isCurrent()
        );
    }

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

    private BigDecimal sumForMonth(List<Transaction> txns, String month, Transaction.Type type) {
        if (month == null) return BigDecimal.ZERO;
        return txns.stream()
                .filter(t -> t.getTxnType() == type && YearMonth.from(t.getTxnDate()).toString().equals(month))
                .map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Double pct(BigDecimal current, BigDecimal prior) {
        if (prior == null || prior.compareTo(BigDecimal.ZERO) == 0) return null;
        return current.subtract(prior).divide(prior.abs(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
    }

    private record HealthResult(int score, String label, Map<String, Double> breakdown) {}

    /** Weighted composite: savings rate 25%, debt utilization 20%, emergency fund 25%,
     *  spend consistency 15%, cash flow stability 15% — identical weighting to the prototype. */
    private HealthResult computeHealthScore(List<Account> accounts, List<Transaction> active,
                                             List<String> months, BigDecimal liquid) {
        List<String> last6 = months.size() > 6 ? months.subList(months.size() - 6, months.size()) : months;

        List<BigDecimal> monthlyExpense = last6.stream().map(m -> sumForMonth(active, m, Transaction.Type.EXPENSE)).toList();
        List<BigDecimal> monthlyIncome = last6.stream().map(m -> sumForMonth(active, m, Transaction.Type.INCOME)).toList();

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

        double mean = avgExpense.doubleValue();
        double variance = monthlyExpense.size() > 1
                ? monthlyExpense.stream().mapToDouble(v -> Math.pow(v.doubleValue() - mean, 2)).average().orElse(0)
                : 0;
        double cv = mean > 0 ? Math.sqrt(variance) / mean : 0;
        double consistencyScore = Math.max(0, 100 - Math.min(cv * 100, 100));

        long positiveMonths = countNonNegativeMonths(monthlyIncome, monthlyExpense);
        double cashFlowScore = last6.isEmpty() ? 0 : (double) positiveMonths / last6.size() * 100;

        int overall = (int) Math.round(savingsRateScore * 0.25 + debtScore * 0.20 + emergencyScore * 0.25
                + consistencyScore * 0.15 + cashFlowScore * 0.15);
        String label = overall >= 80 ? "Excellent" : overall >= 60 ? "Good" : overall >= 40 ? "Fair" : "Needs Attention";

        Map<String, Double> breakdown = new LinkedHashMap<>();
        breakdown.put("Savings Rate", savingsRateScore);
        breakdown.put("Debt Utilization", debtScore);
        breakdown.put("Emergency Fund", emergencyScore);
        breakdown.put("Spend Consistency", consistencyScore);
        breakdown.put("Cash Flow Stability", cashFlowScore);

        return new HealthResult(overall, label, breakdown);
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
