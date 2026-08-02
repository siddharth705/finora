package com.finora.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Optional;

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
 * Returns {@link Optional#empty()} rather than failing application startup when unset/invalid --
 * same dev-convenience gap every other external integration in this codebase has (missing
 * RESEND_API_KEY, missing SMS credentials before this change). ProductionConfigValidator is what
 * actually stops this from silently reaching production unconfigured;
 * PhoneVerificationProvider.isConfigured() is what everything else checks at runtime.
 */
@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Bean
    public Optional<FirebaseApp> firebaseApp() {
        if (!FirebaseApp.getApps().isEmpty()) {
            return Optional.of(FirebaseApp.getInstance());
        }
        try {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.getApplicationDefault())
                    .build();
            return Optional.of(FirebaseApp.initializeApp(options));
        } catch (IOException e) {
            log.warn("Firebase Admin SDK not initialized -- GOOGLE_APPLICATION_CREDENTIALS is unset "
                    + "or invalid. Phone verification will fail until this is configured: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
