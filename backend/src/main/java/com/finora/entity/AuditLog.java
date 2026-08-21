package com.finora.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(name = "entity_type", length = 60)
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // BH-044. Null until AuditService's redaction sweep processes this row; set to the moment it
    // did afterward. See that class's redaction doc for the full retention story -- this column is
    // both the "already redacted" marker and a record of when it happened.
    @Column(name = "redacted_at")
    private Instant redactedAt;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRedactedAt() { return redactedAt; }
    public void setRedactedAt(Instant redactedAt) { this.redactedAt = redactedAt; }
}
