package com.finora.imports.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.imports.pdf.TextSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetadataFactCorrelatorTest {

    private static FieldFact<String> fact(MaterialField field, String value) {
        return new FieldFact<>(field, value, List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)));
    }

    @Test
    void sameFieldSameSection_closeRegion_isSameFact() {
        MetadataObservation<String> a = new MetadataObservation<>(
                fact(MaterialField.CREDIT_LIMIT, "1,15,000"), 0, new BoundingBox(10, 50, 100, 10), null);
        MetadataObservation<String> b = new MetadataObservation<>(
                fact(MaterialField.CREDIT_LIMIT, "1,15,000"), 0, new BoundingBox(11, 51, 100, 10), null);

        assertThat(MetadataFactCorrelator.correlate(a, b)).isEqualTo(Correlation.SAME_FACT);
    }

    @Test
    void sameFieldSameSection_matchingSemanticContext_isSameFact() {
        MetadataObservation<String> a = new MetadataObservation<>(
                fact(MaterialField.CREDIT_LIMIT, "1,15,000"), 0, null, "Credit Limit");
        MetadataObservation<String> b = new MetadataObservation<>(
                fact(MaterialField.CREDIT_LIMIT, "1,15,000"), 0, null, "credit limit (Rs.)");

        assertThat(MetadataFactCorrelator.correlate(a, b)).isEqualTo(Correlation.SAME_FACT);
    }

    @Test
    void differentField_isDifferentFact_regardlessOfEverythingElse() {
        MetadataObservation<String> a = new MetadataObservation<>(
                fact(MaterialField.CREDIT_LIMIT, "1,15,000"), 0, new BoundingBox(10, 50, 100, 10), "Credit Limit");
        MetadataObservation<String> b = new MetadataObservation<>(
                fact(MaterialField.ACCOUNT_HOLDER, "1,15,000"), 0, new BoundingBox(10, 50, 100, 10), "Credit Limit");

        assertThat(MetadataFactCorrelator.correlate(a, b)).isEqualTo(Correlation.DIFFERENT_FACT);
    }

    @Test
    void differentSection_isDifferentFact_theIciciShape() {
        // The exact case this design chain traces back to: same field, same value, but attributed
        // to two different sections. Must never correlate as the same fact -- doing so would let a
        // section-attribution bug quietly launder a wrong value into looking corroborated.
        MetadataObservation<String> sectionZero = new MetadataObservation<>(
                fact(MaterialField.CREDIT_LIMIT, "1,15,000"), 0, new BoundingBox(10, 50, 100, 10), "Credit Limit");
        MetadataObservation<String> sectionOne = new MetadataObservation<>(
                fact(MaterialField.CREDIT_LIMIT, "1,15,000"), 1, new BoundingBox(10, 50, 100, 10), "Credit Limit");

        assertThat(MetadataFactCorrelator.correlate(sectionZero, sectionOne)).isEqualTo(Correlation.DIFFERENT_FACT);
    }

    @Test
    void duplicateHeaderContent_repeatedAcrossSections_isDifferentFact_notSameFact() {
        // "Duplicate page/header content" attack: the same label/value pair (e.g. a bank's own
        // letterhead figure repeated on every page) must not correlate across sections just
        // because the text is identical.
        MetadataObservation<String> onFirstSection = new MetadataObservation<>(
                fact(MaterialField.BRANCH, "MG Road Branch"), 0, null, "Branch");
        MetadataObservation<String> onSecondSection = new MetadataObservation<>(
                fact(MaterialField.BRANCH, "MG Road Branch"), 2, null, "Branch");

        assertThat(MetadataFactCorrelator.correlate(onFirstSection, onSecondSection))
                .isEqualTo(Correlation.DIFFERENT_FACT);
    }

    @Test
    void sameFieldSameSection_noRegionOrSemanticSignal_isUncertain_neverSameFactOnValueAlone() {
        // Field and section agree, but nothing distinguishes or confirms this is genuinely the same
        // observed instance -- equal values alone must not manufacture SAME_FACT here either.
        MetadataObservation<String> a = new MetadataObservation<>(
                fact(MaterialField.ACCOUNT_HOLDER, "Jane Doe"), 0, null, null);
        MetadataObservation<String> b = new MetadataObservation<>(
                fact(MaterialField.ACCOUNT_HOLDER, "Jane Doe"), 0, null, null);

        assertThat(MetadataFactCorrelator.correlate(a, b)).isEqualTo(Correlation.UNCERTAIN);
    }

    @Test
    void sameFieldSameSection_disagreeingRegionAndSemanticContext_isUncertain_notDifferentFact() {
        // Per design §2.3: an active disagreement on region/semantic context, with field+section
        // hard gates already satisfied, stays UNCERTAIN -- it does not escalate to DIFFERENT_FACT,
        // since DIFFERENT_FACT is reserved for identity mismatches, not proximity mismatches.
        MetadataObservation<String> a = new MetadataObservation<>(
                fact(MaterialField.CREDIT_LIMIT, "1,15,000"), 0, new BoundingBox(10, 50, 100, 10), "Credit Limit");
        MetadataObservation<String> b = new MetadataObservation<>(
                fact(MaterialField.CREDIT_LIMIT, "1,15,000"), 0, new BoundingBox(400, 900, 100, 10), "Minimum Due");

        assertThat(MetadataFactCorrelator.correlate(a, b)).isEqualTo(Correlation.UNCERTAIN);
    }

    // --- Dedicated bug-and-gap sweep (second, deeper adversarial pass) ---

    @Test
    void regionOverlapExactlyAtThreshold_doesNotCountAsClose() {
        // Same float/double widening bug class as TransactionFactCorrelator's geometry threshold
        // (verified: a ratio that is mathematically exactly 0.3, widened from float to double,
        // compares greater than the double literal 0.3). REGION_PROXIMITY_THRESHOLD was fixed to
        // float for the same reason; this locks the boundary in.
        BoundingBox a = new BoundingBox(0, 0, 100, 10);
        BoundingBox b = new BoundingBox(0, 7, 100, 10);
        assertThat(a.overlapRatio(b)).isEqualTo(0.3f, org.assertj.core.data.Offset.offset(0.0001f));

        MetadataObservation<String> obsA = new MetadataObservation<>(
                fact(MaterialField.CREDIT_LIMIT, "1,15,000"), 0, a, null);
        MetadataObservation<String> obsB = new MetadataObservation<>(
                fact(MaterialField.CREDIT_LIMIT, "1,15,000"), 0, b, null);

        assertThat(MetadataFactCorrelator.correlate(obsA, obsB)).isEqualTo(Correlation.UNCERTAIN);
    }

    @Test
    void semanticContextSimilarityExactlyAtThreshold_doesNotCount() {
        // "a b c" vs "a b c d e": intersection {a,b,c}=3, union=5, ratio exactly 0.6 -- not above
        // SEMANTIC_CONTEXT_THRESHOLD (0.6), so it must not, by itself, produce SAME_FACT.
        assertThat(TextSimilarity.tokenOverlapRatio("a b c", "a b c d e"))
                .isEqualTo(0.6, org.assertj.core.data.Offset.offset(0.0001));

        MetadataObservation<String> obsA = new MetadataObservation<>(
                fact(MaterialField.CREDIT_LIMIT, "1,15,000"), 0, null, "a b c");
        MetadataObservation<String> obsB = new MetadataObservation<>(
                fact(MaterialField.CREDIT_LIMIT, "1,15,000"), 0, null, "a b c d e");

        assertThat(MetadataFactCorrelator.correlate(obsA, obsB)).isEqualTo(Correlation.UNCERTAIN);
    }

    @Test
    void nullObservation_rejectedWithNullPointerException() {
        MetadataObservation<String> a = new MetadataObservation<>(
                fact(MaterialField.CREDIT_LIMIT, "1,15,000"), 0, null, null);

        assertThatThrownBy(() -> MetadataFactCorrelator.correlate(null, a))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MetadataFactCorrelator.correlate(a, null))
                .isInstanceOf(NullPointerException.class);
    }
}
