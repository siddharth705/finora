package com.finora.service;

import com.finora.entity.Transaction;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * they were both wrong. A third reader is coming ({@code BudgetService} — see below), and the rule
 * has to have one owner before it has three call sites.
 *
 * <p><b>{@code BudgetService} deliberately still does not use this.</b> It loads one calendar
 * month by date range, so the refund rows that offset its expenses are frequently outside the set
 * it queried, and wiring this in means changing that query. That is a real fix and a separate one;
 * it is named here so the remaining copy is a known gap rather than an oversight.
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
     * The rows a report should count at all.
     *
     * <p>The filter the two callers each wrote by hand, minus the asymmetry: duplicates and
     * transfers are not real activity, and a refund leg is money coming back rather than income.
     * The purchase it reverses <em>stays</em>, and {@link #reportableAmount} is what makes that
     * correct.
     */
    public static List<Transaction> reportable(Collection<Transaction> transactions) {
        return transactions.stream()
                .filter(t -> t.getIsDuplicateOf() == null && !t.isTransfer() && !isRefundLeg(t))
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

    /** True when this row is the income side of a matched refund. */
    private static boolean isRefundLeg(Transaction t) {
        return t.getReconciliationStatus() == Transaction.ReconciliationStatus.REFUND;
    }
}
