package com.finora.service;

import com.finora.dto.FinancialJourneyDto;
import com.finora.entity.User;
import com.finora.goals.GoalRepository;
import com.finora.repository.BudgetRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D-25 PR3-B, revised: every milestone but ACCOUNT_CREATED is now a permanent behavioral fact
 * once reached (see {@link FinancialJourneyService}'s own class doc for why), read from a native
 * "earliest ever, regardless of later deletion" query per repository rather than filtered/min'd
 * over a live entity list in this class. What this file used to cover as "picks the earliest, not
 * whichever the repository returns first" and "only counts a goal that actually has a
 * completedAt" is now SQL ({@code MIN(...)}, {@code WHERE completed_at IS NOT NULL}) on the other
 * side of each {@code findEarliest...EverEpochMillis} call -- this covers what IS still this
 * class's own logic: wiring each of the four repository calls to the right milestone, and
 * converting a null epoch-millis result (no such event has ever happened) to an incomplete
 * milestone rather than a bogus 1970-01-01 completedAt.
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
        when(statementImportRepository.findEarliestImportedAtEverEpochMillis(any())).thenReturn(null);
        when(budgetRepository.findEarliestCreatedAtEverEpochMillis(any())).thenReturn(null);
        when(goalRepository.findEarliestCreatedAtEverEpochMillis(any())).thenReturn(null);
        when(goalRepository.findEarliestCompletedAtEverEpochMillis(any())).thenReturn(null);
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
        when(statementImportRepository.findEarliestImportedAtEverEpochMillis(userId))
                .thenReturn(Instant.parse("2026-08-02T00:00:00Z").toEpochMilli());
        when(budgetRepository.findEarliestCreatedAtEverEpochMillis(userId))
                .thenReturn(Instant.parse("2026-08-03T00:00:00Z").toEpochMilli());
        when(goalRepository.findEarliestCreatedAtEverEpochMillis(userId))
                .thenReturn(Instant.parse("2026-08-04T00:00:00Z").toEpochMilli());
        when(goalRepository.findEarliestCompletedAtEverEpochMillis(userId))
                .thenReturn(Instant.parse("2026-08-10T00:00:00Z").toEpochMilli());

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
    void build_reportsFirstImport_fromTheNativeEarliestEverEpochMillisQuery() {
        Instant earliest = Instant.parse("2026-06-01T00:00:00Z");
        when(statementImportRepository.findEarliestImportedAtEverEpochMillis(userId))
                .thenReturn(earliest.toEpochMilli());

        FinancialJourneyDto dto = service.build(userId);

        assertThat(milestoneOf(dto, FinancialJourneyDto.FIRST_IMPORT).completed()).isTrue();
        assertThat(milestoneOf(dto, FinancialJourneyDto.FIRST_IMPORT).completedAt()).isEqualTo(earliest);
    }

    @Test
    void build_goalAchieved_reportsIncomplete_whenNoGoalHasEverBeenAchieved_evenIfFirstGoalIsComplete() {
        // FIRST_GOAL and FIRST_GOAL_ACHIEVED are two independent repository calls (created_at vs.
        // completed_at IS NOT NULL, both filtered in SQL) -- a user with an active, not-yet-achieved
        // goal must show FIRST_GOAL complete and FIRST_GOAL_ACHIEVED still incomplete, not one
        // bleeding into the other.
        when(goalRepository.findEarliestCreatedAtEverEpochMillis(userId))
                .thenReturn(Instant.parse("2026-08-01T00:00:00Z").toEpochMilli());
        when(goalRepository.findEarliestCompletedAtEverEpochMillis(userId)).thenReturn(null);

        FinancialJourneyDto dto = service.build(userId);

        assertThat(milestoneOf(dto, FinancialJourneyDto.FIRST_GOAL).completed()).isTrue();
        assertThat(milestoneOf(dto, FinancialJourneyDto.FIRST_GOAL_ACHIEVED).completed()).isFalse();
        assertThat(milestoneOf(dto, FinancialJourneyDto.FIRST_GOAL_ACHIEVED).completedAt()).isNull();
    }
}
