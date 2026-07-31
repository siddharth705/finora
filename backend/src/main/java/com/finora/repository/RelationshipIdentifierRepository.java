package com.finora.repository;

import com.finora.entity.RelationshipIdentifier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RelationshipIdentifierRepository extends JpaRepository<RelationshipIdentifier, UUID> {
    List<RelationshipIdentifier> findByRelationshipId(UUID relationshipId);
    void deleteByRelationshipId(UUID relationshipId);
}
