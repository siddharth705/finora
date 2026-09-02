package com.finora.notification.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
 * {@code List<String>} and {@code messageSender.send} to return {@code boolean} -- both stale
 * against what this class actually depends on. {@link DeviceTokenService#activeTokensFor} returns
 * {@code List<ActiveDeviceToken>} (token + platform, from Task 9), and {@link FcmMessageSender#send}
 * returns {@link FcmSendOutcome} (from fix round 1 of this task, so a permanently dead token can be
 * distinguished from a merely transient failure and only the former gets revoked). Every fixture
 * below is built against the real signatures.
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

    private Notification notification(UUID userId) {
        return Notification.create(userId, NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL, NotificationChannel.PUSH,
                NotificationPriority.NORMAL, "K1:PUSH", "Statement ready", "Your import finished.",
                Instant.now());
    }

    private Notification notification() {
        return notification(UUID.randomUUID());
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
        when(messageSender.send(any(), any(), any()))
                .thenReturn(FcmSendOutcome.TRANSIENT_FAILURE)
                .thenReturn(FcmSendOutcome.ACCEPTED);

        assertThat(provider.send(notification()).success()).isTrue();
    }

    @Test
    void send_failsWhenEveryDeviceRejects() {
        when(deviceTokenService.activeTokensFor(any())).thenReturn(
                List.of(new ActiveDeviceToken("tokenA", "ANDROID")));
        when(messageSender.send(any(), any(), any())).thenReturn(FcmSendOutcome.TRANSIENT_FAILURE);

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).isEqualTo("all 1 devices rejected");
    }

    @Test
    void send_neverLeaksARawTokenIntoTheResultDetail() {
        when(deviceTokenService.activeTokensFor(any())).thenReturn(
                List.of(new ActiveDeviceToken("secret-token-value", "ANDROID")));
        when(messageSender.send(any(), any(), any())).thenReturn(FcmSendOutcome.TRANSIENT_FAILURE);

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
                .thenReturn(FcmSendOutcome.TRANSIENT_FAILURE)
                .thenThrow(new RuntimeException("unexpected"))
                .thenReturn(FcmSendOutcome.ACCEPTED);

        assertThat(provider.send(notification()).success()).isTrue();
    }

    @Test
    void send_neverThrowsWhenDeviceTokenServiceThrows() {
        when(deviceTokenService.activeTokensFor(any())).thenThrow(new RuntimeException("db down"));

        assertThat(provider.send(notification()).success()).isFalse();
    }

    @Test
    void send_revokesATokenFcmReportsAsDead() {
        UUID userId = UUID.randomUUID();
        when(deviceTokenService.activeTokensFor(userId)).thenReturn(
                List.of(new ActiveDeviceToken("deadToken", "ANDROID")));
        when(messageSender.send(any(), any(), any())).thenReturn(FcmSendOutcome.TOKEN_DEAD);

        provider.send(notification(userId));

        verify(deviceTokenService).revoke(userId, "deadToken");
    }

    @Test
    void send_doesNotRevokeATokenThatFailsTransiently() {
        // This is the test that protects a live device: a transient failure must never be treated
        // as evidence the token is dead.
        UUID userId = UUID.randomUUID();
        when(deviceTokenService.activeTokensFor(userId)).thenReturn(
                List.of(new ActiveDeviceToken("liveToken", "ANDROID")));
        when(messageSender.send(any(), any(), any())).thenReturn(FcmSendOutcome.TRANSIENT_FAILURE);

        provider.send(notification(userId));

        verify(deviceTokenService, never()).revoke(any(), any());
    }

    @Test
    void send_revokesOnlyTheDeadTokenAmongThreeDevices() {
        UUID userId = UUID.randomUUID();
        when(deviceTokenService.activeTokensFor(userId)).thenReturn(List.of(
                new ActiveDeviceToken("tokenA", "ANDROID"),
                new ActiveDeviceToken("tokenB", "ANDROID"),
                new ActiveDeviceToken("tokenC", "ANDROID")));
        when(messageSender.send(eq("tokenA"), any(), any())).thenReturn(FcmSendOutcome.ACCEPTED);
        when(messageSender.send(eq("tokenB"), any(), any())).thenReturn(FcmSendOutcome.TOKEN_DEAD);
        when(messageSender.send(eq("tokenC"), any(), any())).thenReturn(FcmSendOutcome.ACCEPTED);

        ChannelSendResult result = provider.send(notification(userId));

        // The other two devices still received the push.
        assertThat(result.success()).isTrue();
        // Only the dead one was revoked.
        verify(deviceTokenService, times(1)).revoke(any(), any());
        verify(deviceTokenService).revoke(userId, "tokenB");
        verify(deviceTokenService, never()).revoke(userId, "tokenA");
        verify(deviceTokenService, never()).revoke(userId, "tokenC");
    }

    @Test
    void send_deliversToRemainingDevicesWhenRevokeThrows() {
        UUID userId = UUID.randomUUID();
        when(deviceTokenService.activeTokensFor(userId)).thenReturn(List.of(
                new ActiveDeviceToken("deadToken", "ANDROID"),
                new ActiveDeviceToken("liveToken", "ANDROID")));
        when(messageSender.send(eq("deadToken"), any(), any())).thenReturn(FcmSendOutcome.TOKEN_DEAD);
        when(messageSender.send(eq("liveToken"), any(), any())).thenReturn(FcmSendOutcome.ACCEPTED);
        doThrow(new RuntimeException("db down")).when(deviceTokenService).revoke(any(), any());

        ChannelSendResult result = provider.send(notification(userId));

        // The revoke failure for the dead token must not prevent delivery to the live one.
        assertThat(result.success()).isTrue();
        assertThat(result.detail()).isEqualTo("1 of 2 devices accepted");
    }
}
