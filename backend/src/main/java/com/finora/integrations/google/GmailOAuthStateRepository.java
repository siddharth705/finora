package com.finora.integrations.google;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GmailOAuthStateRepository extends JpaRepository<GmailOAuthState, UUID> {

    Optional<GmailOAuthState> findByStateHash(String stateHash);

    /** Bounded, oldest-first — the same shape {@code ImportSessionRepository}'s expiry sweep uses,
     *  so a backlog drains across runs instead of in one unbounded delete. */
    List<GmailOAuthState> findByExpiresAtBeforeOrderByExpiresAtAsc(Instant cutoff, Pageable pageable);
}
