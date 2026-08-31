package com.finora.transactions;

import java.util.List;
import java.util.UUID;

/**
 * "Why this category?" for one transaction — surfaced on demand, not on every list row.
 *
 * <p>Every field here already existed before this class did: {@link
 * com.finora.entity.Transaction#getDecisionSource()} and {@code getDecisionRuleId()} have been
 * written at categorization time since the {@code DecisionSource} enum was introduced, and {@link
 * TransactionExplanationService} is the first thing that reads them back out. This is a surfacing
 * feature, not a new intelligence layer — no field here is computed by anything that didn't
 * already run before the transaction was saved.
 *
 * @param decisionSource {@code Transaction.DecisionSource} name, for a caller that wants to
 *                        branch on it rather than parse {@code summary}.
 * @param summary         one plain-English sentence.
 * @param evidence        supporting detail as short bullet lines, in the same spirit as the
 *                        review queue's {@code GmailReviewItemDto.reasoning} (C6.1) — real
 *                        signals only, never invented detail about a detection that didn't
 *                        happen. Empty, not null, when there is nothing more to add.
 */
public record TransactionExplanationDto(
        String decisionSource,
        String summary,
        List<String> evidence,
        /** 0-100, or null -- {@link com.finora.entity.Transaction#getDecisionConfidence()} read
         *  straight through, same "surfacing, not a new intelligence layer" contract as every
         *  other field on this record. Null for MANUAL/FILE_PROVIDED (see that field's own doc
         *  comment) and for any transaction that predates Transaction Intelligence Phase B. */
        Integer confidence,
        /**
         * "Why this match?" -- Phase 1 of docs/proposals/reconciliation-evolution-roadmap-proposal.md.
         * Null when {@code reconciliationStatus} is {@code OK} (the overwhelming majority of rows,
         * and there is nothing to explain about a row nothing matched). Same surfacing-only
         * contract as the rest of this record: every field here was already written by {@link
         * com.finora.service.ReconciliationService} at match time, via {@link
         * com.finora.service.ReconciliationExplanation} -- this reads it back, it computes nothing.
         */
        ReconciliationExplanationDto reconciliation
) {
    /**
     * @param status               {@code Transaction.ReconciliationStatus} name (DUPLICATE,
     *                             TRANSFER, REFUND, or REVERSAL -- never OK, see above).
     * @param matchedTransactionId the counterpart transaction this row was matched against --
     *                             {@code isDuplicateOf}, {@code transferPairId}, or {@code
     *                             refundOfTransactionId} depending on {@code status}, read
     *                             directly off the entity rather than re-parsed out of the JSON
     *                             explanation, so it's never out of sync with it.
     * @param summary              one plain-English sentence.
     * @param evidence             the individual signals the matching pass weighed, as short
     *                             bullet lines -- same spirit as the categorization {@code
     *                             evidence} field above. Empty, not null, for a transaction that
     *                             predates the reconciliation_explanation column (V55) and
     *                             therefore has a status but no recorded reasoning.
     */
    public record ReconciliationExplanationDto(
            String status,
            UUID matchedTransactionId,
            String summary,
            List<String> evidence
    ) {}
}
