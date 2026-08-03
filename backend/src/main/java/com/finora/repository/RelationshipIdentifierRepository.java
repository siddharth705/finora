package com.finora.repository;

import com.finora.entity.RelationshipIdentifier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RelationshipIdentifierRepository extends JpaRepository<RelationshipIdentifier, UUID> {
    List<RelationshipIdentifier> findByRelationshipId(UUID relationshipId);

    /** Backs RelationshipService.listForUser()'s bulk-fetch-then-group-in-memory pattern -- one
     *  query for every identifier across a user's relationships, instead of one
     *  findByRelationshipId() call per relationship in a loop. */
    List<RelationshipIdentifier> findByRelationshipIdIn(List<UUID> relationshipIds);

    void deleteByRelationshipId(UUID relationshipId);
}
