package com.finora.imports.evidence;

import java.util.List;
import java.util.Objects;

/**
 * The result of grouping a set of observations into one same-fact cluster -- the shared output
 * shape for both {@link TransactionSameFactGrouper} and {@link MetadataSameFactGrouper} (the
 * grouping ALGORITHMS stay separate, per Correlation's own "two implementations, never one
 * generic function" discipline; only this result shape is reusable, since it carries no
 * type-specific correlation logic itself).
 *
 * <p>Per design §2.4/§4a: a pair correlating {@code UNCERTAIN} is excluded from the group
 * entirely, never folded into it and never treated as a contradiction -- kept separately in
 * {@link #excludedAsUncertain} as a diagnostic signal, not silently dropped. A pair correlating
 * {@code DIFFERENT_FACT} genuinely describes something else and is excluded into
 * {@link #excludedAsDifferent} -- never treated as a contradiction either, since {@code
 * DIFFERENT_FACT} means "not even about the same thing," not "disagrees about the same thing".
 */
public record SameFactGroupingResult<T>(
        List<FieldFact<T>> sameFactGroup,
        List<FieldFact<T>> excludedAsDifferent,
        List<FieldFact<T>> excludedAsUncertain) {

    public SameFactGroupingResult {
        Objects.requireNonNull(sameFactGroup, "sameFactGroup");
        Objects.requireNonNull(excludedAsDifferent, "excludedAsDifferent");
        Objects.requireNonNull(excludedAsUncertain, "excludedAsUncertain");
        sameFactGroup = List.copyOf(sameFactGroup);
        excludedAsDifferent = List.copyOf(excludedAsDifferent);
        excludedAsUncertain = List.copyOf(excludedAsUncertain);
    }
}
