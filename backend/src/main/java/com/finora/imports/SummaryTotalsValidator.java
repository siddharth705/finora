package com.finora.imports;

import com.finora.dto.ImportDto;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.pdf.StatementSummaryExtractor.PrintedSummary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks what was parsed against the totals the bank printed on the statement itself.
 *
 * <p><b>This is the first check whose evidence does not come from our own parsing.</b> The balance
 * chain compares rows to the running balance printed beside them; the statement totals check
 * compares those same rows to two header fields. Both are internal: a statement can be
 * self-consistent and still have been read wrongly, and the motivating HDFC import was exactly
 * that — three withdrawals read as zero, and every remaining number still agreeing with every
 * other. The bank's own debit and credit counts come from its ledger, not from its PDF, so they
 * can contradict a perfectly self-consistent misreading. That is what makes this worth having even
 * though it overlaps arithmetically with {@link StatementTotalsValidator}.
 *
 * <p><b>A count is evidence an amount cannot supply.</b> Sums cannot see a row that was split in
 * two, or two rows merged into one, or a duplicate that happens to net out. Counts see all three
 * immediately, which is why they are compared separately rather than folded into the totals.
 *
 * <p><b>It says which kind of mistake, not just that there was one.</b> Counts agreeing while
 * totals differ means the right number of transactions with a wrong value among them. Totals
 * agreeing while counts differ means the right money in the wrong number of rows. And a total row
 * count that matches while the per-direction split does not means a transaction is being read in
 * the wrong direction — which is precisely the failure that started this work, and the one no
 * amount of balance arithmetic detected.
 */
@Component
public class SummaryTotalsValidator {

    /** Stable machine identifier — clients group and explain by it, so it must not track wording. */
    public static final String RULE = "SUMMARY_TOTALS";

    public ImportDto.VerificationFinding check(List<StagedRow> rows, PrintedSummary summary) {
        return check(rows, summary, 0);
    }

    /**
     * The machine-readable name for "the statement says there was activity and we accepted none of
     * it". A bounded constant rather than a sentence, for the same reason every other outcome here
     * carries one: the message is presentation, and a caller that must parse prose to learn what
     * happened is a caller coupled to wording.
     */
    public static final String PRINTED_ACTIVITY_WITH_ZERO_STAGED = "PRINTED_ACTIVITY_WITH_ZERO_STAGED_TRANSACTIONS";

