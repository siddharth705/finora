package com.finora.goals;

import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers GoalService.addContribution's floor-at-zero defense-in-depth (see the comment on that
 * method), the cross-user ownership check, create()'s null-vs-positive starting amount handling,
 * and the timezone bug fix (contributedAt used to rely on GoalContribution's bare
 * LocalDate.now() field default, resolving against the server's zone rather than the user's own).
 * Mockito-based unit tests against mocked repositories, matching this codebase's established
 * pattern (MerchantLearningServiceTest, CategorizationServiceTest).
 */
class GoalServiceTest {

    private GoalRepository goalRepository;
    private GoalContributionRepository contributionRepository;
    private UserRepository userRepository;
    private GoalService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final UUID goalId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        goalRepository = mock(GoalRepository.class);
        contributionRepository = mock(GoalContributionRepository.class);
        userRepository = mock(UserRepository.class);
        service = new GoalService(goalRepository, contributionRepository, userRepository);
        when(goalRepository.save(any(Goal.class))).thenAnswer(inv -> inv.getArgument(0));
        // Default: no user found -> safeZoneId falls back to Asia/Kolkata. Individual tests that
        // care about a specific timezone override this.
        when(userRepository.findById(any())).thenReturn(Optional.empty());
    }

    private Goal goalWith(BigDecimal currentAmount) {
        Goal g = new Goal();
        ReflectionTestUtils.setField(g, "id", goalId);
        g.setUserId(userId);
        g.setName("Emergency Fund");
        g.setTargetAmount(new BigDecimal("10000"));
        g.setCurrentAmount(currentAmount);
        return g;
    }

    @Test
    void addContribution_normalPositiveAmount_addsNormally() {
        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goalWith(new BigDecimal("500"))));

        GoalDto result = service.addContribution(userId, goalId, new BigDecimal("200"));

        assertThat(result.currentAmount()).isEqualByComparingTo("700");
    }

    @Test
    void addContribution_negativeAmountLargerThanBalance_floorsAtZeroRatherThanGoingNegative() {
        // ContributionRequest's @DecimalMin(0.01) should already stop a negative amount from
        // reaching here via the controller, but this is the belt-and-braces service-layer floor
        // in case the method is ever called directly, bypassing that validation.
        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goalWith(new BigDecimal("100"))));

        GoalDto result = service.addContribution(userId, goalId, new BigDecimal("-500"));

        assertThat(result.currentAmount()).isEqualByComparingTo("0");
    }

    @Test
    void addContribution_forAGoalBelongingToAnotherUser_throwsForbidden() {
        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goalWith(new BigDecimal("100"))));

        assertThatThrownBy(() -> service.addContribution(otherUserId, goalId, new BigDecimal("50")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("does not belong to you");
    }

    @Test
    void create_withNullStartingAmount_defaultsToZero_andDoesNotRecordAContribution() {
        GoalDto.CreateRequest req = new GoalDto.CreateRequest("Emergency Fund", new BigDecimal("10000"), null, null);

        GoalDto result = service.create(userId, req);

        assertThat(result.currentAmount()).isEqualByComparingTo("0");
        verify(contributionRepository, never()).save(any());
    }

    @Test
    void create_withPositiveStartingAmount_recordsAnInitialContribution() {
        GoalDto.CreateRequest req = new GoalDto.CreateRequest("Emergency Fund", new BigDecimal("10000"), new BigDecimal("500"), null);

        GoalDto result = service.create(userId, req);

        assertThat(result.currentAmount()).isEqualByComparingTo("500");
        verify(contributionRepository).save(any());
    }

    @Test
    void addContribution_stampsContributedAt_inTheUsersOwnTimezone_notTheServersDefault() {
        User user = new User();
        // UTC+14 -- as far ahead of UTC as any real IANA zone gets, so its "today" is essentially
        // guaranteed to differ from LocalDate.now() under the system default zone (almost
        // certainly UTC in CI), making this assertion meaningful rather than coincidentally
        // passing either way.
        user.setTimezone("Pacific/Kiritimati");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(goalRepository.findById(goalId)).thenReturn(Optional.of(goalWith(new BigDecimal("100"))));

        service.addContribution(userId, goalId, new BigDecimal("50"));

        org.mockito.ArgumentCaptor<GoalContribution> captor = org.mockito.ArgumentCaptor.forClass(GoalContribution.class);
        verify(contributionRepository).save(captor.capture());
        assertThat(captor.getValue().getContributedAt()).isEqualTo(LocalDate.now(ZoneId.of("Pacific/Kiritimati")));
    }

    // Bug fix: create()/addContribution() each do two related writes (Goal + GoalContribution)
    // that must commit atomically -- same class of bug BudgetService.upsert() was already fixed
    // for. A Mockito unit test can't exercise real transactional rollback (that needs a live
    // Spring/DB context), so -- matching BudgetServiceTest.upsert_isTransactional()'s own
    // established pattern for this exact situation -- this asserts the annotation is actually
    // present, which is what a reviewer or a future refactor could otherwise silently drop.
    @Test
    void create_isTransactional() throws NoSuchMethodException {
        assertThat(GoalService.class.getMethod("create", UUID.class, GoalDto.CreateRequest.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
    }

    @Test
    void addContribution_isTransactional() throws NoSuchMethodException {
        assertThat(GoalService.class.getMethod("addContribution", UUID.class, UUID.class, BigDecimal.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
    }
}
