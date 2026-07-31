package com.finora.repository;

import com.finora.entity.Relationship;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RelationshipRepository extends JpaRepository<Relationship, UUID> {
    List<Relationship> findByUserId(UUID userId);
    List<Relationship> findByUserIdAndRelationshipType(UUID userId, Relationship.Type relationshipType);
}
