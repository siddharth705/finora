package com.finora.imports.evidence;

import java.util.List;
import java.util.Objects;

/**
 * A {@link FieldCandidate} together with design §3's three {@link DimensionResult}s and the
 * {@link EvidenceStatus} they combine to -- the Phase-C "field assessment" grain, deliberately its
 * own type rather than a widened {@link FieldCandidate}, per the Phase-C gate decision: {@code
 * FieldCandidate} stays at Phase A's fact/evidence grain and owns only fact-level candidate
 * validation; this type owns the dimension-combination concern on top of it, so
 * observation → correlation → dimension → assessment stay four distinct grains, never collapsed.
 *
 * <p><b>Why the canonical constructor validates a weaker invariant than {@link FieldCandidate}'s
 * does.</b> {@code FieldCandidate}'s status can always be fully re-derived from just its facts,
 * because it only ever uses the conservative default {@link IndependenceRemediationPolicy}. This
 * type must support a real, caller-supplied {@link DimensionIndependenceRemediationPolicy} (design
 * §3.5's {@code RequireIndependentSectionIdentityPolicy}) -- which policy cleared a pairing is not
 * recoverable from the three {@code DimensionResult}s and {@code contradictions} alone, so the
 * compact constructor cannot fully re-derive {@code status} the way {@code FieldCandidate}'s does.
 * It instead validates the parts that are policy-independent (contradictions/conflicting-dimension
 * short-circuits, and that {@code SUPPORTED} requires at least two satisfied dimensions) -- gross
 * misstatements are still caught; only the specific "were these two independent enough, under
 * whichever policy was used" question is left to {@link #of} having computed it correctly by
 * construction.
 *
 * @param candidate the field-level candidate this assessment is about
 * @param structural design §3.1's verdict
 * @param corroboration design §3.2's verdict
 * @param financialValidation design §3.3's verdict
 * @param contradictions observations proposing a materially different value for this fact --
 *        design §3.4, populated from a {@link EvidenceComparison#DISAGREE} result or a
 *        same-source-multiple-locations disagreement; forces {@link EvidenceStatus#CONFLICTING}
 *        unconditionally, regardless of how many dimensions are otherwise satisfied
 * @param status derived, never asserted directly -- see {@link DimensionAssessor#deriveAssessmentStatus}
 */
public record FieldAssessment(
        FieldCandidate<?> candidate,
        DimensionResult structural,
        DimensionResult corroboration,
        DimensionResult financialValidation,
        List<FieldFact<?>> contradictions,
        EvidenceStatus status) {

    public FieldAssessment {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(structural, "structural");
        Objects.requireNonNull(corroboration, "corroboration");
        Objects.requireNonNull(financialValidation, "financialValidation");
        Objects.requireNonNull(contradictions, "contradictions");
        Objects.requireNonNull(status, "status");
        requireDimension(structural, DimensionResult.Dimension.STRUCTURAL);
        requireDimension(corroboration, DimensionResult.Dimension.CORROBORATION);
        requireDimension(financialValidation, DimensionResult.Dimension.FINANCIAL_VALIDATION);
        contradictions = List.copyOf(contradictions);

        boolean anyDimensionConflicting = structural.status() == EvidenceStatus.CONFLICTING
                || corroboration.status() == EvidenceStatus.CONFLICTING
                || financialValidation.status() == EvidenceStatus.CONFLICTING;
        if ((!contradictions.isEmpty() || anyDimensionConflicting) && status != EvidenceStatus.CONFLICTING) {
            throw new IllegalArgumentException(
                    "status " + status + " cannot stand: contradictions present or a dimension is CONFLICTING");
        }
        long satisfiedCount = List.of(structural, corroboration, financialValidation).stream()
                .filter(d -> d.status() == EvidenceStatus.SUPPORTED).count();
        if (status == EvidenceStatus.SUPPORTED && satisfiedCount < 2) {
            throw new IllegalArgumentException(
                    "status SUPPORTED requires at least two satisfied dimensions, found " + satisfiedCount);
        }
        if (status == EvidenceStatus.CONFLICTING && contradictions.isEmpty() && !anyDimensionConflicting) {
            throw new IllegalArgumentException(
                    "status CONFLICTING requires either contradictions or a CONFLICTING dimension");
        }
    }

    public static FieldAssessment of(FieldCandidate<?> candidate, DimensionResult structural,
            DimensionResult corroboration, DimensionResult financialValidation,
            List<FieldFact<?>> contradictions, DimensionIndependenceRemediationPolicy policy) {
        EvidenceStatus status = DimensionAssessor.deriveAssessmentStatus(
                structural, corroboration, financialValidation, contradictions, policy);
        return new FieldAssessment(candidate, structural, corroboration, financialValidation,
                contradictions, status);
    }

    /** Convenience overload using {@link DimensionIndependenceRemediationPolicy#CONSERVATIVE_DEFAULT}. */
    public static FieldAssessment of(FieldCandidate<?> candidate, DimensionResult structural,
            DimensionResult corroboration, DimensionResult financialValidation,
            List<FieldFact<?>> contradictions) {
        return of(candidate, structural, corroboration, financialValidation, contradictions,
                DimensionIndependenceRemediationPolicy.CONSERVATIVE_DEFAULT);
    }

    private static void requireDimension(DimensionResult result, DimensionResult.Dimension expected) {
        if (result.dimension() != expected) {
            throw new IllegalArgumentException(
                    "expected a " + expected + " DimensionResult, got " + result.dimension());
        }
    }
}
