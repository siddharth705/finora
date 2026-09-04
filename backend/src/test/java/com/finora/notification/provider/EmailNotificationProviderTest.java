package com.finora.notification.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.entity.User;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.UserRepository;
import com.finora.service.EmailProvider;
import com.finora.service.EmailResult;
import com.finora.service.ProviderType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmailNotificationProviderTest {

    private EmailProvider emailProvider;
    private UserRepository userRepository;
    private EmailNotificationProvider provider;

    @BeforeEach
    void setUp() {
        emailProvider = mock(EmailProvider.class);
        userRepository = mock(UserRepository.class);
        provider = new EmailNotificationProvider(emailProvider, userRepository);
        when(emailProvider.isConfigured()).thenReturn(true);
    }

    private Notification notification() {
        return Notification.create(UUID.randomUUID(), NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL, NotificationChannel.EMAIL,
                NotificationPriority.NORMAL, "K1:EMAIL", "Statement ready",
                "We finished importing your statement.", Instant.now());
    }

    @Test
    void channel_isEmail() {
        assertThat(provider.channel()).isEqualTo(NotificationChannel.EMAIL);
    }

    @Test
    void send_failsWhenTheUserHasNoEmailOnFile() {
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).doesNotContain("@");
        // Fix wave, IMPORTANT 4: retrying cannot make a user row that doesn't exist appear.
        assertThat(result.permanent()).isTrue();
    }

    @Test
    void send_neverThrowsWhenTheUnderlyingProviderThrows() {
        when(userRepository.findById(any())).thenThrow(new RuntimeException("db down"));

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        // A transient infrastructure exception carries no evidence this is permanently
        // undeliverable -- must stay on the ordinary retry path.
        assertThat(result.permanent()).isFalse();
    }

    @Test
    void send_failsWithoutLeakingWhenTheUserHasABlankEmail() {
        User user = new User();
        user.setEmail("");
        user.setStatus(User.STATUS_ACTIVE);
        when(userRepository.findById(any())).thenReturn(Optional.of(user));

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).doesNotContain("@");
        assertThat(result.permanent()).isTrue();
        verify(emailProvider, never()).send(any());
    }

    @Test
    void send_failsWithoutLeakingWhenTheUserHasANullEmail() {
        User user = new User();
        user.setEmail(null);
        user.setStatus(User.STATUS_ACTIVE);
        when(userRepository.findById(any())).thenReturn(Optional.of(user));

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).doesNotContain("@");
        assertThat(result.permanent()).isTrue();
        verify(emailProvider, never()).send(any());
    }

    @Test
    void send_refusesDeliveryToAPurgedUserEvenThoughItsSentinelEmailIsNonBlank() {
        User user = new User();
        // AccountPurgeSweepService.purgeOne() overwrites a purged account's email with this
        // synthetic, non-blank sentinel -- a null/blank check alone would miss it.
        user.setEmail("deleted-" + UUID.randomUUID() + "@deleted.finora.invalid");
        user.setStatus(User.STATUS_DELETED);
        when(userRepository.findById(any())).thenReturn(Optional.of(user));

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).doesNotContain("@");
        // A purge is not undone by waiting and retrying.
        assertThat(result.permanent()).isTrue();
        verify(emailProvider, never()).send(any());
    }

    /** The flip side: a real provider call that itself reports failure is not confidently
     *  permanent (could be a transient outage on the provider's side) and must stay retryable. */
    @Test
    void send_aProviderReportedFailureIsNotPermanent() {
        User user = new User();
        user.setEmail("user@example.com");
        user.setStatus(User.STATUS_ACTIVE);
        when(userRepository.findById(any())).thenReturn(Optional.of(user));
        when(emailProvider.send(any()))
                .thenReturn(EmailResult.failure(ProviderType.RESEND, "provider outage"));

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.permanent()).isFalse();
    }
}
