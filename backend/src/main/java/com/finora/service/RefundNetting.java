package com.finora.service;

import com.finora.entity.Transaction;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a refunded purchase actually cost, in one place.
 *
 * <h2>The defect this replaces</h2>
 *
 * <p>{@link ReconciliationService}'s refund pass matches an INCOME row to the EXPENSE it reverses
 * and marks <b>only the income leg</b> {@code REFUND}. {@code DashboardService} and
 * {@code ReportService} then each wrote the same one-sided filter: drop {@code REFUND} rows, keep
 * everything else. So the refund left the income total and the purchase stayed in the expense
 * total, and a fully refunded &#8377;500 order was reported as a &#8377;500 loss in a month where
 * nothing had been spent. Every figure derived from those two totals inherited it — savings rate,
 * the month-over-month deltas, category spend, and the health score's monthly-income input — and
 * {@code Account.balance}, which applied both legs and was therefore right, disagreed with the
 * dashboard by exactly the refunded amount.
 *
 * <p>Excluding the income leg is not the error. Dropping the expense leg too would be, for the
 * same reason: a <em>partial</em> refund of &#8377;300 against a &#8377;500 purchase leaves
 * &#8377;200 of genuine spend, and both "count it all" and "count none of it" are wrong. The only
 * treatment correct for both cases is to net the refund off the purchase, which is what this does.
 *
 * <h2>Which period a refund lands in</h2>
 *
 * <p>Against the <b>purchase's</b> period, not the refund's. "This order ultimately cost you
 * &#8377;200" is the question every one of these screens is answering, and it is a property of the
 * purchase. The alternative — crediting the refund to the month it arrived — would show a negative
 * category spend in March for something bought in January, which is an accounting statement none of
 * these screens is making.
 *
 * <p>The visible consequence, stated rather than discovered: a closed month's reported spend can go
 * down when a refund arrives later. That is the honest number changing as the facts change, and it
 * is strictly better than the permanent overstatement it replaces.
 *
 * <h2>Why a class and not a helper method on each caller</h2>
 *
 * <p>Because there were two copies of the one-sided filter and they were identical, which is how
 * they were both wrong. A third reader, {@code BudgetService}, now uses it too (Phase 1 of
 * docs/proposals/reconciliation-evolution-roadmap-proposal.md) — it queries refund/reversal rows
 * across all time via {@code findByUserIdAndReconciliationStatusIn} rather than the calendar-month
 * window it reports spend in, same as {@code ReportService}/{@code AnalyticsService} already did,
 * for the same reason: a refund routinely arrives in a later month than the purchase it reverses.
 */
public final class RefundNetting {

    /** For a caller that has no refunds to apply — every amount is reported as it stands. */
    public static final RefundNetting NONE = new RefundNetting(Map.of());

    /** Expense id -> the total that has been refunded against it. */
    private final Map<UUID, BigDecimal> refundedByExpenseId;

    private RefundNetting(Map<UUID, BigDecimal> refundedByExpenseId) {
        this.refundedByExpenseId = refundedByExpenseId;
    }

    /**
     * Builds the offsets from a set of transactions that includes the refund legs.
     *
     * <p>Summed rather than taken singly: {@link ReconciliationService} can match more than one
     * income row to the same expense (two partial refunds for one order is the ordinary case), so
     * the offset is their total.
     *
     * @param transactions any collection; only rows that are themselves refund legs are read, so a
     *                     caller can hand over the whole ledger without pre-filtering
     */
    public static RefundNetting from(Collection<Transaction> transactions) {
        Map<UUID, BigDecimal> offsets = new HashMap<>();
        for (Transaction t : transactions) {
            if (!isRefundLeg(t)) continue;
            offsets.merge(t.getRefundOfTransactionId(),
                    t.getAmount() == null ? BigDecimal.ZERO : t.getAmount(), BigDecimal::add);
        }
        return offsets.isEmpty() ? NONE : new RefundNetting(offsets);
    }

    /**
     * The rows a report should count at all, with no graph awareness -- callers that have not
     * (yet) looked up {@link TransactionGraphService#ccPaymentFromTransactionIds} get the same
     * answer as before that existed. Prefer the two-argument overload wherever a
     * {@link TransactionGraphService} is already reachable.
     */
    public static List<Transaction> reportable(Collection<Transaction> transactions) {
        return reportable(transactions, Set.of());
    }

