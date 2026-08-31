package com.finora.service;

import java.math.BigDecimal;

/**
 * Computes {@code match_confidence} for a single match (docs/proposals/reconciliation-evolution-
 * roadmap-proposal.md, Part 5) -- "how sure are we THIS SPECIFIC PAIRING is correct?", as opposed
 * to {@link SourceTrust}'s "how much do we trust this channel in general?". The two are computed
 * and stored separately (see {@link com.finora.entity.TransactionRelationship#getConfidence()}/
 * {@code getSourceTrust()}) and this class only ever produces the former.
 *
 * <pre>
 *   match_confidence = base(match_type) x amount_factor x date_decay
 * </pre>
 *
 * Returned on a 0-100 integer scale, matching {@link com.finora.entity.Transaction#getDecisionConfidence()}
 * and {@code TransactionRelationship.confidence}'s existing column scale, rather than the doc's own
 * 0.0-1.0 example -- one confidence-scale convention across the codebase rather than two.
 */
final class ConfidenceScorer {

    private ConfidenceScorer() {}

    /**
     * The three tiers the roadmap doc defines. {@code FUZZY} (Levenshtein-based) is reserved for
     * {@link com.finora.integrations.google.merchant.GmailReconciliationMatcher}'s matching, which
     * this class does not score yet -- see that class's own scope note. {@code ReconciliationService}'s
     * four passes use {@code EXACT} for the duplicate pass's composite-key match and {@code
     * MERCHANT_AND_AMOUNT} for the other three, none of which involve free-text similarity scoring.
     */
    enum MatchType {
        EXACT(0.99), MERCHANT_AND_AMOUNT(0.90), FUZZY(0.75);

        final double baseScore;

        MatchType(double baseScore) {
            this.baseScore = baseScore;
        }
    }

    /**
     * @param matchType        which base tier this match belongs to
     * @param matchedAmount    the amount the match is being judged against (the denominator for
     *                         {@code amount_factor}) -- for a partial refund this is the original
     *                         expense's amount, not the (smaller) refunded amount, since it is the
     *                         expense that defines "how much was there to account for"
     * @param amountDelta      how far the actual matched amount was from {@code matchedAmount};
     *                         zero for an exact match
     * @param daysIntoWindow   how many days into the match's date window this pairing landed --
     *                         0 at the anchor date
     * @param windowSizeDays   the full width of the date window this match type allows; 0 or
     *                         negative is treated as "no decay applies" (an exact-key match has no
     *                         window at all) rather than dividing by zero
     * @return match_confidence on a 0-100 scale
     */
    static int score(MatchType matchType, BigDecimal matchedAmount, BigDecimal amountDelta,
                      long daysIntoWindow, long windowSizeDays) {
        double confidence = matchType.baseScore
                * amountFactor(matchedAmount, amountDelta)
                * dateDecay(daysIntoWindow, windowSizeDays);
        return (int) Math.round(confidence * 100);
    }

    /**
     * 1.0 if exact, else {@code 1 - (|delta| / matchedAmount)}, floored at 0.5 -- a match is never
     * scored below half confidence on amount alone; a wildly divergent amount should have failed
     * the pass's own matching predicate before it ever reaches scoring.
     */
    private static double amountFactor(BigDecimal matchedAmount, BigDecimal amountDelta) {
        if (amountDelta == null || amountDelta.signum() == 0) return 1.0;
        if (matchedAmount == null || matchedAmount.signum() <= 0) return 1.0;
        double factor = 1.0 - (amountDelta.abs().doubleValue() / matchedAmount.doubleValue());
        return Math.max(factor, 0.5);
    }

    /**
     * 1.0 at day 0 of the window, linearly decaying to 0.7 at the window's edge -- so, for example,
     * a same-day refund outranks a 179-day-old one under the existing 180-day refund window.
     */
    private static double dateDecay(long daysIntoWindow, long windowSizeDays) {
        if (windowSizeDays <= 0) return 1.0;
        double fraction = Math.min(1.0, Math.max(0.0, (double) daysIntoWindow / windowSizeDays));
        return 1.0 - fraction * 0.3;
    }
}
