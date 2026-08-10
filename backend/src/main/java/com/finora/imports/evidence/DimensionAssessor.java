package com.finora.imports.evidence;

import com.finora.dto.ImportDto;
import com.finora.imports.BalanceChainValidator;
import com.finora.imports.product.EvidenceSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Produces each of design §3's three independently-answered questions as a {@link DimensionResult},
 * and combines them into a {@link FieldAssessment}'s {@link EvidenceStatus} -- ADR-006 §3's
 * corrected wording: no single dimension is sufficient alone; {@code SUPPORTED} requires at least
 * two of the three, and those two must be shown independent via
 * {@link EvidenceAssessor#shareAnUpstreamFailureMode}, not merely both satisfied.
 *
 * <p>Deliberately not added to {@link EvidenceAssessor}: that class's own contract (Phase A,
 * already reviewed) is "turn a field's raw {@code FieldFact}s into an {@code EvidenceStatus}" --
 * a different, narrower grain than combining three dimension-level verdicts. Keeping this as its
 * own class avoids retroactively widening an already-approved type's scope.
 */
public final class DimensionAssessor {

    private DimensionAssessor() {
    }

    /** Design §3.1. {@code facts} must be non-empty -- there is nothing to assess otherwise (a
     *  field with zero observations never reaches dimension assessment at all; its
     *  {@link FieldCandidate} is already {@code INSUFFICIENT} by construction). */
    public static <T> DimensionResult assessStructural(List<SourcedFact<T>> facts) {
        Objects.requireNonNull(facts, "facts");
        if (facts.isEmpty()) {
            throw new IllegalArgumentException("assessStructural requires at least one SourcedFact");
        }
        // The single fact with the strongest source, not a union of every fact considered --
        // Structural is fundamentally about ONE observation's placement. Ties keep the first
        // encountered. Bug found in the Phase-C end-to-end review: unioning every fact's
        // provenance made this dimension collide with Corroboration (which necessarily spans the
        // SAME small pool of facts) on the acquisition engine they happen to share, even in the
        // textbook two-independent-sources-agree case.
        SourcedFact<T> strongestFact = facts.stream()
                .max(Comparator.comparing(SourcedFact::source))
                .orElseThrow();
        EvidenceSource strongest = strongestFact.source();
        // Calibration choice, not fixed by ADR-006 -- flagged for corpus evaluation (design §3.1):
        // stronger than SECTION_TEXT, i.e. ROW_DATA or COLUMN_HEADERS, not merely "present at all".
        boolean satisfied = strongest.isStrongerThan(EvidenceSource.SECTION_TEXT);
        // Positional (SectionAttribution/ColumnLayoutInterpretation) provenance preferred; a bare
        // Acquisition node is an acceptable fallback here specifically because this is a single
        // fact, not a group -- no collision risk the way Corroboration's group union would have.
        List<ProvenanceNode> provenance = preferPositional(strongestFact.fact().provenance());
        return new DimensionResult(DimensionResult.Dimension.STRUCTURAL,
                satisfied ? EvidenceStatus.SUPPORTED : EvidenceStatus.INSUFFICIENT,
                "strongest source: " + strongest, provenance);
    }

