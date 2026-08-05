package com.finora.imports;

import com.finora.dto.ImportDto;
import com.finora.dto.ImportDto.StagedRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks parsed rows against the arithmetic the statement asserts about itself.
 *
 * <p><b>What this changes.</b> Everything upstream verifies that the parser produced <i>a</i>
 * result. Nothing verified it produced the <i>correct</i> one. Those are different guarantees, and
 * the gap between them is where silent wrongness lives: a real HDFC statement imported three
 * withdrawals as amount 0 and the fourth transaction in the wrong direction, and every stage
 * reported success, because nothing compared the numbers to anything.
 *
 * <p>Every statement already contains the check. Each row prints the balance after it, so each row
 * is an assertion:
 *
 * <pre>
 *   previousBalance + signedAmount == thisRow.balance
 * </pre>
 *
 * A wrong amount, a wrong direction, a dropped row and a duplicated row all break that identity
 * immediately. This is strictly stronger than checking parser heuristics against each other,
 * because it is the document's own arithmetic rather than our opinion of it.
 *
 * <p><b>Why per-row rather than a total.</b> A statement's printed debit/credit totals would tell
 * you "something is wrong". The chain tells you "row 17 is wrong, by 436.00". During support that
 * difference is most of the work — and it is the difference between a report someone can act on
 * and one they have to reproduce first.
 *
 * <p><b>Deliberately does not gate the import.</b> This classifies and reports; the caller decides.
 * A validator that refused an import would turn any false positive into "Finora cannot read my
 * statement", which is a worse failure than the one it prevents — and a statement can legitimately
 * defeat the chain (a mid-statement summary line, a reordered same-day pair, a bank that prints no
 * running balance at all). Refusing on that would be wrong. Being unable to say so would also be
 * wrong; hence three outcomes rather than a boolean.
 */
@Component
public class BalanceChainValidator {

    /**
     * How the parsed rows stand against the statement's own arithmetic.
     *
     * <p>{@link #NOT_APPLICABLE} is a first-class outcome, not a failure: plenty of real statements
     * carry no running-balance column, and reporting those as anything else would either cry wolf
     * or claim a verification that never happened.
     */
    public enum Outcome { VERIFIED, WARNING, FAILED, NOT_APPLICABLE }

    /**
     * One row whose recorded amount does not move the balance the way the statement says it did.
     *
     * <p>{@code difference} is what the row would have needed to be, minus what it was — so it
     * reads directly as "we are short by 436.00 here", which on the motivating statement is exactly
     * the premium that was parsed as zero.
     */
    public record Discrepancy(
            int rowIndex,
            LocalDate date,
            String description,
            BigDecimal expectedBalance,
            BigDecimal actualBalance,
            BigDecimal difference
    ) {}

    /** The verdict, plus the evidence for it. */
    public record Result(Outcome status, List<Discrepancy> discrepancies, int rowsChecked, int rowsWithBalance) {

        public boolean isVerified() { return status == Outcome.VERIFIED; }

        /** One line for a human -- the import preview, a log, or a support conversation. */
        public String summary() {
            return switch (status) {
                case VERIFIED -> "Running balance verified across " + rowsChecked + " transaction(s).";
                case NOT_APPLICABLE -> "This statement has no running-balance column, so the amounts could not be cross-checked.";
                case WARNING -> discrepancies.size() + " of " + rowsChecked
                        + " transaction(s) don't match the statement's running balance. Review before importing.";
                case FAILED -> "The running balance doesn't reconcile for " + discrepancies.size()
                        + " of " + rowsChecked + " transaction(s). These amounts are probably being read incorrectly.";
            };
        }
    }

    /**
     * At least this many consecutive balance pairs must exist before a verdict means anything. One
     * pair is a coin flip -- a single mismatch could as easily be a mid-statement summary line as a
     * parsing fault, and reporting FAILED off one comparison would train people to ignore this.
     */
    private static final int MIN_PAIRS_FOR_A_VERDICT = 2;

    /**
     * At or above this share of checked rows failing, the cause is systematic rather than a quirk.
     *
     * <p>Half is chosen because the failure this exists to catch is a whole COLUMN being misread --
     * which breaks most rows, not a scattered few. Below that threshold the likelier explanation is
     * something about the specific document, which is worth surfacing but not worth calling broken.
     */
    private static final double FAILED_THRESHOLD = 0.5;

    /**
     * FAILED additionally requires this many bad rows, whatever the ratio says.
     *
     * <p>Found by the tests rather than reasoned out first: on a three-row statement there are only
     * two pairs, so a SINGLE bad row is 50% and was being reported as systematic. One discrepancy
     * is never systematic no matter how short the statement -- the whole-column misread this
     * distinction exists to name breaks several rows by definition. Small samples were making the
     * ratio say something it could not support.
     */
    private static final int MIN_DISCREPANCIES_FOR_FAILED = 2;

    /** The machine identifier this validator reports findings under. Stable by contract: clients
     *  group, count and explain by it, so it must not change when the wording does. */
    public static final String RULE = "BALANCE_CHAIN";

