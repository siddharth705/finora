package com.finora.integrations.google;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GmailOAuthStateRepository extends JpaRepository<GmailOAuthState, UUID> {

    Optional<GmailOAuthState> findByStateHash(String stateHash);

    /**
     * Atomically claims a state for redemption — the actual enforcement of single use.
     *
     * <p>Strix security review, CWE-367. This was a read, an {@code isRedeemable} check, and a
     * separate save. Two callbacks presenting the same state can both complete the read before
     * either writes, so both pass the check and both proceed — which defeats the single-use
     * guarantee the whole design rests on. That matters more here than in most races: the callback
     * is deliberately unauthenticated and {@code state} is the ONLY thing binding it to a Finora
     * user, so anyone holding a victim's callback URL could race them with their own authorization
     * code and bind an attacker-controlled mailbox to the victim's account.
     *
     * <p>Same shape, and for the same reason, as
     * {@code ImportSessionRepository.claimForConfirmation}: one conditional UPDATE decides the
     * winner in the database, and the loser sees zero rows affected and is rejected before doing
     * any work. Chosen over {@code @Lock(PESSIMISTIC_WRITE)} on the read because it is a single
     * statement — it needs no transaction held open around a read-then-write pair, and it cannot be
     * defeated by a caller that forgets to wrap it in one.
     *
     * <p>The expiry is part of the same predicate deliberately: checking it separately would
     * reintroduce a smaller version of the same gap.
     *
     * @return 1 if this caller claimed the state, 0 if it was unknown, already consumed, or expired
     */
    @Modifying
    @Query("UPDATE GmailOAuthState s SET s.consumedAt = :now "
            + "WHERE s.stateHash = :stateHash AND s.consumedAt IS NULL AND s.expiresAt > :now")
    int claimForRedemption(@Param("stateHash") String stateHash, @Param("now") Instant now);

    /** Bounded, oldest-first — the same shape {@code ImportSessionRepository}'s expiry sweep uses,
     *  so a backlog drains across runs instead of in one unbounded delete. */
    List<GmailOAuthState> findByExpiresAtBeforeOrderByExpiresAtAsc(Instant cutoff, Pageable pageable);
}
