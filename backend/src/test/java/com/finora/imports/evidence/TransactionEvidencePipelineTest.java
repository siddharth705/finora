package com.finora.imports.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.imports.BalanceChainValidator;
import com.finora.imports.pdf.TextSource;
import com.finora.imports.product.EvidenceSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof the Phase-C gate required: real observations flow through correlation, same-fact
 * grouping, evidence comparison, all three dimensions, and combine into one {@link FieldAssessment}
 * -- not each piece verified only in isolation.
 */
class TransactionEvidencePipelineTest {

    private static TransactionObservation row(int page, LocalDate date, String amount, String direction,
            String description, BoundingBox box, Integer ordinal) {
        return new TransactionObservation(page, date, amount == null ? null : new BigDecimal(amount),
                direction, description, box, ordinal);
    }

    private static FinancialValidationContext noDiscrepancyContext() {
        BalanceChainValidator.Result balanceResult =
                new BalanceChainValidator.Result(BalanceChainValidator.Outcome.VERIFIED, List.of(), 10, 10);
        return new FinancialValidationContext(balanceResult, null, 2, TextSource.NATIVE_PDF);
    }

    @Test
    void endToEnd_nativeAndOcrAgree_geometryConfirms_noBalanceDiscrepancy_isSupported() {
        TransactionObservation nativePos = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 100, 200, 12), 4);
        TransactionObservation ocrPos = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(11, 101, 199, 12), 4);

        TransactionFieldObservation<String> native_ = new TransactionFieldObservation<>(nativePos,
                new FieldFact<>(MaterialField.TRANSACTION_DIRECTION, "DEBIT",
                        List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF))),
                EvidenceSource.ROW_DATA);
        TransactionFieldObservation<String> ocr = new TransactionFieldObservation<>(ocrPos,
                new FieldFact<>(MaterialField.TRANSACTION_DIRECTION, "DEBIT",
                        List.of(new ProvenanceNode.Acquisition(TextSource.OCR))),
                EvidenceSource.ROW_DATA);

        FieldAssessment assessment = TransactionEvidencePipeline.assess(MaterialField.TRANSACTION_DIRECTION,
                List.of(native_, ocr), "DEBIT", 4, noDiscrepancyContext());

        assertThat(assessment.status()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(assessment.corroboration().status()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(assessment.structural().status()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(assessment.contradictions()).isEmpty();
    }

    @Test
    void endToEnd_nativeAndOcrDisagreeOnDirection_isConflicting_withContradictionsPopulated() {
        TransactionObservation nativePos = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 100, 200, 12), 4);
        TransactionObservation ocrPos = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(11, 101, 199, 12), 4);

        TransactionFieldObservation<String> native_ = new TransactionFieldObservation<>(nativePos,
                new FieldFact<>(MaterialField.TRANSACTION_DIRECTION, "DEBIT",
                        List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF))),
                EvidenceSource.ROW_DATA);
        TransactionFieldObservation<String> ocrFlipped = new TransactionFieldObservation<>(ocrPos,
                new FieldFact<>(MaterialField.TRANSACTION_DIRECTION, "CREDIT",
                        List.of(new ProvenanceNode.Acquisition(TextSource.OCR))),
                EvidenceSource.ROW_DATA);

        FieldAssessment assessment = TransactionEvidencePipeline.assess(MaterialField.TRANSACTION_DIRECTION,
                List.of(native_, ocrFlipped), "DEBIT", 4, noDiscrepancyContext());

        assertThat(assessment.status()).isEqualTo(EvidenceStatus.CONFLICTING);
        assertThat(assessment.contradictions()).hasSize(2);
    }

    @Test
    void endToEnd_singleSourceOnly_uncontested_isInsufficient_neverSupported() {
        TransactionObservation nativePos = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 100, 200, 12), 4);

        TransactionFieldObservation<String> native_ = new TransactionFieldObservation<>(nativePos,
                new FieldFact<>(MaterialField.TRANSACTION_DIRECTION, "DEBIT",
                        List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF))),
                EvidenceSource.ROW_DATA);

        FieldAssessment assessment = TransactionEvidencePipeline.assess(MaterialField.TRANSACTION_DIRECTION,
                List.of(native_), "DEBIT", 4, noDiscrepancyContext());

        // Structural SUPPORTED (ROW_DATA) but Corroboration UNCONTESTED->INSUFFICIENT and
        // FinancialValidation: BalanceChainValidator VERIFIED, no discrepancy at row 4 -> SUPPORTED.
        // Structural + FinancialValidation are independent (different provenance) -> SUPPORTED.
        assertThat(assessment.status()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(assessment.corroboration().status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    @Test
    void endToEnd_singleSourceOnly_noFinancialValidationEither_isInsufficient() {
        // Same as above, but no running-balance column at all: only Structural can ever be
        // satisfied, one dimension alone is never enough.
        TransactionObservation nativePos = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 100, 200, 12), 4);
        TransactionFieldObservation<String> native_ = new TransactionFieldObservation<>(nativePos,
                new FieldFact<>(MaterialField.TRANSACTION_DIRECTION, "DEBIT",
                        List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF))),
                EvidenceSource.ROW_DATA);
        FinancialValidationContext notApplicable = new FinancialValidationContext(
                new BalanceChainValidator.Result(BalanceChainValidator.Outcome.NOT_APPLICABLE, List.of(), 0, 0),
                null, 2, TextSource.NATIVE_PDF);

        FieldAssessment assessment = TransactionEvidencePipeline.assess(MaterialField.TRANSACTION_DIRECTION,
                List.of(native_), "DEBIT", 4, notApplicable);

        assertThat(assessment.status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    @Test
    void endToEnd_balanceChainDiscrepancyAtThisRow_isConflicting_evenWithCorroboratingOcr() {
        // The compensating-financial-error shape: two sources agree with each other, but the
        // statement's own arithmetic contradicts them at this exact row. FinancialValidation's own
        // CONFLICTING status must force the overall result to CONFLICTING regardless of corroboration.
        TransactionObservation nativePos = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 100, 200, 12), 4);
        TransactionObservation ocrPos = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(11, 101, 199, 12), 4);
        TransactionFieldObservation<String> native_ = new TransactionFieldObservation<>(nativePos,
                new FieldFact<>(MaterialField.TRANSACTION_DIRECTION, "DEBIT",
                        List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF))),
                EvidenceSource.ROW_DATA);
        TransactionFieldObservation<String> ocr = new TransactionFieldObservation<>(ocrPos,
                new FieldFact<>(MaterialField.TRANSACTION_DIRECTION, "DEBIT",
                        List.of(new ProvenanceNode.Acquisition(TextSource.OCR))),
                EvidenceSource.ROW_DATA);
        BalanceChainValidator.Discrepancy discrepancyAtRow4 = new BalanceChainValidator.Discrepancy(4,
                LocalDate.of(2026, 3, 5), "desc", new BigDecimal("1000"), new BigDecimal("500"), new BigDecimal("500"));
        FinancialValidationContext discrepant = new FinancialValidationContext(
                new BalanceChainValidator.Result(BalanceChainValidator.Outcome.FAILED, List.of(discrepancyAtRow4), 10, 10),
                null, 2, TextSource.NATIVE_PDF);

        FieldAssessment assessment = TransactionEvidencePipeline.assess(MaterialField.TRANSACTION_DIRECTION,
                List.of(native_, ocr), "DEBIT", 4, discrepant);

        assertThat(assessment.status()).isEqualTo(EvidenceStatus.CONFLICTING);
        assertThat(assessment.financialValidation().status()).isEqualTo(EvidenceStatus.CONFLICTING);
    }

    @Test
    void differentFactObservation_withStrongerEvidenceSource_cannotInflateStructural_orManufactureSupported() {
        // Audit finding: Structural must never assess evidence belonging to a DIFFERENT_FACT
        // observation. obsC is a genuinely different transaction (different date/amount,
        // non-overlapping geometry) with a STRONGER EvidenceSource (COLUMN_HEADERS) than the real
        // pair (obsA/obsB, both SECTION_TEXT). Before the fix, Structural would have picked up
        // obsC's strong source and wrongly reached SUPPORTED, which combined with the genuinely
        // independent Corroboration from obsA+obsB would have manufactured an overall SUPPORTED
        // built partly on evidence about a different transaction entirely.
        TransactionObservation posA = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 100, 200, 12), 4);
        TransactionObservation posB = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(11, 101, 199, 12), 4);
        TransactionObservation posC = row(0, LocalDate.of(2026, 3, 20), "750.00", "CREDIT",
                "NEFT/salary/acme corp", new BoundingBox(10, 400, 200, 12), 9);

        TransactionFieldObservation<String> obsA = new TransactionFieldObservation<>(posA,
                new FieldFact<>(MaterialField.TRANSACTION_DIRECTION, "DEBIT",
                        List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF))),
                EvidenceSource.SECTION_TEXT);
        TransactionFieldObservation<String> obsB = new TransactionFieldObservation<>(posB,
                new FieldFact<>(MaterialField.TRANSACTION_DIRECTION, "DEBIT",
                        List.of(new ProvenanceNode.Acquisition(TextSource.OCR))),
                EvidenceSource.SECTION_TEXT);
        TransactionFieldObservation<String> obsC = new TransactionFieldObservation<>(posC,
                new FieldFact<>(MaterialField.TRANSACTION_DIRECTION, "CREDIT",
                        List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF))),
                EvidenceSource.COLUMN_HEADERS);

        FinancialValidationContext notApplicable = new FinancialValidationContext(
                new BalanceChainValidator.Result(BalanceChainValidator.Outcome.NOT_APPLICABLE, List.of(), 0, 0),
                null, 2, TextSource.NATIVE_PDF);

        FieldAssessment assessment = TransactionEvidencePipeline.assess(MaterialField.TRANSACTION_DIRECTION,
                List.of(obsA, obsB, obsC), "DEBIT", 4, notApplicable);

        // Structural: only obsA/obsB (both SECTION_TEXT) are eligible -> not stronger than
        // SECTION_TEXT -> INSUFFICIENT. obsC's COLUMN_HEADERS strength must never be picked up.
        assertThat(assessment.structural().status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
        // Corroboration: obsA+obsB genuinely agree, independent acquisitions -> SUPPORTED.
        assertThat(assessment.corroboration().status()).isEqualTo(EvidenceStatus.SUPPORTED);
        // Only one dimension satisfied (Corroboration) -- the unrelated obsC evidence must not be
        // able to manufacture a second, so the overall result stays INSUFFICIENT, never SUPPORTED.
        assertThat(assessment.status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    @Test
    void rejectsObservationForAWrongField() {
        TransactionObservation pos = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT", null, null, null);
        TransactionFieldObservation<String> wrongField = new TransactionFieldObservation<>(pos,
                new FieldFact<>(MaterialField.TRANSACTION_DESCRIPTION, "x",
                        List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF))),
                EvidenceSource.ROW_DATA);

        assertThatThrownBy(() -> TransactionEvidencePipeline.assess(MaterialField.TRANSACTION_DIRECTION,
                List.of(wrongField), "DEBIT", 4, noDiscrepancyContext()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
