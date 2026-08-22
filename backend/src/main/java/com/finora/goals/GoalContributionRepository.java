package com.finora.goals;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GoalContributionRepository extends JpaRepository<GoalContribution, UUID> {
    List<GoalContribution> findByGoalIdOrderByContributedAtDesc(UUID goalId);

    /** DataExportService.buildBundle -- one batched query for every contribution across all of a
     *  user's goals (including soft-deleted ones, per goalIds' own source), not one
     *  findByGoalIdOrderByContributedAtDesc call per goal. */
    List<GoalContribution> findByGoalIdInOrderByContributedAtDesc(List<UUID> goalIds);
}
