package com.finora.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One merchant awaiting review (WI4).
 *
 * <p>Shaped by the same standard as the learning queue: an operator decides from this payload
 * alone. The three things they need to make the decision are the engine's guessed name, whose
 * account it landed in, and how much of that account's history is already attributed to it —
 * because {@code transactionCount} is what separates "discard this, it was never real" from "merge
 * this, it is on twelve of their transactions".
 *
 * <p>No cross-user fields, per the product decision that this milestone introduces no canonical
 * merchant registry. There is deliberately no "number of users" and no platform-wide merge
 * candidate: {@code merchants.user_id} is NOT NULL, so a merchant belongs to exactly one person and
 * anything else would be inventing an identity the schema does not have.
 *
 * @param transactionCount 0 means nothing points at it, which is the only case where discarding is
 *                         allowed — {@code transactions.merchant_id} is ON DELETE SET NULL, so
 *                         deleting a merchant with history silently strips the attribution from
 *                         real ledger rows
 */
public record MerchantReviewDto(
        UUID id,
        UUID userId,
        String userEmail,
        String canonicalName,
        String lifecycleStatus,
        long transactionCount,
        Instant createdAt
) {}
