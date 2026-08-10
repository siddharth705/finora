package com.finora.imports.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.imports.pdf.TextSource;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RequireIndependentSectionIdentityPolicyTest {

    private static DimensionResult dim(java.util.List<ProvenanceNode> provenance) {
        return new DimensionResult(DimensionResult.Dimension.STRUCTURAL, EvidenceStatus.SUPPORTED, "test", provenance);
    }

    @Test
    void clears_whenSharedSectionIsIndependentlyConfirmed() {
        ProvenanceNode.SectionAttribution shared = new ProvenanceNode.SectionAttribution(3, TextSource.NATIVE_PDF);
        DimensionResult a = dim(java.util.List.of(shared));
        DimensionResult b = dim(java.util.List.of(shared));

        DimensionIndependenceRemediationPolicy policy = RequireIndependentSectionIdentityPolicy.of(Set.of(3));

        assertThat(policy.remediate(a, b)).isTrue();
    }

    @Test
    void refuses_whenSharedSectionIsNotConfirmed() {
        ProvenanceNode.SectionAttribution shared = new ProvenanceNode.SectionAttribution(3, TextSource.NATIVE_PDF);
        DimensionResult a = dim(java.util.List.of(shared));
        DimensionResult b = dim(java.util.List.of(shared));

        DimensionIndependenceRemediationPolicy policy = RequireIndependentSectionIdentityPolicy.of(Set.of(5));

        assertThat(policy.remediate(a, b)).isFalse();
    }

    @Test
    void refuses_whenNothingIsSharedAtAll() {
        DimensionResult a = dim(java.util.List.of(new ProvenanceNode.SectionAttribution(3, TextSource.NATIVE_PDF)));
        DimensionResult b = dim(java.util.List.of(new ProvenanceNode.SectionAttribution(4, TextSource.NATIVE_PDF)));

        DimensionIndependenceRemediationPolicy policy = RequireIndependentSectionIdentityPolicy.of(Set.of(3, 4));

        assertThat(policy.remediate(a, b)).isFalse();
    }

    @Test
    void refuses_whenTheSharedNodeIsAColumnLayoutInterpretation_notASectionAttribution() {
        // The policy addresses ONE specific failure mode (section identity). A shared
        // ColumnLayoutInterpretation node is a DIFFERENT failure mode this policy has no evidence
        // about -- it must not vouch for it just because some section elsewhere is confirmed.
        ProvenanceNode.ColumnLayoutInterpretation sharedColumns =
                new ProvenanceNode.ColumnLayoutInterpretation(3, "anchors:100,300");
        DimensionResult a = dim(java.util.List.of(
                new ProvenanceNode.SectionAttribution(3, TextSource.NATIVE_PDF), sharedColumns));
        DimensionResult b = dim(java.util.List.of(
                new ProvenanceNode.SectionAttribution(3, TextSource.OCR), sharedColumns));

        DimensionIndependenceRemediationPolicy policy = RequireIndependentSectionIdentityPolicy.of(Set.of(3));

        assertThat(policy.remediate(a, b)).isFalse();
    }

    @Test
    void refuses_whenTheSharedNodeIsAnAcquisition_notASectionAttribution() {
        ProvenanceNode.Acquisition sharedAcquisition = new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF);
        DimensionResult a = dim(java.util.List.of(sharedAcquisition));
        DimensionResult b = dim(java.util.List.of(sharedAcquisition));

        DimensionIndependenceRemediationPolicy policy = RequireIndependentSectionIdentityPolicy.of(Set.of(0, 1, 2));

        assertThat(policy.remediate(a, b)).isFalse();
    }

    @Test
    void refuses_whenOnlySomeOfMultipleSharedSectionsAreConfirmed() {
        // Two dimensions sharing TWO different SectionAttribution nodes (e.g. a multi-section
        // aggregate check) -- ALL shared sections must be confirmed, not just one.
        ProvenanceNode.SectionAttribution sectionThree = new ProvenanceNode.SectionAttribution(3, TextSource.NATIVE_PDF);
        ProvenanceNode.SectionAttribution sectionFour = new ProvenanceNode.SectionAttribution(4, TextSource.NATIVE_PDF);
        DimensionResult a = dim(java.util.List.of(sectionThree, sectionFour));
        DimensionResult b = dim(java.util.List.of(sectionThree, sectionFour));

        DimensionIndependenceRemediationPolicy policy = RequireIndependentSectionIdentityPolicy.of(Set.of(3));

        assertThat(policy.remediate(a, b)).isFalse();
    }

    @Test
    void clears_whenAllOfMultipleSharedSectionsAreConfirmed() {
        ProvenanceNode.SectionAttribution sectionThree = new ProvenanceNode.SectionAttribution(3, TextSource.NATIVE_PDF);
        ProvenanceNode.SectionAttribution sectionFour = new ProvenanceNode.SectionAttribution(4, TextSource.NATIVE_PDF);
        DimensionResult a = dim(java.util.List.of(sectionThree, sectionFour));
        DimensionResult b = dim(java.util.List.of(sectionThree, sectionFour));

        DimensionIndependenceRemediationPolicy policy = RequireIndependentSectionIdentityPolicy.of(Set.of(3, 4));

        assertThat(policy.remediate(a, b)).isTrue();
    }

    @Test
    void permissivePolicy_stillCannotOverrideConflictingOrContradictions() {
        // Even an intentionally maximally-permissive policy (always clears) must not be able to
        // rescue a CONFLICTING dimension or non-empty contradictions -- remediate() is structurally
        // never even consulted for those, per DimensionAssessor.deriveAssessmentStatus.
        DimensionIndependenceRemediationPolicy alwaysClears = (a, b) -> true;

        DimensionResult structural = dim(java.util.List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)));
        DimensionResult corroboration = new DimensionResult(DimensionResult.Dimension.CORROBORATION,
                EvidenceStatus.CONFLICTING, "test", java.util.List.of(new ProvenanceNode.Acquisition(TextSource.OCR)));
        DimensionResult financial = new DimensionResult(DimensionResult.Dimension.FINANCIAL_VALIDATION,
                EvidenceStatus.SUPPORTED, "test",
                java.util.List.of(new ProvenanceNode.SectionAttribution(0, TextSource.NATIVE_PDF)));
        DimensionResult structuralSupported = new DimensionResult(DimensionResult.Dimension.STRUCTURAL,
                EvidenceStatus.SUPPORTED, "test", java.util.List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)));

        assertThat(DimensionAssessor.deriveAssessmentStatus(
                structuralSupported, corroboration, financial, java.util.List.of(), alwaysClears))
                .isEqualTo(EvidenceStatus.CONFLICTING);
    }
}
