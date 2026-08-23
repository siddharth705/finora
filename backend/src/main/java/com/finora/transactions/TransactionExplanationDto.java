package com.finora.transactions;

import java.util.List;

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
        Integer confidence
) {}
