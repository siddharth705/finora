package com.finora.goals;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface GoalRepository extends JpaRepository<Goal, UUID> {
    List<Goal> findByUserId(UUID userId);

    // DataExportService.buildBundle. Native query on purpose: Goal carries
    // @SQLRestriction("deleted_at IS NULL"), which Hibernate applies to every HQL/derived-query/
    // Criteria lookup against this entity -- a plain JPQL @Query would still get filtered. Mirrors
    // AccountRepository.findByUserIdIncludingDeleted exactly: this export's own scope doc mirrors
    // AccountPurgeSweepService's purge scope, which purges soft-deleted goals too, so the export
    // must still surface them, explicitly marked, rather than silently dropping them.
    @Query(value = "SELECT * FROM goals WHERE user_id = :userId", nativeQuery = true)
    List<Goal> findByUserIdIncludingDeleted(@Param("userId") UUID userId);

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

    /** D-27 PR3-D: the "first goal" activation-funnel stage -- how many distinct users have EVER
     *  created a goal. Native, bypassing {@code @SQLRestriction} the same way as
     *  {@code BudgetRepository.countDistinctUsersEverActivated} -- see that method's own doc
     *  comment for why a growth milestone must survive the goal later being deleted. */
    @Query(value = "SELECT COUNT(DISTINCT user_id) FROM goals", nativeQuery = true)
    long countDistinctUsersEverActivated();

    /** {@code FinancialJourneyService}'s FIRST_GOAL milestone: this ONE user's earliest goal ever
     *  created, regardless of later deletion -- see {@link #countDistinctUsersEverActivated}'s own
     *  doc comment just above for why. Epoch millis, not {@code Instant}: see
     *  {@code StatementImportRepository.findObjectsUnreferencedSince}'s doc comment for why a
     *  native query has no other reliable way to hand back a JDBC timestamp column without
     *  naming FG-019's banned {@code java.sql.Timestamp}. Null when this user has never created
     *  a goal. */
    @Query(value = "SELECT (EXTRACT(EPOCH FROM MIN(created_at)) * 1000)::bigint FROM goals WHERE user_id = :userId",
           nativeQuery = true)
    Long findEarliestCreatedAtEverEpochMillis(@Param("userId") UUID userId);

    /** {@code FinancialJourneyService}'s FIRST_GOAL_ACHIEVED milestone: this ONE user's earliest
     *  ACHIEVED goal ever, regardless of later deletion -- same permanence reasoning as
     *  {@link #findEarliestCreatedAtEverEpochMillis}. Null when this user has never achieved a
     *  goal. */
    @Query(value = "SELECT (EXTRACT(EPOCH FROM MIN(completed_at)) * 1000)::bigint FROM goals WHERE user_id = :userId AND completed_at IS NOT NULL",
           nativeQuery = true)
    Long findEarliestCompletedAtEverEpochMillis(@Param("userId") UUID userId);
}
