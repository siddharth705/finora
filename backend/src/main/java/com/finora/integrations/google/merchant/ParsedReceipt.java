package com.finora.integrations.google.merchant;

import com.finora.domain.Money;

import java.time.LocalDate;

/**
 * What a parser extracted from one receipt — Phase C5. This is a proposal, not a fact: nothing
 * downstream may treat it as ground truth for the ledger. §12.2 of the design proposal is explicit
 * that the only trusted step is the user's own review.
 *
 * <p>{@link #confidence} exists to be <b>displayed</b>, never to gate automatic creation. A field
 * named "confidence" sitting next to a field that could create a transaction is exactly the shape
 * that invites "just auto-create above 0.95" as a later, unreviewed change — so it is worth saying
 * directly: there is no threshold at which this record is allowed to become a transaction without
 * {@code GmailStagingBridge} routing it through {@code ImportSessionService} review first, the same
 * as every other import source. A 99% confidence wrong amount is still a wrong amount in a user's
 * ledger.
 *
 * @param gmailMessageId      the provenance key — what C5-B's staged row and the {@code
 *                            gmail_processed_messages} row it originated from both key on.
 * @param merchantDomain      the authenticated domain the receipt came from (e.g. {@code
 *                            amazon.in}), not a display name a template happened to use.
 * @param amount              the transaction amount. {@link Money}, not a raw number, for the same
 *                            reason every new money-handling calculation in this codebase uses it —
 *                            see {@code Money}'s own class doc.
 * @param transactionDate     the date the receipt states the charge occurred, not the email's
 *                            {@code Date} header — a receipt can be forwarded, delayed, or dated
 *                            differently from its delivery.
 * @param confidence          0.0–1.0, this parser's own estimate of extraction reliability. Purely
 *                            informational; see the class doc above.
 */
public record ParsedReceipt(String gmailMessageId, String merchantDomain, Money amount,
                            LocalDate transactionDate, double confidence) {

    public ParsedReceipt {
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0.0, 1.0], got " + confidence);
        }
    }
}
