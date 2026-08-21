package com.finora.imports;

/**
 * A rule-based, per-section reliability status for a staged import -- deliberately not a
 * confidence percentage. See {@link ImportReliabilityStatusDeriver} for exactly which evidence
 * produces which value; this enum only names the three outcomes.
 *
 * <p>Computed once, on {@code ImportVerifier.verify()}'s own output, from facts that already
 * exist in the pipeline: the assembled {@code VerificationFinding} outcomes, whether header
 * reconstruction was uncertain, and whether OCR was used. No weight, no score, no calibration
 * data required -- each value is a deterministic OR over named facts, not a synthesized number.
 * See {@code ImportVerifier}'s own doc comment for why that distinction is the whole point.
 *
 * <p>Per-section, like {@code VerificationReport} itself -- a composite statement's sections
 * verify independently, and a merged status would hide exactly the case (one section clean,
 * another not) this was built to surface.
 *
 * <p><b>What this does NOT know.</b> It summarizes verification evidence Finora actually
 * observed during import -- nothing more. It cannot know whether the user selected the right
 * account, whether the statement itself is a complete record of the period, or whether a
 * transaction that parsed cleanly is financially meaningful. {@code CLEAN} means nothing measured
 * found a reason to ask for a second look, not that the import is correct in every sense that
 * word could mean.
 */
public enum ImportReliabilityStatus {

    /** No finding is WARNING or FAILED, no header-reconstruction uncertainty, and native text
     *  extraction was used. Nothing here says the import is perfect -- only that nothing measured
     *  found a reason to ask for a second look. */
    CLEAN,

    /** OCR was used, or some finding is WARNING, but nothing rises to {@link #NEEDS_ATTENTION}.
     *  Worth a look, not proof of loss -- matches {@code RowAccountingValidator}'s own WARNING
     *  discipline: review-worthy, never asserted as wrong. */
    REVIEW_RECOMMENDED,

    /** Evidence exists that specific real transactions were likely affected: a collapsed header
     *  reconstruction ({@code TRANSACTION_HEADER_RECONSTRUCTION_UNCERTAIN}), a pre-header activity
     *  candidate ({@code PRE_HEADER_ACTIVITY_CANDIDATE}), or any finding outcome of FAILED. */
    NEEDS_ATTENTION
}
