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
 * @param counterpartyName    who the receipt says the money actually went to, when that is
 *                            knowable and distinct from {@link #merchantDomain} — e.g. a PhonePe
 *                            P2P payee, or CRED's "{@code <Bank> •••• <last4>}". Null for every
 *                            merchant where the domain already IS the counterparty; every parser
 *                            except a payment-relay one passes null, meaning exactly what it means
 *                            today: no counterparty distinct from the merchant.
 * @param amount              the transaction amount. {@link Money}, not a raw number, for the same
 *                            reason every new money-handling calculation in this codebase uses it —
 *                            see {@code Money}'s own class doc.
 * @param transactionDate     the date the receipt states the charge occurred, not the email's
 *                            {@code Date} header — a receipt can be forwarded, delayed, or dated
 *                            differently from its delivery.
 * @param confidence          0.0–1.0, this parser's own estimate of extraction reliability. Purely
 *                            informational; see the class doc above.
 *
 * <h2>Structural checks live here; business-rule checks live in {@link ParsedReceiptValidator}</h2>
 *
 * This constructor refuses a null or blank required field, on the same reasoning as every other
 * fail-fast validation in this codebase: a parser that produced a null date is a bug, and the bug
 * should surface at the exact call site that made the mistake, with a message naming the field,
 * rather than as an NPE three layers downstream with no indication which parser was responsible.
 * {@code counterpartyName} is deliberately not in that list — null is its legitimate, common value,
 * not a forgotten field.
 *
 * <p>Deliberately does NOT check that {@link #amount} is positive or that {@link #transactionDate}
 * is not absurdly far in the future — those are not "this cannot be a receipt" bugs, they are "is
 * this receipt plausible" judgments, and {@link ParsedReceiptValidator} is where that judgment
 * belongs. Because null/blank is already eliminated here, the validator does not re-check for it —
 * checking a condition this constructor has already made impossible would be validating a state
 * that cannot occur.
 */
public record ParsedReceipt(String gmailMessageId, String merchantDomain, String counterpartyName,
                            Money amount, LocalDate transactionDate, double confidence) {

    public ParsedReceipt {
        if (gmailMessageId == null || gmailMessageId.isBlank()) {
            throw new IllegalArgumentException("gmailMessageId is required");
        }
        if (merchantDomain == null || merchantDomain.isBlank()) {
            throw new IllegalArgumentException("merchantDomain is required");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        if (transactionDate == null) {
            throw new IllegalArgumentException("transactionDate is required");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0.0, 1.0], got " + confidence);
        }
    }
}
