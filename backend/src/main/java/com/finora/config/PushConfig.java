package com.finora.config;

import com.finora.notification.api.DeviceTokenService;
import com.finora.notification.provider.FcmMessageSender;
import com.finora.notification.provider.FcmPushProvider;
import com.finora.notification.provider.FirebaseFcmMessageSender;
import com.finora.notification.provider.NoOpPushProvider;
import com.finora.notification.provider.NotificationChannelProvider;
import com.google.firebase.FirebaseApp;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the push provider by a runtime credential check, matching EmailConfig and SmsConfig.
 * Not @Profile -- this codebase selects providers on configuration presence, not on profile.
 *
 * <p>Two {@code @Bean} methods rather than one: {@link #fcmMessageSender} decides whether FCM is
 * usable at all by asking whether a {@code FirebaseApp} bean exists -- the same
 * null-means-no-bean pattern {@code FirebaseConfig.firebaseApp()} itself uses and documents at
 * length, so an unset/invalid {@code GOOGLE_APPLICATION_CREDENTIALS} propagates here the same way
 * it already does to {@code FirebasePhoneVerificationProvider}. {@link #pushNotificationProvider}
 * then reads that decision back through {@code ObjectProvider} (Spring's other blessed
 * "this bean may not exist" injection point, alongside {@code Optional<X>} -- see
 * {@code FirebasePhoneVerificationProvider} for that one in use) rather than re-deriving it, so
 * there is exactly one place that knows how FCM readiness is determined.
 *
 * <p><b>One provider, one credential, both platforms (Ruling O, Task 11).</b> There is
 * deliberately no separate APNs bean here. iOS devices register an FCM registration token (via
 * {@code @react-native-firebase/messaging}, Task 14), and the project's Firebase console already
 * has the APNs Authentication Keys uploaded for {@code com.fynora.app}, so FCM relays to Apple on
 * this app's behalf -- the single {@code FcmMessageSender} this class wires reaches both
 * platforms. Do not add a second {@code @Bean} for an APNs client on the assumption that
 * {@code DeviceToken.platform() == "IOS"} implies one is needed; see
 * {@link com.finora.notification.provider.FcmPushProvider}'s and
 * {@link com.finora.notification.provider.FirebaseFcmMessageSender}'s class docs for the full
 * reasoning.
 */
@Configuration
public class PushConfig {

    @Bean
    public FcmMessageSender fcmMessageSender(Optional<FirebaseApp> firebaseApp) {
        return firebaseApp.map(FirebaseFcmMessageSender::new).orElse(null);
    }

    @Bean
    public NotificationChannelProvider pushNotificationProvider(
            DeviceTokenService deviceTokenService, ObjectProvider<FcmMessageSender> messageSender) {
        FcmMessageSender sender = messageSender.getIfAvailable();
        if (sender != null) {
            return new FcmPushProvider(deviceTokenService, sender);
        }
        // No FirebaseApp bean -- GOOGLE_APPLICATION_CREDENTIALS unset/invalid, same condition
        // FirebasePhoneVerificationProvider.isConfigured() already reports false for.
        return new NoOpPushProvider();
    }
}
