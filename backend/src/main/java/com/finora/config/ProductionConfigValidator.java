package com.finora.config;

import com.finora.security.crypto.CryptoProperties;
import com.finora.service.PhoneVerificationProvider;
import com.finora.service.SmsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
 *
 * <h2>Why {@link SmartInitializingSingleton} and not {@code ApplicationRunner}</h2>
 *
 * <p>This was an {@code ApplicationRunner}, and "immediately" was not true of it. Spring Boot
 * starts the web server inside {@code AbstractApplicationContext.finishRefresh()}, and only calls
 * {@code ApplicationRunner}s afterwards, from {@code SpringApplication.callRunners()} once
 * {@code run()} has a fully refreshed context. So the ordering was: bind the port, begin accepting
 * connections, THEN check whether the JWT signing key is a placeholder committed to this
 * repository, then throw. Every boot of a misconfigured production deployment served real requests
 * against a publicly-known HS256 key for the width of that window, and because the throw kills the
 * process the platform restarts it and the window reopens -- a crash loop is a repeating exposure,
 * not a single one.
 *
 * <p>{@code afterSingletonsInstantiated()} runs at the end of
 * {@code beanFactory.preInstantiateSingletons()}, inside {@code finishBeanFactoryInitialization()}
 * -- one phase BEFORE {@code finishRefresh()} starts the connector. Throwing from here fails the
 * refresh, so the server never binds and no request is ever served under the configuration this
 * class exists to reject.
 *
 * <p>{@code SmartInitializingSingleton} rather than {@code @PostConstruct} on this bean: the checks
 * call into {@code PhoneVerificationProvider} and {@code SmsProvider}, and a {@code @PostConstruct}
 * fires as soon as THIS bean is constructed, which says nothing about whether those two are past
 * their own initialisation. {@code afterSingletonsInstantiated()} is defined to run once every
 * singleton exists, which is the guarantee the checks actually need.
 *
 * <p>The lifecycle is pinned by guardian rule FG-031 rather than left to a comment, because
 * nothing at the call site makes the ordering visible and a future refactor back to
 * {@code ApplicationRunner} would compile, pass every test here, and silently reopen the window.
 */
