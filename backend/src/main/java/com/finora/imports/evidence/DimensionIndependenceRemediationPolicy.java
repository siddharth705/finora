package com.finora.imports.evidence;

/**
 * The {@link DimensionResult} grain analogue of {@link IndependenceRemediationPolicy} -- design
 * §3.5's remediation policy, generalized from combining {@link FieldFact}s to combining
 * {@link DimensionResult}s, per the Phase-C gate decision that the independence mechanism itself
 * (this policy hook, and {@link EvidenceAssessor#shareAnUpstreamFailureMode}) is the one durable
 * mechanism reused at both grains, never rebuilt.
 *
 * <p>Deliberately takes two {@link DimensionResult}s, not a {@link FieldAssessment}: a
 * {@code FieldAssessment} is assembled from an {@link EvidenceStatus} that itself depends on this
 * policy's outcome, so taking the assessment here would make {@code FieldAssessment}'s canonical
 * constructor circular -- the exact same reasoning that kept {@link IndependenceRemediationPolicy}
 * from taking a {@link FieldCandidate}.
 *
 * <p>Deliberately {@code boolean}, for the same reason as its fact-grain counterpart: this policy
 * can only ever promote an otherwise-disqualified pair of satisfied dimensions to count as
 * independent, or leave it uncounted. It has no way to express {@code SUPPORTED} or
 * {@code CONFLICTING} directly, so it can never itself manufacture support from a shared failure
 * mode -- the one outcome the independence rule exists to prevent.
 */
@FunctionalInterface
public interface DimensionIndependenceRemediationPolicy {

    /**
     * @return {@code true} if some check independent of the shared upstream node confirms the
     *         pairing anyway, so it may count toward {@code SUPPORTED} as if it were independent;
     *         {@code false} to leave it uncounted (the safe default). Never consulted for a pair
     *         where either dimension is {@code CONFLICTING}, or when {@code contradictions} is
     *         non-empty -- see {@link DimensionAssessor#deriveAssessmentStatus}.
     */
    boolean remediate(DimensionResult a, DimensionResult b);

    /** The conservative default: a shared failure mode between two dimensions is never cleared
     *  absent a more specific reason to trust the pairing anyway. */
    DimensionIndependenceRemediationPolicy CONSERVATIVE_DEFAULT = (a, b) -> false;
}
