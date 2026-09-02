package com.finora.notification.provider;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
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
 * <h2>Security: an FCM error can echo the token; it must never leave this class</h2>
 *
 * <p>{@link #send} returns only a {@code boolean} -- deliberately not a result object carrying a
 * message -- so there is no channel for {@link FirebaseMessagingException#getMessage()} (which,
 * for some FCM error responses, echoes the rejected registration token back in its description)
 * to reach {@link FcmPushProvider} or the {@code ChannelSendResult} it builds, which is persisted
 * to {@code notification_logs} and read by admins. The only thing this class logs on failure is
 * the SDK's {@link com.google.firebase.messaging.MessagingErrorCode} -- a fixed enum with no
 * token material -- never {@code getMessage()}, and never the {@code deviceToken} argument.
 */
public class FirebaseFcmMessageSender implements FcmMessageSender {

    private static final Logger log = LoggerFactory.getLogger(FirebaseFcmMessageSender.class);

    private final FirebaseMessaging messaging;

    public FirebaseFcmMessageSender(FirebaseApp firebaseApp) {
        this.messaging = FirebaseMessaging.getInstance(firebaseApp);
    }

    @Override
    public boolean send(String deviceToken, String title, String body) {
        try {
            // Message.builder()...build() is inside the try, not just messaging.send() -- an
            // unexpected IllegalArgumentException from a malformed token/title/body must be caught
            // by the same "must not throw" guarantee as a send failure, not escape from above it.
            Message message = Message.builder()
                    .setToken(deviceToken)
                    .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                    .build();
            messaging.send(message);
            return true;
        } catch (FirebaseMessagingException e) {
            // Error code only (e.g. UNREGISTERED, INVALID_ARGUMENT) -- never getMessage(), which
            // can include the rejected token in its description.
            log.warn("FCM rejected a device token: {}", e.getMessagingErrorCode());
            return false;
        } catch (RuntimeException e) {
            // Defensive: FcmMessageSender's contract is "must not throw." Class name only, for the
            // same reason as above -- an unexpected SDK exception's message is not trusted either.
            log.warn("Unexpected error sending an FCM push: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
