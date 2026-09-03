package com.finora.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry in a held statement's history.
 *
 * <p>Append-only by construction: there are no setters, and nothing updates a row once written.
 * Financial workflows eventually have to answer why a statement was held, who reviewed it and who
 * released it, and reconstructing that from logs later is much harder than recording it as it
 * happens.
 *
 * <p>{@code actorId} null means the system acted -- the worker opening the hold, rather than a
 * person. It is {@code ON DELETE SET NULL} in V144 rather than cascading, so deleting an admin
 * account does not erase the record of what they decided.
 */
@Entity
@Table(name = "held_statement_events")
public class HeldStatementEvent {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "held_statement_id", nullable = false)
    private UUID heldStatementId;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "from_status")
    private String fromStatus;

    @Column(name = "to_status")
    private String toStatus;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected HeldStatementEvent() {}

    public HeldStatementEvent(UUID heldStatementId, UUID actorId, String eventType,
                              String fromStatus, String toStatus, String notes) {
        this.heldStatementId = heldStatementId;
        this.actorId = actorId;
        this.eventType = eventType;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.notes = notes;
    }

    public UUID getId() { return id; }
    public UUID getHeldStatementId() { return heldStatementId; }
    public UUID getActorId() { return actorId; }
    public String getEventType() { return eventType; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getNotes() { return notes; }
    public Instant getCreatedAt() { return createdAt; }
}
