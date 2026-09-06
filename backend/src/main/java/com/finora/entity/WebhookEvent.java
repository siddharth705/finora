package com.finora.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Subscription billing V1 (design spec §4.7). Mandatory webhook idempotency ledger -- Razorpay can
 * and does resend events, and duplicate processing can corrupt subscription state. PK is Razorpay's
 * own event id, a natural key the claim-flow's {@code INSERT ... ON CONFLICT DO NOTHING} targets
 * (see WebhookEventService). {@code status} distinguishes "we saw this and handled it" from "we saw
 * it and our handler threw" for production debugging -- a webhook that errors mid-processing still
 * gets its row (so a Razorpay retry of the same event is still recognized as a duplicate), marked
 * FAILED rather than looking identical to a success.
 */
@Entity
@Table(name = "webhook_events")
public class WebhookEvent {

    public static final String STATUS_PROCESSED = "PROCESSED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @Column(name = "event_id", length = 50)
    private String eventId;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(length = 20)
    private String status;

    @Column(name = "processed_at")
    private Instant processedAt;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
