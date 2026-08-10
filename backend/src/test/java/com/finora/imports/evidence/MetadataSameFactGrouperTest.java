package com.finora.imports.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.imports.pdf.TextSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetadataSameFactGrouperTest {

    private static MetadataFieldObservation<String> obs(
            int sectionIndex, String value, ProvenanceNode.Acquisition acquisition, BoundingBox region) {
        MetadataObservation<String> position = new MetadataObservation<>(
                new FieldFact<>(MaterialField.ACCOUNT_HOLDER, value, List.of(acquisition)),
                sectionIndex, region, "Account Holder");
        return new MetadataFieldObservation<>(position, com.finora.imports.product.EvidenceSource.ROW_DATA);
    }

    @Test
    void empty_isEmptyGroup() {
        SameFactGroupingResult<String> result = MetadataSameFactGrouper.group(List.of());

        assertThat(result.sameFactGroup()).isEmpty();
    }

    @Test
    void singleObservation_isTheWholeGroup() {
        SameFactGroupingResult<String> result = MetadataSameFactGrouper.group(List.of(
                obs(0, "Jane Doe", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF), null)));

        assertThat(result.sameFactGroup()).hasSize(1);
    }

    @Test
    void twoSameFactObservations_bothInGroup() {
        SameFactGroupingResult<String> result = MetadataSameFactGrouper.group(List.of(
                obs(0, "Jane Doe", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF),
                        new BoundingBox(10, 50, 100, 10)),
                obs(0, "Jane Doe", new ProvenanceNode.Acquisition(TextSource.OCR),
                        new BoundingBox(11, 51, 100, 10))));

        assertThat(result.sameFactGroup()).hasSize(2);
    }

    @Test
    void threeGenuinelySameFactObservations_isRejected_notSilentlyGrouped() {
        // Audit finding: same guard as TransactionSameFactGrouper -- 3 observations that ALL
        // correlate SAME_FACT with the anchor (same section, overlapping region) must fail loudly.
        List<MetadataFieldObservation<String>> threeSources = List.of(
                obs(0, "Jane Doe", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF),
                        new BoundingBox(10, 50, 100, 10)),
                obs(0, "Jane Doe", new ProvenanceNode.Acquisition(TextSource.OCR),
                        new BoundingBox(11, 51, 100, 10)),
                obs(0, "Jane Doe", new ProvenanceNode.Acquisition(TextSource.NATIVE_PLUS_OCR),
                        new BoundingBox(9, 49, 100, 10)));

        assertThatThrownBy(() -> MetadataSameFactGrouper.group(threeSources))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void threeObservations_onlyTwoGenuinelySameFact_stillGroupsSuccessfully() {
        // Guard is on the resulting group size, not the input size -- a DIFFERENT_FACT (different
        // section) third observation must not prevent the real pair from grouping successfully.
        SameFactGroupingResult<String> result = MetadataSameFactGrouper.group(List.of(
                obs(0, "Jane Doe", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF),
                        new BoundingBox(10, 50, 100, 10)),
                obs(0, "Jane Doe", new ProvenanceNode.Acquisition(TextSource.OCR),
                        new BoundingBox(11, 51, 100, 10)),
                obs(5, "John Smith", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF),
                        new BoundingBox(10, 900, 100, 10))));

        assertThat(result.sameFactGroup()).hasSize(2);
        assertThat(result.excludedAsDifferent()).hasSize(1);
    }
}
