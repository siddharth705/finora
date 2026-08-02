package com.finora.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Architecture change: phone verification (registration, password reset, authenticated password
 * change) now belongs to Firebase Phone Authentication, not this codebase's own OTP generation/
 * storage/verification -- see the (now removed) OtpService/PhoneOtp/SmsService's git history for
 * what this replaces. The frontend's Firebase Web SDK handles sending and confirming the OTP
 * directly against Firebase; all the backend ever sees is the resulting Firebase ID token, which
 * this initializes the Admin SDK to verify (see PhoneVerificationProvider).
 *
 * Initialized from Application Default Credentials -- i.e. the standard
 * {@code GOOGLE_APPLICATION_CREDENTIALS} environment variable pointing at a service-account JSON
 * key file (Firebase Console -> Project Settings -> Service Accounts -> Generate new private
 * key), the same convention every Google Cloud/Firebase Admin SDK integration uses. That key file
 * is a real secret -- never commit it, never put its contents directly in an env var value, only
 * ever a file path the process can read.
 *
 * Returns {@code null} (no bean registered) rather than failing application startup when
 * unset/invalid -- same dev-convenience gap every other external integration in this codebase has
 * (missing RESEND_API_KEY, missing SMS credentials before this change). ProductionConfigValidator
 * is what actually stops this from silently reaching production unconfigured;
 * PhoneVerificationProvider.isConfigured() is what everything else checks at runtime.
 *
 * <p>Deliberately returns {@code FirebaseApp}, not {@code Optional<FirebaseApp>} -- Spring's
 * constructor/field injection special-cases any injection point declared as {@code Optional<X>}
 * to mean "optionally autowire a plain bean of type X", never "find the registered bean whose own
 * type happens to be Optional<X>". A {@code @Bean} method returning {@code Optional<FirebaseApp>}
 * registers a bean of type {@code Optional<FirebaseApp>}, which that mechanism never matches --
 * every {@code Optional<FirebaseApp>} consumer silently receives {@link Optional#empty()}
 * regardless of whether this method actually returned a real app, exactly the bug this class
 * shipped with and {@link com.finora.architecture.NoOptionalBeanReturnTypeTest} now guards
 * against. Returning a nullable {@code FirebaseApp} instead is the correct pattern: Spring simply
 * registers no bean when a {@code @Bean} method returns {@code null}, so downstream
 * {@code Optional<FirebaseApp>} injection points resolve exactly as intended.
 */
@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Bean
    public FirebaseApp firebaseApp() {
        if (!FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.getInstance();
        }
        try {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.getApplicationDefault())
                    .build();
            return FirebaseApp.initializeApp(options);
        } catch (IOException e) {
            log.warn("Firebase Admin SDK not initialized -- GOOGLE_APPLICATION_CREDENTIALS is unset "
                    + "or invalid. Phone verification will fail until this is configured: {}", e.getMessage());
            return null;
        }
    }
}
