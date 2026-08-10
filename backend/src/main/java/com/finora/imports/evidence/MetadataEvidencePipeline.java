package com.finora.imports.evidence;

import java.util.List;
import java.util.Objects;

/**
 * The end-to-end path for one metadata field, the metadata analogue of
 * {@link TransactionEvidencePipeline}:
 *
 * <pre>
 * MetadataFieldObservations
 *         ↓ (MetadataFactCorrelator, per observation pair)
 * MetadataSameFactGrouper
 *         ↓
 * same-fact group  +  EvidenceComparison (folded into assessCorroboration)
 *         ↓
 * DimensionAssessor.assessStructural / assessCorroboration / assessFinancialValidation
 *         ↓
 * FieldAssessment
 * </pre>
 *
 * <p>{@code financialContext} is required but its inner validator fields are typically both
 * {@code null}: most metadata fields (e.g. {@link MaterialField#ACCOUNT_HOLDER},
 * {@link MaterialField#IFSC}) have no financial validator mapped to them at all (design §5), and
 * only {@link MaterialField#OPENING_BALANCE}/{@link MaterialField#CLOSING_BALANCE} ever consult
 * {@code statementTotals} -- see {@link DimensionAssessor#assessFinancialValidation}.
 *
 * <p>{@code factPolicy}/{@code dimensionPolicy}: see {@link TransactionEvidencePipeline}'s doc --
 * the same two-grain remediation split applies here identically.
 */
public final class MetadataEvidencePipeline {

    private MetadataEvidencePipeline() {
    }

    public static <T> FieldAssessment assess(MaterialField field, List<MetadataFieldObservation<T>> observations,
            T candidateValue, FinancialValidationContext financialContext,
            IndependenceRemediationPolicy factPolicy, DimensionIndependenceRemediationPolicy dimensionPolicy) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(candidateValue, "candidateValue");
        Objects.requireNonNull(financialContext, "financialContext");
        Objects.requireNonNull(factPolicy, "factPolicy");
        Objects.requireNonNull(dimensionPolicy, "dimensionPolicy");
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("MetadataEvidencePipeline requires at least one observation");
        }
        observations.forEach(o -> {
            if (o.position().fact().field() != field) {
                throw new IllegalArgumentException(
                        "observation for " + o.position().fact().field() + " passed to a pipeline assessing " + field);
            }
        });

        SameFactGroupingResult<T> grouping = MetadataSameFactGrouper.group(observations);

        // Audit finding (post-implementation review): same fix as TransactionEvidencePipeline --
        // Structural must never assess evidence belonging to a DIFFERENT_FACT observation. See
        // that class's identical comment for why reference-identity filtering is exact here.
        List<MetadataFieldObservation<T>> sameFactObservations = observations.stream()
                .filter(o -> grouping.sameFactGroup().stream().anyMatch(f -> f == o.position().fact()))
                .toList();

        DimensionResult structural = DimensionAssessor.assessStructural(
                sameFactObservations.stream()
                        .map(o -> new SourcedFact<>(o.position().fact(), o.evidenceSource())).toList());
        DimensionResult corroboration = DimensionAssessor.assessCorroboration(grouping.sameFactGroup(), factPolicy);
        DimensionResult financialValidation = DimensionAssessor.assessFinancialValidation(
                field, null, financialContext);

        List<FieldFact<?>> contradictions =
                EvidenceComparison.compare(grouping.sameFactGroup()) == EvidenceComparison.DISAGREE
                        ? List.copyOf(grouping.sameFactGroup())
                        : List.of();

        FieldCandidate<T> candidate = FieldCandidate.of(field, candidateValue, grouping.sameFactGroup());

        return FieldAssessment.of(candidate, structural, corroboration, financialValidation, contradictions,
                dimensionPolicy);
    }

    /** Convenience overload using both policies' conservative defaults. */
    public static <T> FieldAssessment assess(MaterialField field, List<MetadataFieldObservation<T>> observations,
            T candidateValue, FinancialValidationContext financialContext) {
        return assess(field, observations, candidateValue, financialContext,
                IndependenceRemediationPolicy.CONSERVATIVE_DEFAULT,
                DimensionIndependenceRemediationPolicy.CONSERVATIVE_DEFAULT);
    }
}
