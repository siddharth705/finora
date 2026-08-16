package com.finora.repository;

import com.finora.entity.Budget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetRepository extends JpaRepository<Budget, UUID> {
    List<Budget> findByUserId(UUID userId);
    Optional<Budget> findByUserIdAndCategoryId(UUID userId, UUID categoryId);

    /** AccountPurgeSweepService. Native, bypassing Hibernate's {@code @SQLDelete} entirely -- a
     *  derived/JPQL {@code deleteByUserId} on this entity would only soft-delete (set
     *  {@code deleted_at}), not purge, since {@code Budget extends BaseEntity}. Named
     *  {@code hardDeleteByUserId}, not {@code deleteByUserId}, so the bypass is visible at every
     *  call site -- see TransactionRepository.hardDeleteByUserId's own doc comment for the same
     *  naming discipline (and the reasoning that applies there but not here, since Budget has no
     *  self-referential FK). */
    @Modifying
    @Query(value = "DELETE FROM budgets WHERE user_id = :userId", nativeQuery = true)
    void hardDeleteByUserId(@Param("userId") UUID userId);
}