@Component
public class ProductionConfigValidator implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigValidator.class);

    private static final String DEFAULT_JWT_SECRET =
            "change-this-to-a-long-random-secret-in-your-env-file-min-32-chars";
    /** Lower-cased. See the check below for why this is a short list of known defaults rather
     *  than a password-strength rule. */
    private static final java.util.Set<String> WEAK_DB_PASSWORDS = java.util.Set.of(
            "finora", "postgres", "password", "changeme", "admin", "root", "secret", "test");

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

    /** The local-dev placeholder from application.yml. Matching this in prod means the real key was
     *  never supplied -- see the check in {@link #validate()}. */
    private static final String LOCAL_DEV_ENCRYPTION_KEY = "Zmlub3JhLWxvY2FsLWRldi1rZXktRE8tTk9ULVVTRSE=";

    private final Environment environment;
    private final JwtProperties jwtProperties;
    private final EmailProperties emailProperties;
    private final PhoneVerificationProvider phoneVerificationProvider;
    private final SmsProvider smsProvider;
    private final CryptoProperties cryptoProperties;

    public ProductionConfigValidator(Environment environment, JwtProperties jwtProperties,
                                      EmailProperties emailProperties,
                                      PhoneVerificationProvider phoneVerificationProvider,
                                      SmsProvider smsProvider,
                                      CryptoProperties cryptoProperties) {
        this.environment = environment;
        this.jwtProperties = jwtProperties;
        this.emailProperties = emailProperties;
        this.phoneVerificationProvider = phoneVerificationProvider;
        this.smsProvider = smsProvider;
        this.cryptoProperties = cryptoProperties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validate();
    }

    /** The checks themselves, separated from the lifecycle hook that triggers them so a test can
     *  invoke them directly without standing up a context -- and so the hook above stays a single
     *  line whose only job is to say WHEN this runs. */
    void validate() {
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

        // BH-046. Object storage is REQUIRED in production, not merely required by the async
        // queue. This check used to be gated on app.import.queue.enabled, which defaults to OFF --
        // so a production deployment with the queue off and no provider configured booted happily,
        // and every statement it imported was stored only as a PostgreSQL BYTEA column.
        //
        // That was survivable while file_content was a dual write. It stops being survivable the
        // moment BH-046 removes it: StatementContentService.store() returns empty with no provider,
        // no content address is recorded, and read() then has neither an object nor a legacy column
        // to fall back on. The statement becomes permanently unreadable -- re-import, download and
        // reprocessing all fail -- and nothing notices until a user asks for a document that is no
        // longer anywhere.
        //
        // Failing at startup is the whole point. The alternative is discovering it from a customer
        // whose bank statement cannot be produced, by which time the bytes were never written and
        // there is nothing to recover. A deployment that cannot durably store the documents it
        // accepts should not accept them.
        String storageProvider = environment.getProperty("app.statement-storage.provider");
        boolean storageConfigured = storageProvider != null && !storageProvider.isBlank();
        if (!storageConfigured) {
            problems.append("- app.statement-storage.provider is unset. Production requires durable ")
                    .append("object storage for statement documents: without it the bytes live only ")
                    .append("in the file_content column, which BH-046 removes. Set it to 'r2' (with ")
                    .append("the app.statement-storage.r2.* credentials) or 'filesystem' (with ")
                    .append("app.statement-storage.filesystem.root on a persistent volume).\n");
        }

        // Kept as its own line rather than folded into the check above: when the queue is on, the
        // consequence is different and worth naming. ImportJobService already refuses uploads with
        // 503, so the failure is immediate and total rather than silent and delayed.
        boolean asyncImportEnabled = Boolean.parseBoolean(environment.getProperty("app.import.queue.enabled"));
        if (asyncImportEnabled && !storageConfigured) {
            problems.append("- app.import.queue.enabled is true, which makes the above fatal rather ")
                    .append("than merely dangerous: the async worker runs in another thread with ")
                    .append("nothing to read but a content address, so every upload to ")
                    .append("/api/v1/import/jobs would be refused with 503.\n");
        }

        // BH-032. This compared against the literal "finora" and nothing else, while the message
        // it printed claimed to catch "unset" as well. It did not: an unset password reads as null,
        // null does not equal "finora", and production started silently. The one case the message
        // named was the one case it missed.
        String dbPassword = environment.getProperty("spring.datasource.password");
        if (dbPassword == null || dbPassword.isBlank()) {
            problems.append("- DB_PASSWORD is unset or blank. Set the real database password.\n");
        } else if (WEAK_DB_PASSWORDS.contains(dbPassword.toLowerCase(java.util.Locale.ROOT))) {
            // Case-insensitive, and a short list rather than one literal. These are the values in
            // this repository's own compose file and on the first page of any Postgres tutorial.
            // Deliberately NOT a password-strength rule -- refusing to boot over a password an
            // operator deliberately chose is a different decision, and a validator that cries wolf
            // gets its exception caught.
            problems.append("- DB_PASSWORD is a well-known default. Set the real database password.\n");
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
        // NoOpPushProvider (see PushConfig) is selected on the exact same missing-FirebaseApp-bean
        // condition as phoneVerificationProvider.isConfigured() above -- both derive from whether
        // GOOGLE_APPLICATION_CREDENTIALS produced a usable FirebaseApp, so the hard failure two
        // lines up already prevents this fallback from ever running silently in a deployment that
        // passes that check. Logged explicitly anyway (SilentFallbackConfigValidationTest requires
        // every NoOp*'s declared hint to actually be checked here) so a future refactor that gates
        // push on different config from phone verification can't silently reopen this gap.
        if (!phoneVerificationProvider.isConfigured()) {
            log.warn("GOOGLE_APPLICATION_CREDENTIALS is also what selects NoOpPushProvider (see PushConfig) "
                    + "-- push notifications will be logged only, never actually delivered, until this is set.");
        }

        // ADR-009. Same class of failure as JWT_SECRET above -- a placeholder that is public, in
        // git, and identical for every developer. The consequence differs though: this key protects
        // third-party OAuth refresh tokens, which are live credentials to a user's external account
        // (their mailbox, later their bank). A production deployment running on the dev key means
        // anyone with the repository can decrypt every stored integration token in the database.
        //
        // Checked here rather than only in EnvironmentKeyProvider because that class validates
        // key SHAPE (base64, 32 bytes) -- which the placeholder satisfies perfectly. Only this
        // validator knows the difference between a well-formed key and the right key.
        //
        // EVERY configured key, not just the active one (Strix security review, CWE-321). A
        // rotation deliberately keeps retired keys configured so ciphertext written under them
        // stays readable -- so checking only `active-key-id` would pass a production config that
        // rotated to a real v2 while leaving the repository-public placeholder under v1, and
        // keyById("v1") would go on decrypting legacy rows under a key anyone can read from git.
        // The realistic path there is not exotic: an operator setting up a rotation copies the
        // `keys:` block out of application.yml as a template, which ships the placeholder as v1's
        // default.
        //
        // Blank/null is included for completeness rather than reachability -- EnvironmentKeyProvider
        // is constructed before this runs (SmartInitializingSingleton) and already refuses an empty
        // key -- so that this check does not silently depend on that bean ordering.
        List<String> unsafeKeyIds = cryptoProperties.getKeys().entrySet().stream()
                .filter(entry -> entry.getValue() == null
                        || entry.getValue().isBlank()
                        || LOCAL_DEV_ENCRYPTION_KEY.equals(entry.getValue().trim()))
                .map(Map.Entry::getKey)
                .toList();
        if (!unsafeKeyIds.isEmpty()) {
            // Names the env var, not just the property path: FINORA_ENCRYPTION_KEY is what an
            // operator actually sets, and an error naming only `finora.security.encryption.keys`
            // sends them looking for a file they will not find in a Railway dashboard.
            problems.append("- FINORA_ENCRYPTION_KEY: encryption key(s) ").append(unsafeKeyIds)
                    .append(" are unset or still the local-dev placeholder. These encrypt third-party ")
                    .append("OAuth refresh tokens at rest; on that value, anyone with the repository can ")
                    .append("decrypt every credential stored under them -- including a retired key kept ")
                    .append("configured so a rotation can still read old rows. Generate one with ")
                    .append("`openssl rand -base64 32`.\n");
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

        // SEC-11 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). A soft warning,
        // not a hard failure like JWT_SECRET/DB_PASSWORD above -- the wrong default here fails
        // CLOSED, not open: CorsConfig only ever allows the origins actually listed, so a forgotten
        // CORS_ORIGINS override in production means the real frontend gets rejected with a CORS
        // error (loud, immediate, breaks nothing security-relevant) rather than any origin being
        // silently permitted. Still worth surfacing at boot rather than an operator discovering it
        // from "the deployed site can't log in" -- every other config gap in this validator gets
        // exactly that treatment.
        // Read as the 1-arg form (null if absent) and defaulted explicitly here, rather than the
        // 2-arg getProperty(key, default) overload: a bare mock(Environment.class) in tests doesn't
        // run that overload's real default-substitution logic and would return null regardless (see
        // envWithProfilesAndDbPasswordAndTrustProxyHeaders's own comment on the identical trap for
        // the boolean trust-proxy-headers check above), so this guards against null explicitly
        // instead of depending on a mock behaving like the real Environment.
        String corsOrigins = environment.getProperty("app.cors.allowed-origins");
        boolean corsStillOnLocalhostDefault = corsOrigins == null || corsOrigins.isBlank()
                || Arrays.stream(corsOrigins.split(","))
                        .map(String::trim)
                        .filter(origin -> !origin.isEmpty())
                        .allMatch(origin -> origin.startsWith("http://localhost:") || origin.startsWith("https://localhost:"));
        if (corsStillOnLocalhostDefault) {
            log.warn("CORS_ORIGINS is unset or still the local-dev default (localhost origins only). "
                    + "The deployed frontend's real origin will be rejected by CORS until this is set -- "
                    + "see CorsConfig and application.yml's own comment on app.cors.allowed-origins.");
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
