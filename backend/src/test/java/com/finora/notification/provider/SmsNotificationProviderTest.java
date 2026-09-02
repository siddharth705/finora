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
import com.finora.service.ProviderType;
import com.finora.service.SmsProvider;
import com.finora.service.SmsResult;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SmsNotificationProviderTest {

    private SmsProvider smsProvider;
    private UserRepository userRepository;
    private SmsNotificationProvider provider;

    @BeforeEach
    void setUp() {
        smsProvider = mock(SmsProvider.class);
        userRepository = mock(UserRepository.class);
        provider = new SmsNotificationProvider(smsProvider, userRepository);
        when(smsProvider.isConfigured()).thenReturn(true);
    }

    private Notification notification() {
        return Notification.create(UUID.randomUUID(), NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL, NotificationChannel.SMS,
                NotificationPriority.NORMAL, "K1:SMS", "Statement ready", "Your import finished.",
                Instant.now());
    }

    @Test
    void channel_isSms() {
        assertThat(provider.channel()).isEqualTo(NotificationChannel.SMS);
    }

    @Test
    void send_failsWithoutLeakingThePhoneNumberWhenNoneIsOnFile() {
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).doesNotContain("+");
        // Fix wave, IMPORTANT 4: retrying cannot make a user row that doesn't exist appear.
        assertThat(result.permanent()).isTrue();
    }

    @Test
    void send_neverThrows() {
        when(userRepository.findById(any())).thenThrow(new RuntimeException("db down"));

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        // A transient infrastructure exception carries no evidence this is permanently
        // undeliverable -- must stay on the ordinary retry path.
        assertThat(result.permanent()).isFalse();
    }

    @Test
    void send_failsWithoutLeakingWhenTheUserHasABlankPhoneNumber() {
        User user = new User();
        user.setPhoneNumber("");
        user.setStatus(User.STATUS_ACTIVE);
        when(userRepository.findById(any())).thenReturn(Optional.of(user));

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).doesNotContain("+");
        assertThat(result.permanent()).isTrue();
        verify(smsProvider, never()).send(any());
    }

    @Test
    void send_failsWithoutLeakingWhenTheUserHasANullPhoneNumber() {
        User user = new User();
        user.setPhoneNumber(null);
        user.setStatus(User.STATUS_ACTIVE);
        when(userRepository.findById(any())).thenReturn(Optional.of(user));

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).doesNotContain("+");
        assertThat(result.permanent()).isTrue();
        verify(smsProvider, never()).send(any());
    }

    @Test
    void send_refusesDeliveryToAPurgedUserEvenWhenAPhoneNumberIsStillPresent() {
        User user = new User();
        // AccountPurgeSweepService.purgeOne() happens to null phoneNumber on purge today, which
        // would make the blank check above catch this by coincidence. Setting a real-looking
        // number here (rather than null) proves the status check itself refuses delivery, not
        // just today's incidental nulling.
        user.setPhoneNumber("+919000000705");
        user.setStatus(User.STATUS_DELETED);
        when(userRepository.findById(any())).thenReturn(Optional.of(user));

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).doesNotContain("+");
        // A purge is not undone by waiting and retrying.
        assertThat(result.permanent()).isTrue();
        verify(smsProvider, never()).send(any());
    }

    /** The flip side: a real provider call that itself reports failure is not confidently
     *  permanent (could be a transient outage on the provider's side) and must stay retryable. */
    @Test
    void send_aProviderReportedFailureIsNotPermanent() {
        User user = new User();
        user.setPhoneNumber("+919000000705");
        user.setStatus(User.STATUS_ACTIVE);
        when(userRepository.findById(any())).thenReturn(Optional.of(user));
        when(smsProvider.send(any()))
                .thenReturn(SmsResult.failure(ProviderType.TWO_FACTOR, "provider outage"));

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.permanent()).isFalse();
    }
}