    /**
     * Design §3.2: <i>"Does an <b>independent</b> acquisition source establish the {@code
     * SAME_FACT} with the same value?"</i> -- the word "independent" is load-bearing and was
     * missing from an earlier version of this method. {@link EvidenceComparison#compare} alone
     * only checks value equality; two facts sharing an upstream failure mode (e.g. both
     * reconstructed against the same wrong {@code ColumnLayoutInterpretation} -- the exact ICICI
     * shape this ADR chain traces back to) could otherwise agree on the same wrong value and be
     * scored as real corroboration. So status is derived via
     * {@link EvidenceAssessor#deriveStatus(MaterialField, List)} -- the same independence-aware
     * combining mechanism Phase A already built and reviewed for exactly this failure mode --
     * reused here rather than re-implemented, per the Phase-C gate's own instruction not to build
     * a second parallel independence mechanism. {@link EvidenceComparison#compare} is still
     * computed and kept in the explanation text: it is a useful raw-agreement signal for
     * user-facing explanation (design §7) even when it doesn't count as independent corroboration.
     *
     * <p>{@code sameFactGroup} must be non-empty (at least {@code UNCONTESTED}) -- {@link
     * EvidenceComparison#ABSENT} is a valid comparison outcome but not a valid input to assess a
     * dimension for, since a field with zero observations never reaches this at all.
     *
     * <p>{@code factPolicy} is threaded straight into {@code deriveStatus} -- e.g.
     * {@link RequireIndependentSectionIdentityPolicy#ofFacts} -- so that a section independently
     * confirmed (design §3.5) clears the SAME shared-section-attribution risk consistently whether
     * it is two {@link FieldFact}s within this group sharing it (checked here), or this whole
     * dimension sharing it with another (checked one grain up, in
     * {@link #deriveAssessmentStatus}) -- one confirmed-sections input, applied identically at
     * both grains, never two independent judgments about the same section.
     */
    public static <T> DimensionResult assessCorroboration(
            List<FieldFact<T>> sameFactGroup, IndependenceRemediationPolicy factPolicy) {
        Objects.requireNonNull(sameFactGroup, "sameFactGroup");
        Objects.requireNonNull(factPolicy, "factPolicy");
        if (sameFactGroup.isEmpty()) {
            throw new IllegalArgumentException("assessCorroboration requires at least one FieldFact "
                    + "(EvidenceComparison.ABSENT is not a valid dimension-assessment input)");
        }
        EvidenceComparison comparison = EvidenceComparison.compare(sameFactGroup);
        MaterialField field = sameFactGroup.get(0).field();
        EvidenceStatus status = EvidenceAssessor.deriveStatus(field, sameFactGroup, factPolicy);
        // Positional provenance ONLY -- never a bare Acquisition node, and never falls back to
        // one. Corroboration necessarily spans multiple facts from (when SUPPORTED) genuinely
        // different Acquisition sources; including any of those specific engines in this
        // dimension's own provenance would make it collide with Structural (anchored to a single
        // fact drawn from the same small pool) even though the internal independence check this
        // status already required means no single engine is a real remaining risk here. May be
        // empty when the group carries no positional (SectionAttribution/ColumnLayoutInterpretation)
        // context at all -- that correctly means no shared structural risk remains to report.
        List<ProvenanceNode> provenance = onlyPositional(unionOfFactProvenance(sameFactGroup));
        return new DimensionResult(DimensionResult.Dimension.CORROBORATION, status,
                "evidence comparison: " + comparison, provenance);
    }

    /** Convenience overload using {@link IndependenceRemediationPolicy#CONSERVATIVE_DEFAULT}. */
    public static <T> DimensionResult assessCorroboration(List<FieldFact<T>> sameFactGroup) {
        return assessCorroboration(sameFactGroup, IndependenceRemediationPolicy.CONSERVATIVE_DEFAULT);
    }

    /**
     * Design §3.3, with the round-3 tightening: for {@code TRANSACTION_AMOUNT}/
     * {@code TRANSACTION_DIRECTION}, only {@link BalanceChainValidator}'s row-level discrepancy at
     * this exact {@code rowIndex} may satisfy or contradict this dimension -- aggregate validators
     * are never consulted for these two fields, regardless of what they read, because an aggregate
     * check can pass on a statement with a swapped-direction pair whose net effect cancels out.
     *
     * <p>For {@code OPENING_BALANCE}/{@code CLOSING_BALANCE}, {@code StatementTotalsValidator}'s
     * finding is consulted instead -- there is no row to check a whole-of-statement balance
     * against. Any other {@link MaterialField} has no financial validator mapped to it at all
     * (design §5's table); reported as {@code INSUFFICIENT}, not a crash or a guess.
     */
    public static DimensionResult assessFinancialValidation(
            MaterialField field, Integer rowIndex, FinancialValidationContext context) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(context, "context");
        List<ProvenanceNode> provenance = List.of(
                new ProvenanceNode.SectionAttribution(context.sectionIndex(), context.fromSource()));

