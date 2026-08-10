package com.finora.imports.evidence;

/**
 * The outcome of assessing one {@link FieldCandidate}'s evidence -- design §1.1, ADR-006 §3. Deliberately
 * three-valued rather than a confidence score: a numeric score invites exactly the aggregate-scoring
 * failure mode the design's round-3 review closed off (a single high-source-strength observation and
 * three independent low-strength ones must not average out to "probably fine").
 */
public enum EvidenceStatus {
    /** At least two independently-provenanced {@link FieldFact}s agree on the same value, per
     *  {@link EvidenceAssessor#deriveStatus}. Structural source strength alone never earns this. */
    SUPPORTED,

    /** Two or more facts disagree on the value for the same field, or independently-provenanced
     *  facts disagree on which value is correct after correlation. */
    CONFLICTING,

    /** Fewer than two independently-provenanced facts exist for this field -- either only one
     *  observation exists at all, or every observation shares an upstream failure mode with every
     *  other (see {@link EvidenceAssessor#shareAnUpstreamFailureMode}), so no real corroboration
     *  was possible even though multiple facts were recorded. */
    INSUFFICIENT
}
