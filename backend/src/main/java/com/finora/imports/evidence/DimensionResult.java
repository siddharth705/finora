package com.finora.imports.evidence;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of assessing a {@link FieldCandidate} along one of ADR-006 §3's independent
 * assessment dimensions. Deliberately its own type, not folded into {@link FieldCandidate}: per
 * the Phase-C gate decision, {@code FieldCandidate} stays at Phase A's fact/evidence grain and is
 * never widened to also represent §3's dimension-combining concern -- a later, separate
 * "field assessment" type (Phase C) is what will hold a {@code FieldCandidate} alongside its three
 * {@code DimensionResult}s and their combined verdict, keeping observation → correlation →
 * dimension → assessment as four distinct grains rather than merging any of them.
 *
 * <p>Combining two {@code DimensionResult}s toward {@code SUPPORTED} is only ever valid when
 * {@link EvidenceAssessor#shareAnUpstreamFailureMode} is false for their two {@link #provenance}
 * chains (design §3.5) -- this is the same independence mechanism {@link FieldFact} provenance
 * already uses at the fact grain, generalized to the dimension grain, not a second parallel
 * mechanism. It never averages or scores dimensions together, per the design's explicit rejection
 * of aggregate confidence scoring (design §3.5 / round-3 loophole analysis, aggregate-score danger).
 *
 * @param dimension which independent line of assessment produced this result
 * @param status this dimension's own verdict, in isolation
 * @param explanation a short, user-facing-safe reason for the verdict -- required so
 *        {@code SectionDecisionInput} and statement evidence explanation (design §7) never have to
 *        reconstruct "why" after the fact from raw facts
 * @param provenance the upstream steps this dimension's OWN verdict remains at risk from --
 *        <b>unlike {@link FieldFact#provenance}, this may legitimately be empty</b> (found during
 *        the Phase-C end-to-end adversarial review): {@code Corroboration}'s status already
 *        required, internally, at least one genuinely independent agreeing pair
 *        ({@link EvidenceAssessor#deriveStatus}) before it could be {@code SUPPORTED} -- once that
 *        internal check has run, no single upstream node (in particular, no bare
 *        {@link ProvenanceNode.Acquisition} node -- which specific engine happened to also feed
 *        {@code Structural}) represents a REMAINING shared risk, so it is correctly reported as
 *        none. What is never dropped is genuinely shareable STRUCTURAL/positional risk -- a
 *        {@code SectionAttribution} or {@code ColumnLayoutInterpretation} node -- since that is
 *        exactly the ICICI-shape risk this mechanism exists to keep catching.
 */
public record DimensionResult(Dimension dimension, EvidenceStatus status, String explanation,
        List<ProvenanceNode> provenance) {

    public DimensionResult {
        if (explanation == null || explanation.isBlank()) {
            throw new IllegalArgumentException("a DimensionResult must explain its verdict");
        }
        Objects.requireNonNull(provenance, "provenance");
        provenance = List.copyOf(provenance);
    }

    /** The three independent lines of assessment design §3 defines for a field's evidence.
     *  Independent in the sense that a shared upstream failure mode in one dimension must not be
     *  allowed to inflate another -- see {@link EvidenceAssessor#shareAnUpstreamFailureMode}. */
    public enum Dimension {
        /** Where was this value found, and how strongly does that location vouch for it? Answered
         *  by {@link DimensionAssessor#assessStructural} purely from {@code EvidenceSource}
         *  location-strength (was it seen in a column header, a labelled field, a row, or only in
         *  free-standing document text) -- <b>not</b> a check of the value's own shape, format, or
         *  range. A stronger location says where a value was found, never whether it is correct. */
        STRUCTURAL,
        /** Do independently-provenanced {@link FieldFact}s for this field agree? */
        CORROBORATION,
        /** Does the value hold up against the statement's own arithmetic (balance chain, stated
         *  totals) rather than against another extracted copy of itself? */
        FINANCIAL_VALIDATION
    }
}
