package com.finora.imports.evidence;

import com.finora.dto.ImportDto;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.ImportSession;
import com.finora.imports.ImportSessionService;
import com.finora.imports.StagedAccountSectionFilter;
import com.finora.imports.StatementTotalsValidator;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.TextSource;
import com.finora.imports.storage.StatementContentService;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Phase C-4 -- the production evidence-survival mechanism, per
 * {@code c4-investigation-closing-note.md}. Re-derives a {@link MaterialField#CLOSING_BALANCE}
 * {@link FieldAssessment} at confirm time from the exact staged document, going through the SAME
 * ownership/expiry/integrity checks {@code ImportService.confirmSession}/{@code
 * confirmMultiSection} already enforce -- reusing {@link ImportSessionService}/
 * {@link StatementContentService} directly rather than introducing a second storage path.
 *
 * <p><b>This class is deliberately not called from anywhere yet.</b> Nothing in
 * {@code ImportService} references it. It makes evidence RETRIEVABLE at confirm time; it does not
 * make evidence AUTHORITATIVE over anything -- {@code ClosingBalanceGuard} remains the sole gate
 * on whether a closing balance is written, completely unchanged. Wiring this into the actual
 * confirm decision is a later phase (C-6), gated on first deciding what constitutes sufficient
 * single-source evidence (C-5) -- see the C-3 closing note for why turning this into an
 * enforcement gate today, with {@code SUPPORTED} currently unreachable for single-source
 * evidence, would reject every legitimate import.
 *
 * <p><b>Ordering preserved exactly as the investigation found it already enforced:</b>
 * {@link ImportSessionService#getOwnedSession} (ownership + expiry + not-already-confirmed) is
 * called first and throws before any byte is touched; only then does
 * {@link StatementContentService#read} resolve and integrity-verify the bytes. Deliberately calls
 * {@code getOwnedSession}, not {@code claimForConfirmation} -- claiming mutates the session's
 * status and is reserved for the real confirm mutation; re-deriving evidence must not have that
 * side effect, so it can be called (and, in tests, re-called) without disturbing the session a
 * real confirm would later act on.
 */
@Component
public class ClosingBalanceEvidenceRederivationService {

    private static final int DEFAULT_SECTION_INDEX = 0;

    private final ImportSessionService importSessionService;
    private final StatementContentService statementContentService;
    private final PdfPreviewGenerator pdfPreviewGenerator;
    private final StatementTotalsValidator statementTotalsValidator;

    public ClosingBalanceEvidenceRederivationService(ImportSessionService importSessionService,
            StatementContentService statementContentService, PdfPreviewGenerator pdfPreviewGenerator,
            StatementTotalsValidator statementTotalsValidator) {
        this.importSessionService = importSessionService;
        this.statementContentService = statementContentService;
        this.pdfPreviewGenerator = pdfPreviewGenerator;
        this.statementTotalsValidator = statementTotalsValidator;
    }

    /**
     * @param sourceSectionIndex which STAGED ACCOUNT section to assess -- an index into the same
     *                           filtered list {@code ImportService} persists as
     *                           {@code sectionsJson}, never into the generator's raw section list.
     *                           {@code null} for a single-account session (the whole-document case,
     *                           matching {@code ImportService.confirmSession}'s own {@code null},
     *                           which means filtered index 0), an explicit index for one section of
     *                           a multi-account session (matching {@code confirmMultiSection}'s
     *                           per-section loop). See the filtering comment in
     *                           {@link #rederiveClosingBalanceEvidenceDetailed}.
     * @param closingBalanceClaim the value to assess -- see the class-level scoping note in
     *                            {@code ClosingBalanceEvidenceVerticalSliceTest} (C-3): this is
     *                            fed identically to both this method and whatever the caller
     *                            separately passes to {@code ClosingBalanceGuard}, since testing
     *                            divergence between a re-extracted value and a user-edited one is
     *                            a distinct, later concern, not this method's job.
     * @throws IOException propagated from re-running extraction against the resolved bytes
     */
    public FieldAssessment rederiveClosingBalanceEvidence(UUID userId, UUID sessionId,
            Integer sourceSectionIndex, BigDecimal closingBalanceClaim) throws IOException {
        return rederiveClosingBalanceEvidenceDetailed(userId, sessionId, sourceSectionIndex, closingBalanceClaim)
                .assessment();
    }

    /**
     * One re-derivation, together with the intermediate evidence a {@link FieldAssessment} does not
     * carry -- C-9 shadow mode.
     *
     * <p>{@link FieldAssessment} deliberately keeps only the three {@link DimensionResult}s and the
     * combined {@link EvidenceStatus}. Two things shadow mode has to record separately are
     * therefore not recoverable from it: {@link StatementTotalsValidator}'s own outcome and its
     * {@code suspectedCause} (the {@code FINANCIAL_VALIDATION} explanation collapses a
     * {@code FAILED/TRANSACTIONS} finding and a {@code FAILED/}<em>no cause</em> finding into the
     * same sentence), and the correlation axis -- how many facts actually grouped as
     * {@code SAME_FACT} versus were excluded as {@link Correlation#UNCERTAIN}, which
     * {@link MetadataEvidencePipeline} computes internally and drops.
     *
     * <p>The grouping is therefore computed here as well, by calling the same pure static
     * {@link MetadataSameFactGrouper#group} the pipeline calls, over the same observation list.
     * That is a second call to a deterministic function, not a second implementation of it: no
     * grouping rule is restated here, so the two cannot disagree.
     *
     * <p>{@link #rederiveClosingBalanceEvidence} above is unchanged in behaviour and simply reads
     * the assessment out of this result.
     */
    public ClosingBalanceEvidence rederiveClosingBalanceEvidenceDetailed(UUID userId, UUID sessionId,
            Integer sourceSectionIndex, BigDecimal closingBalanceClaim) throws IOException {
        // Ownership + expiry + not-already-confirmed, non-mutating (see class doc for why this is
        // getOwnedSession, not claimForConfirmation). Throws before any byte is touched.
        ImportSession session = importSessionService.getOwnedSession(userId, sessionId);

        // Resolves through whichever storage mode this session actually used (object storage with
        // SHA-256 verification, or legacy BYTEA) -- the exact same call ImportService's real
        // confirm paths make, not a second read path.
        byte[] bytes = statementContentService.read(session);

        int sectionIndex = sourceSectionIndex != null ? sourceSectionIndex : DEFAULT_SECTION_INDEX;
        PdfPreviewGenerator.PdfGenerationResult result =
                pdfPreviewGenerator.generateSectionsWithContext(userId, session.getFileName(), bytes);

        // THE SECTION INDEX IS IN FILTERED SPACE. It comes from confirmMultiSection's per-section
        // loop (an index into the persisted sectionsJson) or from confirmSession's whole-document
        // case (index 0 of a session that staging only routed down the single-account branch
        // BECAUSE the filtered list held one account). Both of those are indices into
        // ImportService's staged list, which is the generator's raw output put through
        // StagedAccountSectionFilter -- so the same filter is applied here, to the same kind of
        // input, before indexing.
        //
        // Indexing into result.sections() directly is the bug this exists to prevent, and it is not
        // a hypothetical one: on the ordinary combined-statement shape, where a term-deposit or
        // recurring-deposit schedule is printed ABOVE the savings ledger, the deposit schedules
        // stage no transactions, get dropped, and every later section shifts down. Reading raw
        // index 0 for a confirm that meant filtered index 0 then assesses an empty deposit
        // schedule and records the result under the savings section's label -- an observation that
        // is not merely missing but inverted, which is worse than none at all in a corpus whose
        // entire purpose is measurement.
        List<ImportDto.StagedAccountSection> sections =
                StagedAccountSectionFilter.onlySectionsThatAreActuallyAccounts(result.sections());
        if (sectionIndex < 0 || sectionIndex >= sections.size()) {
            // Deliberately loud rather than clamped to something plausible. The observer records
            // this as UNAVAILABLE with the exception TYPE only (no message, no document content),
            // which is the honest outcome: no assertion enters the corpus. Silently falling back
            // to another section would reintroduce exactly the mislabelling above.
            throw new IllegalStateException("Section index " + sectionIndex
                    + " is outside the staged account sections (" + sections.size() + ")");
        }
        ImportDto.StagedAccountSection section = sections.get(sectionIndex);

        List<StagedRow> realRows = realTransactionRows(section.rows());
        BigDecimal openingBalance = section.detectedAccount().openingBalance();

        ImportDto.VerificationFinding statementTotals =
                statementTotalsValidator.check(realRows, openingBalance, closingBalanceClaim);
        FinancialValidationContext financialContext =
                new FinancialValidationContext(null, statementTotals, sectionIndex, TextSource.NATIVE_PDF);

        FieldFact<BigDecimal> fact = new FieldFact<>(MaterialField.CLOSING_BALANCE, closingBalanceClaim,
                List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF),
                        new ProvenanceNode.SectionAttribution(sectionIndex, TextSource.NATIVE_PDF)));
        MetadataObservation<BigDecimal> observation =
                new MetadataObservation<>(fact, sectionIndex, null, "Closing Balance");
        MetadataFieldObservation<BigDecimal> fieldObservation =
                new MetadataFieldObservation<>(observation, com.finora.imports.product.EvidenceSource.ROW_DATA);

        List<MetadataFieldObservation<BigDecimal>> observations = List.of(fieldObservation);
        SameFactGroupingResult<BigDecimal> grouping = MetadataSameFactGrouper.group(observations);

        FieldAssessment assessment = MetadataEvidencePipeline.assess(MaterialField.CLOSING_BALANCE,
                observations, closingBalanceClaim, financialContext);

        return new ClosingBalanceEvidence(assessment, statementTotals,
                EvidenceComparison.compare(grouping.sameFactGroup()),
                grouping.sameFactGroup().size(), grouping.excludedAsUncertain().size(),
                grouping.excludedAsDifferent().size());
    }

    /**
     * A re-derived {@link MaterialField#CLOSING_BALANCE} assessment plus the intermediates shadow
     * mode records as separate axes -- see {@link #rederiveClosingBalanceEvidenceDetailed}.
     *
     * @param assessment the combined verdict, whose {@link FieldAssessment#status()} is the
     *        {@link EvidenceStatus} axis
     * @param statementTotals {@link StatementTotalsValidator}'s own finding, kept whole so its
     *        {@code outcome} and {@code suspectedCause} stay separately readable
     * @param comparison the {@link EvidenceComparison} over the same-fact group -- a different
     *        axis from {@code assessment.status()} and never folded into it
     * @param sameFactGroupSize how many facts correlated as {@link Correlation#SAME_FACT}
     * @param excludedAsUncertainCount how many were excluded as {@link Correlation#UNCERTAIN} --
     *        the "no independent corroboration was available" signal
     * @param excludedAsDifferentCount how many were excluded as
     *        {@link Correlation#DIFFERENT_FACT}
     */
    public record ClosingBalanceEvidence(
            FieldAssessment assessment,
            ImportDto.VerificationFinding statementTotals,
            EvidenceComparison comparison,
            int sameFactGroupSize,
            int excludedAsUncertainCount,
            int excludedAsDifferentCount) {
    }

    /** Real transactions only -- excludes the balance-marker rows staging also produces, matching
     *  exactly what {@code ImportService.persistSection}'s row loop sums. Same filter as C-3's
     *  vertical-slice test, not duplicated logic invented independently. */
    private static List<StagedRow> realTransactionRows(List<StagedRow> allRows) {
        return allRows.stream()
                .filter(r -> r.description() == null || !r.description().toUpperCase(Locale.ROOT).contains("BALANCE"))
                .toList();
    }
}
