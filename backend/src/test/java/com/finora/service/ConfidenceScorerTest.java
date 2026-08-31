package com.finora.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ConfidenceScorerTest {

    @Test
    void exactMatch_atDayZero_scoresTheFullBaseTier() {
        int confidence = ConfidenceScorer.score(ConfidenceScorer.MatchType.EXACT,
                new BigDecimal("100.00"), BigDecimal.ZERO, 0, 0);

        assertThat(confidence).isEqualTo(99); // base(EXACT) x 1.0 x 1.0 = 0.99
    }

    @Test
    void merchantAndAmountMatch_atDayZeroExactAmount_scoresTheFullBaseTier() {
        int confidence = ConfidenceScorer.score(ConfidenceScorer.MatchType.MERCHANT_AND_AMOUNT,
                new BigDecimal("500.00"), BigDecimal.ZERO, 0, 4);

        assertThat(confidence).isEqualTo(90);
    }

    @Test
    void amountFactor_scalesDownProportionallyToTheDelta() {
        // matchedAmount 1000, delta 100 -> amount_factor = 1 - (100/1000) = 0.9
        int confidence = ConfidenceScorer.score(ConfidenceScorer.MatchType.MERCHANT_AND_AMOUNT,
                new BigDecimal("1000.00"), new BigDecimal("100.00"), 0, 180);

        assertThat(confidence).isEqualTo((int) Math.round(0.90 * 0.9 * 1.0 * 100));
    }

    @Test
    void amountFactor_isFlooredAtHalf_evenForAWildlyDivergentAmount() {
        int confidence = ConfidenceScorer.score(ConfidenceScorer.MatchType.MERCHANT_AND_AMOUNT,
                new BigDecimal("100.00"), new BigDecimal("90.00"), 0, 180);

        // amount_factor would be 1 - (90/100) = 0.1 without the floor; floored at 0.5
        assertThat(confidence).isEqualTo((int) Math.round(0.90 * 0.5 * 1.0 * 100));
    }

    @Test
    void dateDecay_isFullConfidenceAtTheAnchorDate() {
        int confidence = ConfidenceScorer.score(ConfidenceScorer.MatchType.MERCHANT_AND_AMOUNT,
                new BigDecimal("500.00"), BigDecimal.ZERO, 0, 180);

        assertThat(confidence).isEqualTo(90);
    }

    @Test
    void dateDecay_reachesSeventyPercentAtTheWindowsEdge() {
        int confidence = ConfidenceScorer.score(ConfidenceScorer.MatchType.MERCHANT_AND_AMOUNT,
                new BigDecimal("500.00"), BigDecimal.ZERO, 180, 180);

        assertThat(confidence).isEqualTo((int) Math.round(0.90 * 1.0 * 0.7 * 100));
    }

    @Test
    void dateDecay_doesNotDecayFurtherPastTheWindowsEdge() {
        // daysIntoWindow beyond windowSizeDays must not push the decay factor negative
        int confidence = ConfidenceScorer.score(ConfidenceScorer.MatchType.MERCHANT_AND_AMOUNT,
                new BigDecimal("500.00"), BigDecimal.ZERO, 400, 180);

        assertThat(confidence).isEqualTo((int) Math.round(0.90 * 1.0 * 0.7 * 100));
    }

    @Test
    void aZeroWidthWindow_appliesNoDecay() {
        // The duplicate pass's exact-key match has no window at all -- a windowSizeDays of 0 must
        // not be treated as "0 days into an infinitely narrow window" and divide by zero.
        int confidence = ConfidenceScorer.score(ConfidenceScorer.MatchType.EXACT,
                new BigDecimal("100.00"), BigDecimal.ZERO, 0, 0);

        assertThat(confidence).isEqualTo(99);
    }

    @Test
    void aNullAmountDelta_isTreatedAsAnExactMatch() {
        int confidence = ConfidenceScorer.score(ConfidenceScorer.MatchType.EXACT,
                new BigDecimal("100.00"), null, 0, 0);

        assertThat(confidence).isEqualTo(99);
    }
}
