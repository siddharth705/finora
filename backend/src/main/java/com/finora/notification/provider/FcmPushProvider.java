package com.finora.notification.provider;

import com.finora.notification.api.ActiveDeviceToken;
import com.finora.notification.api.DeviceTokenService;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationChannel;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers to every registered device for a user, succeeding if any one accepts.
 *
 * <p>Not a {@code @Component}: PushConfig selects between this and NoOpPushProvider by a runtime
 * credential check, matching EmailConfig/SmsConfig. This codebase does not use {@code @Profile}
 * for provider selection.
 *
 * <p>Task 11 scope, not this one: {@link ActiveDeviceToken#platform()} comes back from
 * {@link DeviceTokenService#activeTokensFor} but is deliberately ignored here -- every registered
 * token (Android or iOS) is sent through FCM today. Routing iOS tokens to APNs instead is Task
 * 11's job; sending to both channels today is a known, accepted gap, not an oversight.
 *
 * <h2>Security: no token ever reaches a ChannelSendResult</h2>
 *
 * <p>{@link ActiveDeviceToken} is a record with a {@code token} component, so its default
 * {@code toString()} prints the raw device token -- an {@code ActiveDeviceToken} or a
 * {@code List<ActiveDeviceToken>} must never be logged or interpolated into a result. This class
 * only ever extracts {@link ActiveDeviceToken#token()} to pass to {@link FcmMessageSender#send},
 * and only ever reports counts back in {@link ChannelSendResult#detail()} -- never a token, and
 * never an SDK exception's message (which can itself echo the token FCM rejected; see
 * {@link FirebaseFcmMessageSender} for where that boundary is actually enforced against the live
 * SDK).
 */
public class FcmPushProvider implements NotificationChannelProvider {

    private static final Logger log = LoggerFactory.getLogger(FcmPushProvider.class);
    private static final String PROVIDER_NAME = "fcm";

    private final DeviceTokenService deviceTokenService;
    private final FcmMessageSender messageSender;

    public FcmPushProvider(DeviceTokenService deviceTokenService, FcmMessageSender messageSender) {
        this.deviceTokenService = deviceTokenService;
        this.messageSender = messageSender;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.PUSH;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public ChannelSendResult send(Notification notification) {
        try {
            List<ActiveDeviceToken> tokens =
                    deviceTokenService.activeTokensFor(notification.getUserId());
            if (tokens.isEmpty()) {
                return ChannelSendResult.failure(PROVIDER_NAME, "no registered device");
            }
            int accepted = sendToEach(tokens, notification);
            // Counts only -- a raw token must never reach the detail, which is persisted to
            // notification_logs and read by admins.
            return accepted > 0
                    ? ChannelSendResult.success(PROVIDER_NAME,
                            accepted + " of " + tokens.size() + " devices accepted")
                    : ChannelSendResult.failure(PROVIDER_NAME,
                            "all " + tokens.size() + " devices rejected");
        } catch (RuntimeException e) {
            log.error("Push notification {} could not be sent", notification.getId(), e);
            return ChannelSendResult.failure(PROVIDER_NAME,
                    "exception: " + e.getClass().getSimpleName());
        }
    }

    /**
     * One rejected or misbehaving device must not stop delivery to the rest: a user with three
     * devices where FCM rejects one (e.g. UNREGISTERED) must still receive the push on the other
     * two. Each send gets its own try/catch rather than relying solely on
     * {@link FcmMessageSender}'s "must not throw" contract -- a bug in a future implementation of
     * that seam must not abort the remaining devices in this batch.
     */
    private int sendToEach(List<ActiveDeviceToken> tokens, Notification notification) {
        int accepted = 0;
        for (ActiveDeviceToken deviceToken : tokens) {
            try {
                if (messageSender.send(deviceToken.token(), notification.getTitle(),
                        notification.getMessage())) {
                    accepted++;
                }
            } catch (RuntimeException e) {
                // Never log the exception itself or its message -- only the class name. A real
                // FcmMessageSender's underlying exception can carry the rejected token in its
                // description; this is the last line of defense against that reaching a log line.
                log.warn("A device send threw ({}) for notification {}; treating it as rejected",
                        e.getClass().getSimpleName(), notification.getId());
            }
        }
        return accepted;
    }
}
