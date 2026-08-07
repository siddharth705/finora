package com.finora.accounts;

import com.finora.entity.Account;
import com.finora.entity.Transaction;

import java.math.BigDecimal;
import java.util.Collection;

/**
 * What the sign of {@link Account#getBalance()} means, per account type -- in one place.
 *
 * The convention itself is not new. {@code TransactionService.balanceDelta} has always implemented
 * it correctly ("Credit cards are inverted: Account.balance represents money OWED, not cash on
 * hand"), and {@code DashboardService.computeHealthScore}'s debt-utilization math has always
 * depended on it. What was missing is that the convention lived in a comment on one writer while
 * three other places re-derived it by hand:
 *
 * <ul>
 *   <li>{@code NetWorthService.current} and {@code saveSnapshotForToday} each wrote
 *       {@code assets.subtract(liabilities)} inline;</li>
 *   <li>{@code DashboardService} wrote the same expression a third time;</li>
 *   <li>{@code ImportService} wrote a statement's closing balance straight onto the account with no
 *       type-awareness at all, which is where a credit-card balance could acquire the wrong sign in
 *       the first place.</li>
 * </ul>
 *
 * Four copies of one rule is how a sign bug becomes silent: correcting the arithmetic in one place
 * leaves the other three disagreeing, and net worth is a number nobody can eyeball for correctness.
 *
 * <h2>The convention</h2>
 *
 * <b>Liability accounts (credit cards): balance is money OWED.</b> Positive means debt. A negative
 * balance is meaningful and legitimate -- it is a credit balance, from an overpayment or a refund
 * landing after the bill was settled -- so nothing here clamps or absolute-values it. A card the
 * issuer owes you genuinely does increase your net worth.
 *
 * <b>Every other type: balance is money HELD.</b> Positive is an asset. A negative savings balance
 * is an overdrawn account, which correctly reduces net worth with no special handling -- which is
 * why overdrafts need no separate rule here despite being a form of borrowing.
 */
public final class AccountBalanceConvention {

    private AccountBalanceConvention() {}

    /** True when this type's balance counts as money owed rather than money held. */
    public static boolean isLiability(Account.Type type) {
        return type == Account.Type.CREDIT_CARD;
    }

    /**
     * What this account contributes to net worth: its balance for an asset, the negation of it for
     * a liability.
     *
     * Using this instead of {@code assets.subtract(liabilities)} is not just deduplication. The
     * subtraction form only works when every liability balance is non-negative; this form is
     * correct for a credit balance too, because negating a negative debt adds it back as the asset
     * it actually is.
     */
    public static BigDecimal netWorthContribution(Account.Type type, BigDecimal balance) {
        if (balance == null) return BigDecimal.ZERO;
        return isLiability(type) ? balance.negate() : balance;
    }

    /**
     * True when a stored balance violates this type's convention badly enough to be worth
     * surfacing rather than silently computing with.
     *
     * Only a credit card can violate it in a detectable way, and only in one direction: a balance
     * more negative than a plausible credit balance is far more likely a sign error on import than
     * a genuine overpayment of that size. This does not correct the value -- correcting a number
     * whose provenance is unknown is how a wrong figure becomes an authoritative one. It flags it,
     * so a human sees "this card's balance looks like it has the wrong sign" instead of a net worth
     * that is quietly wrong by twice the balance.
     *
     * Deliberately NOT applied to imports as an automatic flip. Whether a statement's
     * running-balance column expresses a card's outstanding as positive or negative varies by
     * issuer, and no statement in the current corpus exercises the case at all -- every
     * credit-card fixture here has no running-balance column, which is typical of real Indian card
     * statements. Picking a direction from theory and applying it silently would be tuning against
     * a document nobody has seen. Flagging is what the evidence supports today; the flip becomes
     * justified when a real card statement with a balance column shows which way it goes.
     */
    public static boolean looksLikeASignError(Account.Type type, BigDecimal balance) {
        return isLiability(type) && balance != null && balance.signum() < 0;
    }

    /**
     * How much one transaction moves its own account's balance.
     *
     * <p>The fourth copy of this rule, now the only one. {@code TransactionService.balanceDelta}
     * owned it privately, which meant the import path — the single largest writer of transactions
     * in the product — structurally could not reuse it, and did not: a confirmed statement inserted
     * its rows with {@code saveAll} and never moved the balance at all. That is Bug 17, and it is
     * the same shape as the duplication this class was created to end.
     *
     * <p>The liability inversion is the part that must not be re-derived by hand. For a credit
     * card, {@code balance} is money OWED, so a purchase (EXPENSE) INCREASES it and a payment
     * (INCOME) reduces it — the opposite of every other type. Getting that backwards on the import
     * path would corrupt a card's balance by twice its statement total, silently.
     */
    public static BigDecimal balanceDelta(Account.Type type, Transaction.Type txnType, BigDecimal amount) {
        if (amount == null || txnType == null) return BigDecimal.ZERO;
        boolean increases = isLiability(type)
                ? txnType == Transaction.Type.EXPENSE
                : txnType == Transaction.Type.INCOME;
        // abs() so a defensively-signed amount cannot double-invert the convention. Direction is
        // carried by txnType alone -- TransactionService.requirePositiveAmount enforces that on the
        // manual path, and the import path normalises to an absolute value in TransactionNormalizer.
        BigDecimal magnitude = amount.abs();
        return increases ? magnitude : magnitude.negate();
    }

    /**
     * The net movement a whole batch of transactions applies to one account.
     *
     * <p>Exists so the import path states its intent once rather than folding a loop at the call
     * site, and so the {@code delete} path can express "reverse exactly what the import applied" as
     * {@code netDelta(...).negate()} instead of a second loop that has to be kept in step with the
     * first. Those two not being symmetric is how a balance drifts across an import/delete cycle.
     *
     * @param transactions may be empty; every element must belong to the account whose {@code type}
     *                     is passed, since the inversion is a property of the ACCOUNT, not the row
     */
    public static BigDecimal netDelta(Account.Type type, Collection<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) return BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (Transaction t : transactions) {
            total = total.add(balanceDelta(type, t.getTxnType(), t.getAmount()));
        }
        return total;
    }
}
