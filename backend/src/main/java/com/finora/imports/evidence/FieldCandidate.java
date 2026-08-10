package com.finora.imports.evidence;

import java.util.List;
import java.util.Objects;

/**
 * The value ADR-006's evidence model has settled on for one {@link MaterialField}, together with
 * the {@link EvidenceStatus} its {@link #facts} jointly support -- design §1's central type.
 *
 * <p>{@link #value} is deliberately not required to equal any single fact's value: when facts
 * disagree ({@code CONFLICTING}), which one -- if any -- becomes {@link #value} is a caller
 * decision (e.g. "prefer the higher-source-strength observation, but keep the status
 * CONFLICTING"), not something this type dictates.
 */
public record FieldCandidate<T>(MaterialField field, T value, EvidenceStatus status, List<FieldFact<T>> facts) {

    /**
     * Validates that {@code status} is exactly what
     * {@link EvidenceAssessor#deriveStatus(MaterialField, List)} derives from {@code facts} --
     * this is safe to call here, not circular, because {@code deriveStatus} only ever needs a
     * {@link MaterialField} and a list of {@link FieldFact}s, never a constructed
     * {@code FieldCandidate} (see {@link IndependenceRemediationPolicy}'s doc for why its own
     * signature was kept narrow enough to make this possible).
     */
    public FieldCandidate {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(facts, "facts");
        facts = List.copyOf(facts);
        facts.forEach(f -> {
            if (f.field() != field) {
                throw new IllegalArgumentException(
                        "fact for " + f.field() + " does not belong on a FieldCandidate for " + field);
            }
        });
        EvidenceStatus derived = EvidenceAssessor.deriveStatus(field, facts);
        if (derived != status) {
            throw new IllegalArgumentException(
                    "status " + status + " disagrees with EvidenceAssessor.deriveStatus's " + derived
                            + " for " + field);
        }
    }

    /**
     * Builds a {@code FieldCandidate}, deriving its {@link EvidenceStatus} from {@code facts} via
     * {@link EvidenceAssessor#deriveStatus(MaterialField, List)}. This is the ordinary
     * construction path; the canonical constructor remains public so tests can exercise its
     * consistency check directly with a deliberately wrong {@code status}.
     *
     * @param value the value to record on the candidate -- typically the highest-source-strength
     *              fact's value, or the agreed value when facts corroborate; callers decide.
     */
    public static <T> FieldCandidate<T> of(MaterialField field, T value, List<FieldFact<T>> facts) {
        EvidenceStatus status = EvidenceAssessor.deriveStatus(field, facts);
        return new FieldCandidate<>(field, value, status, facts);
    }
}
