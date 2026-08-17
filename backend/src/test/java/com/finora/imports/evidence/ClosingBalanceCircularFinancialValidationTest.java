package com.finora.imports.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finora.dto.ImportDto;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.BalanceChainValidator;
import com.finora.imports.ColumnAmbiguityValidator;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.ImportVerifier;
import com.finora.imports.RowAccountingValidator;
import com.finora.imports.StagedAccountSectionFilter;
import com.finora.imports.StatementTotalsValidator;
import com.finora.imports.SummaryTotalsValidator;
import com.finora.imports.TestRuleEngines;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.analysis.Pass2CorpusFixtures;
import com.finora.imports.pdf.PdfMetadataExtractor;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.PdfTableLocator;
import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.pdf.TextSource;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.product.ProductAttributeExtractor;
import com.finora.imports.product.ProductDiscovery;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

/**
 * C-10 -- the circular {@code FAILED + suspectedCause=OPENING_BALANCE -> SUPPORTED} branch of
 * {@link DimensionAssessor#assessFinancialValidation}, proven closed against the real corpus rather
 * than only against a hand-built {@code VerificationFinding}.
 *
 * <p>The defect, restated from {@code c10-circular-financial-validation-investigation.md}: on the
 * native PDF path the closing-balance CLAIM is the chronologically last row's own running-balance
 * cell ({@code PdfPreviewGenerator:528}), and {@code suspectedCause} is decided by comparing
 * {@code lastStatedBalance(rows)} against that claim ({@code StatementTotalsValidator:83-85}). Those
 * are the same cell, so the predicate is {@code x == x} and the {@code SUPPORTED} it produced
 * carried no information. C-9 measured 27/27 {@code SUPPORTED} on this dimension; C-10 showed 27/27
 * was structural, not a corpus property.
 *
 * <p>Every fixture below is driven through REAL staging ({@link PdfPreviewGenerator}) and the REAL
 * {@link StatementTotalsValidator}, in the same shape
 * {@link ClosingBalanceEvidenceRederivationService} assembles at confirm time -- so these tests fail
 * if either the validator's attribution or the dimension's handling of it regresses, not only if
 * this one branch is edited.
 *
 * <p><b>Scope.</b> Shadow-mode telemetry only. Nothing outside {@code com.finora.imports.evidence}
 * reads a {@link FieldAssessment} or an {@link EvidenceStatus} -- pinned by
 * {@link ShadowModeHasNoConsumerTest} -- so a changed verdict here changes a recorded column and
 * nothing a user sees.
 */
class ClosingBalanceCircularFinancialValidationTest {

    private static final int SECTION_INDEX = 0;

    // ---------------------------------------------------------------- real staging plumbing

