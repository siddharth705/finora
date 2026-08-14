package com.finora.integrations.google;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
