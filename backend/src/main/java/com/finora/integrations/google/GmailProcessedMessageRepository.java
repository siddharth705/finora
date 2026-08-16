package com.finora.integrations.google;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface GmailProcessedMessageRepository extends JpaRepository<GmailProcessedMessage, UUID> {

    /**
     * Which of these message ids this connection has already decided about.
     *
     * <p>Returns the ids rather than the rows on purpose. Discovery asks this once per page of
     * listed ids and only needs set membership, and the rows carry columns it would immediately
     * discard — a page of 100 receipts is 100 entities materialised to answer a question about
     * strings.
     *
     * <p>This is the query that makes re-listing a date window cheap. Listing ids costs one quota
     * unit a page; fetching headers costs five per message. Subtracting what is already here means
     * the expensive call only happens for genuinely new mail, which is why C4 needs no history
     * cursor (see V83's comment).
     */
    @Query("""
           select m.gmailMessageId from GmailProcessedMessage m
           where m.connectionId = :connectionId and m.gmailMessageId in :messageIds
           """)
    Set<String> findAlreadyProcessedIds(@Param("connectionId") UUID connectionId,
                                        @Param("messageIds") Collection<String> messageIds);

    long countByConnectionId(UUID connectionId);

    /** "Transactions found" on the connection status panel (C5.4) — every message this mailbox
     *  ever produced a receipt from, regardless of whether it has since been approved, rejected,
     *  or still sits in the review queue. */
    long countByConnectionIdAndOutcome(UUID connectionId, GmailProcessedMessage.Outcome outcome);

    /**
     * Trusted messages still waiting on extraction — C5-B's work queue. Oldest first, so a
     * mailbox with a backlog processes in the order the mail actually arrived rather than
     * whatever order the database happens to return rows in.
     *
     * <p>Scoped to one connection, matching {@code GmailMessageDiscoveryService.discoverFor}'s own
     * per-connection shape — extraction runs as the next step for the same connection a discovery
     * pass just finished, not as a separate cross-mailbox scan.
     */
    List<GmailProcessedMessage> findByConnectionIdAndOutcomeOrderByProcessedAtAsc(
            UUID connectionId, GmailProcessedMessage.Outcome outcome, Pageable pageable);

    /**
     * Per-merchant-domain outcome breakdown for the admin Merchant Intelligence page (C6.2) — the
     * same "{@code GROUP BY} two columns, aggregate the rest in Java" shape as
     * {@code StatementAnalysisSessionRepository#failureCodeLayoutCounts} and
     * {@code MerchantRepository#platformMerchantCounts}, over this table instead of theirs.
     *
     * <p>{@link GmailProcessedMessage.Outcome#SKIPPED_UNTRUSTED_SENDER} is excluded on purpose,
     * not just uninteresting: it fires before any {@code MerchantEmailParser} is ever consulted
     * (a sender-authentication failure, not a parser one), so including it would mix a security
     * signal into a parser-health one and could group unrelated/spoofed domains in as if they
     * were merchants. Every remaining outcome only exists on a row created via
     * {@link GmailProcessedMessage#trusted}, which never leaves {@code authenticatedDomain} null —
     * so this needs no separate null filter.
     *
     * <p>{@code since} has no default, matching {@code failureCodeLayoutCounts}'s own reasoning: an
     * unbounded scan of a table that only grows is a cost this method should never silently absorb.
     */
    @Query("""
           SELECT m.authenticatedDomain, m.outcome, COUNT(m), MAX(m.processedAt)
           FROM GmailProcessedMessage m
           WHERE m.outcome != com.finora.integrations.google.GmailProcessedMessage$Outcome.SKIPPED_UNTRUSTED_SENDER
             AND m.processedAt >= :since
           GROUP BY m.authenticatedDomain, m.outcome
           ORDER BY m.authenticatedDomain ASC
           """)
    List<Object[]> merchantOutcomeCounts(@Param("since") Instant since);
}
