package com.finora.imports.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.dto.ImportDto;
import com.finora.imports.BalanceChainValidator;
import com.finora.imports.pdf.TextSource;
import com.finora.imports.product.EvidenceSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DimensionAssessorTest {

    private static FieldFact<String> fact(MaterialField field, String value, ProvenanceNode... provenance) {
        return new FieldFact<>(field, value, List.of(provenance));
    }

    // --- assessStructural ---

    @Test
    void assessStructural_rowData_isSatisfied() {
        SourcedFact<String> f = new SourcedFact<>(
                fact(MaterialField.ACCOUNT_HOLDER, "Jane Doe", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)),
                EvidenceSource.ROW_DATA);

        DimensionResult result = DimensionAssessor.assessStructural(List.of(f));

        assertThat(result.status()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(result.dimension()).isEqualTo(DimensionResult.Dimension.STRUCTURAL);
    }

    @Test
    void assessStructural_sectionTextOnly_isNotSatisfied() {
        SourcedFact<String> f = new SourcedFact<>(
                fact(MaterialField.ACCOUNT_HOLDER, "Jane Doe", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)),
                EvidenceSource.SECTION_TEXT);

        assertThat(DimensionAssessor.assessStructural(List.of(f)).status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    @Test
    void assessStructural_documentTextOnly_isNotSatisfied() {
        SourcedFact<String> f = new SourcedFact<>(
                fact(MaterialField.ACCOUNT_HOLDER, "Jane Doe", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)),
                EvidenceSource.DOCUMENT_TEXT);

        assertThat(DimensionAssessor.assessStructural(List.of(f)).status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    @Test
    void assessStructural_strongestOfSeveralWins() {
        SourcedFact<String> weak = new SourcedFact<>(
                fact(MaterialField.ACCOUNT_HOLDER, "Jane Doe", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)),
                EvidenceSource.DOCUMENT_TEXT);
        SourcedFact<String> strong = new SourcedFact<>(
                fact(MaterialField.ACCOUNT_HOLDER, "Jane Doe", new ProvenanceNode.Acquisition(TextSource.OCR)),
                EvidenceSource.COLUMN_HEADERS);

        assertThat(DimensionAssessor.assessStructural(List.of(weak, strong)).status())
                .isEqualTo(EvidenceStatus.SUPPORTED);
    }

    @Test
    void assessStructural_rejectsEmpty() {
        assertThatThrownBy(() -> DimensionAssessor.assessStructural(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- assessCorroboration ---

    @Test
    void assessCorroboration_agree_isSupported() {
        List<FieldFact<String>> group = List.of(
                fact(MaterialField.ACCOUNT_HOLDER, "Jane Doe", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)),
                fact(MaterialField.ACCOUNT_HOLDER, "Jane Doe", new ProvenanceNode.Acquisition(TextSource.OCR)));

        assertThat(DimensionAssessor.assessCorroboration(group).status()).isEqualTo(EvidenceStatus.SUPPORTED);
    }

    @Test
    void assessCorroboration_disagree_isConflicting() {
        List<FieldFact<String>> group = List.of(
                fact(MaterialField.ACCOUNT_HOLDER, "Jane Doe", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)),
                fact(MaterialField.ACCOUNT_HOLDER, "J. Doe", new ProvenanceNode.Acquisition(TextSource.OCR)));

        assertThat(DimensionAssessor.assessCorroboration(group).status()).isEqualTo(EvidenceStatus.CONFLICTING);
    }

    @Test
    void assessCorroboration_uncontested_isInsufficient_neverSupported() {
        List<FieldFact<String>> group = List.of(
                fact(MaterialField.ACCOUNT_HOLDER, "Jane Doe", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)));

        assertThat(DimensionAssessor.assessCorroboration(group).status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    @Test
    void assessCorroboration_rejectsEmpty_absentIsNotAValidAssessmentInput() {
        assertThatThrownBy(() -> DimensionAssessor.assessCorroboration(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assessCorroboration_agreeingButSharedFailureMode_isInsufficient_notSupported() {
        // Adversarial-review finding (P0): two facts agreeing but sharing the SAME
        // ColumnLayoutInterpretation node -- the exact ICICI/shared-column-layout shape ADR-006
        // exists to catch -- must not count as independent corroboration merely because their
        // values happen to match. This is what "independent acquisition source" in design §3.2
        // means, enforced, not merely documented.
        ProvenanceNode.ColumnLayoutInterpretation sharedColumns =
                new ProvenanceNode.ColumnLayoutInterpretation(0, "anchors:100,300");
        List<FieldFact<String>> group = List.of(
                fact(MaterialField.TRANSACTION_DIRECTION, "DEBIT",
                        new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF), sharedColumns),
                fact(MaterialField.TRANSACTION_DIRECTION, "DEBIT",
                        new ProvenanceNode.Acquisition(TextSource.OCR), sharedColumns));

        assertThat(DimensionAssessor.assessCorroboration(group).status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    @Test
    void assessCorroboration_duplicateWithinSingleAcquisition_isInsufficient_notSupported() {
        // A parsing duplicate-row bug producing the "same" observation twice from one native pass
        // (same Acquisition node) must not manufacture corroboration either.
        ProvenanceNode.Acquisition sameRun = new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF);
        List<FieldFact<String>> group = List.of(
                fact(MaterialField.TRANSACTION_AMOUNT, "500.00", sameRun),
                fact(MaterialField.TRANSACTION_AMOUNT, "500.00", sameRun));

        assertThat(DimensionAssessor.assessCorroboration(group).status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    @Test
    void assessCorroboration_independentAgreement_stillSupported_afterTheFix() {
        // Confirms the fix didn't overcorrect: genuinely independent agreement (different
        // Acquisition, no shared node at all) still reaches SUPPORTED.
        List<FieldFact<String>> group = List.of(
                fact(MaterialField.TRANSACTION_DIRECTION, "DEBIT", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)),
                fact(MaterialField.TRANSACTION_DIRECTION, "DEBIT", new ProvenanceNode.Acquisition(TextSource.OCR)));

        assertThat(DimensionAssessor.assessCorroboration(group).status()).isEqualTo(EvidenceStatus.SUPPORTED);
    }

    // --- assessFinancialValidation: transaction-level fields ---

    private static BalanceChainValidator.Result balanceResult(
            BalanceChainValidator.Outcome outcome, BalanceChainValidator.Discrepancy... discrepancies) {
        return new BalanceChainValidator.Result(outcome, List.of(discrepancies), 10, 10);
    }

    private static BalanceChainValidator.Discrepancy discrepancyAt(int rowIndex) {
        return new BalanceChainValidator.Discrepancy(rowIndex, LocalDate.of(2026, 3, 5), "desc",
                new BigDecimal("1000.00"), new BigDecimal("500.00"), new BigDecimal("500.00"));
    }

    @Test
    void assessFinancialValidation_transactionAmount_noDiscrepancyAtRow_isSupported() {
        FinancialValidationContext context = new FinancialValidationContext(
                balanceResult(BalanceChainValidator.Outcome.VERIFIED), null, 0, TextSource.NATIVE_PDF);

        DimensionResult result = DimensionAssessor.assessFinancialValidation(
                MaterialField.TRANSACTION_AMOUNT, 4, context);

        assertThat(result.status()).isEqualTo(EvidenceStatus.SUPPORTED);
    }

    @Test
    void assessFinancialValidation_transactionAmount_discrepancyAtThisRow_isConflicting() {
        FinancialValidationContext context = new FinancialValidationContext(
                balanceResult(BalanceChainValidator.Outcome.FAILED, discrepancyAt(4)), null, 0, TextSource.NATIVE_PDF);

        DimensionResult result = DimensionAssessor.assessFinancialValidation(
                MaterialField.TRANSACTION_AMOUNT, 4, context);

        assertThat(result.status()).isEqualTo(EvidenceStatus.CONFLICTING);
    }

    @Test
    void assessFinancialValidation_transactionAmount_discrepancyAtDifferentRow_isSupported() {
        // The discrepancy exists, but not at THIS row -- this row's own arithmetic still holds.
        FinancialValidationContext context = new FinancialValidationContext(
                balanceResult(BalanceChainValidator.Outcome.FAILED, discrepancyAt(7)), null, 0, TextSource.NATIVE_PDF);

        DimensionResult result = DimensionAssessor.assessFinancialValidation(
                MaterialField.TRANSACTION_AMOUNT, 4, context);

        assertThat(result.status()).isEqualTo(EvidenceStatus.SUPPORTED);
    }

    @Test
    void assessFinancialValidation_transactionAmount_notApplicable_isInsufficient() {
        FinancialValidationContext context = new FinancialValidationContext(
                balanceResult(BalanceChainValidator.Outcome.NOT_APPLICABLE), null, 0, TextSource.NATIVE_PDF);

        DimensionResult result = DimensionAssessor.assessFinancialValidation(
                MaterialField.TRANSACTION_AMOUNT, 4, context);

        assertThat(result.status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    @Test
    void assessFinancialValidation_transactionAmount_requiresRowIndex() {
        FinancialValidationContext context = new FinancialValidationContext(
                balanceResult(BalanceChainValidator.Outcome.VERIFIED), null, 0, TextSource.NATIVE_PDF);

        assertThatThrownBy(() -> DimensionAssessor.assessFinancialValidation(
                MaterialField.TRANSACTION_AMOUNT, null, context))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- assessFinancialValidation: opening/closing balance ---

    private static ImportDto.VerificationFinding statementTotals(String outcome, String suspectedCause) {
        Map<String, Object> details = suspectedCause == null ? Map.of() : Map.of("suspectedCause", suspectedCause);
        return new ImportDto.VerificationFinding("STATEMENT_TOTALS", outcome, details);
    }

    @Test
    void assessFinancialValidation_openingBalance_verified_isSupported() {
        FinancialValidationContext context = new FinancialValidationContext(
                null, statementTotals("VERIFIED", null), 0, TextSource.NATIVE_PDF);

        assertThat(DimensionAssessor.assessFinancialValidation(MaterialField.OPENING_BALANCE, null, context).status())
                .isEqualTo(EvidenceStatus.SUPPORTED);
    }

    @Test
    void assessFinancialValidation_openingBalance_failedWithOpeningBalanceCause_isConflicting() {
        FinancialValidationContext context = new FinancialValidationContext(
                null, statementTotals("FAILED", "OPENING_BALANCE"), 0, TextSource.NATIVE_PDF);

        assertThat(DimensionAssessor.assessFinancialValidation(MaterialField.OPENING_BALANCE, null, context).status())
                .isEqualTo(EvidenceStatus.CONFLICTING);
    }

    @Test
    void assessFinancialValidation_closingBalance_failedWithOpeningBalanceCause_isInsufficient() {
        // C-10: this used to assert SUPPORTED. The "independently matches" reasoning it encoded is
        // circular -- suspectedCause=OPENING_BALANCE is produced by comparing the closing-balance
        // claim against the very row the claim is derived from (StatementTotalsValidator:83-85 vs
        // PdfPreviewGenerator:528), so the predicate is x == x. With no independent origin for the
        // claim available anywhere in the system, the dimension must not treat it as support.
        FinancialValidationContext context = new FinancialValidationContext(
                null, statementTotals("FAILED", "OPENING_BALANCE"), 0, TextSource.NATIVE_PDF);

        DimensionResult result =
                DimensionAssessor.assessFinancialValidation(MaterialField.CLOSING_BALANCE, null, context);

        assertThat(result.status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
        assertThat(result.status()).isNotEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(result.explanation()).contains("no independent origin for the claim");
    }

    @Test
    void assessFinancialValidation_openingBalance_failedWithTransactionsCause_isInsufficient_notConflicting() {
        FinancialValidationContext context = new FinancialValidationContext(
                null, statementTotals("FAILED", "TRANSACTIONS"), 0, TextSource.NATIVE_PDF);

        assertThat(DimensionAssessor.assessFinancialValidation(MaterialField.OPENING_BALANCE, null, context).status())
                .isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    @Test
    void assessFinancialValidation_closingBalance_failedWithTransactionsCause_isInsufficient() {
        FinancialValidationContext context = new FinancialValidationContext(
                null, statementTotals("FAILED", "TRANSACTIONS"), 0, TextSource.NATIVE_PDF);

        assertThat(DimensionAssessor.assessFinancialValidation(MaterialField.CLOSING_BALANCE, null, context).status())
                .isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    @Test
    void assessFinancialValidation_unmappedField_isInsufficient() {
        FinancialValidationContext context = new FinancialValidationContext(
                balanceResult(BalanceChainValidator.Outcome.VERIFIED), statementTotals("VERIFIED", null), 0,
                TextSource.NATIVE_PDF);

        assertThat(DimensionAssessor.assessFinancialValidation(MaterialField.IFSC, null, context).status())
                .isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    @Test
    void assessFinancialValidation_aggregateValidatorNeverConsultedForTransactionFields() {
        // A statementTotals VERIFIED must not leak into satisfying TRANSACTION_AMOUNT even if
        // balanceChain itself is null/NOT_APPLICABLE -- design's round-3 tightening.
        FinancialValidationContext context = new FinancialValidationContext(
                null, statementTotals("VERIFIED", null), 0, TextSource.NATIVE_PDF);

        assertThat(DimensionAssessor.assessFinancialValidation(MaterialField.TRANSACTION_AMOUNT, 4, context).status())
                .isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    // --- deriveAssessmentStatus ---

    private static DimensionResult dim(DimensionResult.Dimension dimension, EvidenceStatus status) {
        return new DimensionResult(dimension, status, "test",
                List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)));
    }

    private static DimensionResult dim(DimensionResult.Dimension dimension, EvidenceStatus status,
            ProvenanceNode... provenance) {
        return new DimensionResult(dimension, status, "test", List.of(provenance));
    }

    @Test
    void deriveAssessmentStatus_contradictionsAlwaysWins() {
        DimensionResult structural = dim(DimensionResult.Dimension.STRUCTURAL, EvidenceStatus.SUPPORTED,
                new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF));
        DimensionResult corroboration = dim(DimensionResult.Dimension.CORROBORATION, EvidenceStatus.SUPPORTED,
                new ProvenanceNode.Acquisition(TextSource.OCR));
        DimensionResult financial = dim(DimensionResult.Dimension.FINANCIAL_VALIDATION, EvidenceStatus.SUPPORTED,
                new ProvenanceNode.SectionAttribution(0, TextSource.NATIVE_PDF));
        List<FieldFact<?>> contradictions = List.of(
                fact(MaterialField.TRANSACTION_AMOUNT, "500.00", new ProvenanceNode.Acquisition(TextSource.OCR)));

        assertThat(DimensionAssessor.deriveAssessmentStatus(structural, corroboration, financial, contradictions))
                .isEqualTo(EvidenceStatus.CONFLICTING);
    }

    @Test
    void deriveAssessmentStatus_anyConflictingDimension_forcesConflicting_evenWithEmptyContradictions() {
        DimensionResult structural = dim(DimensionResult.Dimension.STRUCTURAL, EvidenceStatus.SUPPORTED,
                new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF));
        DimensionResult corroboration = dim(DimensionResult.Dimension.CORROBORATION, EvidenceStatus.CONFLICTING,
                new ProvenanceNode.Acquisition(TextSource.OCR));
        DimensionResult financial = dim(DimensionResult.Dimension.FINANCIAL_VALIDATION, EvidenceStatus.SUPPORTED,
                new ProvenanceNode.SectionAttribution(0, TextSource.NATIVE_PDF));

        assertThat(DimensionAssessor.deriveAssessmentStatus(structural, corroboration, financial, List.of()))
                .isEqualTo(EvidenceStatus.CONFLICTING);
    }

    @Test
    void deriveAssessmentStatus_twoIndependentSupportedDimensions_isSupported() {
        DimensionResult structural = dim(DimensionResult.Dimension.STRUCTURAL, EvidenceStatus.SUPPORTED,
                new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF));
        DimensionResult corroboration = dim(DimensionResult.Dimension.CORROBORATION, EvidenceStatus.SUPPORTED,
                new ProvenanceNode.Acquisition(TextSource.OCR));
        DimensionResult financial = dim(DimensionResult.Dimension.FINANCIAL_VALIDATION, EvidenceStatus.INSUFFICIENT,
                new ProvenanceNode.SectionAttribution(0, TextSource.NATIVE_PDF));

        assertThat(DimensionAssessor.deriveAssessmentStatus(structural, corroboration, financial, List.of()))
                .isEqualTo(EvidenceStatus.SUPPORTED);
    }

    @Test
    void deriveAssessmentStatus_onlyOneSatisfiedDimension_isInsufficient() {
        DimensionResult structural = dim(DimensionResult.Dimension.STRUCTURAL, EvidenceStatus.SUPPORTED,
                new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF));
        DimensionResult corroboration = dim(DimensionResult.Dimension.CORROBORATION, EvidenceStatus.INSUFFICIENT,
                new ProvenanceNode.Acquisition(TextSource.OCR));
        DimensionResult financial = dim(DimensionResult.Dimension.FINANCIAL_VALIDATION, EvidenceStatus.INSUFFICIENT,
                new ProvenanceNode.SectionAttribution(0, TextSource.NATIVE_PDF));

        assertThat(DimensionAssessor.deriveAssessmentStatus(structural, corroboration, financial, List.of()))
                .isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    @Test
    void deriveAssessmentStatus_theIcicShape_twoSatisfiedButSharingSectionAttribution_isInsufficient() {
        // The exact worked example ADR-006 §3 itself names: StructuralEvidence and
        // FinancialValidation both satisfied, but both computed from the SAME (possibly wrong)
        // section attribution -- not independent, must not count toward SUPPORTED.
        ProvenanceNode.SectionAttribution sameSectionDecision =
                new ProvenanceNode.SectionAttribution(3, TextSource.NATIVE_PDF);
        DimensionResult structural = dim(DimensionResult.Dimension.STRUCTURAL, EvidenceStatus.SUPPORTED,
                sameSectionDecision);
        DimensionResult corroboration = dim(DimensionResult.Dimension.CORROBORATION, EvidenceStatus.INSUFFICIENT,
                new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF));
        DimensionResult financial = dim(DimensionResult.Dimension.FINANCIAL_VALIDATION, EvidenceStatus.SUPPORTED,
                sameSectionDecision);

        assertThat(DimensionAssessor.deriveAssessmentStatus(structural, corroboration, financial, List.of()))
                .isEqualTo(EvidenceStatus.INSUFFICIENT);
    }
}
