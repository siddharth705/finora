package com.finora.imports.evidence;

/**
 * Called only when {@link EvidenceAssessor#shareAnUpstreamFailureMode} has already disqualified an
 * otherwise-agreeing pair of {@link FieldFact}s from counting as independent corroboration --
 * design §3.5 (ADR-006 §3's independence question, detailed further in the design notes). Returns
 * whether some additional, independently-obtained check clears them anyway;
 * it is never asked about a pair that disagrees, since a disagreement is a real contradiction
 * regardless of what produced it (design §3.4), not something a policy can rehabilitate into
 * support.
 *
 * <p>Deliberately takes {@link MaterialField} rather than a {@link FieldCandidate}: a candidate is
 * assembled from an {@link EvidenceStatus} that itself depends on this policy's outcome, so taking
 * the candidate here would make {@code FieldCandidate.of(...)} circular. The field identity plus
 * the two facts in question is everything a remediation decision needs.
 *
 * <p>Deliberately {@code boolean}, not a status: this policy can only ever promote an otherwise-
 * disqualified agreeing pair to count as support, or leave it uncounted. It has no way to express
 * {@code SUPPORTED} or {@code CONFLICTING} directly, so a policy can never accidentally manufacture
 * support from a shared failure mode -- the one outcome the independence rule exists to prevent.
 */
@FunctionalInterface
public interface IndependenceRemediationPolicy {

    /**
     * @return {@code true} if some check independent of both facts' shared upstream node confirms
     *         the pairing anyway, so it may count toward {@code SUPPORTED} as if it were
     *         independent; {@code false} to leave it uncounted (the safe default).
     */
    boolean remediate(MaterialField field, FieldFact<?> a, FieldFact<?> b);

    /** The conservative default: a shared failure mode is never cleared absent a more specific
     *  reason to trust the pairing anyway, so it simply counts as if the corroboration never
     *  happened. */
    IndependenceRemediationPolicy CONSERVATIVE_DEFAULT = (field, a, b) -> false;
}
