package com.finora.notification.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finora.notification.api.ActiveDeviceToken;
import com.finora.notification.api.DeviceTokenService;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The task-10 brief's own test snippet mocks {@code activeTokensFor} to return
 * {@code List<String>} -- stale against what Task 9 actually shipped.
 * {@link DeviceTokenService#activeTokensFor} returns {@code List<ActiveDeviceToken>} (token +
 * platform, see that record's own doc comment for why), so every fixture below builds
 * {@link ActiveDeviceToken} instances instead of bare strings. The scenarios themselves are the
 * brief's, adapted to the real signature, plus a few this class's own partial-failure guarantee
 * (see {@link FcmPushProvider#send}) needs covered.
 */
class FcmPushProviderTest {

    private DeviceTokenService deviceTokenService;
    private FcmMessageSender messageSender;
    private FcmPushProvider provider;

    @BeforeEach
    void setUp() {
        deviceTokenService = mock(DeviceTokenService.class);
        messageSender = mock(FcmMessageSender.class);
        provider = new FcmPushProvider(deviceTokenService, messageSender);
    }

    private Notification notification() {
        return Notification.create(UUID.randomUUID(), NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL, NotificationChannel.PUSH,
                NotificationPriority.NORMAL, "K1:PUSH", "Statement ready", "Your import finished.",
                Instant.now());
    }

    @Test
    void channel_isPush() {
        assertThat(provider.channel()).isEqualTo(NotificationChannel.PUSH);
    }

    @Test
    void isConfigured_isAlwaysTrue() {
        // PushConfig is what decides real-vs-NoOp on credential presence (see PushConfig /
        // NoOpPushProvider); once this class is constructed at all, it IS the configured provider --
        // matching EmailNotificationProvider/SmsNotificationProvider, whose isConfigured() reports
        // on the driver they wrap rather than re-deciding it themselves.
        assertThat(provider.isConfigured()).isTrue();
    }

    @Test
    void send_failsWhenTheUserHasNoRegisteredDevice() {
        when(deviceTokenService.activeTokensFor(any())).thenReturn(List.of());

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).isEqualTo("no registered device");
    }

    @Test
    void send_succeedsWhenAtLeastOneDeviceAccepts() {
        when(deviceTokenService.activeTokensFor(any())).thenReturn(List.of(
                new ActiveDeviceToken("tokenA", "ANDROID"),
                new ActiveDeviceToken("tokenB", "ANDROID")));
        when(messageSender.send(any(), any(), any())).thenReturn(false).thenReturn(true);

        assertThat(provider.send(notification()).success()).isTrue();
    }

    @Test
    void send_failsWhenEveryDeviceRejects() {
        when(deviceTokenService.activeTokensFor(any())).thenReturn(
                List.of(new ActiveDeviceToken("tokenA", "ANDROID")));
        when(messageSender.send(any(), any(), any())).thenReturn(false);

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).isEqualTo("all 1 devices rejected");
    }

    @Test
    void send_neverLeaksARawTokenIntoTheResultDetail() {
        when(deviceTokenService.activeTokensFor(any())).thenReturn(
                List.of(new ActiveDeviceToken("secret-token-value", "ANDROID")));
        when(messageSender.send(any(), any(), any())).thenReturn(false);

        // The detail is persisted to notification_logs, which admins read.
        assertThat(provider.send(notification()).detail()).doesNotContain("secret-token-value");
    }

    @Test
    void send_deliversToTheRemainingDevicesWhenOneTokenThrows() {
        // FcmMessageSender's contract says "must not throw" -- this proves the provider's own
        // partial-failure guarantee does not merely rely on that promise: three devices, the
        // middle send throws, the third must still be attempted and the overall send must still
        // count as a success because at least one device accepted.
        when(deviceTokenService.activeTokensFor(any())).thenReturn(List.of(
                new ActiveDeviceToken("tokenA", "ANDROID"),
                new ActiveDeviceToken("tokenB", "ANDROID"),
                new ActiveDeviceToken("tokenC", "ANDROID")));
        when(messageSender.send(any(), any(), any()))
                .thenReturn(false)
                .thenThrow(new RuntimeException("unexpected"))
                .thenReturn(true);

        assertThat(provider.send(notification()).success()).isTrue();
    }

    @Test
    void send_neverThrowsWhenDeviceTokenServiceThrows() {
        when(deviceTokenService.activeTokensFor(any())).thenThrow(new RuntimeException("db down"));

        assertThat(provider.send(notification()).success()).isFalse();
    }
}