    /**
     * Runs the check and returns it in the shape the API exposes.
     *
     * <p>Kept here rather than in each producer so the two staging paths (CSV and PDF) cannot
     * describe the same finding differently -- the sort of drift this repository has repeatedly
     * had to go back and fix.
     *
     * <p>Returns a report holding exactly one finding, deliberately rather than the finding itself:
     * a second validator appends to the list and nothing about the shape changes, whereas returning
     * a bare finding would have to be reshaped the first time there were two.
     */
    public ImportDto.VerificationReport report(List<StagedRow> rows, BigDecimal openingBalance) {
        Result result = validate(rows, openingBalance);

        // Typed while it is being built, then handed over as the finding's per-rule payload. The
        // wire format does not name this shape, because the next checks planned (statement totals,
        // structural) have no row to point at -- see VerificationFinding.details.
        List<ImportDto.RowDiscrepancy> discrepancies = result.discrepancies().stream()
                .map(d -> new ImportDto.RowDiscrepancy(
                        d.rowIndex(), d.expectedBalance(), d.actualBalance(), d.difference()))
                .toList();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rowsChecked", result.rowsChecked());
        details.put("rowsWithBalance", result.rowsWithBalance());
        details.put("anchoredOnOpeningBalance", openingBalance != null);
        details.put("discrepancies", discrepancies);

        // The rule's verdict about its own domain, keeping the WARNING/FAILED distinction rather
        // than flattening to a boolean -- "a few rows disagree" and "this column is being misread"
        // call for different responses, and only this class knows which it saw.
        var finding = new ImportDto.VerificationFinding(RULE, result.status().name(), details);
        return new ImportDto.VerificationReport(List.of(finding));
    }

    /**
     * Validates rows in the order the statement presents them.
     *
     * <p>Order matters and is the caller's responsibility: the chain is only meaningful along the
     * document's own sequence, which for same-day transactions is not date order. That is exactly
     * what {@link BalanceChainUtil} exists to reconstruct, so rows arrive here already sequenced.
     */
    public Result validate(List<StagedRow> rows) {
        return validate(rows, null);
    }

    /**
     * As {@link #validate(List)}, but anchored on the statement's printed opening balance.
     *
     * <p><b>This is not a refinement, it closes a hole.</b> Chaining consecutive pairs never tests
     * the FIRST row -- there is nothing before it to chain from -- so a first row with the wrong
     * amount or the wrong direction passes silently while every later row verifies. That is not
     * hypothetical: on the statement that motivated this validator, the opening deposit is typed
     * as an expense and the pair-only check reports VERIFIED, because the error sits in the one
     * position the chain cannot see.
     *
     * <p>An opening balance turns the first row into an ordinary link. Pass null when the document
     * did not state one, or when what was detected is not trustworthy -- a WRONG anchor would
     * report a discrepancy on a row that is actually correct, which is the one outcome that would
     * make people stop believing this check.
     */
    public Result validate(List<StagedRow> rows, BigDecimal openingBalance) {
        if (rows == null || rows.isEmpty()) {
            return new Result(Outcome.NOT_APPLICABLE, List.of(), 0, 0);
        }

        int rowsWithBalance = (int) rows.stream().filter(r -> r.balanceAfter() != null).count();

        List<Discrepancy> discrepancies = new ArrayList<>();
        int pairsChecked = 0;
        StagedRow previous = null;

        // The anchor makes the first row an ordinary link instead of an untestable one. Held as a
        // balance rather than a row, so the loop below needs no special case for it.
        BigDecimal previousBalance = openingBalance;

        for (int i = 0; i < rows.size(); i++) {
            StagedRow row = rows.get(i);
            if (row.balanceAfter() == null || row.amount() == null) continue;

            if (previous != null || previousBalance != null) {
                BigDecimal from = previous != null ? previous.balanceAfter() : previousBalance;
                BigDecimal expected = from.add(signedAmount(row));
                if (expected.compareTo(row.balanceAfter()) != 0) {
                    discrepancies.add(new Discrepancy(
                            i, row.date(), row.description(),
                            expected, row.balanceAfter(),
                            row.balanceAfter().subtract(expected)));
                }
                pairsChecked++;
            }
            previous = row;
        }

        if (pairsChecked < MIN_PAIRS_FOR_A_VERDICT) {
            return new Result(Outcome.NOT_APPLICABLE, List.of(), pairsChecked, rowsWithBalance);
        }
        if (discrepancies.isEmpty()) {
            return new Result(Outcome.VERIFIED, List.of(), pairsChecked, rowsWithBalance);
        }

        boolean systematic = discrepancies.size() >= MIN_DISCREPANCIES_FOR_FAILED
                && (double) discrepancies.size() / pairsChecked >= FAILED_THRESHOLD;
        Outcome status = systematic ? Outcome.FAILED : Outcome.WARNING;
        return new Result(status, List.copyOf(discrepancies), pairsChecked, rowsWithBalance);
    }

    /**
     * The amount as it moves the balance: negative for money out, positive for money in.
     *
     * <p>Reads the normalized type rather than the sign, because {@code StagedRow.amount} is
     * already absolute by the time it gets here (see TransactionNormalizer's {@code .abs()}). That
     * is what makes this check sensitive to DIRECTION errors and not just magnitude ones -- a
     * deposit recorded as an expense moves the balance the wrong way and shows up here as double
     * the amount, which is a far louder signal than being merely absent.
     */
    private static BigDecimal signedAmount(StagedRow row) {
        BigDecimal magnitude = row.amount().abs();
        return "INCOME".equals(row.type()) ? magnitude : magnitude.negate();
    }
}
