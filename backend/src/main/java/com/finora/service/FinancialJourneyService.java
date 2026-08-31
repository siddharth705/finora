package com.finora.service;

import com.finora.dto.FinancialJourneyDto;
import com.finora.dto.FinancialJourneyDto.Milestone;
import com.finora.entity.User;
import com.finora.goals.GoalRepository;
import com.finora.repository.BudgetRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * D-25 PR3-B. Backs the dashboard's "Your Financial Journey" section with five milestones, four
 * already fully derivable from existing timestamps (User.createdAt, StatementImport.importedAt,
 * Budget.createdAt, Goal.createdAt -- all via BaseEntity except User's own copy) and the fifth
 * from Goal.completedAt, added alongside this service.
 *
 * <p>Every milestone but ACCOUNT_CREATED is a permanent behavioral fact once reached, not a
 * live reflection of what currently exists -- the four repository lookups below deliberately
 * bypass each entity's {@code @SQLRestriction("deleted_at IS NULL")} (via a native {@code
 * findEarliest...EverEpochMillis} query, same bypass technique and reasoning
 * {@code BudgetRepository.countDistinctUsersEverActivated}'s own doc comment already establishes
 * for the platform-wide activation-funnel metric) so that a user who deletes their only
 * statement/budget/goal -- to fix a bad import and re-upload, to replace one budget with
 * another, to clean up after finishing a goal -- keeps every milestone they already earned. An
 * onboarding checklist that un-ticks itself the moment the user tidies up after themselves would
 * punish exactly the engaged behavior it exists to encourage.
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

        Instant firstImportAt = epochMillisToInstant(statementImportRepository.findEarliestImportedAtEverEpochMillis(userId));
        Instant firstBudgetAt = epochMillisToInstant(budgetRepository.findEarliestCreatedAtEverEpochMillis(userId));
        Instant firstGoalAt = epochMillisToInstant(goalRepository.findEarliestCreatedAtEverEpochMillis(userId));
        Instant firstGoalAchievedAt = epochMillisToInstant(goalRepository.findEarliestCompletedAtEverEpochMillis(userId));

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

    /** Each {@code findEarliest...EverEpochMillis} native query returns epoch milliseconds (or
     *  {@code null} over zero rows) rather than an {@code Instant} -- see any one of those
     *  methods' own doc comment for why. This is the one place that converts back. */
    private Instant epochMillisToInstant(Long epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis);
    }
}
