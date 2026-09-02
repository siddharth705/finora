package com.finora.notification.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.UserRepository;
import com.finora.service.SmsProvider;
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
    }

    @Test
    void send_neverThrows() {
        when(userRepository.findById(any())).thenThrow(new RuntimeException("db down"));

        assertThat(provider.send(notification()).success()).isFalse();
    }
}
