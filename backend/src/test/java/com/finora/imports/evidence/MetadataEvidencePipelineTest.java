package com.finora.imports.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.dto.ImportDto;
import com.finora.imports.pdf.TextSource;
import com.finora.imports.product.EvidenceSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** End-to-end proof for metadata fields, the counterpart to {@link TransactionEvidencePipelineTest}. */
class MetadataEvidencePipelineTest {

    private static FinancialValidationContext noFinancialCheck() {
        return new FinancialValidationContext(null, null, 0, TextSource.NATIVE_PDF);
    }

    @Test
    void endToEnd_nativeAndOcrAgreeOnAccountHolder_isSupported() {
        MetadataObservation<String> native_ = new MetadataObservation<>(
                new FieldFact<>(MaterialField.ACCOUNT_HOLDER, "Jane Doe",
                        List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF))),
                0, new BoundingBox(10, 50, 100, 10), "Account Holder");
        MetadataObservation<String> ocr = new MetadataObservation<>(
                new FieldFact<>(MaterialField.ACCOUNT_HOLDER, "Jane Doe",
                        List.of(new ProvenanceNode.Acquisition(TextSource.OCR))),
                0, new BoundingBox(11, 51, 100, 10), "Account Holder");

        FieldAssessment assessment = MetadataEvidencePipeline.assess(MaterialField.ACCOUNT_HOLDER,
                List.of(new MetadataFieldObservation<>(native_, EvidenceSource.ROW_DATA),
                        new MetadataFieldObservation<>(ocr, EvidenceSource.ROW_DATA)),
                "Jane Doe", noFinancialCheck());

        assertThat(assessment.status()).isEqualTo(EvidenceStatus.SUPPORTED);
    }

    @Test
    void endToEnd_iciciShape_agreeingButSameSectionAttribution_isInsufficient_notSupported() {
        // The exact worked example this ADR chain traces back to: native and OCR are genuinely
        // different Acquisition runs, but both were routed to their section by the identical
        // (possibly wrong) SectionAttribution decision. Must stay INSUFFICIENT.
        ProvenanceNode.SectionAttribution sameSectionDecision =
                new ProvenanceNode.SectionAttribution(1, TextSource.NATIVE_PDF);
        MetadataObservation<String> native_ = new MetadataObservation<>(
                new FieldFact<>(MaterialField.CREDIT_LIMIT, "1,15,000",
                        List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF), sameSectionDecision)),
                1, new BoundingBox(10, 50, 100, 10), "Credit Limit");
        MetadataObservation<String> ocr = new MetadataObservation<>(
                new FieldFact<>(MaterialField.CREDIT_LIMIT, "1,15,000",
                        List.of(new ProvenanceNode.Acquisition(TextSource.OCR), sameSectionDecision)),
                1, new BoundingBox(11, 51, 100, 10), "Credit Limit");

        FieldAssessment assessment = MetadataEvidencePipeline.assess(MaterialField.CREDIT_LIMIT,
                List.of(new MetadataFieldObservation<>(native_, EvidenceSource.ROW_DATA),
                        new MetadataFieldObservation<>(ocr, EvidenceSource.ROW_DATA)),
                "1,15,000", noFinancialCheck());

        assertThat(assessment.status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    @Test
    void endToEnd_iciciShape_clearsWithIndependentSectionConfirmation() {
        // Same shape as above, but this time the section's identity was independently confirmed --
        // proving RequireIndependentSectionIdentityPolicy is a real, working escape hatch.
        ProvenanceNode.SectionAttribution sameSectionDecision =
                new ProvenanceNode.SectionAttribution(1, TextSource.NATIVE_PDF);
        MetadataObservation<String> native_ = new MetadataObservation<>(
                new FieldFact<>(MaterialField.CREDIT_LIMIT, "1,15,000",
                        List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF), sameSectionDecision)),
                1, new BoundingBox(10, 50, 100, 10), "Credit Limit");
        MetadataObservation<String> ocr = new MetadataObservation<>(
                new FieldFact<>(MaterialField.CREDIT_LIMIT, "1,15,000",
                        List.of(new ProvenanceNode.Acquisition(TextSource.OCR), sameSectionDecision)),
                1, new BoundingBox(11, 51, 100, 10), "Credit Limit");

        DimensionIndependenceRemediationPolicy dimensionPolicy = RequireIndependentSectionIdentityPolicy.of(Set.of(1));
        IndependenceRemediationPolicy factPolicy = RequireIndependentSectionIdentityPolicy.ofFacts(Set.of(1));

        FieldAssessment assessment = MetadataEvidencePipeline.assess(MaterialField.CREDIT_LIMIT,
                List.of(new MetadataFieldObservation<>(native_, EvidenceSource.ROW_DATA),
                        new MetadataFieldObservation<>(ocr, EvidenceSource.ROW_DATA)),
                "1,15,000", noFinancialCheck(), factPolicy, dimensionPolicy);

        assertThat(assessment.status()).isEqualTo(EvidenceStatus.SUPPORTED);
    }

    @Test
    void endToEnd_openingBalance_verifiedStatementTotals_plusCorroboration_isSupported() {
        MetadataObservation<String> native_ = new MetadataObservation<>(
                new FieldFact<>(MaterialField.OPENING_BALANCE, "50000.00",
                        List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF))),
                0, new BoundingBox(10, 50, 100, 10), "Opening Balance");
        MetadataObservation<String> ocr = new MetadataObservation<>(
                new FieldFact<>(MaterialField.OPENING_BALANCE, "50000.00",
                        List.of(new ProvenanceNode.Acquisition(TextSource.OCR))),
                0, new BoundingBox(11, 51, 100, 10), "Opening Balance");
        ImportDto.VerificationFinding verified =
                new ImportDto.VerificationFinding("STATEMENT_TOTALS", "VERIFIED", Map.of());
        FinancialValidationContext context = new FinancialValidationContext(null, verified, 0, TextSource.NATIVE_PDF);

        FieldAssessment assessment = MetadataEvidencePipeline.assess(MaterialField.OPENING_BALANCE,
                List.of(new MetadataFieldObservation<>(native_, EvidenceSource.ROW_DATA),
                        new MetadataFieldObservation<>(ocr, EvidenceSource.ROW_DATA)),
                "50000.00", context);

        assertThat(assessment.status()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(assessment.financialValidation().status()).isEqualTo(EvidenceStatus.SUPPORTED);
    }

    @Test
    void endToEnd_openingBalance_statementTotalsImplicatesIt_isConflicting_evenIfSourcesAgree() {
        // Two sources agree on a WRONG opening balance -- StatementTotalsValidator's own
        // arithmetic implicates this exact field. Must be CONFLICTING regardless of corroboration.
        MetadataObservation<String> native_ = new MetadataObservation<>(
                new FieldFact<>(MaterialField.OPENING_BALANCE, "50000.00",
                        List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF))),
                0, new BoundingBox(10, 50, 100, 10), "Opening Balance");
        MetadataObservation<String> ocr = new MetadataObservation<>(
                new FieldFact<>(MaterialField.OPENING_BALANCE, "50000.00",
                        List.of(new ProvenanceNode.Acquisition(TextSource.OCR))),
                0, new BoundingBox(11, 51, 100, 10), "Opening Balance");
        ImportDto.VerificationFinding failed = new ImportDto.VerificationFinding(
                "STATEMENT_TOTALS", "FAILED", Map.of("suspectedCause", "OPENING_BALANCE"));
        FinancialValidationContext context = new FinancialValidationContext(null, failed, 0, TextSource.NATIVE_PDF);

        FieldAssessment assessment = MetadataEvidencePipeline.assess(MaterialField.OPENING_BALANCE,
                List.of(new MetadataFieldObservation<>(native_, EvidenceSource.ROW_DATA),
                        new MetadataFieldObservation<>(ocr, EvidenceSource.ROW_DATA)),
                "50000.00", context);

        assertThat(assessment.status()).isEqualTo(EvidenceStatus.CONFLICTING);
    }

    @Test
    void differentFactObservation_withStrongerEvidenceSource_cannotInflateStructural_orManufactureSupported() {
        // Audit finding, metadata counterpart of TransactionEvidencePipelineTest's equivalent test:
        // obsC is a genuinely different metadata instance (different section -- a DIFFERENT_FACT
        // hard gate per MetadataFactCorrelator) with a STRONGER EvidenceSource (COLUMN_HEADERS)
        // than the real agreeing pair (both SECTION_TEXT). Structural must never pick it up.
        MetadataObservation<String> native_ = new MetadataObservation<>(
                new FieldFact<>(MaterialField.ACCOUNT_HOLDER, "Jane Doe",
                        List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF))),
                0, new BoundingBox(10, 50, 100, 10), "Account Holder");
        MetadataObservation<String> ocr = new MetadataObservation<>(
                new FieldFact<>(MaterialField.ACCOUNT_HOLDER, "Jane Doe",
                        List.of(new ProvenanceNode.Acquisition(TextSource.OCR))),
                0, new BoundingBox(11, 51, 100, 10), "Account Holder");
        MetadataObservation<String> unrelatedSection = new MetadataObservation<>(
                new FieldFact<>(MaterialField.ACCOUNT_HOLDER, "John Smith",
                        List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF))),
                5, new BoundingBox(10, 900, 100, 10), "Account Holder");

        FieldAssessment assessment = MetadataEvidencePipeline.assess(MaterialField.ACCOUNT_HOLDER,
                List.of(new MetadataFieldObservation<>(native_, EvidenceSource.SECTION_TEXT),
                        new MetadataFieldObservation<>(ocr, EvidenceSource.SECTION_TEXT),
                        new MetadataFieldObservation<>(unrelatedSection, EvidenceSource.COLUMN_HEADERS)),
                "Jane Doe", noFinancialCheck());

        // Structural: only the real pair (SECTION_TEXT) is eligible -> not stronger than
        // SECTION_TEXT -> INSUFFICIENT. The unrelated section's COLUMN_HEADERS strength must never
        // be picked up.
        assertThat(assessment.structural().status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
        assertThat(assessment.corroboration().status()).isEqualTo(EvidenceStatus.SUPPORTED);
        // FinancialValidation is INSUFFICIENT for ACCOUNT_HOLDER (no validator maps to it) -- only
        // one dimension satisfied, so the unrelated evidence must not manufacture overall SUPPORTED.
        assertThat(assessment.status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
    }
}
