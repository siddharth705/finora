package com.finora.onboarding;

import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.goals.GoalRepository;
import com.finora.repository.BudgetRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OnboardingServiceTest {

    private UserRepository userRepository;
    private UserFinancialFocusRepository focusRepository;
    private UserChecklistEventRepository checklistEventRepository;
    private ImportJobRepository importJobRepository;
    private BudgetRepository budgetRepository;
    private GoalRepository goalRepository;
    private OnboardingService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        focusRepository = mock(UserFinancialFocusRepository.class);
        checklistEventRepository = mock(UserChecklistEventRepository.class);
        importJobRepository = mock(ImportJobRepository.class);
        budgetRepository = mock(BudgetRepository.class);
        goalRepository = mock(GoalRepository.class);
        service = new OnboardingService(userRepository, focusRepository, checklistEventRepository,
                importJobRepository, budgetRepository, goalRepository);
        when(userRepository.findById(userId)).thenReturn(Optional.of(new User()));
        when(importJobRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any())).thenReturn(List.of());
        when(budgetRepository.findByUserId(userId)).thenReturn(List.of());
        when(goalRepository.findByUserId(userId)).thenReturn(List.of());
        when(checklistEventRepository.findByUserId(userId)).thenReturn(List.of());
    }

    @Test
    void setFinancialFocusRejectsAnUnknownKey() {
        assertThatThrownBy(() -> service.setFinancialFocus(userId, List.of("NOT_A_REAL_KEY")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void setFinancialFocusReplacesTheExistingSet() {
        service.setFinancialFocus(userId, List.of("TRACK_SPENDING", "REDUCE_DEBT"));

        verify(focusRepository).deleteByUserId(userId);
        verify(focusRepository, times(2)).save(any(UserFinancialFocus.class));
    }

    @Test
    void setFinancialFocusAcceptsAnEmptyListAsASkip() {
        service.setFinancialFocus(userId, List.of());

        verify(focusRepository).deleteByUserId(userId);
        verify(focusRepository, never()).save(any());
    }

    @Test
    void getStatusReportsOnboardingIncompleteAndTheStoredFocusSet() {
        User user = new User();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(focusRepository.findByUserId(userId)).thenReturn(
                List.of(new UserFinancialFocus(userId, "TRACK_SPENDING")));

        OnboardingDto.StatusResponse status = service.getStatus(userId);

        assertThat(status.onboardingCompleted()).isFalse();
        assertThat(status.financialFocus()).containsExactly("TRACK_SPENDING");
    }

    @Test
    void getStatusReportsOnboardingCompleteWhenSet() {
        User user = new User();
        user.setOnboardingCompletedAt(java.time.Instant.now());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(focusRepository.findByUserId(userId)).thenReturn(List.of());

        assertThat(service.getStatus(userId).onboardingCompleted()).isTrue();
    }

    @Test
    void completeSetsOnboardingCompletedAtOnlyOnce() {
        User user = new User();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        service.complete(userId);
        var firstCompletedAt = user.getOnboardingCompletedAt();
        service.complete(userId);

        assertThat(user.getOnboardingCompletedAt()).isEqualTo(firstCompletedAt);
    }

    @Test
    void resetClearsOnboardingCompletedAt() {
        User user = new User();
        user.setOnboardingCompletedAt(java.time.Instant.now());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        service.reset(userId);

        assertThat(user.getOnboardingCompletedAt()).isNull();
    }

    @Test
    void checklistReportsAllSixItemsInOrderWithNoneCompletedForABrandNewUser() {
        OnboardingDto.ChecklistResponse checklist = service.getChecklist(userId);

        assertThat(checklist.items()).extracting(OnboardingDto.ChecklistItemDto::key)
                .containsExactly("COMPLETE_PROFILE", "IMPORT_STATEMENT", "REVIEW_TRANSACTIONS",
                        "CREATE_BUDGET", "CREATE_GOAL", "VIEW_INSIGHTS");
        assertThat(checklist.completedCount()).isZero();
        assertThat(checklist.totalCount()).isEqualTo(6);
    }

    @Test
    void completeProfileRequiresNameAndVerifiedEmailAndEitherNoPhoneOrAVerifiedOne() {
        User user = new User();
        user.setFullName("Ada Lovelace");
        user.setEmailVerified(true);
        user.setPhoneNumber(null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        boolean profileComplete = service.getChecklist(userId).items().stream()
                .filter(i -> i.key().equals("COMPLETE_PROFILE")).findFirst().orElseThrow().completed();

        assertThat(profileComplete).isTrue();
    }

    @Test
    void completeProfileIsFalseWhenPhoneIsOnFileButUnverified() {
        User user = new User();
        user.setFullName("Ada Lovelace");
        user.setEmailVerified(true);
        user.setPhoneNumber("+911234567890"); // synthetic-ok: fixture, not a real number
        user.setPhoneVerified(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        boolean profileComplete = service.getChecklist(userId).items().stream()
                .filter(i -> i.key().equals("COMPLETE_PROFILE")).findFirst().orElseThrow().completed();

        assertThat(profileComplete).isFalse();
    }

    @Test
    void importStatementCompletedReflectsWhetherAnImportJobExists() {
        when(importJobRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any()))
                .thenReturn(List.of(mock(com.finora.entity.ImportJob.class)));

        boolean completed = service.getChecklist(userId).items().stream()
                .filter(i -> i.key().equals("IMPORT_STATEMENT")).findFirst().orElseThrow().completed();

        assertThat(completed).isTrue();
    }

    @Test
    void createBudgetAndCreateGoalReflectExistingRows() {
        when(budgetRepository.findByUserId(userId)).thenReturn(List.of(mock(com.finora.entity.Budget.class)));
        when(goalRepository.findByUserId(userId)).thenReturn(List.of(mock(com.finora.goals.Goal.class)));

        List<OnboardingDto.ChecklistItemDto> items = service.getChecklist(userId).items();
        boolean budgetDone = items.stream().filter(i -> i.key().equals("CREATE_BUDGET")).findFirst().orElseThrow().completed();
        boolean goalDone = items.stream().filter(i -> i.key().equals("CREATE_GOAL")).findFirst().orElseThrow().completed();

        assertThat(budgetDone).isTrue();
        assertThat(goalDone).isTrue();
    }

    @Test
    void reviewTransactionsAndViewInsightsReflectExplicitEvents() {
        when(checklistEventRepository.findByUserId(userId)).thenReturn(
                List.of(new UserChecklistEvent(userId, "VIEW_INSIGHTS")));

        List<OnboardingDto.ChecklistItemDto> items = service.getChecklist(userId).items();
        boolean reviewDone = items.stream().filter(i -> i.key().equals("REVIEW_TRANSACTIONS")).findFirst().orElseThrow().completed();
        boolean insightsDone = items.stream().filter(i -> i.key().equals("VIEW_INSIGHTS")).findFirst().orElseThrow().completed();

        assertThat(reviewDone).isFalse();
        assertThat(insightsDone).isTrue();
    }
}
