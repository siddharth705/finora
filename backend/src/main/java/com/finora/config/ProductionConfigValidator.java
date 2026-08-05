package com.finora.config;

import com.finora.service.PhoneVerificationProvider;
import com.finora.service.SmsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

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

    /**
     * Bug fix: this validator used to compare the secret against {@link #DEFAULT_JWT_SECRET} and
     * nothing else -- but the repository ships TWO placeholder secrets, not one.
     * {@code docker-compose.yml} sets {@code local-dev-secret-change-me-before-any-real-deployment},
     * which is 53 characters and is not that constant, so it passed the equality check and the
     * length check both, silently. The likely path is mundane: copy the compose file to a server,
     * set {@code SPRING_PROFILES_ACTIVE=prod} and real database credentials, and leave
     * {@code JWT_SECRET} alone because it already looks configured. The HS256 signing key is then
     * a constant committed to the repository, and anyone who can read it can mint a token for any
     * user id.
     *
     * <p>The deeper fault is that the guard checked a proxy for the property it cared about: it
     * asked "is the secret THIS string" when it meant "is the secret one nobody outside this
     * deployment could know." Enumerating placeholders is still an enumeration, so the marker
     * check below backs it up -- any secret announcing itself as a placeholder is rejected on that
     * basis alone, whether or not it is one of the two known ones. A real generated secret does
     * not contain "change-me" or "local-dev".
     */
    private static final List<String> KNOWN_PLACEHOLDER_SECRETS = List.of(
            DEFAULT_JWT_SECRET,
            "local-dev-secret-change-me-before-any-real-deployment"
    );

    /** Substrings that only ever appear in a value somebody meant to replace. Matched
     *  case-insensitively against the whole secret. */
    private static final List<String> PLACEHOLDER_MARKERS = List.of(
            "change-me", "changeme", "change-this", "changethis", "local-dev", "localdev",
            "placeholder", "example", "your-secret", "yoursecret", "replace-me", "replaceme",
            "dev-secret", "test-secret", "sample", "dummy", "insecure", "notasecret"
    );

    /** True when the secret is one of the placeholders this repository ships, or announces itself
     *  as a placeholder by its own text. Package-private so {@code ProductionConfigValidatorTest}
     *  can assert both halves directly -- a guard whose failure mode is "silently permits" needs a
     *  test that names the exact values it must reject. */
    static boolean looksLikePlaceholderSecret(String secret) {
        if (secret == null) return true;
        String normalized = secret.trim().toLowerCase();
        if (KNOWN_PLACEHOLDER_SECRETS.stream().anyMatch(p -> p.equalsIgnoreCase(secret.trim()))) return true;
        return PLACEHOLDER_MARKERS.stream().anyMatch(normalized::contains);
    }

    private final Environment environment;
    private final JwtProperties jwtProperties;
    private final EmailProperties emailProperties;
    private final PhoneVerificationProvider phoneVerificationProvider;
    private final SmsProvider smsProvider;

    public ProductionConfigValidator(Environment environment, JwtProperties jwtProperties,
                                      EmailProperties emailProperties,
                                      PhoneVerificationProvider phoneVerificationProvider,
                                      SmsProvider smsProvider) {
        this.environment = environment;
        this.jwtProperties = jwtProperties;
        this.emailProperties = emailProperties;
        this.phoneVerificationProvider = phoneVerificationProvider;
        this.smsProvider = smsProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (!isProd) return;

        StringBuilder problems = new StringBuilder();

        String secret = jwtProperties.getSecret();
        if (looksLikePlaceholderSecret(secret)) {
            problems.append("- JWT_SECRET is unset or still a placeholder value (this repository ships ")
                    .append("more than one, including the docker-compose.yml default). ")
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
        // back to NoOpEmailProvider, and AuthService.forgotPassword() branches on
        // emailProvider.isConfigured() to decide whether to actually send the reset email or just
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

        // Unlike RESEND_API_KEY/GOOGLE_APPLICATION_CREDENTIALS above, TWO_FACTOR_API_KEY is
        // deliberately NOT a hard boot-time requirement -- see SmsProperties' own doc comment for
        // why (transaction alert SMS is a best-effort notification, not a security control; a
        // missing key just means TwoFactorSmsProvider's NoOp fallback logs instead of sending).
        // Still surfaced as a startup warning, not silence, so an operator who meant to configure
        // it notices immediately rather than discovering it the first time a user asks "why didn't
        // I get an SMS for that transaction."
        if (!smsProvider.isConfigured()) {
            log.warn("TWO_FACTOR_API_KEY is unset -- transaction alert SMS will be logged only, never actually sent.");
        }

        // Same soft-warning treatment as TWO_FACTOR_API_KEY above, for the same reason: correctness
        // depends on the actual deployment topology, not just on being in the prod profile, so this
        // can't be a hard failure the way JWT_SECRET/RESEND_API_KEY are -- an operator running prod
        // NOT behind a reverse proxy correctly leaves this false. But the default is false, and on
        // Railway (this app's actual deployment target -- see docker-compose.yml/deployment-guide.md)
        // it must be true, so silently starting with the default in prod is exactly the kind of easy
        // operator mistake this validator exists to surface. See RateLimitFilter's own doc comment
        // for the full story: wrong in one direction shares one rate-limit bucket across every user
        // (the proxy's own IP instead of each real client's); wrong in the other direction lets a
        // client bypass rate limiting entirely by spoofing X-Forwarded-For.
        if (!environment.getProperty("app.security.trust-proxy-headers", Boolean.class, false)) {
            log.warn("TRUST_PROXY_HEADERS is unset (defaults to false). If this deployment sits behind a "
                    + "trusted reverse proxy (e.g. Railway), set it true -- every user is currently sharing "
                    + "one rate-limit bucket keyed off the proxy's own IP instead of each real client's. If "
                    + "it does NOT sit behind a trusted proxy, false is correct and this warning can be "
                    + "ignored.");
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
