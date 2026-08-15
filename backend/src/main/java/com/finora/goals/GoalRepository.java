package com.finora.goals;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface GoalRepository extends JpaRepository<Goal, UUID> {
    List<Goal> findByUserId(UUID userId);

    /** AccountPurgeSweepService. Native, bypassing Hibernate's {@code @SQLDelete} entirely -- a
     *  derived/JPQL {@code deleteByUserId} on this entity would only soft-delete (set
     *  {@code deleted_at}), not purge, since {@code Goal extends BaseEntity}. Named
     *  {@code hardDeleteByUserId}, not {@code deleteByUserId}, so the bypass is visible at every
     *  call site -- see {@code TransactionRepository.hardDeleteByUserId}'s own doc comment for the
     *  same naming discipline. {@code goal_contributions} needs no separate cleanup: it has no
     *  {@code user_id} column and cascades automatically via its own
     *  {@code goal_id ON DELETE CASCADE}. */
    @Modifying
    @Query(value = "DELETE FROM goals WHERE user_id = :userId", nativeQuery = true)
    void hardDeleteByUserId(@Param("userId") UUID userId);
}
