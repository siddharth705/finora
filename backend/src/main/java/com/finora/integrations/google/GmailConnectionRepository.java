package com.finora.integrations.google;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GmailConnectionRepository extends JpaRepository<GmailConnection, UUID> {

    /** The user's live connection, if any. Mirrors the partial unique index in V80 — at most one
     *  row can match, and the database is what guarantees that rather than this query. */
    Optional<GmailConnection> findByUserIdAndStatusIn(UUID userId, List<GmailConnection.Status> statuses);

    /** Whether this mailbox is already connected to SOME Finora account -- not necessarily this
     *  one. Lets the connect path answer "that Gmail account is already linked elsewhere" with a
     *  clear message instead of letting the unique index reject it as an opaque 409. */
    Optional<GmailConnection> findByGoogleUserIdAndStatusIn(String googleUserId,
                                                            List<GmailConnection.Status> statuses);

    List<GmailConnection> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Connections due for a discovery pass, oldest-checked first.
     *
     * <p>{@code CONNECTED} only, never the whole LIVE set: a {@code REAUTH_REQUIRED} connection has
     * a dead grant that only the user can revive, so including it would spend a token-refresh
     * request per tick to learn the same thing forever.
     *
     * <p>Never-checked rows sort first, so a mailbox connected moments ago is picked up on the next
     * tick rather than queueing behind every established connection.
     *
     * <p>Paged rather than "all of them", so one tick's work is bounded by the slice size instead of
     * by how many users the product has.
     */
    @Query("""
           select c from GmailConnection c
           where c.status = com.finora.integrations.google.GmailConnection$Status.CONNECTED
             and (c.lastDiscoveryAt is null or c.lastDiscoveryAt < :checkedBefore)
           order by c.lastDiscoveryAt asc nulls first
           """)
    List<GmailConnection> findDueForDiscovery(@Param("checkedBefore") Instant checkedBefore,
                                              Pageable pageable);
}
