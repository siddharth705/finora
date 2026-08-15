package com.finora.repository;

import com.finora.entity.PasswordHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PasswordHistoryRepository extends JpaRepository<PasswordHistory, UUID> {
    List<PasswordHistory> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** AccountPurgeSweepService -- hard delete, no soft-delete concern on this entity. */
    void deleteByUserId(UUID userId);
}
