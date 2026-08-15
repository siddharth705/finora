package com.finora.integrations.google;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * What Finora decided about one Gmail message — Phase C4, design proposal §7.
 *
 * <h2>Two jobs: idempotency and provenance</h2>
 *
 * <b>Idempotency</b>, because a sync is not a transaction. A run that dies halfway — a killed
 * process, a rate limit, an expired token — must be safely resumable, and the only way to resume
 * without re-processing is to know what has already been seen. The unique index on
 * {@code (connection_id, gmail_message_id)} is what makes a retry cheap instead of duplicative, and
 * it is enforced by the database rather than by the worker remembering to check.
 *
 * <p><b>Provenance</b>, because "why did this transaction appear?" and "why did this receipt NOT
 * appear?" are both questions users and support will ask, and neither is answerable from the ledger
 * alone. A skipped message leaves no transaction to inspect; this is the only place its fate is
 * written down.
 *
 * <h2>What is deliberately absent</h2>
 *
 * No subject, no sender address, no body, no snippet. Unlike a bank statement — where Finora's copy
 * is the only durable record — the source email already lives in the user's own mailbox, and a
 * receipt carries markedly more incidental personal data than a statement does (delivery addresses,
 * phone numbers, other people's names). The message id is enough to re-fetch for debugging.
 *
 * <p>The one exception is {@link #authenticatedDomain}, which is not personal data and is the whole
 * "which parser should we write next" signal (§16.1).
 */
@Entity
@Table(name = "gmail_processed_messages")
public class GmailProcessedMessage {

    /**
     * What happened to a message.
     *
     * <p>Ordered by how far the message got, which is also the order the pipeline decides them in.
     */
    public enum Outcome {
        /** Failed authentication, or the authenticated domain is not on the registry. The body was
         *  never fetched — the gate runs on headers precisely so it never has to be. */
        SKIPPED_UNTRUSTED_SENDER,
        /** From a trusted sender, but nothing about it looks transactional. */
        SKIPPED_NOT_RECEIPT,
        /** Trusted and receipt-shaped, but no merchant-specific parser handles it. Recorded, never
         *  staged (§10.3) — a generic parser inventing a transaction is the failure this avoids. */
        DETECTED_NOT_STAGED,
        /** Reserved for C5. Nothing writes these yet; nothing parses. */
        PARSED,
        PARSE_FAILED
    }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    /** Gmail's own immutable id for the message within this mailbox. */
    @Column(name = "gmail_message_id", nullable = false, length = 128)
    private String gmailMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Outcome outcome;

    /** The domain that actually passed authentication — never the {@code From} header as written.
     *  Null when nothing passed. */
    @Column(name = "authenticated_domain", length = 253)
    private String authenticatedDomain;

    /**
     * Why the gate refused, when it did — {@link SenderAuthenticationService.Verdict} as a string,
     * minus {@code TRUSTED}.
     *
     * <p>Distinguishing "not authenticated" from "authenticated but not on the registry" matters:
     * the first is a spoof or a delivery problem, the second is a merchant nobody has added yet.
     * Collapsing them would make a rising spoof rate look like ordinary unparsed mail.
     */
    @Column(name = "skip_reason", length = 40)
    private String skipReason;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt = Instant.now();

    protected GmailProcessedMessage() {
        // JPA
    }

    private GmailProcessedMessage(UUID connectionId, String gmailMessageId, Outcome outcome,
                                  String authenticatedDomain, String skipReason) {
        this.connectionId = connectionId;
        this.gmailMessageId = gmailMessageId;
        this.outcome = outcome;
        this.authenticatedDomain = authenticatedDomain;
        this.skipReason = skipReason;
    }

    /**
     * Records a message the gate refused.
     *
     * <p>Takes the whole {@link SenderAuthenticationService.Result} rather than a domain and a
     * reason separately, so a caller cannot pair one verdict's reason with another's domain.
     */
    public static GmailProcessedMessage skipped(UUID connectionId, String gmailMessageId,
                                                SenderAuthenticationService.Result gate) {
        return new GmailProcessedMessage(connectionId, gmailMessageId,
                Outcome.SKIPPED_UNTRUSTED_SENDER, gate.authenticatedDomain(),
                gate.verdict().name());
    }

    /**
     * Records a message that cleared the gate.
     *
     * <p>{@code skipReason} is null by construction here: a trusted message is not a skip, and the
     * CHECK constraint in V83 refuses a row that claims to be both.
     */
    public static GmailProcessedMessage trusted(UUID connectionId, String gmailMessageId,
                                                Outcome outcome, String authenticatedDomain) {
        return new GmailProcessedMessage(connectionId, gmailMessageId, outcome,
                authenticatedDomain, null);
    }

    public UUID getId() { return id; }
    public UUID getConnectionId() { return connectionId; }
    public String getGmailMessageId() { return gmailMessageId; }
    public Outcome getOutcome() { return outcome; }
    public String getAuthenticatedDomain() { return authenticatedDomain; }
    public String getSkipReason() { return skipReason; }
    public Instant getProcessedAt() { return processedAt; }
}
