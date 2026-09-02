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
import com.finora.service.EmailProvider;
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
    }

    @Test
    void send_neverThrowsWhenTheUnderlyingProviderThrows() {
        when(userRepository.findById(any())).thenThrow(new RuntimeException("db down"));

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
    }
}