        if (field == MaterialField.TRANSACTION_AMOUNT || field == MaterialField.TRANSACTION_DIRECTION) {
            if (rowIndex == null) {
                throw new IllegalArgumentException(field + " requires a rowIndex for financial validation");
            }
            BalanceChainValidator.Result balanceChain = context.balanceChain();
            if (balanceChain == null || balanceChain.status() == BalanceChainValidator.Outcome.NOT_APPLICABLE) {
                return new DimensionResult(DimensionResult.Dimension.FINANCIAL_VALIDATION,
                        EvidenceStatus.INSUFFICIENT,
                        "no running-balance column; per-row financial validation not possible", provenance);
            }
            boolean discrepantHere = balanceChain.discrepancies().stream().anyMatch(d -> d.rowIndex() == rowIndex);
            if (discrepantHere) {
                return new DimensionResult(DimensionResult.Dimension.FINANCIAL_VALIDATION,
                        EvidenceStatus.CONFLICTING,
                        "balance chain discrepancy at row " + rowIndex, provenance);
            }
            return new DimensionResult(DimensionResult.Dimension.FINANCIAL_VALIDATION,
                    EvidenceStatus.SUPPORTED, "balance chain holds at row " + rowIndex, provenance);
        }

        if (field == MaterialField.OPENING_BALANCE || field == MaterialField.CLOSING_BALANCE) {
            ImportDto.VerificationFinding finding = context.statementTotals();
            if (finding == null) {
                return new DimensionResult(DimensionResult.Dimension.FINANCIAL_VALIDATION,
                        EvidenceStatus.INSUFFICIENT, "statement totals not checked", provenance);
            }
            String outcome = finding.outcome();
            if ("VERIFIED".equals(outcome)) {
                return new DimensionResult(DimensionResult.Dimension.FINANCIAL_VALIDATION,
                        EvidenceStatus.SUPPORTED, "opening + transactions reconciles to closing", provenance);
            }
            if ("FAILED".equals(outcome)) {
                Object suspectedCause = finding.details().get("suspectedCause");
                if (field == MaterialField.OPENING_BALANCE && "OPENING_BALANCE".equals(suspectedCause)) {
                    return new DimensionResult(DimensionResult.Dimension.FINANCIAL_VALIDATION,
                            EvidenceStatus.CONFLICTING,
                            "transactions reach the closing balance independently of the opening balance",
                            provenance);
                }
                if (field == MaterialField.CLOSING_BALANCE && "OPENING_BALANCE".equals(suspectedCause)) {
                    // The transactions' own running balance independently reaches the stated
                    // closing balance (that agreement is exactly what attributed the failure to
                    // the opening balance instead) -- this is corroborating evidence FOR the
                    // closing balance, not against it.
                    return new DimensionResult(DimensionResult.Dimension.FINANCIAL_VALIDATION,
                            EvidenceStatus.SUPPORTED,
                            "the last row's own running balance independently matches the stated closing balance",
                            provenance);
                }
                // TRANSACTIONS-caused failure, or a failure with no determinable cause: does not
                // implicate this specific field on its own (design §5's named gap).
                return new DimensionResult(DimensionResult.Dimension.FINANCIAL_VALIDATION,
                        EvidenceStatus.INSUFFICIENT,
                        "statement totals mismatch not attributable to this field specifically", provenance);
            }
            return new DimensionResult(DimensionResult.Dimension.FINANCIAL_VALIDATION,
                    EvidenceStatus.INSUFFICIENT, "statement totals check outcome: " + outcome, provenance);
        }