    /**
     * @param locatedRowCount rows the parser found in the table before normalisation, carried only
     *                        as evidence and deliberately distinguished from staged rows. "66
     *                        located, 0 staged" and "0 located, 0 staged" are different failures --
     *                        the first says the table was seen and every row of it rejected, the
     *                        second that no table was seen at all. Only the staged count is a claim
     *                        about the ledger; the located count says where it went wrong.
     */
    public ImportDto.VerificationFinding check(List<StagedRow> rows, PrintedSummary summary, int locatedRowCount) {
        Map<String, Object> details = new LinkedHashMap<>();

        // A statement that claims activity while nothing reached the ledger is not "nothing to
        // compare against" -- it is the strongest evidence available that the read failed, and the
        // one case where the printed summary matters MORE than usual, precisely because our own
        // parse produced nothing to weigh against it.
        //
        // Guarded on the summary CLAIMING ACTIVITY rather than merely existing. Zero staged rows is
        // not itself a contradiction: a dormant account's statement can print a summary of zeroes
        // and have been read perfectly. The contradiction needs the document to assert that
        // something happened.
        //
        // WARNING rather than FAILED: the financial data did not fail validation, it never arrived.
        // WARNING is also what the existing renderers and the corpus diff already treat as "worth a
        // human look" -- VerificationPanel's notable filter is WARNING-or-FAILED, so a new outcome
        // would have been silently invisible in the one place a person would look for it.
        if ((rows == null || rows.isEmpty()) && summary != null && claimsActivity(summary)) {
            details.put("suspectedCause", PRINTED_ACTIVITY_WITH_ZERO_STAGED);
            putIfPresent(details, "printedDebitCount", summary.debitCount());
            putIfPresent(details, "printedCreditCount", summary.creditCount());
            putIfPresent(details, "printedDebitTotal", summary.debitTotal());
            putIfPresent(details, "printedCreditTotal", summary.creditTotal());
            details.put("stagedTransactionCount", 0);
            details.put("locatedRowCount", locatedRowCount);
            details.put("explanation", "The statement reports activity of its own and no transactions "
                    + "were accepted into the ledger. Nothing here says the amounts are wrong -- it "
                    + "says they never arrived.");
            return new ImportDto.VerificationFinding(RULE, "WARNING", details);
        }

        if (rows == null || rows.isEmpty() || summary == null || summary.isEmpty()) {
            // Says what this method KNOWS, not what it assumes. The previous wording -- "The
            // statement did not print its own totals" -- asserted a fact about the document that
            // this method has no way to establish: it receives an already-resolved PrintedSummary
            // and cannot tell a statement that printed nothing from one whose totals its caller
            // declined to attribute. Both arrive here as PrintedSummary.NONE.
            //
            // Measured on a real HDFC composite statement: it prints "Debit Count 66 / Credit
            // Count 9" and totals of 39,601.91 and 98,197.00, all four of which match the parse
            // exactly -- and this rule reported that the statement did not print its own totals.
            // The caller withholds a document-level summary on a multi-section document (see
            // PdfPreviewGenerator's own comment on why), so the claim was false and would send
            // anyone investigating to look for a summary block that is on page 1.
            //
            // Whether that summary SHOULD be attributed is a separate question with its own
            // change; this one only stops the engine explaining itself with something untrue.
            details.put("reason", rows == null || rows.isEmpty()
                    ? "No transactions were parsed."
                    : "No printed totals were available for this section, so there was nothing to "
                            + "compare against.");
            return new ImportDto.VerificationFinding(RULE, "NOT_APPLICABLE", details);
        }

        BigDecimal parsedCredits = sum(rows, true);
        BigDecimal parsedDebits = sum(rows, false);
        int parsedCreditCount = count(rows, true);
        int parsedDebitCount = count(rows, false);

        List<String> mismatches = new ArrayList<>();
        boolean creditTotalOff = differs(summary.creditTotal(), parsedCredits);
        boolean debitTotalOff = differs(summary.debitTotal(), parsedDebits);
        boolean creditCountOff = differs(summary.creditCount(), parsedCreditCount);
        boolean debitCountOff = differs(summary.debitCount(), parsedDebitCount);

        record Comparison(String key, Object printed, Object parsed, boolean off) {}
        List<Comparison> comparisons = List.of(
                new Comparison("creditTotal", summary.creditTotal(), parsedCredits, creditTotalOff),
                new Comparison("debitTotal", summary.debitTotal(), parsedDebits, debitTotalOff),
                new Comparison("creditCount", summary.creditCount(), parsedCreditCount, creditCountOff),
                new Comparison("debitCount", summary.debitCount(), parsedDebitCount, debitCountOff));

        for (Comparison c : comparisons) {
            // Only fields the statement actually printed are reported. Emitting a parsed figure
            // beside a blank printed one reads as a comparison that was made and passed.
            if (c.printed() == null) continue;
            details.put("printed" + capitalize(c.key()), c.printed());
            details.put("parsed" + capitalize(c.key()), c.parsed());
            if (c.off()) mismatches.add(c.key());
        }

        if (mismatches.isEmpty()) {
            return new ImportDto.VerificationFinding(RULE, "VERIFIED", details);
        }

        details.put("mismatches", List.copyOf(mismatches));
        boolean countsOff = creditCountOff || debitCountOff;
        boolean totalsOff = creditTotalOff || debitTotalOff;

        // A row count that matches overall while the split between directions does not can only
        // mean a transaction was read on the wrong side -- nothing is missing and nothing is extra.
        boolean rowCountAgrees = summary.creditCount() != null && summary.debitCount() != null
                && (summary.creditCount() + summary.debitCount()) == rows.size();

        if (countsOff && rowCountAgrees) {
            details.put("suspectedCause", "DIRECTION");
            details.put("explanation", "The statement has the expected number of transactions, but not "
                    + "the expected number on each side. At least one is being read as money moving the "
                    + "wrong way.");
        } else if (countsOff && !totalsOff) {
            details.put("suspectedCause", "ROW_GROUPING");
            details.put("explanation", "The amounts add up but the number of transactions does not. A "
                    + "transaction has most likely been split in two, or two combined into one.");
        } else if (countsOff) {
            details.put("suspectedCause", "MISSING_OR_EXTRA_ROWS");
            details.put("explanation", "Neither the number of transactions nor the amounts match what "
                    + "the statement reports. A transaction is likely missing or duplicated.");
        } else {
            details.put("suspectedCause", "AMOUNTS");
            details.put("explanation", "Every transaction on the statement was found, but at least one "
                    + "amount is being read incorrectly.");
        }

        return new ImportDto.VerificationFinding(RULE, "FAILED", details);
    }

    /** True when the statement asserts that something happened -- any count or any total above
     *  zero. A summary of zeroes asserts the opposite, and must not raise a contradiction. */
    private static boolean claimsActivity(PrintedSummary summary) {
        return positive(summary.debitCount()) || positive(summary.creditCount())
                || positive(summary.debitTotal()) || positive(summary.creditTotal());
    }

    private static boolean positive(Integer count) {
        return count != null && count > 0;
    }

    private static boolean positive(BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }

    private static void putIfPresent(Map<String, Object> details, String key, Object value) {
        if (value != null) details.put(key, value);
    }

    /** Absent printed evidence is not a mismatch — a statement that printed no credit count says
     *  nothing about ours, and treating silence as disagreement would fail correct imports. */
    private static boolean differs(BigDecimal printed, BigDecimal parsed) {
        return printed != null && printed.compareTo(parsed) != 0;
    }

    private static boolean differs(Integer printed, int parsed) {
        return printed != null && printed != parsed;
    }

    private static BigDecimal sum(List<StagedRow> rows, boolean credits) {
        return rows.stream()
                .filter(r -> r.amount() != null)
                .filter(r -> credits == "INCOME".equals(r.type()))
                .map(r -> r.amount().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static int count(List<StagedRow> rows, boolean credits) {
        return (int) rows.stream().filter(r -> credits == "INCOME".equals(r.type())).count();
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
