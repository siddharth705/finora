package com.finora.imports;

import com.finora.dto.ImportDto;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.UnparseableRow;
import com.finora.imports.pdf.PdfTableLocator.DroppedCandidateRow;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Checks that every physical row a document offered has an accounted-for fate -- not whether the
 * numbers add up (that's {@link BalanceChainValidator}/{@link StatementTotalsValidator}), not
 * whether the bank's own printed totals agree (that's {@link SummaryTotalsValidator}), but
 * whether anything was silently left behind on the way to those checks ever running at all.
 *
 * <p>This is the gap every arithmetic validator shares by construction: a statement with 100 real
 * transactions from which the parser extracted 70, all 70 individually correct, still reports
 * {@code VERIFIED} everywhere else -- the missing 30 are invisible to a check that only ever sees
 * what WAS extracted. This rule is the one place that asks about the other 30.
 *
 * <p>Deliberately narrow: this does NOT re-derive whether a printed transaction count agrees with
 * what was staged -- {@link SummaryTotalsValidator} already owns that question, from its own
 * inputs, with its own long-established {@code suspectedCause} vocabulary. Two rules computing
 * the same fact from the same evidence would drift the moment one of them changes; a future
 * decision layer combines what each rule already knows, rather than either rule reaching into the
 * other's domain.
 *
 * <p>Never returns {@code FAILED}. A dropped transaction-shaped row is not proof anything is
 * wrong -- {@link com.finora.imports.pdf.PdfTableLocator.DroppedCandidateRow}'s own doc comment is
 * explicit that this is a SHAPE fact, not a claim of loss. {@code WARNING} is what
 * {@link SummaryTotalsValidator} already uses for its own "the financial data did not fail
 * validation, it never arrived" case, and the same reasoning applies here.
 */
@Component
public class RowAccountingValidator {

    /** Stable machine identifier — clients group and explain by it, so it must not track wording. */
    public static final String RULE = "ROW_ACCOUNTING";

    /**
     * @param locatedRowCount rows the parser found in the table before normalisation -- same
     *                        figure {@link SummaryTotalsValidator} already receives, carried here
     *                        purely as evidence (see that class's own doc comment on why "located"
     *                        and "staged" are deliberately different counts).
     * @param droppedTransactionCandidates rows this section discarded with transaction shape --
     *                        see {@link DroppedCandidateRow}'s own doc comment for exactly what
     *                        that does and does not claim.
     */
    public ImportDto.VerificationFinding check(List<StagedRow> stagedRows, List<UnparseableRow> unparseableRows,
            List<DroppedCandidateRow> droppedTransactionCandidates, int locatedRowCount) {
        Map<String, Object> details = new LinkedHashMap<>();
        int stagedCount = stagedRows == null ? 0 : stagedRows.size();
        int unparseableCount = unparseableRows == null ? 0 : unparseableRows.size();
        List<DroppedCandidateRow> dropped = droppedTransactionCandidates == null
                ? List.of() : droppedTransactionCandidates;

        details.put("stagedTransactionCount", stagedCount);
        details.put("locatedRowCount", locatedRowCount);
        details.put("unparseableRowCount", unparseableCount);
        details.put("droppedTransactionCandidateCount", dropped.size());

        // Genuinely nothing to account for -- no table, nothing staged, nothing unparseable,
        // nothing dropped. Distinguished from "a table was found and everything in it is
        // accounted for", which is VERIFIED below, not this.
        if (stagedCount == 0 && unparseableCount == 0 && dropped.isEmpty()) {
            details.put("reason", "No transaction table was located, so there is nothing to account for.");
            return new ImportDto.VerificationFinding(RULE, "NOT_APPLICABLE", details);
        }

        if (dropped.isEmpty()) {
            details.put("explanation", "Every row this parser located has an accounted-for fate.");
            return new ImportDto.VerificationFinding(RULE, "VERIFIED", details);
        }

        // TreeMap for a stable, alphabetical iteration order in the details payload -- a Map keyed
        // by reason code has no natural order of its own, and an unstable one would make two runs
        // over the identical document produce a different-looking (if equal) finding.
        Map<String, Long> reasonCounts = new TreeMap<>();
        for (DroppedCandidateRow row : dropped) {
            reasonCounts.merge(row.reason(), 1L, Long::sum);
        }
        details.put("droppedTransactionCandidateReasons", reasonCounts);
        // "Candidate"/"discarded"/"require review" throughout -- never "missing transactions".
        // This rule only ever sees a row's SHAPE, never confirms it was really a transaction.
        details.put("explanation", dropped.size() + " row" + (dropped.size() == 1 ? "" : "s") + " outside "
                + "the recognized transaction table had the shape of a transaction candidate (a date and "
                + "an amount on the same line) and were discarded. Nothing here says they were "
                + "transactions -- only that they were never explained, and require review.");
        return new ImportDto.VerificationFinding(RULE, "WARNING", details);
    }
}