        return new DimensionResult(DimensionResult.Dimension.FINANCIAL_VALIDATION,
                EvidenceStatus.INSUFFICIENT, "no financial validator maps to " + field, provenance);
    }

    /**
     * Design §3's corrected combining rule: {@code contradictions} non-empty always wins
     * (regardless of how many dimensions are satisfied); otherwise {@code SUPPORTED} requires at
     * least two of the three dimensions independently satisfied -- "independently" checked via
     * {@link EvidenceAssessor#shareAnUpstreamFailureMode} over their {@link DimensionResult#provenance},
     * the same mechanism {@link FieldFact} provenance already uses at the fact grain, not a second
     * parallel one. A dimension whose own status is {@code CONFLICTING} also forces the overall
     * result to {@code CONFLICTING} -- defensively, not only via the separately-passed
     * {@code contradictions} list, so a caller that finds a dimension-level contradiction but
     * forgets to also mirror it into {@code contradictions} cannot silently understate the result.
     */
    public static EvidenceStatus deriveAssessmentStatus(DimensionResult structural,
            DimensionResult corroboration, DimensionResult financialValidation,
            List<FieldFact<?>> contradictions, DimensionIndependenceRemediationPolicy policy) {
        Objects.requireNonNull(structural, "structural");
        Objects.requireNonNull(corroboration, "corroboration");
        Objects.requireNonNull(financialValidation, "financialValidation");
        Objects.requireNonNull(contradictions, "contradictions");
        Objects.requireNonNull(policy, "policy");

        if (!contradictions.isEmpty()) {
            return EvidenceStatus.CONFLICTING;
        }
        List<DimensionResult> all = List.of(structural, corroboration, financialValidation);
        if (all.stream().anyMatch(d -> d.status() == EvidenceStatus.CONFLICTING)) {
            return EvidenceStatus.CONFLICTING;
        }

        List<DimensionResult> satisfied = all.stream()
                .filter(d -> d.status() == EvidenceStatus.SUPPORTED)
                .toList();
        if (satisfied.size() < 2) {
            return EvidenceStatus.INSUFFICIENT;
        }
        for (int i = 0; i < satisfied.size(); i++) {
            for (int j = i + 1; j < satisfied.size(); j++) {
                DimensionResult x = satisfied.get(i);
                DimensionResult y = satisfied.get(j);
                boolean independent = !EvidenceAssessor.shareAnUpstreamFailureMode(x.provenance(), y.provenance());
                if (independent || policy.remediate(x, y)) {
                    return EvidenceStatus.SUPPORTED;
                }
            }
        }
        return EvidenceStatus.INSUFFICIENT;
    }

    /** Convenience overload using {@link DimensionIndependenceRemediationPolicy#CONSERVATIVE_DEFAULT}. */
    public static EvidenceStatus deriveAssessmentStatus(DimensionResult structural,
            DimensionResult corroboration, DimensionResult financialValidation,
            List<FieldFact<?>> contradictions) {
        return deriveAssessmentStatus(structural, corroboration, financialValidation, contradictions,
                DimensionIndependenceRemediationPolicy.CONSERVATIVE_DEFAULT);
    }

    private static <T> List<ProvenanceNode> unionOfFactProvenance(List<FieldFact<T>> facts) {
        List<ProvenanceNode> union = new ArrayList<>();
        for (FieldFact<T> fact : facts) {
            for (ProvenanceNode node : fact.provenance()) {
                if (!union.contains(node)) {
                    union.add(node);
                }
            }
        }
        return List.copyOf(union);
    }

    /** Positional (non-{@code Acquisition}) nodes only, with NO fallback -- see
     *  {@link #assessCorroboration}'s doc for why this dimension must never report a bare
     *  {@code Acquisition} node as a remaining risk. May return an empty list. */
    private static List<ProvenanceNode> onlyPositional(List<ProvenanceNode> nodes) {
        return nodes.stream().filter(node -> !(node instanceof ProvenanceNode.Acquisition)).toList();
    }

    /** Positional nodes preferred; falls back to the full (possibly {@code Acquisition}-only)
     *  input only when there is no positional node at all -- safe here specifically because this
     *  is always called with a SINGLE fact's own provenance (see {@link #assessStructural}), never
     *  a multi-fact group, so there is no cross-dimension collision risk in falling back. */
    private static List<ProvenanceNode> preferPositional(List<ProvenanceNode> nodes) {
        List<ProvenanceNode> positional = onlyPositional(nodes);
        return positional.isEmpty() ? nodes : positional;
    }
}
