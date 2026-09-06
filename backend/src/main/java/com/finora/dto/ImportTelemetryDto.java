package com.finora.dto;

import java.util.List;
import java.util.Map;

/**
 * The aggregate readout over the trust telemetry V141 records.
 *
 * <p>Exists to answer one question that is otherwise unanswerable: <i>if a trust rule gated on some
 * threshold, how often would it fire, and on what?</i> Until this, the columns were write-only --
 * and a signal nothing reads is how {@code ImportVerificationRecorder.recordForJob} managed to sit
 * dead for months without anyone noticing.
 *
 * <p><b>The denominator is completed imports, not all imports.</b> Telemetry is recorded on the
 * worker's success path only, so a failed or held job has no telemetry by construction rather than
 * by age. Dividing by every job would quietly deflate every rate here with rows that were never
 * eligible to have a value -- the same mistake in a different coat as treating NULL as CLEAN, which
 * is precisely what V141's nullable-no-default columns exist to prevent.
 */
public final class ImportTelemetryDto {

    private ImportTelemetryDto() {}

    /**
     * @param completedJobs           every import that reached COMPLETED -- the only population
     *                                eligible to carry telemetry
     * @param withTelemetry           completed imports carrying a reliability status: the honest
     *                                denominator for every rate below
     * @param predatesTelemetry       completed imports from before V141. Reported, never folded
     *                                into CLEAN
     * @param notCompleted            failed, held or in-flight jobs, excluded by construction and
     *                                named so the exclusion is visible rather than implied
     * @param byReliabilityStatus     CLEAN / REVIEW_RECOMMENDED / NEEDS_ATTENTION counts
     * @param byTextSource            NATIVE / OCR / NATIVE_PLUS_OCR -- whether a candidate
     *                                threshold behaves differently on scanned documents
     * @param headerReconstructionUncertain the one NEEDS_ATTENTION driver that emits no finding of
     *                                its own, so it is only countable from this column
     * @param withFailedFindings      imports where at least one rule returned FAILED
     * @param withWarningFindings     imports where at least one rule returned WARNING
     * @param byParserVersion         the same distribution split by deploy, because a rate measured
     *                                across a parser change is two populations averaged together
     */
    public record Summary(
            long completedJobs,
            long withTelemetry,
            long predatesTelemetry,
            long notCompleted,
            Map<String, Long> byReliabilityStatus,
            Map<String, Long> byTextSource,
            long headerReconstructionUncertain,
            long withFailedFindings,
            long withWarningFindings,
            List<ParserVersionBreakdown> byParserVersion) {
    }

    /**
     * One deploy's slice of the distribution.
     *
     * <p>A parser fix moves the distribution, so a single pooled rate silently averages the
     * behaviour before a fix with the behaviour after it. Splitting by the SHA that did the parsing
     * is what keeps "this rule fires on 3% of imports" from meaning two different things at once.
     *
     * <p>Not a fix-attribution key: a deploy SHA changes on every commit, including unrelated ones,
     * so this groups runs of imports rather than identifying which change moved a number.
     */
    public record ParserVersionBreakdown(
            String parserVersion,
            long jobs,
            long clean,
            long reviewRecommended,
            long needsAttention) {
    }
}
