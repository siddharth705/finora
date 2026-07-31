package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Lets a user tag another person or one of their own other accounts (docs/
 * rule-engine-relationship-engine-eds.md §3.3). OWN_ACCOUNT relationships feed
 * ReconciliationService's transfer detection as a confidence signal alongside (not replacing)
 * its existing amount+date heuristic. FAMILY/FRIEND/OTHER are recognized and persisted for
 * future rule/analytics conditioning (see RuleEngineService's Field enum, which doesn't yet
 * include a relationship-based field -- that's a fast-follow, not this milestone).
 */
@Entity
@Table(name = "relationships")
public class Relationship {

    public enum Type { FAMILY, FRIEND, OWN_ACCOUNT, OTHER }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship_type", nullable = false)
    private Type relationshipType;

    // Only meaningful for OWN_ACCOUNT -- not enforced at the database level (see V18: a plain
    // nullable FK) because RelationshipService validates it's set (and owned by this user)
    // specifically when relationshipType is OWN_ACCOUNT, same layering as other cross-field
    // validation in this codebase (e.g. CategoryRule's scope/user_id CHECK is DB-level because
    // it's a simple invariant; this one needs an ownership lookup, which belongs in the service).
    @Column(name = "linked_account_id")
    private UUID linkedAccountId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Type getRelationshipType() { return relationshipType; }
    public void setRelationshipType(Type relationshipType) { this.relationshipType = relationshipType; }
    public UUID getLinkedAccountId() { return linkedAccountId; }
    public void setLinkedAccountId(UUID linkedAccountId) { this.linkedAccountId = linkedAccountId; }
    public Instant getCreatedAt() { return createdAt; }
}
