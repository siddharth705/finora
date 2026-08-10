package com.finora.imports.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Groups a field's {@link TransactionFieldObservation}s into one same-fact cluster using
 * {@link TransactionFactCorrelator} -- the missing link between Phase B's correlation and Phase
 * C's dimension assessment.
 *
 * <p><b>Grouping is anchored against the first observation, not fully pairwise-transitive.</b>
 * Every other observation is correlated only against {@code observations.get(0)}; it is not
 * checked against every other member of the emerging group. For today's realistic case -- at
 * most two sources (native, OCR) per field, since {@code RoutingTextAcquirer} runs them mutually
 * exclusively and even the approved routing change (design §4b) only ever produces two -- this is
 * exactly equivalent to full pairwise correlation, because there is only ever one pair to check.
 * Documented honestly as a scoped simplification, not silently assumed general: if a third
 * concurrent acquisition source is ever added, this must be revisited with real pairwise/
 * transitive clustering, evaluated against real multi-source documents -- not invented here
 * without that evidence, the same discipline the design applies to its own thresholds.
 *
 * <p><b>The size guard below is on the resulting same-fact GROUP, not the input list.</b> The
 * input may legitimately contain any number of candidate observations -- most will correlate
 * {@code DIFFERENT_FACT}/{@code UNCERTAIN} and be excluded, which is exactly the case
 * {@code TransactionEvidencePipelineTest}'s audit-finding regression test exercises with 3 inputs.
 * What's out of scope, and now fails loudly rather than silently trusting a non-transitive
 * clustering, is 3+ observations that ALL genuinely correlate {@code SAME_FACT} with the anchor --
 * the one shape this algorithm's documented simplification does not cover.
 */
public final class TransactionSameFactGrouper {

    private TransactionSameFactGrouper() {
    }

    public static <T> SameFactGroupingResult<T> group(List<TransactionFieldObservation<T>> observations) {
        Objects.requireNonNull(observations, "observations");
        if (observations.isEmpty()) {
            return new SameFactGroupingResult<>(List.of(), List.of(), List.of());
        }

        TransactionFieldObservation<T> anchor = observations.get(0);
        List<FieldFact<T>> sameFactGroup = new ArrayList<>();
        List<FieldFact<T>> excludedAsDifferent = new ArrayList<>();
        List<FieldFact<T>> excludedAsUncertain = new ArrayList<>();
        sameFactGroup.add(anchor.fact());

        for (int i = 1; i < observations.size(); i++) {
            TransactionFieldObservation<T> candidate = observations.get(i);
            Correlation correlation = TransactionFactCorrelator.correlate(anchor.position(), candidate.position());
            switch (correlation) {
                case SAME_FACT -> sameFactGroup.add(candidate.fact());
                case DIFFERENT_FACT -> excludedAsDifferent.add(candidate.fact());
                case UNCERTAIN -> excludedAsUncertain.add(candidate.fact());
            }
        }

        if (sameFactGroup.size() > 2) {
            throw new IllegalStateException(
                    "TransactionSameFactGrouper's anchor-based grouping is only valid for at most 2 "
                            + "genuinely same-fact sources (today's native+OCR ceiling); got "
                            + sameFactGroup.size() + ". Real pairwise/transitive clustering is required "
                            + "before this can be trusted for 3+ concurrent agreeing sources.");
        }

        return new SameFactGroupingResult<>(sameFactGroup, excludedAsDifferent, excludedAsUncertain);
    }
}
