package com.finora.integrations.google.merchant;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row in the Gmail review queue (C5.4) — a {@code STAGED}, {@code SOURCE_GMAIL}
 * {@code ImportSession}, unpacked into what the queue actually needs to show. {@code sessionId} is
 * the identifier every action (approve/reject) is keyed on; there is no separate "receipt id" —
 * each Gmail-sourced session already holds exactly one {@code StagedRow}
 * ({@code GmailStagingBridge}'s own contract), so the session IS the receipt for this purpose.
 *
 * @param merchant       a display name derived from the parser's domain (e.g. {@code amazon.in} ->
 *                        "Amazon") — see {@link GmailReviewService#displayNameFor}. Cosmetic only;
 *                        the domain itself, not this label, is what every trust/routing decision
 *                        upstream of staging was already made against.
 * @param confidence     the parser's own extraction confidence (0.0–1.0), carried straight through
 *                        from {@code ParsedReceipt.confidence} via {@code StagedRow.confidence} —
 *                        display-only, per that field's own doc comment, and never a gate here
 *                        either.
 * @param category       the current suggested category — {@code StagedRow.suggestedCategory}, so
 *                        the queue can show what approving right now would file this under.
 */
public record GmailReviewItemDto(
        UUID sessionId,
        String merchant,
        String merchantDomain,
        BigDecimal amount,
        LocalDate date,
        String category,
        Double confidence,
        Instant stagedAt
) {}