    /**
     * The rows a report should count at all.
     *
     * <p>The filter the two callers each wrote by hand, minus the asymmetry: duplicates and
     * transfers are not real activity, and a refund leg is money coming back rather than income.
     * The purchase it reverses <em>stays</em>, and {@link #reportableAmount} is what makes that
     * correct.
     *
     * <p>{@code ccPaymentFromTransactionIds} extends the same rule to the transaction graph
     * (docs/proposals/reconciliation-evolution-roadmap-proposal.md, Part 4/10 "Net worth & cash
     * flow, graph-aware"): a savings-side payment that settles a credit card statement is, like a
     * transfer, money moving between the user's own accounts rather than new spend -- the card
     * charges it settles are the real spend, and they stay counted. Without this, a #511
     * CC_PAYMENT match settling several charges left the payment itself still counted as its own
     * expense on top of them, exactly the double-counting Part 4 of the roadmap names as the
     * concrete goal to avoid. See {@link TransactionGraphService#ccPaymentFromTransactionIds} for
     * which edge statuses qualify.
     *
     * <p>Deliberately does NOT drop {@code INVESTMENT_TRANSFER} rows -- unlike every exclusion
     * above, an investment outflow still belongs to a real, meaningful category ("Investments")
     * that a per-category budget or category-breakdown chart can legitimately track; dropping it
     * here, upstream of every {@code reportable()} caller including the ones that group by
     * category, would make that category silently vanish everywhere, budgets included, rather
     * than just from the cross-category total this classification exists to correct. See
     * {@link #excludingInvestmentTransfers} for the narrower, total-only cut every top-line
     * spend/income sum should apply instead.
     */
    public static List<Transaction> reportable(Collection<Transaction> transactions,
                                                 Set<UUID> ccPaymentFromTransactionIds) {
        return transactions.stream()
                .filter(t -> t.getIsDuplicateOf() == null && !t.isTransfer() && !isRefundLeg(t)
                        && (t.getId() == null || !ccPaymentFromTransactionIds.contains(t.getId())))
                .toList();
    }

    /**
     * The additional cut a cross-category total (total spend, total income, savings rate, cash
     * flow) needs on top of {@link #reportable} -- excluding {@code INVESTMENT_TRANSFER} rows the
     * same way {@code TRANSFER} already is, for the same reason: a SIP or other investment
     * outflow is money moving into savings, not consumption. Never apply this before a
     * category-grouping step; see {@link #reportable}'s own comment on why.
     */
    public static List<Transaction> excludingInvestmentTransfers(Collection<Transaction> transactions) {
        return transactions.stream()
                .filter(t -> t.getReconciliationStatus() != Transaction.ReconciliationStatus.INVESTMENT_TRANSFER)
                .toList();
    }

    /**
     * What this transaction should count as, with any refunds against it netted off.
     *
     * <p>Only ever reduces an expense. Income is returned untouched — a refund leg never reaches
     * here (it is filtered by {@link #reportable}) and nothing else can be refunded.
     *
     * <p>Floored at zero. {@code ReconciliationService} already refuses to match a refund larger
     * than its purchase, so an over-refund should be unreachable; a negative expense would flow
     * into category totals and a savings rate as a silently wrong number rather than an obvious
     * one, so it is not left to that guarantee alone.
     */
    public BigDecimal reportableAmount(Transaction t) {
        BigDecimal amount = t.getAmount() == null ? BigDecimal.ZERO : t.getAmount();
        if (t.getTxnType() != Transaction.Type.EXPENSE || refundedByExpenseId.isEmpty()) {
            return amount;
        }
        BigDecimal refunded = refundedByExpenseId.get(t.getId());
        if (refunded == null) return amount;
        BigDecimal net = amount.subtract(refunded);
        return net.signum() < 0 ? BigDecimal.ZERO : net;
    }

    /**
     * True when this row is the income side of a matched refund or reversal. Both statuses net
     * the same way against the purchase they reverse -- REFUND vs. REVERSAL only records *why*
     * the money came back (a merchant refund vs. a bank-side reversal), not whether it should be
     * netted off the original expense, which is unconditionally yes for either.
     */
    private static boolean isRefundLeg(Transaction t) {
        return t.getReconciliationStatus() == Transaction.ReconciliationStatus.REFUND
                || t.getReconciliationStatus() == Transaction.ReconciliationStatus.REVERSAL;
    }
}
