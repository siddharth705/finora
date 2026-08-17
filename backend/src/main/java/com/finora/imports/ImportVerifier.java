package com.finora.imports;

import com.finora.dto.ImportDto;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.UnparseableRow;
import com.finora.imports.pdf.PdfTableLocator.DroppedCandidateRow;
import com.finora.imports.pdf.StatementSummaryExtractor.PrintedSummary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs every verification rule over a staged statement and collects what they found.
 *
 * <p><b>Assembly, not aggregation, and the distinction is deliberate.</b> This gathers findings
 * into one report. It does not weigh them, rank them, or derive an overall verdict — the report has
 * no overall status field precisely because nothing is yet in a position to compute one honestly.
 * Combining several rules into a single judgement needs a weighting policy, and a weighting policy
 * invented before there is anything to calibrate it against is a guess with an authoritative
 * appearance. See docs/engineering/import-verification-framework.md.
 *
 * <p>What this class does earn its place for is that the two staging paths, CSV and PDF, must run
 * the same rules with the same inputs. Before it existed each producer called one validator
 * directly, which was fine for one rule and becomes drift the moment there are two — the shape of
 * problem this repository's audit history is largely a record of.
 *
 * <p>Adding a rule means one line here and a renderer entry in the client. Nothing else changes:
 * not the wire format, not the producers, not the UI's structure.
 */
@Component
public class ImportVerifier {

    private final BalanceChainValidator balanceChainValidator;
    private final StatementTotalsValidator statementTotalsValidator;
    private final SummaryTotalsValidator summaryTotalsValidator;
    private final ColumnAmbiguityValidator columnAmbiguityValidator;
    private final RowAccountingValidator rowAccountingValidator;

    public ImportVerifier(BalanceChainValidator balanceChainValidator,
                           StatementTotalsValidator statementTotalsValidator,
                           SummaryTotalsValidator summaryTotalsValidator,
                           ColumnAmbiguityValidator columnAmbiguityValidator,
                           RowAccountingValidator rowAccountingValidator) {
        this.balanceChainValidator = balanceChainValidator;
        this.statementTotalsValidator = statementTotalsValidator;
        this.summaryTotalsValidator = summaryTotalsValidator;
        this.columnAmbiguityValidator = columnAmbiguityValidator;
        this.rowAccountingValidator = rowAccountingValidator;
    }

    /**
     * Verifies a staged section against the evidence the statement carries about itself.
     *
     * <p>Both balances are nullable because plenty of statements state neither, and a rule that
     * cannot run says so rather than being skipped silently — "not applicable" and "not checked"
     * are different claims, and only one of them is true here.
     */
    public ImportDto.VerificationReport verify(List<StagedRow> rows, BigDecimal openingBalance,
                                                BigDecimal closingBalance) {
        return verify(rows, openingBalance, closingBalance, PrintedSummary.NONE, List.of(), List.of(), List.of());
    }

    /**
     * Adds the totals the statement printed about itself, when it printed any.
     *
     * <p>Kept as a separate parameter rather than folded into the balances because it is evidence
     * of a different kind: the balances are fields we read off the document, while the printed
     * counts came from the bank's ledger and can therefore contradict a reading of the document
     * that is otherwise entirely self-consistent. {@link PrintedSummary#NONE} is the honest value
     * for a statement that printed nothing — the rule then reports that it could not run.
     *
     * @param unparseableRows rows the locator found and bucketed, but which failed normalization --
     *                        already computed by every caller for {@code StagedAccountSection}'s
     *                        own field, carried here purely as ROW_ACCOUNTING evidence.
     * @param droppedTransactionCandidates rows discarded before ever reaching normalization, but
     *                        with transaction shape -- see
     *                        {@link com.finora.imports.pdf.PdfTableLocator.DroppedCandidateRow}.
     */
    public ImportDto.VerificationReport verify(List<StagedRow> rows, BigDecimal openingBalance,
                                                BigDecimal closingBalance, PrintedSummary printedSummary,
                                                List<java.util.Map<String, String>> rawRows,
                                                List<UnparseableRow> unparseableRows,
                                                List<DroppedCandidateRow> droppedTransactionCandidates) {
        List<ImportDto.VerificationFinding> findings = new ArrayList<>();
        findings.addAll(balanceChainValidator.report(rows, openingBalance).findings());
        findings.add(statementTotalsValidator.check(rows, openingBalance, closingBalance));
        // rawRows is what the locator found before normalisation. The summary rule needs its SIZE
        // to tell "the table was read and every row rejected" from "no table was found at all" --
        // both stage zero rows and they are not the same failure.
        int locatedRowCount = rawRows == null ? 0 : rawRows.size();
        findings.add(summaryTotalsValidator.check(rows, printedSummary, locatedRowCount));
        // The rows BEFORE normalization, which is the only point at which an ambiguous cell is
        // still ambiguous -- every other rule here sees values whose reading is already settled.
        findings.add(columnAmbiguityValidator.check(rawRows));
        findings.add(rowAccountingValidator.check(rows, unparseableRows, droppedTransactionCandidates, locatedRowCount));
        return new ImportDto.VerificationReport(List.copyOf(findings));
    }

    /**
     * Re-decides SUMMARY_TOTALS alone, once it is known which section a document-level printed
     * summary belongs to.
     *
     * <p>A printed summary describes the whole document, so at the moment a section is built there
     * is no way to know whether it is the section the totals are about. That is only answerable
     * after every section exists and has been parsed -- see PdfPreviewGenerator's attribution
     * step. This lets that answer be applied without re-running the other three rules, which
     * depend on nothing that changed and would have to be handed inputs this class no longer has.
     *
     * <p>Only SUMMARY_TOTALS is replaced, and only ever by re-asking the same validator the same
     * question with better information. Every other finding is carried through untouched, in
     * order: a report is assembled, not aggregated, and reordering it would change what a client
     * renders first for no reason.
     */
    public ImportDto.VerificationReport reviseSummaryTotals(ImportDto.VerificationReport report,
                                                            List<StagedRow> rows,
                                                            PrintedSummary printedSummary,
                                                            int locatedRowCount) {
        if (report == null) return null;
        List<ImportDto.VerificationFinding> revised = new ArrayList<>();
        for (ImportDto.VerificationFinding finding : report.findings()) {
            revised.add(SummaryTotalsValidator.RULE.equals(finding.rule())
                    ? summaryTotalsValidator.check(rows, printedSummary, locatedRowCount)
                    : finding);
        }
        return new ImportDto.VerificationReport(List.copyOf(revised));
    }
}
