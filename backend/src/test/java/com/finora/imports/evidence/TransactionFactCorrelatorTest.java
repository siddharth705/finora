package com.finora.imports.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class TransactionFactCorrelatorTest {

    private static TransactionObservation row(int page, LocalDate date, String amount, String direction,
            String description, BoundingBox box, Integer ordinal) {
        return new TransactionObservation(page, date, amount == null ? null : new BigDecimal(amount),
                direction, description, box, ordinal);
    }

    @Test
    void nativeAndOcr_sameTransaction_overlappingGeometry_isSameFact() {
        TransactionObservation native_ = row(0, LocalDate.of(2026, 3, 5), "5000.00", "DEBIT",
                "UPI/paytm/priya sharma", new BoundingBox(10, 100, 200, 12), 4);
        TransactionObservation ocr = row(0, LocalDate.of(2026, 3, 5), "5000.00", "DEBIT",
                "UPI PAYTM PRIYA SHARMA", new BoundingBox(11, 101, 198, 13), 4);

        assertThat(TransactionFactCorrelator.correlate(native_, ocr)).isEqualTo(Correlation.SAME_FACT);
    }

    @Test
    void nativeAndOcr_differentTransactions_nonOverlappingGeometry_isDifferentFact() {
        TransactionObservation native_ = row(0, LocalDate.of(2026, 3, 5), "5000.00", "DEBIT",
                "UPI/paytm/priya sharma", new BoundingBox(10, 100, 200, 12), 4);
        TransactionObservation ocr = row(0, LocalDate.of(2026, 3, 20), "750.00", "CREDIT",
                "NEFT/salary/acme corp", new BoundingBox(10, 300, 200, 12), 9);

        assertThat(TransactionFactCorrelator.correlate(native_, ocr)).isEqualTo(Correlation.DIFFERENT_FACT);
    }

    @Test
    void adjacentRows_identicalValues_differentGeometryAndOrdinal_isDifferentFact_notSameFact() {
        // THE core invariant: two genuinely separate ₹500 debits on the same day, same description,
        // must never be conflated as one observation merely because their values are equal.
        TransactionObservation rowFive = row(0, LocalDate.of(2026, 3, 5), "500.00", "DEBIT",
                "ATM WDL", new BoundingBox(10, 100, 200, 12), 4);
        TransactionObservation rowSix = row(0, LocalDate.of(2026, 3, 5), "500.00", "DEBIT",
                "ATM WDL", new BoundingBox(10, 120, 200, 12), 5);

        assertThat(TransactionFactCorrelator.correlate(rowFive, rowSix)).isEqualTo(Correlation.DIFFERENT_FACT);
    }

    @Test
    void identicalValues_noSpatialSignalAtAll_isUncertain_neverSameFact() {
        // Same invariant, harder version: no geometry AND no ordinal position on either side. There
        // is nothing here that can rule out two distinct rows that simply look alike, so the result
        // must be capped at UNCERTAIN even though every scored value agrees.
        TransactionObservation a = row(0, LocalDate.of(2026, 3, 5), "500.00", "DEBIT", "ATM WDL", null, null);
        TransactionObservation b = row(0, LocalDate.of(2026, 3, 5), "500.00", "DEBIT", "ATM WDL", null, null);

        assertThat(TransactionFactCorrelator.correlate(a, b)).isEqualTo(Correlation.UNCERTAIN);
    }

    @Test
    void sameAmount_differentDates_isNeverSameFact() {
        TransactionObservation a = row(0, LocalDate.of(2026, 3, 1), "1000.00", "DEBIT", "grocery", null, null);
        TransactionObservation b = row(0, LocalDate.of(2026, 3, 28), "1000.00", "DEBIT", "grocery", null, null);

        assertThat(TransactionFactCorrelator.correlate(a, b)).isNotEqualTo(Correlation.SAME_FACT);
    }

    @Test
    void sameDate_multipleTransactions_distinguishedByGeometryAndOrdinal_isDifferentFact() {
        TransactionObservation first = row(0, LocalDate.of(2026, 3, 5), "500.00", "DEBIT", "grocery",
                new BoundingBox(10, 100, 200, 12), 4);
        TransactionObservation second = row(0, LocalDate.of(2026, 3, 5), "1500.00", "DEBIT", "electronics",
                new BoundingBox(10, 130, 200, 12), 5);

        assertThat(TransactionFactCorrelator.correlate(first, second)).isEqualTo(Correlation.DIFFERENT_FACT);
    }

    @Test
    void twoDifferentTransactions_thatLookSuperficiallySimilar_isNotSameFact() {
        // Same day, similar description, but a materially different amount and non-overlapping
        // geometry -- must not be mistaken for the same row just because it "looks similar".
        TransactionObservation a = row(0, LocalDate.of(2026, 3, 5), "500.00", "DEBIT",
                "UPI/swiggy/order", new BoundingBox(10, 100, 200, 12), 4);
        TransactionObservation b = row(0, LocalDate.of(2026, 3, 5), "5000.00", "DEBIT",
                "UPI/swiggy/order", new BoundingBox(10, 400, 200, 12), 11);

        assertThat(TransactionFactCorrelator.correlate(a, b)).isNotEqualTo(Correlation.SAME_FACT);
    }

    @Test
    void wrappedDescription_stillCorrelatesViaTokenOverlap() {
        TransactionObservation wrapped = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "NEFT TRANSFER TO", new BoundingBox(10, 100, 150, 12), 4);
        TransactionObservation unwrapped = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "NEFT TRANSFER TO JOHN DOE ACCT 1234", new BoundingBox(10, 101, 300, 12), 4);

        assertThat(TransactionFactCorrelator.correlate(wrapped, unwrapped)).isEqualTo(Correlation.SAME_FACT);
    }

    @Test
    void reorderedOcrDescription_stillCorrelates() {
        TransactionObservation native_ = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 100, 200, 12), 4);
        // OCR recognised the same tokens in a scrambled order.
        TransactionObservation ocrReordered = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "sharma priya UPI paytm", new BoundingBox(11, 101, 199, 12), 4);

        assertThat(TransactionFactCorrelator.correlate(native_, ocrReordered)).isEqualTo(Correlation.SAME_FACT);
    }

    @Test
    void missingDate_stillReachesSameFactViaOtherStrongSignals() {
        TransactionObservation missingDate = row(0, null, "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 100, 200, 12), 4);
        TransactionObservation withDate = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(11, 101, 199, 12), 4);

        assertThat(TransactionFactCorrelator.correlate(missingDate, withDate)).isEqualTo(Correlation.SAME_FACT);
    }

    @Test
    void missingDate_withOnlyWeakOtherSignals_isUncertainNotSameFact() {
        TransactionObservation missingDate = row(0, null, "2500.00", "DEBIT", null, null, null);
        TransactionObservation withDate = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT", null, null, null);

        assertThat(TransactionFactCorrelator.correlate(missingDate, withDate)).isEqualTo(Correlation.UNCERTAIN);
    }

    @Test
    void missingAmount_stillReachesSameFactViaOtherStrongSignals() {
        TransactionObservation missingAmount = row(0, LocalDate.of(2026, 3, 5), null, "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 100, 200, 12), 4);
        TransactionObservation withAmount = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(11, 101, 199, 12), 4);

        assertThat(TransactionFactCorrelator.correlate(missingAmount, withAmount)).isEqualTo(Correlation.SAME_FACT);
    }

    @Test
    void missingAmount_withOnlyWeakOtherSignals_isUncertainNotSameFact() {
        TransactionObservation missingAmount = row(0, LocalDate.of(2026, 3, 5), null, "DEBIT", null, null, null);
        TransactionObservation withAmount = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT", null, null, null);

        assertThat(TransactionFactCorrelator.correlate(missingAmount, withAmount)).isEqualTo(Correlation.UNCERTAIN);
    }

    @Test
    void differentPages_neverSameFact_evenIfEverythingElseMatchesPerfectly() {
        // Multi-page transaction boundary / duplicate header-style content: a perfect match on
        // every scored field must still never correlate across a page boundary.
        TransactionObservation onPageOne = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 100, 200, 12), 4);
        TransactionObservation onPageTwo = row(1, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 100, 200, 12), 4);

        assertThat(TransactionFactCorrelator.correlate(onPageOne, onPageTwo)).isEqualTo(Correlation.DIFFERENT_FACT);
    }

    @Test
    void oneAcquisitionSplitsARowDifferently_ordinalMismatch_stillCorrelatesViaGeometry() {
        // Native reconstructs a wrapped row as index 5; OCR merges two lines differently and calls
        // it index 6. The ordinal disagreement alone must not force DIFFERENT_FACT when geometry
        // strongly confirms it's the same physical region.
        TransactionObservation native_ = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 100, 200, 12), 5);
        TransactionObservation ocrSplitDifferently = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 101, 200, 12), 6);

        assertThat(TransactionFactCorrelator.correlate(native_, ocrSplitDifferently)).isEqualTo(Correlation.SAME_FACT);
    }

    @Test
    void adjacentRows_identicalValues_hairlineGeometryTouch_isUncertain_notSameFact() {
        // Adversarial-review finding: two adjacent, genuinely distinct rows whose bounding boxes
        // just barely touch (plausible row-padding overlap in real extraction geometry) must not
        // be read as positive spatial confirmation. A marginal, sub-threshold overlap combined
        // with a differing ordinal position must land on UNCERTAIN, not SAME_FACT.
        TransactionObservation rowFive = row(0, LocalDate.of(2026, 3, 5), "500.00", "DEBIT",
                "ATM WDL", new BoundingBox(10, 100, 200, 12), 4);
        // y spans 100-112 vs 111-123: a 1-unit sliver of overlap, ratio far below the meaningful
        // overlap threshold.
        TransactionObservation rowSix = row(0, LocalDate.of(2026, 3, 5), "500.00", "DEBIT",
                "ATM WDL", new BoundingBox(10, 111, 200, 12), 5);

        assertThat(TransactionFactCorrelator.correlate(rowFive, rowSix)).isEqualTo(Correlation.UNCERTAIN);
    }

    @Test
    void ambiguousCase_partialSignalsOnly_remainsUncertain() {
        TransactionObservation a = row(0, LocalDate.of(2026, 3, 5), "2500.00", null, null, null, null);
        TransactionObservation b = row(0, LocalDate.of(2026, 3, 6), "2500.00", null, null, null, null);

        assertThat(TransactionFactCorrelator.correlate(a, b)).isEqualTo(Correlation.UNCERTAIN);
    }

    // --- Dedicated bug-and-gap sweep (second, deeper adversarial pass) ---

    @Test
    void ordinalAgreement_cannotRescueAGeometryConfirmedNonOverlap() {
        // Bug found in this sweep: geometry, when available, is the stronger spatial signal. If it
        // actively shows zero overlap, an agreeing ordinal position must not be trusted to confirm
        // SAME_FACT anyway -- the two spatial signals are contradicting each other, which is itself
        // a reason for doubt, not confidence.
        TransactionObservation a = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 100, 100, 10), 4);
        TransactionObservation b = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 500, 100, 10), 4);

        assertThat(TransactionFactCorrelator.correlate(a, b)).isEqualTo(Correlation.UNCERTAIN);
    }

    @Test
    void ordinalAgreement_stillConfirms_whenGeometryIsSimplyAbsent_notContradicting() {
        // The fix above must not overcorrect: when geometry isn't available at all (not actively
        // disagreeing, just missing), ordinal agreement remains valid spatial confirmation.
        TransactionObservation a = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", null, 4);
        TransactionObservation b = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", null, 4);

        assertThat(TransactionFactCorrelator.correlate(a, b)).isEqualTo(Correlation.SAME_FACT);
    }

    @Test
    void scoreExactlyAtThreshold_withSpatialConfirmation_isSameFact() {
        // date exact(3) + amount rounding-tolerance(1) + geometry meaningful overlap(2) +
        // direction(2) + page(2) = 10, exactly at SCORE_THRESHOLD_HIGH.
        TransactionObservation a = row(0, LocalDate.of(2026, 3, 5), "1000.00", "DEBIT", null,
                new BoundingBox(10, 100, 100, 12), null);
        TransactionObservation b = row(0, LocalDate.of(2026, 3, 5), "1000.50", "DEBIT", null,
                new BoundingBox(11, 101, 99, 12), null);

        assertThat(TransactionFactCorrelator.correlate(a, b)).isEqualTo(Correlation.SAME_FACT);
    }

    @Test
    void scoreOneBelowThreshold_isUncertain_notSameFact() {
        // Identical to the exactly-at-threshold case above, minus the direction match (-2): score 8.
        TransactionObservation a = row(0, LocalDate.of(2026, 3, 5), "1000.00", "DEBIT", null,
                new BoundingBox(10, 100, 100, 12), null);
        TransactionObservation b = row(0, LocalDate.of(2026, 3, 5), "1000.50", "CREDIT", null,
                new BoundingBox(11, 101, 99, 12), null);

        assertThat(TransactionFactCorrelator.correlate(a, b)).isEqualTo(Correlation.UNCERTAIN);
    }

    @Test
    void hittingScoreThresholdViaNonSpatialSignalsAlone_isStillUncertain() {
        // date(3) + amount(3) + description(2) + page(2) = 10, but neither geometry nor ordinal is
        // present at all. Hitting the numeric threshold must not be sufficient by itself --
        // spatial confirmation is mandatory, not merely score-additive.
        TransactionObservation a = row(0, LocalDate.of(2026, 3, 5), "2500.00", null,
                "UPI paytm priya sharma", null, null);
        TransactionObservation b = row(0, LocalDate.of(2026, 3, 5), "2500.00", null,
                "UPI paytm priya sharma", null, null);

        assertThat(TransactionFactCorrelator.correlate(a, b)).isEqualTo(Correlation.UNCERTAIN);
    }

    @Test
    void weakToleranceSignalsOnly_withoutGeometry_neverReachSameFact() {
        // Only "close" date and "rounding" amount (never exact), plus direction/description/ordinal
        // but NO geometry: date(1) + amount(1) + direction(2) + description(2) + ordinal(1) +
        // page(2) = 9, provably short of the threshold. Locks in that tolerance-only signals cannot
        // stack their way to SAME_FACT without a geometry backstop.
        TransactionObservation a = row(0, LocalDate.of(2026, 3, 5), "1000.00", "DEBIT",
                "UPI paytm priya sharma", null, 4);
        TransactionObservation b = row(0, LocalDate.of(2026, 3, 6), "1000.50", "DEBIT",
                "UPI paytm priya sharma", null, 4);

        assertThat(TransactionFactCorrelator.correlate(a, b)).isEqualTo(Correlation.UNCERTAIN);
    }

    @Test
    void sameFact_despiteDirectionDisagreement_whenGeometryAndOrdinalStronglyConfirm() {
        // Important layering point: Correlation answers "same fact?", not "do the values agree?".
        // A direction flip between two observations of the same physical row (one engine misread
        // the sign) must still correlate as SAME_FACT -- the disagreement is meant to be caught
        // downstream by design §4b's EvidenceComparison (Phase C), not silently lost by this
        // correlator declaring them unrelated.
        TransactionObservation native_ = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 100, 200, 12), 4);
        TransactionObservation ocrFlippedSign = row(0, LocalDate.of(2026, 3, 5), "2500.00", "CREDIT",
                "UPI paytm priya sharma", new BoundingBox(11, 101, 199, 12), 4);

        assertThat(TransactionFactCorrelator.correlate(native_, ocrFlippedSign)).isEqualTo(Correlation.SAME_FACT);
    }

    @Test
    void sameFact_despiteAmountDisagreement_whenGeometryAndOrdinalStronglyConfirm() {
        // Same layering point, for the financially-significant case (the exact shape of the
        // BOB/HDFC/ICICI bugs this ADR chain traces back to): a misread amount on an otherwise
        // spatially-confirmed same row must still correlate as SAME_FACT, so the value mismatch can
        // be caught as a contradiction downstream rather than silently dropped as unrelated.
        TransactionObservation native_ = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 100, 200, 12), 4);
        TransactionObservation ocrMisreadAmount = row(0, LocalDate.of(2026, 3, 5), "7500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(11, 101, 199, 12), 4);

        assertThat(TransactionFactCorrelator.correlate(native_, ocrMisreadAmount)).isEqualTo(Correlation.SAME_FACT);
    }

    @Test
    void withoutAnyDateOrAmountAgreement_neverReachesSameFact_regardlessOfOtherSignals() {
        // direction(2) + description(2) + geometry(2) + ordinal(1) + page(2) = 9, provably short of
        // threshold when neither date nor amount contributes anything at all.
        TransactionObservation a = row(0, LocalDate.of(2026, 3, 5), "1000.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 100, 200, 12), 4);
        TransactionObservation b = row(0, LocalDate.of(2026, 3, 25), "9999.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(11, 101, 199, 12), 4);

        assertThat(TransactionFactCorrelator.correlate(a, b)).isNotEqualTo(Correlation.SAME_FACT);
    }

    @Test
    void geometryOverlapExactlyAtThreshold_doesNotCountAsMeaningful() {
        // Strict-greater-than semantics: a ratio exactly equal to GEOMETRY_OVERLAP_THRESHOLD (0.3)
        // must not count as meaningful overlap, locking in the boundary so a future refactor to
        // >= doesn't silently change behavior.
        // Boxes sized so intersection/smallerArea == 0.3 exactly: smaller area 1000 (100x10),
        // intersection 300 (100 wide x 3 tall).
        BoundingBox a = new BoundingBox(0, 0, 100, 10);
        BoundingBox b = new BoundingBox(0, 7, 100, 10);
        assertThat(a.overlapRatio(b)).isEqualTo(0.3f, org.assertj.core.data.Offset.offset(0.001f));

        TransactionObservation obsA = row(0, LocalDate.of(2026, 3, 5), "1000.00", "DEBIT", null, a, null);
        TransactionObservation obsB = row(0, LocalDate.of(2026, 3, 5), "1000.50", "DEBIT", null, b, null);
        // date(3)+amount rounding(1)+direction(2)+page(2)=8, no geometry bonus at exactly-threshold,
        // no ordinal -> score 8, and spatiallyConfirmed false (not meaningfully overlapping) -> UNCERTAIN.
        assertThat(TransactionFactCorrelator.correlate(obsA, obsB)).isEqualTo(Correlation.UNCERTAIN);
    }

    @Test
    void descriptionSimilarityExactlyAtThreshold_doesNotCount() {
        // "a b c" vs "a b c d e": intersection {a,b,c}=3, union=5, ratio exactly 0.6 -- not above
        // DESCRIPTION_SIMILARITY_THRESHOLD (0.6), so it must not contribute score.
        assertThat(TextSimilarity.tokenOverlapRatio("a b c", "a b c d e"))
                .isEqualTo(0.6, org.assertj.core.data.Offset.offset(0.0001));

        TransactionObservation a = row(0, LocalDate.of(2026, 3, 5), "1000.00", null, "a b c", null, null);
        TransactionObservation b = row(0, LocalDate.of(2026, 3, 5), "1000.00", null, "a b c d e", null, null);
        // date(3) + amount(3) + page(2) = 8, no description bonus at exactly-threshold, no
        // geometry/ordinal -> UNCERTAIN regardless (also correctly short of the score threshold).
        assertThat(TransactionFactCorrelator.correlate(a, b)).isEqualTo(Correlation.UNCERTAIN);
    }

    @Test
    void dateCloseToleranceExactlyOneDayApart_countsAsClose_twoDaysDoesNot() {
        TransactionObservation base = row(0, LocalDate.of(2026, 3, 5), "1000.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 100, 200, 12), 4);
        TransactionObservation oneDayApart = row(0, LocalDate.of(2026, 3, 6), "1000.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(11, 101, 199, 12), 4);
        TransactionObservation twoDaysApart = row(0, LocalDate.of(2026, 3, 7), "1000.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(11, 101, 199, 12), 4);

        // One day apart: date_close(1) + amount(3) + direction(2) + desc(2) + geometry(2) +
        // ordinal(1) + page(2) = 13 -- SAME_FACT.
        assertThat(TransactionFactCorrelator.correlate(base, oneDayApart)).isEqualTo(Correlation.SAME_FACT);
        // Two days apart: date contributes 0 (beyond tolerance) -- score 12, still >= 10 with
        // spatial confirmation via geometry+ordinal, and NOT caught by the "clearly differ" gate
        // since amount doesn't clearly differ. Still SAME_FACT: this documents that the tolerance
        // boundary alone doesn't flip the overall outcome once geometry/ordinal already confirm --
        // the date signal is corroborating, not decisive alone, exactly as design §2.2 states.
        assertThat(TransactionFactCorrelator.correlate(base, twoDaysApart)).isEqualTo(Correlation.SAME_FACT);
    }

    @Test
    void nullObservation_rejectedWithNullPointerException() {
        TransactionObservation a = row(0, LocalDate.of(2026, 3, 5), "1000.00", "DEBIT", null, null, null);

        assertThatThrownBy(() -> TransactionFactCorrelator.correlate(null, a))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TransactionFactCorrelator.correlate(a, null))
                .isInstanceOf(NullPointerException.class);
    }
}
