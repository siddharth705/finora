package com.finora.imports.evidence;

import java.util.List;
import java.util.Objects;

/**
 * The end-to-end path for one transaction-row field, demonstrating the full chain the Phase-C
 * gate required proof of:
 *
 * <pre>
 * TransactionFieldObservations
 *         ↓ (TransactionFactCorrelator, per observation pair)
 * TransactionSameFactGrouper
 *         ↓
 * same-fact group  +  EvidenceComparison (folded into assessCorroboration)
 *         ↓
 * DimensionAssessor.assessStructural / assessCorroboration / assessFinancialValidation
 *         ↓
 * FieldAssessment
 * </pre>
 *
 * <p>Scoped to {@link MaterialField#TRANSACTION_AMOUNT}, {@link MaterialField#TRANSACTION_DATE},
 * {@link MaterialField#TRANSACTION_DIRECTION}, {@link MaterialField#TRANSACTION_DESCRIPTION} --
 * the row-level fields {@link TransactionFieldObservation} exists for. Only
 * {@code TRANSACTION_AMOUNT}/{@code TRANSACTION_DIRECTION} ever get a non-{@code INSUFFICIENT}
 * {@code FinancialValidation} dimension, per {@link DimensionAssessor#assessFinancialValidation}'s
 * design §5 scope mapping -- {@code rowIndex} is still threaded through for the other two so a
 * future validator mapped to them does not require touching this pipeline again.
 *
 * <p>{@code factPolicy} and {@code dimensionPolicy} are two grains of the SAME remediation
 * decision (see {@link RequireIndependentSectionIdentityPolicy}'s doc) -- {@code factPolicy}
 * governs whether facts within the same-fact group corroborate each other despite sharing a
 * section attribution; {@code dimensionPolicy} governs whether {@code Structural} and
 * {@code Corroboration} count as independent of EACH OTHER despite sharing one. Callers building
 * both from the same confirmed-section set (the normal case) get consistent answers at both
 * grains; {@link FieldCandidate} itself always stays on the conservative default, per the
 * Phase-C gate decision that it never grows a policy-selection concern.
 */
public final class TransactionEvidencePipeline {

    private TransactionEvidencePipeline() {
    }

    public static <T> FieldAssessment assess(MaterialField field, List<TransactionFieldObservation<T>> observations,
            T candidateValue, int rowIndex, FinancialValidationContext financialContext,
            IndependenceRemediationPolicy factPolicy, DimensionIndependenceRemediationPolicy dimensionPolicy) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(candidateValue, "candidateValue");
        Objects.requireNonNull(financialContext, "financialContext");
        Objects.requireNonNull(factPolicy, "factPolicy");
        Objects.requireNonNull(dimensionPolicy, "dimensionPolicy");
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("TransactionEvidencePipeline requires at least one observation");
        }
        observations.forEach(o -> {
            if (o.fact().field() != field) {
                throw new IllegalArgumentException(
                        "observation for " + o.fact().field() + " passed to a pipeline assessing " + field);
            }
        });

        SameFactGroupingResult<T> grouping = TransactionSameFactGrouper.group(observations);

        // Audit finding (post-implementation review): Structural must never assess evidence
        // belonging to a DIFFERENT_FACT observation -- a differently-correlated observation with a
        // strong EvidenceSource could otherwise be picked as "strongest" and manufacture a
        // Structural SUPPORTED verdict for evidence that isn't even about this candidate.
        // Filtered by reference identity against grouping.sameFactGroup(), not List.contains/equals:
        // the grouper reuses the SAME FieldFact object references from the accepted observations
        // (see TransactionSameFactGrouper), so identity comparison is exact and unambiguous here,
        // unlike structural/value equality which could in principle collide.
        List<TransactionFieldObservation<T>> sameFactObservations = observations.stream()
                .filter(o -> grouping.sameFactGroup().stream().anyMatch(f -> f == o.fact()))
                .toList();

        DimensionResult structural = DimensionAssessor.assessStructural(
                sameFactObservations.stream().map(o -> new SourcedFact<>(o.fact(), o.evidenceSource())).toList());
        DimensionResult corroboration = DimensionAssessor.assessCorroboration(grouping.sameFactGroup(), factPolicy);
        DimensionResult financialValidation = DimensionAssessor.assessFinancialValidation(
                field, rowIndex, financialContext);

        List<FieldFact<?>> contradictions =
                EvidenceComparison.compare(grouping.sameFactGroup()) == EvidenceComparison.DISAGREE
                        ? List.copyOf(grouping.sameFactGroup())
                        : List.of();

        FieldCandidate<T> candidate = FieldCandidate.of(field, candidateValue, grouping.sameFactGroup());

        return FieldAssessment.of(candidate, structural, corroboration, financialValidation, contradictions,
                dimensionPolicy);
    }

    /** Convenience overload using both policies' conservative defaults. */
    public static <T> FieldAssessment assess(MaterialField field, List<TransactionFieldObservation<T>> observations,
            T candidateValue, int rowIndex, FinancialValidationContext financialContext) {
        return assess(field, observations, candidateValue, rowIndex, financialContext,
                IndependenceRemediationPolicy.CONSERVATIVE_DEFAULT,
                DimensionIndependenceRemediationPolicy.CONSERVATIVE_DEFAULT);
    }
}
