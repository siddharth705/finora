package com.finora.service;

import com.finora.dto.FinancialJourneyDto;
import com.finora.dto.FinancialJourneyDto.Milestone;
import com.finora.entity.Budget;
import com.finora.entity.User;
import com.finora.goals.Goal;
import com.finora.goals.GoalRepository;
import com.finora.repository.BudgetRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.StatementImportRepository.StatementMetadata;
import com.finora.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * D-25 PR3-B. Backs the dashboard's "Your Financial Journey" section with five milestones, four
 * already fully derivable from existing timestamps (User.createdAt, StatementImport.importedAt,
 * Budget.createdAt, Goal.createdAt -- all via BaseEntity except User's own copy) and the fifth
 * from Goal.completedAt, added alongside this service.
 */
@Service
public class FinancialJourneyService {

    private final UserRepository userRepository;
    private final StatementImportRepository statementImportRepository;
    private final BudgetRepository budgetRepository;
    private final GoalRepository goalRepository;

    public FinancialJourneyService(UserRepository userRepository,
                                    StatementImportRepository statementImportRepository,
                                    BudgetRepository budgetRepository,
                                    GoalRepository goalRepository) {
        this.userRepository = userRepository;
        this.statementImportRepository = statementImportRepository;
        this.budgetRepository = budgetRepository;
        this.goalRepository = goalRepository;
    }

    @Transactional(readOnly = true)
    public FinancialJourneyDto build(UUID userId) {
        // DashboardService.summarize's own "shouldn't happen for an authenticated caller" fallback:
        // rather than a 500 for a row that can't be found, ACCOUNT_CREATED just reports incomplete.
        Instant accountCreatedAt = userRepository.findById(userId).map(User::getCreatedAt).orElse(null);

        // findMetadataByUserIdOrderByImportedAtDesc, not findByUserIdOrderByImportedAtDesc: the
        // latter's own doc comment documents that it eagerly loads every statement's raw file
        // bytes (fileContent's LAZY annotation is a no-op without bytecode enhancement in this
        // build) -- exactly the load DataExportService was fixed to avoid. This only needs the
        // timestamp, so the metadata projection never touches that column.
        List<StatementMetadata> imports = statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(userId);
        Instant firstImportAt = imports.stream().map(StatementMetadata::getImportedAt)
                .min(Comparator.naturalOrder()).orElse(null);

        List<Budget> budgets = budgetRepository.findByUserId(userId);
        Instant firstBudgetAt = budgets.stream().map(Budget::getCreatedAt)
                .min(Comparator.naturalOrder()).orElse(null);

        List<Goal> goals = goalRepository.findByUserId(userId);
        Instant firstGoalAt = goals.stream().map(Goal::getCreatedAt)
                .min(Comparator.naturalOrder()).orElse(null);
        Instant firstGoalAchievedAt = goals.stream().map(Goal::getCompletedAt).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);

        return new FinancialJourneyDto(List.of(
                milestone(FinancialJourneyDto.ACCOUNT_CREATED, accountCreatedAt),
                milestone(FinancialJourneyDto.FIRST_IMPORT, firstImportAt),
                milestone(FinancialJourneyDto.FIRST_BUDGET, firstBudgetAt),
                milestone(FinancialJourneyDto.FIRST_GOAL, firstGoalAt),
                milestone(FinancialJourneyDto.FIRST_GOAL_ACHIEVED, firstGoalAchievedAt)
        ));
    }

    private Milestone milestone(String type, Instant completedAt) {
        return new Milestone(type, completedAt != null, completedAt);
    }
}
