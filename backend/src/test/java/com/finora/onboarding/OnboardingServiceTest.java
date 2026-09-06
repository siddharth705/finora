package com.finora.onboarding;

import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OnboardingServiceTest {

    private UserRepository userRepository;
    private UserFinancialFocusRepository focusRepository;
    private OnboardingService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        focusRepository = mock(UserFinancialFocusRepository.class);
        service = new OnboardingService(userRepository, focusRepository);
        when(userRepository.findById(userId)).thenReturn(Optional.of(new User()));
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
}
