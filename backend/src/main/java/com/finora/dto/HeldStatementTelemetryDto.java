package com.finora.dto;

import java.util.Map;

/**
 * The aggregate readout over held_statements. Same "cheap live aggregate, counts never rates"
 * discipline as {@link ImportTelemetryDto} -- see that class's own doc for the full reasoning.
 *
 * <p>No nested {@code Summary} wrapper, unlike {@code ImportTelemetryDto}: that class carries a
 * second, real sub-shape ({@code ParserVersionBreakdown}) alongside its own summary, which is what
 * the wrapper exists to distinguish. This DTO has only the one shape, so this record IS the
 * summary.
 *
 * @param totalHolds           every held statement ever created, open or resolved
 * @param resolved             totalHolds that reached IMPORTED or REJECTED. NOT the denominator
 *                             for falsePositives -- see that field's own doc.
 * @param approved             resolved via approve (IMPORTED). The actual denominator for
 *                             falsePositives: a rejection can never be marked one (see Plan 4's
 *                             Decisions table), so falsePositives / resolved understates the true
 *                             proportion by folding in rejections that were never eligible to be a
 *                             false positive in the first place.
 * @param rejected             resolved via reject (REJECTED)
 * @param falsePositives       of `approved` (not `resolved` -- see that field's own doc), how many
 *                             an operator explicitly marked false positive at approve time. Never
 *                             divided by anything here -- see this plan's Global Constraints. A
 *                             caller computing a proportion should divide by `approved`.
 * @param byCategory           which TrustPredicate condition fired, across every hold that
 *                             recorded one -- a hold predating V152 (Plan 4) is excluded, not
 *                             counted as zero
 * @param medianResolutionHours median hours from created to resolved, over resolved holds only.
 *                             Null when nothing has resolved yet.
 */
public record HeldStatementTelemetryDto(
        long totalHolds,
        long resolved,
        long approved,
        long rejected,
        long falsePositives,
        Map<String, Long> byCategory,
        Double medianResolutionHours) {
}
