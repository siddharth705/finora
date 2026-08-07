package com.finora.imports;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decides whether a claimed closing balance may be written onto an account's balance.
 *
 * <p><b>Reusable validation rule, not a point patch.</b> {@code ImportService.confirm} took
 * {@code request.statementClosingBalance()} — a field off the HTTP request body — and assigned it
 * directly to {@code Account.balance}, with no check that it had anything to do with the
 * transactions being imported alongside it. Every derived financial figure in the product reads
 * that column: net worth, the dashboard's liquid/assets/liabilities tiles, the health score's
 * debt-utilisation and emergency-fund components, and the low-balance notification threshold.
 *
 * <h2>What "corroborated" means here</h2>
 * The same arithmetic {@link StatementTotalsValidator} already applies to a staged statement:
 *
 * <pre>
 *   openingBalance + Σ(credits) − Σ(debits) == closingBalance
 * </pre>
 *
 * <p>The totals are summed over the rows that are <em>actually being imported</em>, not over the
 * whole staged file, which is what makes this a real check rather than a restatement. If the
 * transactions Finora is about to hold do not reach the claimed balance, then writing that balance
 * would leave {@code Account.balance} disagreeing with the sum of the ledger underneath it — and
 * nothing in the product ever recomputes it, so the disagreement is permanent and silent.
 *
 * <h2>Why this is a data-integrity guard and not an authorization one</h2>
 * Worth being precise, because it changes what this can and cannot promise. A caller who wants
 * their balance to read some number can still get it by importing transactions that genuinely sum
 * to it — and that is correct, because those are then real ledger entries in their own account.
 * {@code ImportService.resolveTargetAccount} already enforces that the account belongs to the
 * caller. What this stops is the case where the balance and the transactions disagree, which is
 * always a defect: either the parser misread the statement, the user excluded rows, or a client
 * sent a number it made up.
 *
 * <h2>Refusing is the safe direction</h2>
 * An uncorroborated balance is not applied and the import proceeds with a warning. That leaves the
 * account balance where it was — the same outcome as today's behaviour when a statement states no
 * closing balance at all, so this adds no new failure mode. It does NOT attempt to compute a
 * better balance from the ledger; that is a separate, larger question about which source of truth
 * owns the column, and answering it inside a bug fix would be a change of design rather than a
 * correction.
 */
public final class ClosingBalanceGuard {

    /** Stable machine identifier, matching {@link StatementTotalsValidator#RULE}'s convention —
     *  it must not track the wording of any message. */
    public static final String RULE = "CLOSING_BALANCE_CORROBORATION";

    private ClosingBalanceGuard() {}

    public enum Verdict {
        /** The imported rows reach the claimed closing balance. Safe to write. */
        CORROBORATED,
        /** The rows and the claimed balance disagree, or the claim cannot be tested. Do not write. */
        UNCORROBORATED,
        /** No balance was claimed, so there is nothing to apply and nothing to check. */
        NOT_APPLICABLE
    }

    /**
     * @param details machine-readable evidence for the verdict, in the shape
     *                {@code StatementTotalsValidator} already uses for its findings, so an
     *                operator reading an import warning and one reading a verification finding see
     *                the same field names.
     */
    public record Decision(Verdict verdict, String reason, Map<String, Object> details) {

        /** The single question {@code ImportService} asks. Written as a method rather than an
         *  enum comparison at the call site so that adding a verdict cannot silently change which
         *  branch a caller takes. */
        public boolean mayOverwriteAccountBalance() {
            return verdict == Verdict.CORROBORATED;
        }
    }

    /**
     * Assesses a claimed closing balance against the rows actually being imported.
     *
     * @param openingBalance  as stated on the statement; null when it stated none
     * @param closingBalance  the claim under test; null means nothing is being claimed
     * @param totalCredits    summed over imported rows only, absolute values
     * @param totalDebits     summed over imported rows only, absolute values
     * @param rowsImported    how many rows are actually being written
     * @param rowsSkipped     how many the user excluded during review
     */
    public static Decision assess(BigDecimal openingBalance, BigDecimal closingBalance,
                                   BigDecimal totalCredits, BigDecimal totalDebits,
                                   int rowsImported, int rowsSkipped) {
        Map<String, Object> details = new LinkedHashMap<>();

        if (closingBalance == null) {
            return new Decision(Verdict.NOT_APPLICABLE,
                    "The statement stated no closing balance.", details);
        }
        details.put("closingBalance", closingBalance);

        if (rowsImported <= 0) {
            return new Decision(Verdict.NOT_APPLICABLE,
                    "No transactions were imported, so there is nothing to corroborate against.",
                    details);
        }

        // An excluded row is a real gap in the arithmetic, not a rounding nuisance: the statement's
        // closing balance accounts for it and Finora's ledger will not. Applying the balance anyway
        // is precisely how the column comes to disagree with the transactions beneath it.
        if (rowsSkipped > 0) {
            details.put("rowsSkipped", rowsSkipped);
            return new Decision(Verdict.UNCORROBORATED,
                    rowsSkipped + " row(s) were excluded during review, so the statement's closing "
                            + "balance is not the balance these transactions reach.", details);
        }

        if (openingBalance == null) {
            return new Decision(Verdict.UNCORROBORATED,
                    "The statement stated no opening balance, so its closing balance cannot be "
                            + "checked against the imported transactions.", details);
        }

        BigDecimal credits = totalCredits == null ? BigDecimal.ZERO : totalCredits;
        BigDecimal debits = totalDebits == null ? BigDecimal.ZERO : totalDebits;
        BigDecimal expectedClosing = openingBalance.add(credits).subtract(debits);
        BigDecimal difference = closingBalance.subtract(expectedClosing);

        details.put("openingBalance", openingBalance);
        details.put("totalCredits", credits);
        details.put("totalDebits", debits);
        details.put("expectedClosingBalance", expectedClosing);
        details.put("difference", difference);

        // compareTo, never equals: BigDecimal.equals is scale-sensitive, so "1500.00" and "1500"
        // are unequal under it. This is the trap MoneyMath exists to name, and getting it wrong
        // here would reject every correct statement whose parsed scale differs from its opening
        // balance's.
        if (difference.signum() == 0) {
            return new Decision(Verdict.CORROBORATED,
                    "The imported transactions reach the statement's closing balance.", details);
        }

        return new Decision(Verdict.UNCORROBORATED,
                "The imported transactions do not reach the statement's closing balance "
                        + "(off by " + difference.abs().toPlainString() + ").", details);
    }
}
