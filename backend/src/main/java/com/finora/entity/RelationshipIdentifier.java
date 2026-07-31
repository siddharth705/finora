package com.finora.entity;

import jakarta.persistence.*;

import java.util.UUID;

/** One raw identifier (UPI id, last-4 account digits, or a name pattern) that resolves to a
 *  Relationship -- exact-match matching against a normalized transaction description, the same
 *  pattern MerchantAlias uses for merchants (see RelationshipService). */
@Entity
@Table(name = "relationship_identifiers")
public class RelationshipIdentifier {

    public enum Type { UPI_ID, ACCOUNT_LAST4, NAME_PATTERN }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "relationship_id", nullable = false)
    private UUID relationshipId;

    @Enumerated(EnumType.STRING)
    @Column(name = "identifier_type", nullable = false)
    private Type identifierType;

    @Column(name = "identifier_value", nullable = false)
    private String identifierValue;

    public UUID getId() { return id; }
    public UUID getRelationshipId() { return relationshipId; }
    public void setRelationshipId(UUID relationshipId) { this.relationshipId = relationshipId; }
    public Type getIdentifierType() { return identifierType; }
    public void setIdentifierType(Type identifierType) { this.identifierType = identifierType; }
    public String getIdentifierValue() { return identifierValue; }
    public void setIdentifierValue(String identifierValue) { this.identifierValue = identifierValue; }
}
