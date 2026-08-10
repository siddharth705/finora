package com.finora.imports.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.imports.pdf.TextSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class FieldAssessmentTest {

    private static DimensionResult dim(DimensionResult.Dimension dimension, EvidenceStatus status,
            ProvenanceNode... provenance) {
        return new DimensionResult(dimension, status, "test", List.of(provenance));
    }

    private static FieldFact<String> fact(String value, ProvenanceNode... provenance) {
        return new FieldFact<>(MaterialField.ACCOUNT_HOLDER, value, List.of(provenance));
    }

    private static FieldCandidate<String> candidate() {
        FieldFact<String> a = fact("Jane Doe", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF));
        FieldFact<String> b = fact("Jane Doe", new ProvenanceNode.Acquisition(TextSource.OCR));
        return FieldCandidate.of(MaterialField.ACCOUNT_HOLDER, "Jane Doe", List.of(a, b));
    }

    @Test
    void of_derivesStatusFromDimensionsAndContradictions() {
        DimensionResult structural = dim(DimensionResult.Dimension.STRUCTURAL, EvidenceStatus.SUPPORTED,
                new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF));
        DimensionResult corroboration = dim(DimensionResult.Dimension.CORROBORATION, EvidenceStatus.SUPPORTED,
                new ProvenanceNode.Acquisition(TextSource.OCR));
        DimensionResult financial = dim(DimensionResult.Dimension.FINANCIAL_VALIDATION, EvidenceStatus.INSUFFICIENT,
                new ProvenanceNode.SectionAttribution(0, TextSource.NATIVE_PDF));

        FieldAssessment assessment = FieldAssessment.of(candidate(), structural, corroboration, financial, List.of());

        assertThat(assessment.status()).isEqualTo(EvidenceStatus.SUPPORTED);
    }

    @Test
    void canonicalConstructor_rejectsStatusDisagreeingWithDerivation() {
        DimensionResult structural = dim(DimensionResult.Dimension.STRUCTURAL, EvidenceStatus.INSUFFICIENT,
                new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF));
        DimensionResult corroboration = dim(DimensionResult.Dimension.CORROBORATION, EvidenceStatus.INSUFFICIENT,
                new ProvenanceNode.Acquisition(TextSource.OCR));
        DimensionResult financial = dim(DimensionResult.Dimension.FINANCIAL_VALIDATION, EvidenceStatus.INSUFFICIENT,
                new ProvenanceNode.SectionAttribution(0, TextSource.NATIVE_PDF));

        assertThatThrownBy(() -> new FieldAssessment(candidate(), structural, corroboration, financial, List.of(),
                EvidenceStatus.SUPPORTED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allThreeSupported_corroborationTaintedByStructural_butFinancialValidationIndependent_isSupported() {
        // Structural and Corroboration share a node (tainted), but FinancialValidation is
        // independent of BOTH -- the Structural/FinancialValidation pair alone is enough to reach
        // SUPPORTED. A third, non-independent dimension present in the mix must not poison an
        // otherwise-valid independent pairing.
        ProvenanceNode.Acquisition sharedNode = new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF);
        DimensionResult structural = dim(DimensionResult.Dimension.STRUCTURAL, EvidenceStatus.SUPPORTED, sharedNode);
        DimensionResult corroboration = dim(DimensionResult.Dimension.CORROBORATION, EvidenceStatus.SUPPORTED, sharedNode);
        DimensionResult financial = dim(DimensionResult.Dimension.FINANCIAL_VALIDATION, EvidenceStatus.SUPPORTED,
                new ProvenanceNode.SectionAttribution(2, TextSource.OCR));

        FieldAssessment assessment = FieldAssessment.of(candidate(), structural, corroboration, financial, List.of());

        assertThat(assessment.status()).isEqualTo(EvidenceStatus.SUPPORTED);
    }

    @Test
    void allThreeSupported_butAllShareTheSameNode_isInsufficient() {
        // No pair among the three is independent -- must not reach SUPPORTED regardless of how
        // many dimensions individually claim SUPPORTED.
        ProvenanceNode.Acquisition sharedNode = new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF);
        DimensionResult structural = dim(DimensionResult.Dimension.STRUCTURAL, EvidenceStatus.SUPPORTED, sharedNode);
        DimensionResult corroboration = dim(DimensionResult.Dimension.CORROBORATION, EvidenceStatus.SUPPORTED, sharedNode);
        DimensionResult financial = dim(DimensionResult.Dimension.FINANCIAL_VALIDATION, EvidenceStatus.SUPPORTED, sharedNode);

        FieldAssessment assessment = FieldAssessment.of(candidate(), structural, corroboration, financial, List.of());

        assertThat(assessment.status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    @Test
    void canonicalConstructor_rejectsADimensionInTheWrongSlot() {
        DimensionResult wrongSlot = dim(DimensionResult.Dimension.CORROBORATION, EvidenceStatus.SUPPORTED,
                new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF));
        DimensionResult corroboration = dim(DimensionResult.Dimension.CORROBORATION, EvidenceStatus.SUPPORTED,
                new ProvenanceNode.Acquisition(TextSource.OCR));
        DimensionResult financial = dim(DimensionResult.Dimension.FINANCIAL_VALIDATION, EvidenceStatus.INSUFFICIENT,
                new ProvenanceNode.SectionAttribution(0, TextSource.NATIVE_PDF));

        assertThatThrownBy(() -> FieldAssessment.of(candidate(), wrongSlot, corroboration, financial, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
