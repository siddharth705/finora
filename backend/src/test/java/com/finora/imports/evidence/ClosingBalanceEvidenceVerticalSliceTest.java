package com.finora.imports.evidence;

import com.finora.imports.TestAccountRepositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.dto.ImportDto;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.imports.BalanceChainValidator;
import com.finora.imports.ClosingBalanceGuard;
import com.finora.imports.ColumnAmbiguityValidator;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.ImportVerifier;
import com.finora.imports.RowAccountingValidator;
import com.finora.imports.StatementTotalsValidator;
import com.finora.imports.SummaryTotalsValidator;
import com.finora.imports.TestRuleEngines;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.PdfMetadataExtractor;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.PdfTableLocator;
import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.pdf.TextSource;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.product.EvidenceSource;
import com.finora.imports.product.ProductAttributeExtractor;
import com.finora.imports.product.ProductDiscovery;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase C-3 -- the production evidence vertical slice, proof-only, per
 * {@code c2.5-production-evidence-contract.md}. Field: {@link MaterialField#CLOSING_BALANCE},
 * chosen there because it is the only load-bearing field with a real financial validator
 * ({@link StatementTotalsValidator}), a real production enforcement gate already wired
 * ({@link ClosingBalanceGuard}, called from {@code ImportService.persistSection}), and a real
 * documented negative-path bug ({@code ClosingBalanceGuard}'s own "Bug 02").
 *
 * <p><b>Nothing production is modified.</b> This test calls only real, unmodified production
 * code: {@link PdfPreviewGenerator} (real extractor), {@link StatementTotalsValidator}/
 * {@link ClosingBalanceGuard} (real, unmodified, called directly with the same inputs
 * {@code persistSection} computes -- verified by reading that method), and the evidence package
 * (already built, untouched). {@code ImportService}, the DB schema, and {@code ConfirmRequest}'s
 * wire shape are never touched.
 *
 * <p><b>Scoping decision, stated explicitly (not a silent assumption):</b> {@code
 * ClosingBalanceGuard} tests the CLIENT-CONFIRMED closing balance, which can in principle diverge
 * from the freshly-extracted one if a user hand-edits it during review. Every test here feeds both
 * mechanisms the SAME value -- proving the evidence path reaches a coherent conclusion when asked
 * the same question as the existing guard, not detecting client-side tampering, which is a
 * separate, later concern.
 *
 * <p><b>"Confirm-time re-derivation" (contract requirement D)</b> is proven by calling
 * {@link PdfPreviewGenerator#generateSectionsWithContext} a SECOND, independent time against the
 * same stored bytes in {@link #confirmTimeRederivation_isIndependentOfTheStagingResponseObject()}
 * -- not by wiring into {@code ImportService.confirmSession} itself, which is explicitly out of
 * scope for this slice (that is C-4/C-5).
 */
class ClosingBalanceEvidenceVerticalSliceTest {

    private static final int SECTION_INDEX = 0;

    private byte[] goldenFixtureBytes() throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/pdf/separate_debit_credit_balance_sample.pdf"));
    }

    /** Mirrors {@code PdfPreviewGeneratorTest.realGenerator()} exactly -- real extraction, real
     *  verifiers (including the real {@link StatementTotalsValidator}), only the categorization/
     *  duplicate-detection dependencies mocked (irrelevant to metadata evidence). */
    private PdfPreviewGenerator realGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive());
        TransactionNormalizer transactionNormalizer =
                new TransactionNormalizer(categorizationService, duplicateDetector, TestRuleEngines.empty());

        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), transactionNormalizer, ProductDiscovery.standard(),
                new ProductAttributeExtractor(),
                new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                        new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new RowAccountingValidator(), new com.finora.imports.CreditCardStatementTotalsValidator(), new com.finora.imports.CreditCardFlowReconciliationValidator()),
                TestRuleEngines.empty());
    }

    /** Real transactions only -- excludes the two balance-marker rows the fixture also stages
     *  (see {@code PdfPreviewGeneratorTest}'s own comment: "OPENING BALANCE, 4 real transactions,
     *  CLOSING BALANCE"), matching exactly what {@code ImportService.persistSection}'s row loop
     *  sums (rows a user would actually confirm as transactions, not balance markers). */
    private static List<StagedRow> realTransactionRows(List<StagedRow> allRows) {
        return allRows.stream()
                .filter(r -> r.description() == null || !r.description().toUpperCase(java.util.Locale.ROOT).contains("BALANCE"))
                .toList();
    }

    private static BigDecimal sumByType(List<StagedRow> rows, String type) {
        return rows.stream().filter(r -> type.equals(r.type())).map(StagedRow::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Builds the FieldFact + FinancialValidationContext + FieldAssessment for CLOSING_BALANCE
     *  from one real extraction pass, given a candidate closing-balance CLAIM (the value both
     *  ClosingBalanceGuard and the evidence path are asked to assess -- see the class-level
     *  scoping note on why both always receive the same value in this slice). */
    private record EvidenceResult(FieldAssessment assessment, ClosingBalanceGuard.Decision guardDecision) {}

    private EvidenceResult assessClosingBalance(ImportDto.StagedAccountSection section,
            BigDecimal closingBalanceClaim, Account.Type accountType) {
        List<StagedRow> realRows = realTransactionRows(section.rows());
        BigDecimal openingBalance = section.detectedAccount().openingBalance();
        BigDecimal totalCredits = sumByType(realRows, "INCOME");
        BigDecimal totalDebits = sumByType(realRows, "EXPENSE");

        // Real StatementTotalsValidator, called directly with the SAME rows/claim -- the identical
        // arithmetic FinancialValidationContext's design intends it to reference, not duplicate.
        ImportDto.VerificationFinding statementTotals =
                new StatementTotalsValidator().check(realRows, openingBalance, closingBalanceClaim);
        FinancialValidationContext financialContext =
                new FinancialValidationContext(null, statementTotals, SECTION_INDEX, TextSource.NATIVE_PDF);

        FieldFact<BigDecimal> fact = new FieldFact<>(MaterialField.CLOSING_BALANCE, closingBalanceClaim,
                List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF),
                        new ProvenanceNode.SectionAttribution(SECTION_INDEX, TextSource.NATIVE_PDF)));
        MetadataObservation<BigDecimal> observation = new MetadataObservation<>(
                fact, SECTION_INDEX, null, "Closing Balance");
        MetadataFieldObservation<BigDecimal> fieldObservation =
                new MetadataFieldObservation<>(observation, EvidenceSource.ROW_DATA);

        FieldAssessment assessment = MetadataEvidencePipeline.assess(MaterialField.CLOSING_BALANCE,
                List.of(fieldObservation), closingBalanceClaim, financialContext);

        ClosingBalanceGuard.Decision guardDecision = ClosingBalanceGuard.assess(accountType,
                openingBalance, closingBalanceClaim, totalCredits, totalDebits, realRows.size(), 0);

        return new EvidenceResult(assessment, guardDecision);
    }

    // --- A. Happy path ---

    @Test
    void happyPath_realExtractionAndRealArithmeticBothSupportTheClaimedBalance() throws Exception {
        var section = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "statement.pdf", goldenFixtureBytes()).sections().get(0);
        BigDecimal detectedClosingBalance = section.detectedAccount().closingBalance();
        assertThat(detectedClosingBalance).isEqualByComparingTo("117209.50"); // matches the golden fixture's own known value

        EvidenceResult result = assessClosingBalance(section, detectedClosingBalance, Account.Type.SAVINGS);

        // Both individual signals agree the claim is arithmetically sound:
        assertThat(result.guardDecision().verdict()).isEqualTo(ClosingBalanceGuard.Verdict.CORROBORATED);
        assertThat(result.assessment().financialValidation().status()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(result.assessment().structural().status()).isEqualTo(EvidenceStatus.SUPPORTED);

        // The honest, load-bearing finding of this vertical slice: FieldAssessment.status() does
        // NOT reach SUPPORTED here, even though ClosingBalanceGuard accepts. This is not a defect
        // in either mechanism -- it is FieldAssessment correctly being MORE conservative than
        // ClosingBalanceGuard for a single-source production import. Structural and
        // FinancialValidation both trace to the SAME SectionAttribution node (there is only one
        // acquisition source today, per the C-2 production investigation), so per design §3.5 they
        // are correctly refused as independent evidence absent a remediation policy -- which is
        // deliberately NOT wired in this slice (constraint: do not wire
        // RequireIndependentSectionIdentityPolicy to ProductValidator yet). ClosingBalanceGuard
        // asks a narrower question (does the arithmetic hold) than FieldAssessment (does
        // independent evidence support this value) -- and the narrower question is exactly the one
        // that could NOT have caught the ICICI-shape bug this whole ADR chain traces back to, since
        // arithmetic can be self-consistent while attributed to the wrong section entirely.
        assertThat(result.assessment().corroboration().status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
        assertThat(result.assessment().status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    // --- B. Negative path ---

    @Test
    void negativePath_arithmeticMismatch_bothMechanismsReject() throws Exception {
        var section = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "statement.pdf", goldenFixtureBytes()).sections().get(0);
        // The known ClosingBalanceGuard failure shape (ClosingBalanceGuardTest's own convention:
        // feed a claim the real extracted rows do not reach) -- real extracted rows and real
        // opening balance, a deliberately wrong claimed closing balance.
        BigDecimal wrongClosingBalance = new BigDecimal("999999.00");

        EvidenceResult result = assessClosingBalance(section, wrongClosingBalance, Account.Type.SAVINGS);

        assertThat(result.guardDecision().verdict()).isEqualTo(ClosingBalanceGuard.Verdict.UNCORROBORATED);
        // Second honest finding of this slice, run against real production code, not assumed:
        // this specific mismatch makes StatementTotalsValidator attribute the failure to
        // suspectedCause=TRANSACTIONS (the rows' own last stated balance does not reach the
        // claim), not OPENING_BALANCE. Per DimensionAssessor.assessFinancialValidation's existing,
        // already-reviewed Phase C logic, only an OPENING_BALANCE-attributed failure resolves
        // CLOSING_BALANCE's dimension definitively (to SUPPORTED, since the rows independently
        // corroborate it); a TRANSACTIONS-attributed one leaves CLOSING_BALANCE's own dimension
        // INSUFFICIENT, not CONFLICTING -- deliberately conservative rather than confidently wrong,
        // per that code's own "not attributable to this field specifically" branch. This is NOT a
        // bug this slice should fix (changing DimensionAssessor is a refactor, out of scope for a
        // proof slice) -- it is a real, worth-recording refinement candidate for a later phase: a
        // direct lastRowBalance-vs-claim comparison (already present in the same
        // VerificationFinding's details map) could sharpen this to CONFLICTING.
        //
        // What matters for THIS slice's success criterion still holds: per the enforcement
        // semantics agreed for C-5 (SUPPORTED -> allowed, CONFLICTING/INSUFFICIENT -> rejected),
        // both mechanisms land in the "reject" bucket -- they do not disagree on the accept/reject
        // OUTCOME, only on which of the two REJECT-family statuses names it.
        assertThat(result.assessment().financialValidation().status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
        assertThat(result.assessment().status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
        assertThat(result.assessment().status()).isNotEqualTo(EvidenceStatus.SUPPORTED);
    }

    // --- C. Missing/insufficient evidence ---

    @Test
    void missingEvidence_noClosingBalanceStated_neitherMechanismManufacturesAcceptance() throws Exception {
        var section = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "statement.pdf", goldenFixtureBytes()).sections().get(0);

        // No claim at all -- ClosingBalanceGuard's own NOT_APPLICABLE path.
        ClosingBalanceGuard.Decision guardDecision = ClosingBalanceGuard.assess(Account.Type.SAVINGS,
                section.detectedAccount().openingBalance(), null, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
        assertThat(guardDecision.verdict()).isEqualTo(ClosingBalanceGuard.Verdict.NOT_APPLICABLE);
        assertThat(guardDecision.mayOverwriteAccountBalance()).isFalse();

        // No value observed at all -- FieldCandidate itself is INSUFFICIENT with zero facts, by
        // construction (Phase A's own invariant), never SUPPORTED. Nothing to assess a
        // FieldAssessment for in the first place: there is no candidate value to build one from,
        // which is itself the honest "no support manufactured from nothing" proof.
        FieldCandidate<BigDecimal> emptyCandidate =
                FieldCandidate.of(MaterialField.CLOSING_BALANCE, BigDecimal.ZERO, List.of());
        assertThat(emptyCandidate.status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    // --- D. Confirm-time re-derivation ---

    @Test
    void confirmTimeRederivation_isIndependentOfTheStagingResponseObject() throws Exception {
        byte[] storedBytes = goldenFixtureBytes(); // stands in for statementContentService.read(session)

        // First ("staging") pass.
        var stagingSection = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "statement.pdf", storedBytes).sections().get(0);

        // Second, independent pass against the SAME stored bytes -- a fresh PdfPreviewGenerator
        // instance, a fresh call, nothing carried over from the first pass's returned objects.
        // Stands in for what a real confirm-time re-derivation would do: re-run extraction from
        // the session's stored bytes rather than trusting whatever DetectedAccountInfo staging
        // returned to the client and got echoed back.
        var confirmTimeSection = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "statement.pdf", storedBytes).sections().get(0);

        BigDecimal stagingValue = stagingSection.detectedAccount().closingBalance();
        BigDecimal rederivedValue = confirmTimeSection.detectedAccount().closingBalance();

        assertThat(rederivedValue).isEqualByComparingTo(stagingValue);
        assertThat(rederivedValue).isEqualByComparingTo("117209.50");

        // And the re-derived value builds a real FieldAssessment exactly as the staging-time one
        // did -- the mechanism does not depend on which pass's DetectedAccountInfo it started from.
        EvidenceResult fromRederivation = assessClosingBalance(confirmTimeSection, rederivedValue, Account.Type.SAVINGS);
        EvidenceResult fromStaging = assessClosingBalance(stagingSection, stagingValue, Account.Type.SAVINGS);
        assertThat(fromRederivation.assessment().status()).isEqualTo(fromStaging.assessment().status());
        assertThat(fromRederivation.guardDecision().verdict()).isEqualTo(fromStaging.guardDecision().verdict());
    }
}
