package com.finora.imports.evidence;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of comparing values across a group of {@link FieldFact}s already established to be
 * mutually {@link Correlation#SAME_FACT} -- ADR-006 §4b. Deliberately a second, later step from
 * correlation, never merged into it: comparing values before establishing they describe the same
 * fact can manufacture false {@link #AGREE} results that look like corroboration and are not
 * (§4a's own stated reason for splitting correlation out as a mandatory first step).
 *
 * <p>{@link #compare} takes the group as already-correlated -- it has no opinion on how that
 * grouping was formed. Any pair the correlator returned {@link Correlation#UNCERTAIN} for must be
 * excluded from the group before calling this, per design §2.4/§4a: an uncertain pair contributes
 * neither {@code AGREE} nor {@code DISAGREE}, so it must never reach this function at all.
 */
public enum EvidenceComparison {
    /** Every {@code SAME_FACT}-correlated source proposes the same value. */
    AGREE,
    /** {@code SAME_FACT}-correlated sources disagree -- this is what forces {@code CONFLICTING}
     *  (design §3), never a silent pick of one value over another. */
    DISAGREE,
    /** Exactly one source produced a candidate for this fact; nothing to correlate against, let
     *  alone compare. Deliberately not {@link #AGREE} -- one source with no rival is not agreement
     *  (design §4b, "kept exactly as written" per its own review). */
    UNCONTESTED,
    /** No source produced a candidate for this fact at all. */
    ABSENT;

    /**
     * @param sameFactGroup facts already established to be mutually {@code SAME_FACT}-correlated
     *        for one {@link MaterialField}. May be empty ({@link #ABSENT}).
     */
    public static <T> EvidenceComparison compare(List<FieldFact<T>> sameFactGroup) {
        Objects.requireNonNull(sameFactGroup, "sameFactGroup");
        if (sameFactGroup.isEmpty()) {
            return ABSENT;
        }
        if (sameFactGroup.size() == 1) {
            return UNCONTESTED;
        }
        Object firstValue = sameFactGroup.get(0).value();
        boolean allAgree = sameFactGroup.stream().allMatch(f -> Objects.equals(f.value(), firstValue));
        return allAgree ? AGREE : DISAGREE;
    }
}
