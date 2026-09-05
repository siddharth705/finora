package com.finora.repository;

import com.finora.entity.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {

    /**
     * Claim-once insert. {@code WebhookEvent.eventId} is a manually-assigned natural key (Razorpay's
     * own event id), not a {@code @GeneratedValue} — Hibernate's {@code save()} treats an entity
     * whose id is already set as detached and issues a SELECT+UPDATE (a merge) rather than an
     * INSERT, so a plain {@code saveAndFlush()} never throws a duplicate-key violation for a repeat
     * event id and silently "succeeds" twice. Same {@code INSERT ... ON CONFLICT DO NOTHING
     * RETURNING} shape as {@code NotificationRepository.insertIfAbsent} for the identical reason.
     *
     * @return the claimed event id, or empty if {@code eventId} already existed (a Razorpay retry or
     *     a concurrent delivery of the same event).
     */
    @Query(value = """
           INSERT INTO webhook_events (event_id, provider, event_type, payload)
           VALUES (:eventId, :provider, :eventType, CAST(:payload AS jsonb))
           ON CONFLICT (event_id) DO NOTHING
           RETURNING event_id
           """, nativeQuery = true)
    Optional<String> insertIfAbsent(@Param("eventId") String eventId, @Param("provider") String provider,
            @Param("eventType") String eventType, @Param("payload") String payload);
}