    /** Mirrors {@code ClosingBalanceEvidenceVerticalSliceTest.realGenerator()} exactly. */
    private static PdfPreviewGenerator realGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        TransactionNormalizer transactionNormalizer =
                new TransactionNormalizer(categorizationService, duplicateDetector, TestRuleEngines.empty());

        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), transactionNormalizer, ProductDiscovery.standard(),
                new ProductAttributeExtractor(),
                new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                        new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new RowAccountingValidator()),
                TestRuleEngines.empty());
    }

    /** Same filter {@code ClosingBalanceEvidenceRederivationService:140-141} applies before indexing. */
    private static ImportDto.StagedAccountSection stageSection(byte[] pdf) throws Exception {
        List<ImportDto.StagedAccountSection> sections = StagedAccountSectionFilter
                .onlySectionsThatAreActuallyAccounts(realGenerator()
                        .generateSectionsWithContext(UUID.randomUUID(), "statement.pdf", pdf).sections());
        return sections.get(SECTION_INDEX);
    }

    /** Same row filter {@code ClosingBalanceEvidenceRederivationService:210-214} applies. */
    private static List<StagedRow> realTransactionRows(List<StagedRow> allRows) {
        return allRows.stream()
                .filter(r -> r.description() == null || !r.description().toUpperCase(Locale.ROOT).contains("BALANCE"))
                .toList();
    }

    /** What shadow mode records for one section: the validator's finding plus the dimension it
     *  produces for {@code CLOSING_BALANCE}, assembled exactly as the re-derivation service does. */
    private record Observed(ImportDto.VerificationFinding statementTotals, DimensionResult closingBalanceDimension,
            DimensionResult openingBalanceDimension, FieldAssessment assessment) {

        Object suspectedCause() {
            return statementTotals.details().get("suspectedCause");
        }
    }

    private static Observed observe(ImportDto.StagedAccountSection section) {
        return observe(section, section.detectedAccount().closingBalance());
    }

    private static Observed observe(ImportDto.StagedAccountSection section, BigDecimal closingBalanceClaim) {
        List<StagedRow> realRows = realTransactionRows(section.rows());
        return observe(realRows, section.detectedAccount().openingBalance(), closingBalanceClaim);
    }

    private static Observed observe(List<StagedRow> realRows, BigDecimal openingBalance,
            BigDecimal closingBalanceClaim) {
        ImportDto.VerificationFinding statementTotals =
                new StatementTotalsValidator().check(realRows, openingBalance, closingBalanceClaim);
        FinancialValidationContext context = new FinancialValidationContext(
                null, statementTotals, SECTION_INDEX, TextSource.NATIVE_PDF);

        FieldFact<BigDecimal> fact = new FieldFact<>(MaterialField.CLOSING_BALANCE, closingBalanceClaim,
                List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF),
                        new ProvenanceNode.SectionAttribution(SECTION_INDEX, TextSource.NATIVE_PDF)));
        MetadataObservation<BigDecimal> observation =
                new MetadataObservation<>(fact, SECTION_INDEX, null, "Closing Balance");
        MetadataFieldObservation<BigDecimal> fieldObservation = new MetadataFieldObservation<>(
                observation, com.finora.imports.product.EvidenceSource.ROW_DATA);
        FieldAssessment assessment = MetadataEvidencePipeline.assess(MaterialField.CLOSING_BALANCE,
                List.of(fieldObservation), closingBalanceClaim, context);

        return new Observed(statementTotals,
                DimensionAssessor.assessFinancialValidation(MaterialField.CLOSING_BALANCE, null, context),
                DimensionAssessor.assessFinancialValidation(MaterialField.OPENING_BALANCE, null, context),
                assessment);
    }

    // ================================================================
    // Property 1 -- the five FAILED documents of the C-9 corpus
    // ================================================================

    private interface FixtureBytes {
        byte[] get() throws Exception;
    }

    private static byte[] pass2(String id) throws Exception {
        return Pass2CorpusFixtures.all().stream()
                .filter(f -> f.spec().id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Pass2 fixture not found: " + id))
                .bytes();
    }

    /**
     * The complete set of {@code statementTotalsOutcome=FAILED} documents in C-9 §2.2's measured
     * corpus -- all five, all recorded {@code suspectedCause=OPENING_BALANCE}, all previously
     * recorded {@code financialValidationStatus=SUPPORTED}.
     */
    static java.util.stream.Stream<Arguments> c9FailedCorpus() {
        return java.util.stream.Stream.of(
                Arguments.of("buildReferenceNumberAndBalanceSample",
                        (FixtureBytes) PdfFixtureBuilder::buildReferenceNumberAndBalanceSample),
                Arguments.of("buildReverseChronologicalRunningBalanceSample",
                        (FixtureBytes) PdfFixtureBuilder::buildReverseChronologicalRunningBalanceSample),
                Arguments.of("buildSingularDepositWithdrawalColumnsSample",
                        (FixtureBytes) PdfFixtureBuilder::buildSingularDepositWithdrawalColumnsSample),
                Arguments.of("buildSingleTrailingBalanceDiscrepancySample",
                        (FixtureBytes) PdfFixtureBuilder::buildSingleTrailingBalanceDiscrepancySample),
                Arguments.of("merged-amount-single-run",
                        (FixtureBytes) () -> pass2("merged-amount-single-run")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("c9FailedCorpus")
    void c9FailedDocuments_areNoLongerPromotedToSupported(String name, FixtureBytes bytes) throws Exception {
        Observed observed = observe(stageSection(bytes.get()));

        // The precondition C-9 measured is still exactly reproduced -- if it were not, this test
        // would be asserting the fix on an input that never reached the branch at all.
        assertThat(observed.statementTotals().outcome()).as("%s statement totals outcome", name).isEqualTo("FAILED");
        assertThat(observed.suspectedCause()).as("%s suspected cause", name).isEqualTo("OPENING_BALANCE");

        // And the branch no longer manufactures support from a value compared against itself.
        assertThat(observed.closingBalanceDimension().status()).as("%s financialValidation", name)
                .isEqualTo(EvidenceStatus.INSUFFICIENT);
        assertThat(observed.closingBalanceDimension().status()).as("%s must not be SUPPORTED", name)
                .isNotEqualTo(EvidenceStatus.SUPPORTED);
    }

    /**
     * The worst case in the corpus, called out on its own because it is the one document where the
     * closing balance is provably the wrong figure: the printed final balance is 8305.00 where the
     * chain reaches 8300.00 ({@code PdfFixtureBuilder.buildSingleTrailingBalanceDiscrepancySample}).
     * Before C-10 this document reported the closing balance SUPPORTED.
     */
    @Test
    void provablyWrongClosingBalance_isNotReportedAsSupported() throws Exception {
        var section = stageSection(PdfFixtureBuilder.buildSingleTrailingBalanceDiscrepancySample());
        Observed observed = observe(section);

        assertThat(section.detectedAccount().closingBalance()).isEqualByComparingTo("8305.00");
        assertThat(observed.statementTotals().details().get("expectedClosingBalance"))
                .isEqualTo(new BigDecimal("8300.00"));
        assertThat(observed.suspectedCause()).isEqualTo("OPENING_BALANCE");
        assertThat(observed.closingBalanceDimension().status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    // ================================================================
    // Property 2 -- a GENUINE opening-balance discrepancy
    // ================================================================

    /**
     * Not an extraction artifact and not a wrong closing balance: a ledger whose rows form a
     * perfect chain reaching a correct closing balance, against an opening balance that is
     * genuinely 100.00 too high. This is the only shape the removed branch was ever written for.
     *
     * <p>It still classifies as {@code suspectedCause=OPENING_BALANCE} (untouched attribution
     * logic), the {@code OPENING_BALANCE} field is still {@code CONFLICTING} (untouched branch) --
     * and {@code CLOSING_BALANCE} is nevertheless {@code INSUFFICIENT}, because the agreement that
     * produced the attribution is still a comparison of the claim against the row it came from. The
     * gate does not depend on what is "really" wrong with the document; it depends on the claim
     * having no independent origin, which is true here too.
     */
    @Test
    void genuineOpeningBalanceDiscrepancy_stillAttributedToOpeningBalance_butClosingBalanceIsNotSupported() {
        List<StagedRow> rows = List.of(
                row("2026-07-01", "UPI/DR/One", "500.00", "EXPENSE", "9500.00"),
                row("2026-07-05", "UPI/DR/Two", "300.00", "EXPENSE", "9200.00"),
                row("2026-07-10", "UPI/CR/Three", "700.00", "INCOME", "9900.00"));
        BigDecimal claim = new BigDecimal("9900.00");           // the ledger's own last balance
        BigDecimal wrongOpening = new BigDecimal("10100.00");   // truth is 10000.00

        Observed observed = observe(rows, wrongOpening, claim);

        assertThat(observed.statementTotals().outcome()).isEqualTo("FAILED");
        assertThat(observed.suspectedCause()).isEqualTo("OPENING_BALANCE");
        // Untouched branch: the opening balance IS implicated, and still says so.
        assertThat(observed.openingBalanceDimension().status()).isEqualTo(EvidenceStatus.CONFLICTING);
        // The branch under test: no support for the closing balance, correctly-attributed or not.
        assertThat(observed.closingBalanceDimension().status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    // ================================================================
    // Property 3 -- REGRESSION: the VERIFIED branch is untouched
    // ================================================================

    /**
     * A document that genuinely reconciles must be completely unaffected. {@code VERIFIED} is a real
     * arithmetic cross-check -- the balance column's endpoints against the amount column's sum, two
     * different columns (C-10 §"Branch A") -- and C-10 explicitly declines to change it.
     */
    @Test
    void verifiedReconcilingDocument_isStillSupported() throws Exception {
        var section = stageSection(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/test/resources/pdf/separate_debit_credit_balance_sample.pdf")));
        Observed observed = observe(section);

        assertThat(section.detectedAccount().closingBalance()).isEqualByComparingTo("117209.50");
        assertThat(observed.statementTotals().outcome()).isEqualTo("VERIFIED");
        assertThat(observed.closingBalanceDimension().status()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(observed.closingBalanceDimension().explanation())
                .isEqualTo("opening + transactions reconciles to closing");
        // The overall assessment is unchanged too: still INSUFFICIENT, for the separate and correct
        // single-source reason (structural and financialValidation share one SectionAttribution).
        assertThat(observed.assessment().status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    /** The same regression proof over further real-staged VERIFIED documents from the C-9 corpus,
     *  so "unaffected" is shown across document shapes rather than on one golden file. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("c9VerifiedCorpus")
    void verifiedDocumentsAcrossTheCorpus_areStillSupported(String name, FixtureBytes bytes) throws Exception {
        Observed observed = observe(stageSection(bytes.get()));

        assertThat(observed.statementTotals().outcome()).as("%s statement totals outcome", name).isEqualTo("VERIFIED");
        assertThat(observed.closingBalanceDimension().status()).as("%s financialValidation", name)
                .isEqualTo(EvidenceStatus.SUPPORTED);
    }

    static java.util.stream.Stream<Arguments> c9VerifiedCorpus() {
        return java.util.stream.Stream.of(
                Arguments.of("buildLeadingNarrationContinuationSample",
                        (FixtureBytes) PdfFixtureBuilder::buildLeadingNarrationContinuationSample),
                Arguments.of("buildMonthNameFirstDrCrColumnSample",
                        (FixtureBytes) PdfFixtureBuilder::buildMonthNameFirstDrCrColumnSample),
                Arguments.of("buildNarrationAboveItsDateRowSample",
                        (FixtureBytes) PdfFixtureBuilder::buildNarrationAboveItsDateRowSample),
                Arguments.of("buildParenthesizedDrCrRunningBalanceSample",
                        (FixtureBytes) PdfFixtureBuilder::buildParenthesizedDrCrRunningBalanceSample),
                Arguments.of("icici-serial-ledger", (FixtureBytes) () -> pass2("icici-serial-ledger")),
                Arguments.of("union-bank-single-amount-ledger",
                        (FixtureBytes) () -> pass2("union-bank-single-amount-ledger")));
    }

    /** Unit-grain restatement of the same regression, at the exact input the branch reads, so a
     *  future edit to the {@code VERIFIED} arm fails here even if no fixture happens to cover it. */
    @Test
    void verifiedBranch_isUntouched_atTheDimensionInput() {
        FinancialValidationContext context = new FinancialValidationContext(null,
                new ImportDto.VerificationFinding("STATEMENT_TOTALS", "VERIFIED", java.util.Map.of()),
                SECTION_INDEX, TextSource.NATIVE_PDF);

        assertThat(DimensionAssessor.assessFinancialValidation(MaterialField.CLOSING_BALANCE, null, context).status())
                .isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(DimensionAssessor.assessFinancialValidation(MaterialField.OPENING_BALANCE, null, context).status())
                .isEqualTo(EvidenceStatus.SUPPORTED);
    }

    /** And the other two {@code FAILED} arms keep their existing verdicts -- also out of C-10's
     *  scope, also pinned so this change cannot have widened past the one branch. */
    @Test
    void otherFailedArms_areUntouched() {
        FinancialValidationContext transactions = new FinancialValidationContext(null,
                new ImportDto.VerificationFinding("STATEMENT_TOTALS", "FAILED",
                        java.util.Map.of("suspectedCause", "TRANSACTIONS")),
                SECTION_INDEX, TextSource.NATIVE_PDF);
        FinancialValidationContext noCause = new FinancialValidationContext(null,
                new ImportDto.VerificationFinding("STATEMENT_TOTALS", "FAILED", java.util.Map.of()),
                SECTION_INDEX, TextSource.NATIVE_PDF);

        assertThat(DimensionAssessor.assessFinancialValidation(
                MaterialField.CLOSING_BALANCE, null, transactions).status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
        assertThat(DimensionAssessor.assessFinancialValidation(
                MaterialField.OPENING_BALANCE, null, transactions).status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
        assertThat(DimensionAssessor.assessFinancialValidation(
                MaterialField.CLOSING_BALANCE, null, noCause).status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
        // CLOSING_BALANCE still has no CONFLICTING outcome anywhere in this dimension (C-10 §E3) --
        // recorded as a known gap, deliberately NOT closed here.
        assertThat(DimensionAssessor.assessFinancialValidation(
                MaterialField.CLOSING_BALANCE, null, noCause).status()).isNotEqualTo(EvidenceStatus.CONFLICTING);
    }

    // ================================================================
    // Property 4 -- the single-source native path is the only path there is
    // ================================================================

    /**
     * The gate is unconditional on acquisition source, and that is deliberate: {@code
     * RoutingTextAcquirer} makes native and OCR mutually exclusive (C-10 §"A second acquisition
     * source"), so a document is one or the other and neither carries a second, independent
     * closing-balance observation. Whichever {@link TextSource} a future caller stamps into the
     * context, the claim's origin is still the ledger the validator reconciles.
     */
    @Test
    void gateAppliesOnEverySingleSourceAcquisitionPath() {
        for (TextSource source : TextSource.values()) {
            FinancialValidationContext context = new FinancialValidationContext(null,
                    new ImportDto.VerificationFinding("STATEMENT_TOTALS", "FAILED",
                            java.util.Map.of("suspectedCause", "OPENING_BALANCE")),
                    SECTION_INDEX, source);

            assertThat(DimensionAssessor.assessFinancialValidation(
                    MaterialField.CLOSING_BALANCE, null, context).status())
                    .as("financialValidation for a %s acquisition", source)
                    .isEqualTo(EvidenceStatus.INSUFFICIENT);
        }
    }

    /** The production caller is the native path, and it is the only one wired: the re-derivation
     *  service stamps {@code NATIVE_PDF} unconditionally
     *  ({@code ClosingBalanceEvidenceRederivationService:160}). Pinned so "this fix covers the only
     *  path that exists" stays a checked statement rather than a remembered one. */
    @Test
    void theOnlyWiredAcquisitionPathIsNative() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/finora/imports/evidence/ClosingBalanceEvidenceRederivationService.java"));

        assertThat(source).contains("TextSource.NATIVE_PDF");
        assertThat(source).doesNotContain("TextSource.OCR");
    }

    // ================================================================
    // Property 5 -- forward compatibility: what would have to be true to allow SUPPORTED again
    // ================================================================

    /**
     * <b>This test exists to fail.</b> It pins the ABSENCE of a claim-origin signal, which is the
     * precondition the removed branch silently assumed.
     *
     * <p>{@link FinancialValidationContext} today carries the acquisition that produced the ROW
     * data ({@code fromSource}) and nothing at all about where the closing-balance CLAIM came from
     * -- C-10 §Q3: <i>the claim's origin is nowhere represented</i>. That is exactly why the branch
     * cannot test its own precondition and had to be removed rather than guarded.
     *
     * <p>If someone adds such a component -- an independent-origin flag, a claim
     * {@code ProvenanceNode} set, a printed-summary-sourced balance (C-10 R2) -- this assertion
     * fails, and the failure is the point: it forces a deliberate revisit of
     * {@link DimensionAssessor#assessFinancialValidation}'s {@code CLOSING_BALANCE} arm, where the
     * new signal must be CHECKED before any {@code SUPPORTED} is restored. Restoring the blanket
     * promotion without such a check reintroduces the {@code x == x} circularity.
     *
     * <p>To be explicit about the shape required, in the order it must be built:
     * <ol>
     *   <li>an independently-acquired closing balance must exist at all (none does today -- see
     *       {@code StatementSummaryExtractor.PrintedSummary}, which reads debit/credit totals and
     *       counts and no balance);</li>
     *   <li>its origin must reach this dimension, e.g. as a new {@code FinancialValidationContext}
     *       component or as claim provenance comparable via
     *       {@link EvidenceAssessor#shareAnUpstreamFailureMode};</li>
     *   <li>the branch must then return {@code SUPPORTED} only when that comparison shows the claim
     *       did NOT come from the rows the validator reconciled it against.</li>
     * </ol>
     * Neither (1) nor (2) is implemented here; C-10 R1/R2 defer both pending real corpus evidence.
     */
    @Test
    void financialValidationContextCarriesNoIndependentOriginForTheClaim_yet() {
        List<String> components = java.util.Arrays.stream(FinancialValidationContext.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(components)
                .as("FinancialValidationContext gained a component -- if it represents the "
                        + "closing-balance CLAIM's origin, DimensionAssessor's CLOSING_BALANCE arm must "
                        + "now CHECK it before any FAILED+OPENING_BALANCE case may return SUPPORTED "
                        + "again (C-10). Do not simply update this list.")
                .containsExactly("balanceChain", "statementTotals", "sectionIndex", "fromSource");
    }

    // ---------------------------------------------------------------- helpers

    private static StagedRow row(String date, String description, String amount, String type, String balanceAfter) {
        return new StagedRow(LocalDate.parse(date), description, new BigDecimal(amount), type,
                "Uncategorized", "default", null, false, null, new BigDecimal(balanceAfter));
    }
}
