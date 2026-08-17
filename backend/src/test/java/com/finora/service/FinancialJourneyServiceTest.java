package com.finora.service;

import com.finora.dto.FinancialJourneyDto;
import com.finora.entity.Budget;
import com.finora.entity.User;
import com.finora.goals.Goal;
import com.finora.goals.GoalRepository;
import com.finora.repository.BudgetRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D-25 PR3-B. Covers the five-milestone aggregation (ACCOUNT_CREATED/FIRST_IMPORT/FIRST_BUDGET/
 * FIRST_GOAL/FIRST_GOAL_ACHIEVED), that each milestone reports the EARLIEST qualifying timestamp
 * rather than the first one returned, and the below-floor "nothing yet" case. Mockito-based unit
 * tests against mocked repositories, matching DashboardServiceTest/GoalServiceTest's own pattern.
 */
class FinancialJourneyServiceTest {

    private UserRepository userRepository;
    private StatementImportRepository statementImportRepository;
    private BudgetRepository budgetRepository;
    private GoalRepository goalRepository;
    private FinancialJourneyService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        statementImportRepository = mock(StatementImportRepository.class);
        budgetRepository = mock(BudgetRepository.class);
        goalRepository = mock(GoalRepository.class);
        service = new FinancialJourneyService(userRepository, statementImportRepository, budgetRepository, goalRepository);

        when(userRepository.findById(any())).thenReturn(Optional.empty());
        when(statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(any())).thenReturn(List.of());
        when(budgetRepository.findByUserId(any())).thenReturn(List.of());
        when(goalRepository.findByUserId(any())).thenReturn(List.of());
    }

    private static StatementImportRepository.StatementMetadata importedAt(Instant at) {
        StatementImportRepository.StatementMetadata m = mock(StatementImportRepository.StatementMetadata.class);
        when(m.getImportedAt()).thenReturn(at);
        return m;
    }

    private static Budget budgetCreatedAt(Instant at) {
        Budget b = new Budget();
        ReflectionTestUtils.setField(b, "createdAt", at);
        return b;
    }

    private static Goal goalCreatedAt(Instant at) {
        Goal g = new Goal();
        ReflectionTestUtils.setField(g, "createdAt", at);
        return g;
    }

    private FinancialJourneyDto.Milestone milestoneOf(FinancialJourneyDto dto, String type) {
        return dto.milestones().stream().filter(m -> m.type().equals(type)).findFirst()
                .orElseThrow(() -> new AssertionError("missing milestone " + type));
    }

    @Test
    void build_returnsAllFiveMilestonesInTheProposedOrder_whenUserHasDoneEverything() {
        User user = new User();
        ReflectionTestUtils.setField(user, "createdAt", Instant.parse("2026-08-01T00:00:00Z"));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        StatementImportRepository.StatementMetadata importMeta = importedAt(Instant.parse("2026-08-02T00:00:00Z"));
        when(statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(userId))
                .thenReturn(List.of(importMeta));
        Budget budget = budgetCreatedAt(Instant.parse("2026-08-03T00:00:00Z"));
        when(budgetRepository.findByUserId(userId)).thenReturn(List.of(budget));
        Goal achievedGoal = goalCreatedAt(Instant.parse("2026-08-04T00:00:00Z"));
        achievedGoal.setCompletedAt(Instant.parse("2026-08-10T00:00:00Z"));
        when(goalRepository.findByUserId(userId)).thenReturn(List.of(achievedGoal));

        FinancialJourneyDto dto = service.build(userId);

        assertThat(dto.milestones()).extracting(FinancialJourneyDto.Milestone::type).containsExactly(
                FinancialJourneyDto.ACCOUNT_CREATED, FinancialJourneyDto.FIRST_IMPORT,
                FinancialJourneyDto.FIRST_BUDGET, FinancialJourneyDto.FIRST_GOAL,
                FinancialJourneyDto.FIRST_GOAL_ACHIEVED);
        assertThat(dto.milestones()).allMatch(FinancialJourneyDto.Milestone::completed);
        assertThat(milestoneOf(dto, FinancialJourneyDto.FIRST_GOAL_ACHIEVED).completedAt())
                .isEqualTo(Instant.parse("2026-08-10T00:00:00Z"));
    }

    @Test
    void build_marksOnlyAccountCreated_forABrandNewUserWithNoOtherData() {
        User user = new User();
        ReflectionTestUtils.setField(user, "createdAt", Instant.parse("2026-08-17T00:00:00Z"));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        FinancialJourneyDto dto = service.build(userId);

        assertThat(milestoneOf(dto, FinancialJourneyDto.ACCOUNT_CREATED).completed()).isTrue();
        assertThat(milestoneOf(dto, FinancialJourneyDto.FIRST_IMPORT).completed()).isFalse();
        assertThat(milestoneOf(dto, FinancialJourneyDto.FIRST_BUDGET).completed()).isFalse();
        assertThat(milestoneOf(dto, FinancialJourneyDto.FIRST_GOAL).completed()).isFalse();
        assertThat(milestoneOf(dto, FinancialJourneyDto.FIRST_GOAL_ACHIEVED).completed()).isFalse();
        assertThat(milestoneOf(dto, FinancialJourneyDto.FIRST_IMPORT).completedAt()).isNull();
    }

    @Test
    void build_reportsTheEarliestImport_notWhicheverTheRepositoryReturnsFirst() {
        // findMetadataByUserIdOrderByImportedAtDesc is ordered DESC -- the milestone must still
        // report the EARLIEST one (the "first" import), not simply the last element of that list,
        // so this deliberately hands the mock its rows already in DESC order like the real query.
        Instant earliest = Instant.parse("2026-06-01T00:00:00Z");
        Instant latest = Instant.parse("2026-08-01T00:00:00Z");
        StatementImportRepository.StatementMetadata latestMeta = importedAt(latest);
        StatementImportRepository.StatementMetadata earliestMeta = importedAt(earliest);
        when(statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(userId))
                .thenReturn(List.of(latestMeta, earliestMeta));

        FinancialJourneyDto dto = service.build(userId);

        assertThat(milestoneOf(dto, FinancialJourneyDto.FIRST_IMPORT).completedAt()).isEqualTo(earliest);
    }

    @Test
    void build_goalAchieved_onlyCountsGoalsThatActuallyHaveACompletedAt() {
        Goal notYetAchieved = goalCreatedAt(Instant.parse("2026-08-01T00:00:00Z"));
        Goal achieved = goalCreatedAt(Instant.parse("2026-08-02T00:00:00Z"));
        achieved.setCompletedAt(Instant.parse("2026-08-05T00:00:00Z"));
        when(goalRepository.findByUserId(userId)).thenReturn(List.of(notYetAchieved, achieved));

        FinancialJourneyDto dto = service.build(userId);

        assertThat(milestoneOf(dto, FinancialJourneyDto.FIRST_GOAL).completed()).isTrue();
        assertThat(milestoneOf(dto, FinancialJourneyDto.FIRST_GOAL_ACHIEVED).completedAt())
                .isEqualTo(Instant.parse("2026-08-05T00:00:00Z"));
    }
}
