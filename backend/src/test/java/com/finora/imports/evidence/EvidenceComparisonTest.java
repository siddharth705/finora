package com.finora.imports.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.imports.pdf.TextSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceComparisonTest {

    private static FieldFact<String> fact(String value, TextSource source) {
        return new FieldFact<>(MaterialField.ACCOUNT_HOLDER, value, List.of(new ProvenanceNode.Acquisition(source)));
    }

    @Test
    void empty_isAbsent() {
        assertThat(EvidenceComparison.compare(List.of())).isEqualTo(EvidenceComparison.ABSENT);
    }

    @Test
    void singleFact_isUncontested_neverAgree() {
        assertThat(EvidenceComparison.compare(List.of(fact("Jane Doe", TextSource.NATIVE_PDF))))
                .isEqualTo(EvidenceComparison.UNCONTESTED);
    }

    @Test
    void allAgreeingFacts_isAgree() {
        assertThat(EvidenceComparison.compare(List.of(
                fact("Jane Doe", TextSource.NATIVE_PDF), fact("Jane Doe", TextSource.OCR))))
                .isEqualTo(EvidenceComparison.AGREE);
    }

    @Test
    void anyDisagreeingFact_isDisagree() {
        assertThat(EvidenceComparison.compare(List.of(
                fact("Jane Doe", TextSource.NATIVE_PDF), fact("Jane Doe", TextSource.OCR),
                fact("J. Doe", TextSource.NATIVE_PDF))))
                .isEqualTo(EvidenceComparison.DISAGREE);
    }

    @Test
    void nullGroup_rejected() {
        assertThatThrownBy(() -> EvidenceComparison.compare(null)).isInstanceOf(NullPointerException.class);
    }
}
