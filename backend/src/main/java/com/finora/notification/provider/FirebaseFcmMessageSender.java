package com.finora.notification.provider;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real {@link FcmMessageSender}, backed by the Firebase Admin SDK's Cloud Messaging client on
 * the same {@link FirebaseApp} {@code FirebaseConfig} already initializes for phone auth.
 *
 * <p>Constructed only by {@code PushConfig.fcmMessageSender}, and only when a {@code FirebaseApp}
 * bean actually exists there -- mirroring {@code FirebaseConfig.firebaseApp()}'s own
 * null-means-unconfigured contract (see that class's doc comment) rather than being a plain
 * {@code @Component}: a {@code @Component} is registered unconditionally by classpath scanning
 * and cannot skip registration the way {@code PushConfig}'s
 * {@code ObjectProvider<FcmMessageSender>.getIfAvailable()} needs a genuinely-absent bean to.
 *
 * <h2>Owner action needed before this can work in production</h2>
 *
 * <p>The existing Firebase service account (configured via {@code GOOGLE_APPLICATION_CREDENTIALS})
 * has so far only been used for phone-auth token verification. Whether it also carries Cloud
 * Messaging (FCM send) permission cannot be confirmed from this repository -- there is no
 * documented record of the service account's IAM roles either way. This class assumes it does;
 * if it does not, every send fails with a {@link FirebaseMessagingException} (logged by error
 * code only, see below) and every push notification dead-letters after retries. Confirm FCM
 * scope in the Firebase/GCP console (or by exercising a real send against a non-prod project)
 * before relying on this in production.
 *
 * <h2>iOS tokens need no branch here (Ruling O, Task 11)</h2>
 *
 * <p>{@link #send} builds one {@link Message} shape for every token, Android or iOS. That is
 * correct, not an oversight: iOS devices in this app register an FCM registration token (via
 * {@code @react-native-firebase/messaging}, Task 14), not a raw APNs device token, so the same
 * {@code Message.builder().setToken(...)} call FCM already routes for Android also routes
 * correctly for iOS -- FCM relays to APNs using the Authentication Keys uploaded in the Firebase
 * console for {@code com.fynora.app}, entirely on Google's side, without this class or
 * {@code PushConfig} holding any APNs credential. See {@link FcmPushProvider}'s class doc for the
 * fuller account of why a direct-to-Apple client was rejected.
 *
 * <h3>Why no {@code ApnsConfig} is set (yet)</h3>
 *
 * <p>{@link Message} supports a per-platform {@code ApnsConfig} (APNs {@code aps} fields --
 * sound, badge, {@code content-available}, {@code apns-priority}) alongside the {@code AndroidConfig}
 * this class also does not set. Left unset, FCM derives the APNs {@code aps.alert.title}/
 * {@code aps.alert.body} from the shared {@link Notification} payload automatically, so the push
 * still arrives and displays on iOS -- the one documented gap is that, without an explicit
 * {@code aps.sound}, iOS delivers the banner silently where Android's default channel plays a
 * sound. That asymmetry is deliberately left alone here rather than patched with an iOS-only
 * {@code ApnsConfig.aps.sound("default")}: this class sets no {@code AndroidConfig} either, so
 * every platform-specific delivery knob (sound, badge, priority) is currently at parity in the
 * sense that none of them are configured anywhere, for any platform. Badge counts specifically
 * cannot be set correctly yet regardless -- there is no unread-count feature behind this provider
 * to compute one from, and shipping a hardcoded/guessed badge value would be worse than none. If
 * per-platform sound/badge/priority tuning is wanted, it should be designed for both platforms
 * together (not iOS alone) as its own follow-up, informed by
 * {@link com.finora.notification.domain.NotificationPriority} rather than guessed here.
 *
 * <h2>Error-code mapping to {@link FcmSendOutcome}</h2>
 *
 * <p>Only {@link MessagingErrorCode#UNREGISTERED} and {@link MessagingErrorCode#INVALID_ARGUMENT}
 * map to {@link FcmSendOutcome#TOKEN_DEAD}:
 * <ul>
 *   <li>{@code UNREGISTERED} is FCM's own documented signal that this exact token will never work
 *       again -- the app was uninstalled, the token expired, or it was rotated by a reinstall.
 *   <li>{@code INVALID_ARGUMENT} is broader in general (FCM can also raise it for a malformed
 *       message), but in this codebase the message shape is fixed -- title/body are always plain
 *       strings rendered by {@code NotificationTemplate}, never user-controlled or variable in
 *       structure -- so the only part of a request that actually varies per call is the token
 *       itself. A malformed/corrupted token is the realistic cause here.
 * </ul>
 *
 * <p>Every other code (including {@code INTERNAL}, {@code UNAVAILABLE}, {@code QUOTA_EXCEEDED},
 * {@code SENDER_ID_MISMATCH}, {@code THIRD_PARTY_AUTH_ERROR}, and an unmapped/{@code null} code)
 * maps to {@link FcmSendOutcome#TRANSIENT_FAILURE}, deliberately conservatively: revoking a live
 * device on an infrastructure-side or ambiguous error would silently and permanently disable a
 * device that never actually rejected the token, which is worse than retrying a dead one a few
 * extra times on the notification's own backoff.
 *
 * <h2>Security: an FCM error can echo the token; it must never leave this class</h2>
 *
 * <p>{@link #send} returns only an {@link FcmSendOutcome} -- deliberately not a result object
 * carrying a message -- so there is no channel for {@link FirebaseMessagingException#getMessage()}
 * (which, for some FCM error responses, echoes the rejected registration token back in its
 * description) to reach {@link FcmPushProvider} or the {@code ChannelSendResult} it builds, which
 * is persisted to {@code notification_logs} and read by admins. The only thing this class logs on
 * failure is the SDK's {@link MessagingErrorCode} -- a fixed enum with no token material -- never
 * {@code getMessage()}, and never the {@code deviceToken} argument.
 */
public class FirebaseFcmMessageSender implements FcmMessageSender {

    private static final Logger log = LoggerFactory.getLogger(FirebaseFcmMessageSender.class);

    private final FirebaseMessaging messaging;

    public FirebaseFcmMessageSender(FirebaseApp firebaseApp) {
        this.messaging = FirebaseMessaging.getInstance(firebaseApp);
    }

    @Override
    public FcmSendOutcome send(String deviceToken, String title, String body) {
        try {
            // Message.builder()...build() is inside the try, not just messaging.send() -- an
            // unexpected IllegalArgumentException from a malformed token/title/body must be caught
            // by the same "must not throw" guarantee as a send failure, not escape from above it.
            Message message = Message.builder()
                    .setToken(deviceToken)
                    .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                    .build();
            messaging.send(message);
            return FcmSendOutcome.ACCEPTED;
        } catch (FirebaseMessagingException e) {
            MessagingErrorCode code = e.getMessagingErrorCode();
            // Error code only (e.g. UNREGISTERED, INVALID_ARGUMENT) -- never getMessage(), which
            // can include the rejected token in its description.
            log.warn("FCM rejected a device token: {}", code);
            if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                return FcmSendOutcome.TOKEN_DEAD;
            }
            return FcmSendOutcome.TRANSIENT_FAILURE;
        } catch (RuntimeException e) {
            // Defensive: FcmMessageSender's contract is "must not throw." Class name only, for the
            // same reason as above -- an unexpected SDK exception's message is not trusted either.
            // Not confidently permanent, so TRANSIENT_FAILURE, never TOKEN_DEAD.
            log.warn("Unexpected error sending an FCM push: {}", e.getClass().getSimpleName());
            return FcmSendOutcome.TRANSIENT_FAILURE;
        }
    }
}
