package com.finora.imports;

import com.finora.dto.ImportDto;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.UnparseableRow;
import com.finora.imports.pdf.PdfTableLocator.DroppedCandidateRow;
import com.finora.imports.pdf.PdfTableLocator.HeaderReconstructionFinding;
import com.finora.imports.pdf.StatementSummaryExtractor.PrintedSummary;
import com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence;
import com.finora.imports.pdf.TextSource;
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
 * <p><b>Correction (import reliability status):</b> assembly above is still exactly what this
 * class does with the 7 validators' findings -- unchanged. What's new is a narrow, separately-
 * computed {@code reliabilityStatus} layered on top by {@link ImportReliabilityStatusDeriver},
 * a distinct concern from the "weighting policy" rejected above: it invents no weights and needs
 * no calibration data, because each of its three outcomes is a deterministic OR over facts this
 * class already has (a finding's own outcome, header-reconstruction uncertainty, OCR provenance)
 * -- not a synthesized score. See {@link ImportDto.VerificationReport}'s own correction note.
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
    private final CreditCardStatementTotalsValidator creditCardStatementTotalsValidator;
    private final CreditCardFlowReconciliationValidator creditCardFlowReconciliationValidator;

    public ImportVerifier(BalanceChainValidator balanceChainValidator,
                           StatementTotalsValidator statementTotalsValidator,
                           SummaryTotalsValidator summaryTotalsValidator,
                           ColumnAmbiguityValidator columnAmbiguityValidator,
                           RowAccountingValidator rowAccountingValidator,
                           CreditCardStatementTotalsValidator creditCardStatementTotalsValidator,
                           CreditCardFlowReconciliationValidator creditCardFlowReconciliationValidator) {
        this.balanceChainValidator = balanceChainValidator;
        this.statementTotalsValidator = statementTotalsValidator;
        this.summaryTotalsValidator = summaryTotalsValidator;
        this.columnAmbiguityValidator = columnAmbiguityValidator;
        this.rowAccountingValidator = rowAccountingValidator;
        this.creditCardStatementTotalsValidator = creditCardStatementTotalsValidator;
        this.creditCardFlowReconciliationValidator = creditCardFlowReconciliationValidator;
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
        // List.of()/null: the CSV path this overload serves has neither header-reconstruction
        // findings (a PDF-only concept) nor OCR provenance (a PDF-acquisition-only concept).
        return verify(rows, openingBalance, closingBalance, PrintedSummary.NONE, List.of(), List.of(), List.of(),
                CreditCardSummaryEvidence.NONE, List.of(), null);
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
     * @param headerReconstructionFindings evidence that a multi-line header candidate failed to
     *                        merge and a low-confidence fallback was accepted instead -- see
     *                        {@link HeaderReconstructionFinding}. Feeds {@code reliabilityStatus}
     *                        only; never null from {@code PdfPreviewGenerator}, but {@code List.of()}
     *                        from the CSV-facing overload, which has no such concept.
     * @param textSource how this document's text was acquired -- native, OCR, or both. Feeds
     *                        {@code reliabilityStatus} only; null from the CSV-facing overload.
     */
    public ImportDto.VerificationReport verify(List<StagedRow> rows, BigDecimal openingBalance,
                                                BigDecimal closingBalance, PrintedSummary printedSummary,
                                                List<java.util.Map<String, String>> rawRows,
                                                List<UnparseableRow> unparseableRows,
                                                List<DroppedCandidateRow> droppedTransactionCandidates,
                                                CreditCardSummaryEvidence printedCreditCardSummary,
                                                List<HeaderReconstructionFinding> headerReconstructionFindings,
                                                TextSource textSource) {
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
        // Reads no row at all -- see the validator's own doc comment for why that is the point, not
        // an oversight: a credit-card statement's transaction table can be malformed while its
        // billing-summary panel is still readable, and this rule stays meaningful either way.
        findings.add(creditCardStatementTotalsValidator.check(printedCreditCardSummary));
        // Reads BOTH rows and the summary panel, deliberately unlike the rule above -- see its own
        // doc comment for what that lets it prove (transaction classification consistency) that a
        // summary-only check cannot.
        findings.add(creditCardFlowReconciliationValidator.check(rows, printedCreditCardSummary));

        boolean headerReconstructionUncertain = headerReconstructionFindings != null
                && !headerReconstructionFindings.isEmpty();
        List<ImportDto.VerificationFinding> assembled = List.copyOf(findings);
        ImportReliabilityStatus reliabilityStatus = ImportReliabilityStatusDeriver.derive(assembled,
                headerReconstructionUncertain, textSource);
        return new ImportDto.VerificationReport(assembled, headerReconstructionUncertain,
                textSource == null ? null : textSource.name(), reliabilityStatus);
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
     *
     * <p>{@code reliabilityStatus} IS recomputed, though -- SUMMARY_TOTALS can flip between
     * FAILED/WARNING/VERIFIED here, and a stale status would silently disagree with the findings
     * it's supposed to summarise. This method has no access to the original {@code
     * headerReconstructionFindings}/{@code textSource} (only {@code List<StagedAccountSection>} at
     * its own call site), which is exactly why {@link ImportDto.VerificationReport} carries those
     * two facts on itself -- recomputed from {@code report.headerReconstructionUncertain()}/
     * {@code report.textSource()}, not re-derived from scratch.
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
        List<ImportDto.VerificationFinding> assembled = List.copyOf(revised);
        TextSource textSource = report.textSource() == null ? null : TextSource.valueOf(report.textSource());
        ImportReliabilityStatus reliabilityStatus = ImportReliabilityStatusDeriver.derive(assembled,
                report.headerReconstructionUncertain(), textSource);
        return new ImportDto.VerificationReport(assembled, report.headerReconstructionUncertain(),
                report.textSource(), reliabilityStatus);
    }
}
