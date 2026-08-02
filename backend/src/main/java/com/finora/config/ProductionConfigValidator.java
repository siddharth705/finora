package com.finora.config;

import com.finora.service.PhoneVerificationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Production-readiness pass: every secret in application.yml has a local-dev-convenience default
 * ({@code ${JWT_SECRET:change-this-to-a-long-random-secret-in-your-env-file-min-32-chars}},
 * {@code ${DB_PASSWORD:finora}}) -- exactly right for a friction-free `docker compose up`, but
 * that same convenience means a real deployment that simply forgets to set one of these env vars
 * doesn't fail loudly; it starts up completely normally, using a publicly-known, guessable value
 * to protect real user sessions or a real database. "Graceful startup when environment variables
 * are missing" cuts both ways: missing-and-obvious-crash is fine, missing-and-silently-insecure
 * in production is the actually dangerous failure mode, so this fails loudly and immediately
 * instead. Deliberately does nothing outside the prod profile -- these placeholder defaults are
 * exactly what makes local dev and CI convenient, and must keep working with zero setup there.
 */
@Component
public class ProductionConfigValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigValidator.class);

    private static final String DEFAULT_JWT_SECRET =
            "change-this-to-a-long-random-secret-in-your-env-file-min-32-chars";
    private static final String DEFAULT_DB_PASSWORD = "finora";

    private final Environment environment;
    private final JwtProperties jwtProperties;
    private final EmailProperties emailProperties;
    private final PhoneVerificationProvider phoneVerificationProvider;

    public ProductionConfigValidator(Environment environment, JwtProperties jwtProperties,
                                      EmailProperties emailProperties,
                                      PhoneVerificationProvider phoneVerificationProvider) {
        this.environment = environment;
        this.jwtProperties = jwtProperties;
        this.emailProperties = emailProperties;
        this.phoneVerificationProvider = phoneVerificationProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (!isProd) return;

        StringBuilder problems = new StringBuilder();

        String secret = jwtProperties.getSecret();
        if (secret == null || secret.equals(DEFAULT_JWT_SECRET)) {
            problems.append("- JWT_SECRET is unset or still the placeholder default. ")
                    .append("Set a real random 32+ character value.\n");
        } else if (secret.length() < 32) {
            problems.append("- JWT_SECRET is set but shorter than the 32 characters HS256 requires.\n");
        }

        String dbPassword = environment.getProperty("spring.datasource.password");
        if (DEFAULT_DB_PASSWORD.equals(dbPassword)) {
            problems.append("- DB_PASSWORD is unset or still the local-dev default (\"finora\"). ")
                    .append("Set the real database password.\n");
        }

        // Bug fix: JWT_SECRET/DB_PASSWORD were the only two settings this validator checked, even
        // though EmailConfig has its own silent "convenience default" -- no RESEND_API_KEY falls
        // back to NoOpEmailService, and AuthService.forgotPassword() branches on
        // emailService.isConfigured() to decide whether to actually send the reset email or just
        // return the raw, valid reset link directly in the API response body instead (the same
        // dev-environment convenience CorsConfig's own class doc calls out this validator as
        // existing specifically to catch reaching production). Omitting RESEND_API_KEY from a
        // real deployment -- an easy operator mistake, since every OTHER secret here fails loudly
        // and this one didn't -- turned "forgot password" into a full account-takeover primitive
        // for anyone who knows a victim's email address, no email access required at all.
        if (emailProperties.getApiKey() == null || emailProperties.getApiKey().isBlank()) {
            problems.append("- RESEND_API_KEY is unset. Without it, password-reset links are ")
                    .append("returned directly in the API response instead of emailed -- anyone who ")
                    .append("knows a user's email address could take over their account.\n");
        }
        // GOOGLE_APPLICATION_CREDENTIALS (see FirebaseConfig) selects whether the Firebase Admin
        // SDK can actually verify a Firebase ID token -- isConfigured() is the exact same check
        // PhoneVerificationProvider itself uses before attempting verification, so this
        // can never disagree with what actually happens at runtime. Without it, registration,
        // password reset, and authenticated password change can never complete phone
        // verification at all (every call fails with 503), not a silently-degraded fallback --
        // still worth catching at boot rather than the first real user's first failed request.
        if (!phoneVerificationProvider.isConfigured()) {
            problems.append("- GOOGLE_APPLICATION_CREDENTIALS is unset or invalid. Without it, the Firebase ")
                    .append("Admin SDK can't verify phone numbers, so registration, password reset, and ")
                    .append("password change can never complete their phone-verification step.\n");
        }

        if (!problems.isEmpty()) {
            String message = "Refusing to start with the prod profile active and insecure default "
                    + "configuration still in place:\n" + problems
                    + "These defaults exist for local development convenience only.";
            log.error(message);
            throw new IllegalStateException(message);
        }

        log.info("Production configuration validated: JWT_SECRET and DB_PASSWORD are not using their local-dev defaults.");
    }
}
