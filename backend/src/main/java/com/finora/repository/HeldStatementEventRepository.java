package com.finora.repository;

import com.finora.entity.HeldStatementEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface HeldStatementEventRepository extends JpaRepository<HeldStatementEvent, UUID> {

    /** Oldest first -- this is read as a narrative of what happened to one statement, and a
     *  history that starts at the end is not one. */
    List<HeldStatementEvent> findByHeldStatementIdOrderByCreatedAtAsc(UUID heldStatementId);
}
