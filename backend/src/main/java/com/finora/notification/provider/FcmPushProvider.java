package com.finora.notification.provider;

import com.finora.notification.api.ActiveDeviceToken;
import com.finora.notification.api.DeviceTokenService;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationChannel;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers to every registered device for a user, succeeding if any one accepts.
 *
 * <p>Not a {@code @Component}: PushConfig selects between this and NoOpPushProvider by a runtime
 * credential check, matching EmailConfig/SmsConfig. This codebase does not use {@code @Profile}
 * for provider selection.
 *
 * <h2>iOS delivery: FCM's APNs relay, not a direct Apple client (Ruling O, Task 11)</h2>
 *
 * <p>{@link ActiveDeviceToken#platform()} comes back from {@link DeviceTokenService#activeTokensFor}
 * and is deliberately NOT branched on here -- every registered token, Android or iOS, is sent
 * through the single {@link FcmMessageSender} seam. This is a considered decision, not a deferred
 * one: the mobile app registers iOS devices through {@code @react-native-firebase/messaging}
 * (Task 14), which hands the client an FCM registration token, the same token shape ANDROID
 * already gets -- never a raw APNs device token. The project's Firebase console has the APNs
 * Authentication Keys (Development and Production, Key ID {@code 656Q43Q4GD}, Team ID
 * {@code A28NNDT4LN}) uploaded for the iOS app {@code com.fynora.app}, so Firebase relays every
 * send to Apple on our behalf once it reaches FCM; this backend never talks to Apple directly and
 * holds no APNs credential of its own. A second, direct-to-APNs client would be a second
 * credential path and a second failure mode for a token type ({@code IOS} raw APNs device tokens)
 * this system never actually stores -- see {@link FirebaseFcmMessageSender} for the send call
 * itself, including why it needs no per-platform branch either, and its own note on why it sets no
 * {@code ApnsConfig} today.
 *
 * <p>{@code platform} is retained on {@link ActiveDeviceToken} (and on
 * {@code DeviceToken}/{@code ActiveDeviceToken} generally) for diagnostics, per-platform delivery
 * metrics, and as the field a future direct-APNs path would dispatch on -- it is not, and must not
 * be treated as, evidence that iOS support is missing here. If a genuine reason to branch on it
 * ever shows up (a direct APNs client, per-platform throttling), add that branch deliberately with
 * its own credential/config surface; do not infer routing from this field's mere existence.
 *
 * <h2>Dead tokens are revoked, transient failures are not</h2>
 *
 * <p>{@link FcmMessageSender#send} reports {@link FcmSendOutcome#TOKEN_DEAD} when FCM says a token
 * will never work again (app uninstalled, token expired/rotated). This class revokes exactly that
 * token via {@link DeviceTokenService#revoke} so it stops being retried on every future
 * notification -- without this, a single-device user who uninstalled the app would burn every
 * retry and dead-letter on every subsequent push forever, with no way back. A
 * {@link FcmSendOutcome#TRANSIENT_FAILURE} (an outage, a rate limit, or anything not confidently
 * permanent -- see {@link FirebaseFcmMessageSender}'s mapping) is never revoked: that would
 * silently and permanently disable a device that never actually rejected the token.
 *
 * <h2>Security: no token ever reaches a ChannelSendResult</h2>
 *
 * <p>{@link ActiveDeviceToken} is a record with a {@code token} component, so its default
 * {@code toString()} prints the raw device token -- an {@code ActiveDeviceToken} or a
 * {@code List<ActiveDeviceToken>} must never be logged or interpolated into a result. This class
 * only ever extracts {@link ActiveDeviceToken#token()} to pass to {@link FcmMessageSender#send}
 * and {@link DeviceTokenService#revoke}, and only ever reports counts back in
 * {@link ChannelSendResult#detail()} -- never a token, and never an SDK exception's message
 * (which can itself echo the token FCM rejected; see {@link FirebaseFcmMessageSender} for where
 * that boundary is actually enforced against the live SDK).
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
     * that seam must not abort the remaining devices in this batch. A send that throws is treated
     * the same as {@link FcmSendOutcome#TRANSIENT_FAILURE} -- not accepted, not revoked -- since an
     * unexpected exception carries no evidence the token itself is the problem.
     */
    private int sendToEach(List<ActiveDeviceToken> tokens, Notification notification) {
        int accepted = 0;
        for (ActiveDeviceToken deviceToken : tokens) {
            FcmSendOutcome outcome;
            try {
                outcome = messageSender.send(deviceToken.token(), notification.getTitle(),
                        notification.getMessage());
            } catch (RuntimeException e) {
                // Never log the exception itself or its message -- only the class name. A real
                // FcmMessageSender's underlying exception can carry the rejected token in its
                // description; this is the last line of defense against that reaching a log line.
                log.warn("A device send threw ({}) for notification {}; treating it as rejected",
                        e.getClass().getSimpleName(), notification.getId());
                continue;
            }
            if (outcome == FcmSendOutcome.ACCEPTED) {
                accepted++;
            } else if (outcome == FcmSendOutcome.TOKEN_DEAD) {
                revokeDeadToken(notification.getUserId(), deviceToken.token(), notification.getId());
            }
            // TRANSIENT_FAILURE: not accepted, not revoked -- retried on the notification's own
            // backoff the next time this user gets a push.
        }
        return accepted;
    }

    /**
     * Revoking must not be able to take down delivery to this user's remaining devices -- same
     * containment discipline as {@link #sendToEach}'s own per-device try/catch. A revoke failure
     * (e.g. a transient DB error) just means this dead token gets retried -- and re-discovered as
     * dead -- on a future send; that is a wasted call, not a correctness problem, and strictly
     * better than losing delivery to a device that is still live.
     */
    private void revokeDeadToken(UUID userId, String token, UUID notificationId) {
        try {
            deviceTokenService.revoke(userId, token);
        } catch (RuntimeException e) {
            // Class name only -- never the token, never the exception's message.
            log.warn("Could not revoke a dead device token found while sending notification {} ({})",
                    notificationId, e.getClass().getSimpleName());
        }
    }
}
