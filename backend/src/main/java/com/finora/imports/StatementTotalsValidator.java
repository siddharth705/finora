package com.finora.imports;

import com.finora.dto.ImportDto;
import com.finora.dto.ImportDto.StagedRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks the whole statement at once: does what came in and out actually take the opening balance
 * to the closing balance?
 *
 * <pre>
 *   openingBalance + Σ(credits) − Σ(debits) == closingBalance
 * </pre>
 *
 * <p><b>This is not a second opinion on {@link BalanceChainValidator}, it closes that check's blind
 * spot.</b> The chain compares consecutive rows, so it can never test the FIRST row — nothing
 * precedes it. Run against the statement that motivated this work, the chain reported VERIFIED
 * while the opening deposit was still typed as an expense: the error sat in the one position pair
 * comparison cannot see. A total spanning the whole document has no such position.
 *
 * <p><b>The three facts can disagree in more than one way, and this says which.</b> A mismatch
 * means the opening balance, the rows, and the closing balance are not mutually consistent — it
 * does not by itself mean the rows are wrong. So this also compares the LAST row's own running
 * balance against the detected closing balance. When those agree, the rows and the closing balance
 * corroborate each other and the opening balance is the outlier; when they do not, the rows are
 * implicated. Reporting "these three do not agree" without saying which is odd would send someone
 * to re-read every transaction when the actual fault is one misdetected header field.
 *
 * <p>That distinction is not hypothetical either. On the motivating HDFC statement the opening
 * balance is detected as 50,000 while the document plainly states 0.00, and the rows are correct.
 * A validator that blamed the rows there would be confidently wrong.
 */
@Component
public class StatementTotalsValidator {

    /** Stable machine identifier — clients group and explain by it, so it must not track wording. */
    public static final String RULE = "STATEMENT_TOTALS";

    /**
     * Runs the check, or reports that it could not be run.
     *
     * <p>Requires both balances. Many statements print neither, and inventing one — by assuming the
     * first row's balance minus its amount, say — would turn this from a check into a restatement
     * of the rows, which cannot contradict them and is therefore worthless as evidence.
     */
    public ImportDto.VerificationFinding check(List<StagedRow> rows, BigDecimal openingBalance,
                                                BigDecimal closingBalance) {
        Map<String, Object> details = new LinkedHashMap<>();

        if (rows == null || rows.isEmpty() || openingBalance == null || closingBalance == null) {
            details.put("reason", openingBalance == null && closingBalance == null
                    ? "The statement did not state an opening or closing balance."
                    : openingBalance == null ? "The statement did not state an opening balance."
                    : closingBalance == null ? "The statement did not state a closing balance."
                    : "No transactions were parsed.");
            return new ImportDto.VerificationFinding(RULE, "NOT_APPLICABLE", details);
        }

        BigDecimal credits = sumOf(rows, true);
        BigDecimal debits = sumOf(rows, false);
        BigDecimal expectedClosing = openingBalance.add(credits).subtract(debits);
        BigDecimal difference = closingBalance.subtract(expectedClosing);

        details.put("openingBalance", openingBalance);
        details.put("closingBalance", closingBalance);
        details.put("totalCredits", credits);
        details.put("totalDebits", debits);
        details.put("expectedClosingBalance", expectedClosing);
        details.put("difference", difference);

        if (difference.signum() == 0) {
            return new ImportDto.VerificationFinding(RULE, "VERIFIED", details);
        }

        // Which of the three facts is the odd one out. The last row's own running balance is
        // independent evidence: if it matches the stated closing balance, the transactions and the
        // closing balance agree with each other and the opening balance is what does not fit.
        BigDecimal lastRowBalance = lastStatedBalance(rows);
        if (lastRowBalance != null) {
            boolean rowsAgreeWithClosing = lastRowBalance.compareTo(closingBalance) == 0;
            details.put("lastRowBalance", lastRowBalance);
            details.put("likelyCause", rowsAgreeWithClosing
                    ? "OPENING_BALANCE"
                    : "TRANSACTIONS");
            details.put("explanation", rowsAgreeWithClosing
                    ? "The transactions reach the statement's closing balance, so they agree with each "
                      + "other. It is the opening balance that does not fit."
                    : "The transactions do not reach the statement's closing balance, so at least one "
                      + "of them is being read incorrectly.");
        }

        return new ImportDto.VerificationFinding(RULE, "FAILED", details);
    }

    /** Signed by the normalized type, not by the amount's sign -- StagedRow.amount is absolute by
     *  the time it reaches here, which is what makes this sensitive to direction errors. */
    private static BigDecimal sumOf(List<StagedRow> rows, boolean credits) {
        return rows.stream()
                .filter(r -> r.amount() != null)
                .filter(r -> credits == "INCOME".equals(r.type()))
                .map(r -> r.amount().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The running balance of the last row that states one -- not necessarily the last row, since a
     *  trailing summary line may carry none. */
    private static BigDecimal lastStatedBalance(List<StagedRow> rows) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (rows.get(i).balanceAfter() != null) return rows.get(i).balanceAfter();
        }
        return null;
    }
}
