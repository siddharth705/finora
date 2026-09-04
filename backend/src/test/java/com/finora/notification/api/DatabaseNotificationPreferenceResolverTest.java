package com.finora.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.entity.User;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPreference;
import com.finora.notification.repository.NotificationPreferenceRepository;
import com.finora.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatabaseNotificationPreferenceResolverTest {

    private NotificationPreferenceRepository repository;
    private UserRepository userRepository;
    private DatabaseNotificationPreferenceResolver resolver;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(NotificationPreferenceRepository.class);
        userRepository = mock(UserRepository.class);
        resolver = new DatabaseNotificationPreferenceResolver(repository, userRepository);
    }

    // --- SECURITY: forcibly on, regardless of preference row or account status ---

    @Test
    void securityNotificationsAreAlwaysEnabled() {
        boolean enabled = resolver.isEnabled(userId, NotificationCategory.SECURITY,
                NotificationChannel.EMAIL);

        assertThat(enabled).isTrue();
        // Not even consulted -- security alerts are not silenceable.
        verify(repository, never()).findByUserIdAndCategoryAndChannel(any(), any(), any());
        verify(userRepository, never()).findById(any());
    }

    @Test
    void securityNotificationsReachADeactivatedUser() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithStatus(User.STATUS_DEACTIVATED)));

        assertThat(resolver.isEnabled(userId, NotificationCategory.SECURITY, NotificationChannel.EMAIL))
                .isTrue();
    }

    @Test
    void securityNotificationsReachASuspendedUser() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithStatus(User.STATUS_SUSPENDED)));

        assertThat(resolver.isEnabled(userId, NotificationCategory.SECURITY, NotificationChannel.EMAIL))
                .isTrue();
    }

    // --- FINANCIAL: opt-out preference, subject to account-status suppression ---

    @Test
    void financialNotificationsDefaultToEnabledWhenNoPreferenceRowExists() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithStatus(User.STATUS_ACTIVE)));
        when(repository.findByUserIdAndCategoryAndChannel(any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThat(resolver.isEnabled(userId, NotificationCategory.FINANCIAL,
                NotificationChannel.EMAIL)).isTrue();
    }

    @Test
    void financialNotificationsRespectAnExplicitOptOut() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithStatus(User.STATUS_ACTIVE)));
        when(repository.findByUserIdAndCategoryAndChannel(userId, NotificationCategory.FINANCIAL,
                NotificationChannel.SMS))
                .thenReturn(Optional.of(NotificationPreference.of(userId,
                        NotificationCategory.FINANCIAL, NotificationChannel.SMS, false)));

        assertThat(resolver.isEnabled(userId, NotificationCategory.FINANCIAL,
                NotificationChannel.SMS)).isFalse();
    }

    @Test
    void financialNotificationsAreSuppressedForADeactivatedUser() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithStatus(User.STATUS_DEACTIVATED)));
        // Even an explicit opt-in must not override a stepped-away account.
        when(repository.findByUserIdAndCategoryAndChannel(userId, NotificationCategory.FINANCIAL,
                NotificationChannel.EMAIL))
                .thenReturn(Optional.of(NotificationPreference.of(userId,
                        NotificationCategory.FINANCIAL, NotificationChannel.EMAIL, true)));

        assertThat(resolver.isEnabled(userId, NotificationCategory.FINANCIAL,
                NotificationChannel.EMAIL)).isFalse();
        // Status suppression short-circuits -- the preference row is never even consulted.
        verify(repository, never()).findByUserIdAndCategoryAndChannel(any(), any(), any());
    }

    @Test
    void financialNotificationsAreSuppressedForASuspendedUser() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithStatus(User.STATUS_SUSPENDED)));

        assertThat(resolver.isEnabled(userId, NotificationCategory.FINANCIAL,
                NotificationChannel.EMAIL)).isFalse();
    }

    @Test
    void financialNotificationsAreSuppressedForAUserPendingDeletion() {
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(userWithStatus(User.STATUS_PENDING_DELETION)));

        assertThat(resolver.isEnabled(userId, NotificationCategory.FINANCIAL,
                NotificationChannel.EMAIL)).isFalse();
    }

    @Test
    void financialNotificationsAreNotSuppressedForADeletedUser_thatIsHandledByProvidersInstead() {
        // STATUS_DELETED is deliberately not checked here -- EmailNotificationProvider and
        // SmsNotificationProvider already refuse via user.isDeleted(). Duplicating it here would
        // just be a second place for the two rules to drift apart.
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithStatus(User.STATUS_DELETED)));
        when(repository.findByUserIdAndCategoryAndChannel(any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThat(resolver.isEnabled(userId, NotificationCategory.FINANCIAL,
                NotificationChannel.EMAIL)).isTrue();
    }

    // --- MARKETING: opt-in preference, subject to the same account-status suppression ---

    @Test
    void marketingNotificationsDefaultToDisabled() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithStatus(User.STATUS_ACTIVE)));
        when(repository.findByUserIdAndCategoryAndChannel(any(), any(), any()))
                .thenReturn(Optional.empty());

        // Opt-in, not opt-out -- no send logic exists for MARKETING in v1 regardless.
        assertThat(resolver.isEnabled(userId, NotificationCategory.MARKETING,
                NotificationChannel.EMAIL)).isFalse();
    }

    @Test
    void marketingNotificationsAreSuppressedForADeactivatedUserEvenWithAnOptIn() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithStatus(User.STATUS_DEACTIVATED)));
        when(repository.findByUserIdAndCategoryAndChannel(userId, NotificationCategory.MARKETING,
                NotificationChannel.EMAIL))
                .thenReturn(Optional.of(NotificationPreference.of(userId,
                        NotificationCategory.MARKETING, NotificationChannel.EMAIL, true)));

        assertThat(resolver.isEnabled(userId, NotificationCategory.MARKETING,
                NotificationChannel.EMAIL)).isFalse();
    }

    // --- Resolution never throws into the caller ---

    @Test
    void aPreferenceLookupFailureFallsBackToTheFinancialDefaultInsteadOfThrowing() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithStatus(User.STATUS_ACTIVE)));
        when(repository.findByUserIdAndCategoryAndChannel(any(), any(), any()))
                .thenThrow(new RuntimeException("db unavailable"));

        assertThat(resolver.isEnabled(userId, NotificationCategory.FINANCIAL,
                NotificationChannel.EMAIL)).isTrue();
    }

    @Test
    void aPreferenceLookupFailureFallsBackToTheMarketingDefaultInsteadOfThrowing() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithStatus(User.STATUS_ACTIVE)));
        when(repository.findByUserIdAndCategoryAndChannel(any(), any(), any()))
                .thenThrow(new RuntimeException("db unavailable"));

        assertThat(resolver.isEnabled(userId, NotificationCategory.MARKETING,
                NotificationChannel.EMAIL)).isFalse();
    }

    @Test
    void aUserStatusLookupFailureFallsBackToTheCategoryDefaultInsteadOfThrowing() {
        when(userRepository.findById(userId)).thenThrow(new RuntimeException("db unavailable"));

        assertThat(resolver.isEnabled(userId, NotificationCategory.FINANCIAL,
                NotificationChannel.EMAIL)).isTrue();
    }

    // --- A missing user row is an anomaly, not evidence of a stepped-away account ---

    @Test
    void financialNotificationsAreNotSuppressedWhenTheUserRowIsMissing() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        when(repository.findByUserIdAndCategoryAndChannel(any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThat(resolver.isEnabled(userId, NotificationCategory.FINANCIAL,
                NotificationChannel.EMAIL)).isTrue();
    }

    private static User userWithStatus(String status) {
        User user = new User();
        user.setStatus(status);
        return user;
    }
}
