package com.finora.imports.evidence;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Decides whether two {@link TransactionObservation}s describe the same physical transaction row --
 * design §2.2. The scored weights and thresholds below are a placeholder structure, not a tuned
 * formula (design's own words: real weighting waits on corpus evidence once native+OCR co-occur in
 * production); what is fixed, and load-bearing, is:
 *
 * <ul>
 *   <li>page is a hard gate, never a scored signal;</li>
 *   <li>date and amount are the two strongest scored signals;</li>
 *   <li>description similarity and geometry corroborate, never decide alone;</li>
 *   <li><b>equal field values alone never produce {@code SAME_FACT}.</b> Two rows on the same page
 *       with identical date/amount/direction/description (e.g. two genuinely separate ₹500 debits
 *       on the same day) are exactly the case this correlator must not conflate. {@code SAME_FACT}
 *       additionally requires positive <em>spatial</em> confirmation -- overlapping geometry, or a
 *       matching ordinal row index -- precisely so that value-equality by itself is never mistaken
 *       for identity. When neither side carries any spatial signal at all, the outcome is capped at
 *       {@code UNCERTAIN} even if every scored value matches, because nothing here can rule out two
 *       distinct rows that simply happen to look alike.</li>
 * </ul>
 */
public final class TransactionFactCorrelator {

    private static final int SAME_PAGE_SCORE = 2;
    private static final int DATE_EXACT_SCORE = 3;
    private static final int DATE_CLOSE_SCORE = 1;
    private static final long DATE_CLOSE_TOLERANCE_DAYS = 1;
    private static final int AMOUNT_EXACT_SCORE = 3;
    private static final int AMOUNT_ROUNDING_SCORE = 1;
    private static final double AMOUNT_ROUNDING_RELATIVE_TOLERANCE = 0.001;
    private static final int DIRECTION_MATCH_SCORE = 2;
    private static final int DESCRIPTION_SIMILARITY_SCORE = 2;
    private static final double DESCRIPTION_SIMILARITY_THRESHOLD = 0.6;
    private static final int GEOMETRY_OVERLAP_SCORE = 2;
    // float, deliberately matching BoundingBox#overlapRatio's return type: comparing a float
    // against a double literal widens the float and can push a value that is mathematically
    // exactly at the threshold to compare as *greater than* it (verified: 300f/1000f widened to
    // double is 0.30000001192092896, which is > the double literal 0.3) -- silently defeating the
    // intended strict-boundary exclusion. Bug found in the Phase B adversarial sweep.
    private static final float GEOMETRY_OVERLAP_THRESHOLD = 0.3f;
    private static final int ORDINAL_MATCH_SCORE = 1;
    private static final int SCORE_THRESHOLD_HIGH = 10;

    private TransactionFactCorrelator() {
    }

