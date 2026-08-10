package com.finora.imports.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Groups a field's {@link MetadataFieldObservation}s into one same-fact cluster using
 * {@link MetadataFactCorrelator} -- the metadata analogue of {@link TransactionSameFactGrouper}.
 * Same anchor-based scoping and the same reasoning for why that is sufficient today (see that
 * class's javadoc) -- kept as a separate implementation, not a shared generic function, per
 * {@code Correlation}'s own "two implementations, never one generic function" discipline. Same
 * group-size guard, on the same terms (input size is unrestricted; the resulting same-fact group
 * must not exceed 2).
 */
public final class MetadataSameFactGrouper {

    private MetadataSameFactGrouper() {
    }

    public static <T> SameFactGroupingResult<T> group(List<MetadataFieldObservation<T>> observations) {
        Objects.requireNonNull(observations, "observations");
        if (observations.isEmpty()) {
            return new SameFactGroupingResult<>(List.of(), List.of(), List.of());
        }

        MetadataFieldObservation<T> anchor = observations.get(0);
        List<FieldFact<T>> sameFactGroup = new ArrayList<>();
        List<FieldFact<T>> excludedAsDifferent = new ArrayList<>();
        List<FieldFact<T>> excludedAsUncertain = new ArrayList<>();
        sameFactGroup.add(anchor.position().fact());

        for (int i = 1; i < observations.size(); i++) {
            MetadataFieldObservation<T> candidate = observations.get(i);
            Correlation correlation = MetadataFactCorrelator.correlate(anchor.position(), candidate.position());
            switch (correlation) {
                case SAME_FACT -> sameFactGroup.add(candidate.position().fact());
                case DIFFERENT_FACT -> excludedAsDifferent.add(candidate.position().fact());
                case UNCERTAIN -> excludedAsUncertain.add(candidate.position().fact());
            }
        }

        if (sameFactGroup.size() > 2) {
            throw new IllegalStateException(
                    "MetadataSameFactGrouper's anchor-based grouping is only valid for at most 2 "
                            + "genuinely same-fact sources (today's native+OCR ceiling); got "
                            + sameFactGroup.size() + ". Real pairwise/transitive clustering is required "
                            + "before this can be trusted for 3+ concurrent agreeing sources.");
        }

        return new SameFactGroupingResult<>(sameFactGroup, excludedAsDifferent, excludedAsUncertain);
    }
}
