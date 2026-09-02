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