    public static Correlation correlate(TransactionObservation a, TransactionObservation b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        if (a.page() != b.page()) {
            return Correlation.DIFFERENT_FACT;
        }
        int score = SAME_PAGE_SCORE;

        boolean dateComparable = a.date() != null && b.date() != null;
        boolean datesEqual = dateComparable && a.date().equals(b.date());
        long daysApart = dateComparable ? Math.abs(ChronoUnit.DAYS.between(a.date(), b.date())) : -1;
        boolean datesCloseWithinTolerance = dateComparable && !datesEqual && daysApart <= DATE_CLOSE_TOLERANCE_DAYS;
        boolean datesClearlyDiffer = dateComparable && daysApart > DATE_CLOSE_TOLERANCE_DAYS;
        if (datesEqual) {
            score += DATE_EXACT_SCORE;
        } else if (datesCloseWithinTolerance) {
            score += DATE_CLOSE_SCORE;
        }

        boolean amountComparable = a.amount() != null && b.amount() != null;
        boolean amountsEqual = amountComparable && a.amount().compareTo(b.amount()) == 0;
        boolean amountsWithinRounding = amountComparable && !amountsEqual
                && relativeDifference(a.amount(), b.amount()) < AMOUNT_ROUNDING_RELATIVE_TOLERANCE;
        boolean amountsClearlyDiffer = amountComparable && !amountsEqual && !amountsWithinRounding;
        if (amountsEqual) {
            score += AMOUNT_EXACT_SCORE;
        } else if (amountsWithinRounding) {
            score += AMOUNT_ROUNDING_SCORE;
        }

        if (a.direction() != null && a.direction().equals(b.direction())) {
            score += DIRECTION_MATCH_SCORE;
        }

        if (TextSimilarity.tokenOverlapRatio(a.description(), b.description()) > DESCRIPTION_SIMILARITY_THRESHOLD) {
            score += DESCRIPTION_SIMILARITY_SCORE;
        }

        boolean geometryComparable = a.boundingBox() != null && b.boundingBox() != null;
        float overlapRatio = geometryComparable ? a.boundingBox().overlapRatio(b.boundingBox()) : 0f;
        // Two distinct notions, deliberately not conflated: "any overlap at all" is the strict,
        // conservative check used to block a DIFFERENT_FACT conclusion (a hairline touch between
        // two adjacent rows' boxes must not be read as "clearly not the same region"); "meaningful
        // overlap" (above threshold) is what actually counts as positive spatial confirmation for
        // SAME_FACT. A tiny accidental overlap between two genuinely adjacent, distinct rows must
        // satisfy neither the scoring bonus nor spatial confirmation -- see spatiallyConfirmed below.
        boolean geometryOverlapsAtAll = geometryComparable && overlapRatio > 0f;
        boolean geometryMeaningfullyOverlaps = geometryComparable && overlapRatio > GEOMETRY_OVERLAP_THRESHOLD;
        if (geometryMeaningfullyOverlaps) {
            score += GEOMETRY_OVERLAP_SCORE;
        }

        boolean ordinalComparable = a.ordinalPosition() != null && b.ordinalPosition() != null;
        boolean ordinalsEqual = ordinalComparable && a.ordinalPosition().equals(b.ordinalPosition());
        boolean ordinalsDiffer = ordinalComparable && !ordinalsEqual;
        if (ordinalsEqual) {
            score += ORDINAL_MATCH_SCORE;
        }

        // Design §2.2's stated rule: strong disagreement on both of the two strongest signals,
        // confirmed by non-overlapping geometry, is definitive.
        if (datesClearlyDiffer && amountsClearlyDiffer && geometryComparable && !geometryOverlapsAtAll) {
            return Correlation.DIFFERENT_FACT;
        }

        // Same page, geometry present for both and provably not the same physical region, and a
        // row-index that also disagrees: two distinct rows, regardless of what their values say.
        // This is what stops "adjacent rows with identical values" from being conflated on value
        // agreement alone.
        if (geometryComparable && !geometryOverlapsAtAll && ordinalsDiffer) {
            return Correlation.DIFFERENT_FACT;
        }

        // Spatial confirmation for SAME_FACT requires *meaningful* overlap, not merely nonzero --
        // a hairline touch between two adjacent, distinct rows' boxes (plausible with real
        // extraction geometry) must not count as proof they're the same physical row.
        //
        // Bug found in adversarial re-review: ordinal-position agreement alone must not be
        // allowed to "rescue" a case where geometry, when available, actively shows zero overlap.
        // Row-index assignment can coincidentally collide (e.g. across a reconstruction quirk);
        // pixel-space geometry is the more direct, harder-to-fake signal, so when both are present
        // and they *disagree* with each other (ordinal says same row, geometry says provably not
        // the same region), that is itself a contradiction between two spatial signals -- correctly
        // UNCERTAIN, not a confident SAME_FACT. Ordinal-only confirmation still applies normally
        // when geometry is simply unavailable, not when it's available and actively disagrees.
        boolean geometryActivelyContradicts = geometryComparable && !geometryOverlapsAtAll;
        boolean spatiallyConfirmed = geometryMeaningfullyOverlaps
                || (ordinalsEqual && !geometryActivelyContradicts);
        if (score >= SCORE_THRESHOLD_HIGH && spatiallyConfirmed) {
            return Correlation.SAME_FACT;
        }
        return Correlation.UNCERTAIN;
    }

    private static double relativeDifference(BigDecimal a, BigDecimal b) {
        BigDecimal larger = a.abs().max(b.abs());
        if (larger.signum() == 0) {
            return 0.0;
        }
        return a.subtract(b).abs().divide(larger, java.math.MathContext.DECIMAL64).doubleValue();
    }
}
