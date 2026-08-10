package com.finora.imports.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.imports.pdf.TextSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class FieldCandidateTest {

    private static FieldFact<String> fact(String value, ProvenanceNode... provenance) {
        return new FieldFact<>(MaterialField.ACCOUNT_HOLDER, value, List.of(provenance));
    }

    @Test
    void of_derivesStatusFromFacts() {
        FieldFact<String> native_ = fact("Jane Doe", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF));
        FieldFact<String> ocr = fact("Jane Doe", new ProvenanceNode.Acquisition(TextSource.OCR));

        FieldCandidate<String> candidate =
                FieldCandidate.of(MaterialField.ACCOUNT_HOLDER, "Jane Doe", List.of(native_, ocr));

        assertThat(candidate.status()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(candidate.value()).isEqualTo("Jane Doe");
    }

    @Test
    void canonicalConstructor_rejectsAStatusThatDisagreesWithDerivedStatus() {
        FieldFact<String> onlyFact = fact("Jane Doe", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF));

        // A single fact derives to INSUFFICIENT; asserting SUPPORTED here must be rejected.
        assertThatThrownBy(() -> new FieldCandidate<>(
                MaterialField.ACCOUNT_HOLDER, "Jane Doe", EvidenceStatus.SUPPORTED, List.of(onlyFact)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disagrees");
    }

    @Test
    void canonicalConstructor_acceptsAStatusThatMatchesDerivedStatus() {
        FieldFact<String> onlyFact = fact("Jane Doe", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF));

        FieldCandidate<String> candidate = new FieldCandidate<>(
                MaterialField.ACCOUNT_HOLDER, "Jane Doe", EvidenceStatus.INSUFFICIENT, List.of(onlyFact));

        assertThat(candidate.status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    @Test
    void canonicalConstructor_rejectsAFactForADifferentField() {
        FieldFact<String> wrongField = new FieldFact<>(MaterialField.IFSC, "HDFC0XXXXXX",
                List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)));

        assertThatThrownBy(() -> new FieldCandidate<>(
                MaterialField.ACCOUNT_HOLDER, "Jane Doe", EvidenceStatus.INSUFFICIENT, List.of(wrongField)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Adversarial review: attempts to break the model, per the post-fix re-review ---

    @Test
    void canonicalConstructor_rejectsNullValue() {
        assertThatThrownBy(() -> new FieldCandidate<>(
                MaterialField.ACCOUNT_HOLDER, null, EvidenceStatus.INSUFFICIENT, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void canonicalConstructor_rejectsNullFacts() {
        assertThatThrownBy(() -> new FieldCandidate<>(
                MaterialField.ACCOUNT_HOLDER, "Jane Doe", EvidenceStatus.INSUFFICIENT, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void canonicalConstructor_acceptsEmptyFactsWithInsufficientStatus() {
        // A field never observed at all is still a representable candidate: INSUFFICIENT with zero
        // facts, not an error state.
        FieldCandidate<String> candidate = new FieldCandidate<>(
                MaterialField.BRANCH, "unknown", EvidenceStatus.INSUFFICIENT, List.of());

        assertThat(candidate.facts()).isEmpty();
        assertThat(candidate.status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    @Test
    void canonicalConstructor_rejectsSupportedWithEmptyFacts() {
        // No facts at all can never justify SUPPORTED -- guards the same invariant as the
        // single-fact case, at the zero-fact boundary.
        assertThatThrownBy(() -> new FieldCandidate<>(
                MaterialField.BRANCH, "Some Branch", EvidenceStatus.SUPPORTED, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disagrees");
    }

    @Test
    void canonicalConstructor_rejectsInsufficientWhenFactsActuallySupport() {
        // The consistency check must also catch the opposite direction: understating a candidate
        // that its own facts actually support is rejected too, not only overstating one.
        FieldFact<String> native_ = fact("Jane Doe", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF));
        FieldFact<String> ocr = fact("Jane Doe", new ProvenanceNode.Acquisition(TextSource.OCR));

        assertThatThrownBy(() -> new FieldCandidate<>(
                MaterialField.ACCOUNT_HOLDER, "Jane Doe", EvidenceStatus.INSUFFICIENT, List.of(native_, ocr)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disagrees");
    }

    @Test
    void of_withNoFacts_isInsufficient() {
        FieldCandidate<String> candidate = FieldCandidate.of(MaterialField.BRANCH, "unknown", List.of());

        assertThat(candidate.status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
    }
}
