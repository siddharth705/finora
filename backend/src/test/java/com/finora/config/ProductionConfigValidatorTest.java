package com.finora.config;

import com.finora.service.PhoneVerificationProvider;
import com.finora.service.SmsProvider;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductionConfigValidatorTest {

    private JwtProperties jwtWith(String secret) {
        JwtProperties props = new JwtProperties();
        props.setSecret(secret);
        return props;
    }

    private JwtProperties realJwt() {
        return jwtWith("a-genuinely-long-random-secret-value-here-ok");
    }

    private EmailProperties emailWith(String apiKey) {
        EmailProperties props = new EmailProperties();
        props.setApiKey(apiKey);
        return props;
    }

    private EmailProperties realEmail() {
        return emailWith("re_real_resend_api_key");
    }

    private PhoneVerificationProvider firebaseWith(boolean configured) {
        PhoneVerificationProvider service = mock(PhoneVerificationProvider.class);
        when(service.isConfigured()).thenReturn(configured);
        return service;
    }

    private PhoneVerificationProvider configuredFirebase() {
        return firebaseWith(true);
    }

    private PhoneVerificationProvider unconfiguredFirebase() {
        return firebaseWith(false);
    }

    private SmsProvider smsWith(boolean configured) {
        SmsProvider provider = mock(SmsProvider.class);
        when(provider.isConfigured()).thenReturn(configured);
        return provider;
    }

    private Environment envWithProfilesAndDbPassword(String[] profiles, String dbPassword) {
        return envWithProfilesAndDbPasswordAndTrustProxyHeaders(profiles, dbPassword, true);
    }

    // A bare mock(Environment.class) doesn't run the real getProperty(String, Class, T) logic --
    // an unstubbed call returns null, not the supplied default, which would NPE on unboxing to
    // boolean in ProductionConfigValidator. trustProxyHeaders defaults true here so every
    // pre-existing test above (none of which care about this setting) keeps exercising a
    // no-warning path unless a test explicitly wants otherwise.
    private Environment envWithProfilesAndDbPasswordAndTrustProxyHeaders(
            String[] profiles, String dbPassword, boolean trustProxyHeaders) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(profiles);
        when(environment.getProperty("spring.datasource.password")).thenReturn(dbPassword);
        when(environment.getProperty("app.security.trust-proxy-headers", Boolean.class, false))
                .thenReturn(trustProxyHeaders);
        // BH-046: production now requires a storage provider unconditionally. Stubbed configured
        // here so every pre-existing test above keeps testing the thing it was written to test --
        // the storage cases get their own tests below, where the stub is overridden deliberately.
        when(environment.getProperty("app.statement-storage.provider")).thenReturn("r2");
        return environment;
    }

    /**
     * BH-046. Production must refuse to start without durable object storage.
     *
     * <p><b>This is the case that used to boot.</b> The storage check existed already, gated on
     * {@code app.import.queue.enabled} — which defaults to OFF. So a production deployment with
     * the queue off and no provider configured started happily and stored every statement it
     * accepted as a PostgreSQL BYTEA column and nowhere else.
     *
     * <p>Survivable while {@code file_content} is a dual write; not survivable once BH-046 removes
     * it. With no provider, {@code StatementContentService.store()} returns empty, no content
     * address is recorded, and {@code read()} then has neither an object nor a legacy column. The
     * document is gone, and nothing notices until someone asks for it.
     */
    @Test
    void run_inProdProfile_withNoStorageProvider_throws_evenWhenTheAsyncQueueIsOff() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "a-real-password");
        when(environment.getProperty("app.statement-storage.provider")).thenReturn(null);
        when(environment.getProperty("app.import.queue.enabled")).thenReturn("false");
        var validator = new ProductionConfigValidator(environment, realJwt(), realEmail(), configuredFirebase(), mock(SmsProvider.class));

        assertThatThrownBy(validator::validate)
                .as("the queue being off must no longer excuse missing storage -- that gate is "
                        + "exactly the hole BH-046 cannot ship over")
                .hasMessageContaining("app.statement-storage.provider is unset");
    }

    @Test
    void run_inProdProfile_withABlankStorageProvider_throws() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "a-real-password");
        when(environment.getProperty("app.statement-storage.provider")).thenReturn("   ");
        var validator = new ProductionConfigValidator(environment, realJwt(), realEmail(), configuredFirebase(), mock(SmsProvider.class));

        assertThatThrownBy(validator::validate)
                .as("an empty string is not a configured provider -- Railway sets blank variables "
                        + "as readily as it sets real ones")
                .hasMessageContaining("app.statement-storage.provider is unset");
    }

    @Test
    void run_inProdProfile_withNoStorageAndTheQueueOn_saysBothThings() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "a-real-password");
        when(environment.getProperty("app.statement-storage.provider")).thenReturn(null);
        when(environment.getProperty("app.import.queue.enabled")).thenReturn("true");
        var validator = new ProductionConfigValidator(environment, realJwt(), realEmail(), configuredFirebase(), mock(SmsProvider.class));

        assertThatThrownBy(validator::validate)
                .as("the queue message adds information the general one does not -- uploads fail "
                        + "immediately with 503 rather than silently losing bytes")
                .hasMessageContaining("app.statement-storage.provider is unset")
                .hasMessageContaining("503");
    }

    @Test
    void run_inProdProfile_withFilesystemStorage_doesNotThrow() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "a-real-password");
        when(environment.getProperty("app.statement-storage.provider")).thenReturn("filesystem");
        var validator = new ProductionConfigValidator(environment, realJwt(), realEmail(), configuredFirebase(), mock(SmsProvider.class));

        assertThat(catchNoThrow(validator))
                .as("filesystem is a supported provider -- this check is about durability being "
                        + "configured at all, not about which backend was chosen")
                .isTrue();
    }

    @Test
    void run_outsideProdProfile_withNoStorageProvider_doesNotThrow() {
        // NEGATIVE. Local dev, tests and CI must keep working with zero storage setup. A check that
        // fired outside prod would make every developer configure R2 to run the app.
        Environment environment = envWithProfilesAndDbPassword(new String[]{"dev"}, "finora");
        when(environment.getProperty("app.statement-storage.provider")).thenReturn(null);
        JwtProperties jwt = jwtWith("change-this-to-a-long-random-secret-in-your-env-file-min-32-chars");
        var validator = new ProductionConfigValidator(environment, jwt, emailWith(null), unconfiguredFirebase(), mock(SmsProvider.class));

        assertThat(catchNoThrow(validator)).isTrue();
    }

    @Test
    void run_inProdProfile_withThePlaceholderJwtSecretStillSet_throws() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "a-real-password");
        JwtProperties jwt = jwtWith("change-this-to-a-long-random-secret-in-your-env-file-min-32-chars");
        var validator = new ProductionConfigValidator(environment, jwt, realEmail(), configuredFirebase(), mock(SmsProvider.class));

        assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void run_inProdProfile_withATooShortJwtSecret_throws() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "a-real-password");
        JwtProperties jwt = jwtWith("too-short");
        var validator = new ProductionConfigValidator(environment, jwt, realEmail(), configuredFirebase(), mock(SmsProvider.class));

        assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void run_inProdProfile_withTheDefaultDbPasswordStillSet_throws() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "finora");
        var validator = new ProductionConfigValidator(environment, realJwt(), realEmail(), configuredFirebase(), mock(SmsProvider.class));

        assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_PASSWORD");
    }

    // Bug fix regression tests: RESEND_API_KEY/phone-verification credentials used to not be
    // checked here at all -- a prod deployment missing either started up completely normally,
    // silently returning real password-reset links directly in API responses (no
    // RESEND_API_KEY) or leaving every phone-verification call failing with a 503 the moment a
    // real user hit it (no GOOGLE_APPLICATION_CREDENTIALS for the Firebase Admin SDK).

    @Test
    void run_inProdProfile_withNoResendApiKeyConfigured_throws() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "a-real-password");
        var validator = new ProductionConfigValidator(environment, realJwt(), emailWith(null), configuredFirebase(), mock(SmsProvider.class));

        assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESEND_API_KEY");
    }

    @Test
    void run_inProdProfile_withABlankResendApiKeyConfigured_throws() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "a-real-password");
        var validator = new ProductionConfigValidator(environment, realJwt(), emailWith("   "), configuredFirebase(), mock(SmsProvider.class));

        assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESEND_API_KEY");
    }

    @Test
    void run_inProdProfile_withFirebaseNotConfigured_throws() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "a-real-password");
        var validator = new ProductionConfigValidator(environment, realJwt(), realEmail(), unconfiguredFirebase(), mock(SmsProvider.class));

        assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GOOGLE_APPLICATION_CREDENTIALS");
    }

    /** TWO_FACTOR_API_KEY is deliberately NOT a hard boot-time requirement (see SmsProperties'
     *  own doc comment) -- an unconfigured SMS provider must only warn, never block startup, even
     *  with every other required setting otherwise correct. */
    @Test
    void run_inProdProfile_withNoTwoFactorApiKeyConfigured_warnsButDoesNotThrow() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "a-real-password");
        var validator = new ProductionConfigValidator(environment, realJwt(), realEmail(), configuredFirebase(), smsWith(false));

        assertThat(catchNoThrow(validator)).isTrue();
    }

    /** Same soft-warning treatment as TWO_FACTOR_API_KEY -- an unset TRUST_PROXY_HEADERS must only
     *  warn, never block startup, since correctness depends on deployment topology, not just the
     *  prod profile being active. */
    @Test
    void run_inProdProfile_withTrustProxyHeadersUnset_warnsButDoesNotThrow() {
        Environment environment = envWithProfilesAndDbPasswordAndTrustProxyHeaders(
                new String[]{"prod"}, "a-real-password", false);
        var validator = new ProductionConfigValidator(environment, realJwt(), realEmail(), configuredFirebase(), mock(SmsProvider.class));

        assertThat(catchNoThrow(validator)).isTrue();
    }

    @Test
    void run_inProdProfile_withTrustProxyHeadersSetTrue_doesNotThrow() {
        Environment environment = envWithProfilesAndDbPasswordAndTrustProxyHeaders(
                new String[]{"prod"}, "a-real-password", true);
        var validator = new ProductionConfigValidator(environment, realJwt(), realEmail(), configuredFirebase(), mock(SmsProvider.class));

        assertThat(catchNoThrow(validator)).isTrue();
    }

    /**
     * BH-032. The check compared against the literal "finora" and nothing else, while the message
     * it printed claimed to catch "unset" too. It did not -- an unset password reads as null, null
     * does not equal "finora", and production started silently. The one case the message named was
     * the one case it missed.
     */
    @Test
    void run_inProdProfile_withNoDbPassword_throws() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, null);
        var validator = new ProductionConfigValidator(environment, realJwt(), realEmail(), configuredFirebase(), mock(SmsProvider.class));

        assertThatThrownBy(validator::validate)
                .as("an unset password was the case the old message claimed to catch and did not")
                .hasMessageContaining("DB_PASSWORD is unset or blank");
    }

    @Test
    void run_inProdProfile_withABlankDbPassword_throws() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "   ");
        var validator = new ProductionConfigValidator(environment, realJwt(), realEmail(), configuredFirebase(), mock(SmsProvider.class));

        assertThatThrownBy(validator::validate)
                .hasMessageContaining("DB_PASSWORD is unset or blank");
    }

    @Test
    void run_inProdProfile_withAWellKnownDefaultDbPassword_throws() {
        // Case-insensitive, and not only this repository's own compose default. "POSTGRES" is the
        // first thing on the first page of any tutorial, and a production database reachable with
        // it is not meaningfully protected.
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "POSTGRES");
        var validator = new ProductionConfigValidator(environment, realJwt(), realEmail(), configuredFirebase(), mock(SmsProvider.class));

        assertThatThrownBy(validator::validate)
                .hasMessageContaining("DB_PASSWORD is a well-known default");
    }

    @Test
    void run_inProdProfile_withADeliberateIfShortDbPassword_doesNotThrow() {
        // NEGATIVE. This is a known-defaults check, not a password-strength rule. Refusing to boot
        // over a password an operator deliberately chose is a different decision and not this
        // validator's to make -- and a validator that cries wolf gets its exception caught.
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "hunter2");
        var validator = new ProductionConfigValidator(environment, realJwt(), realEmail(), configuredFirebase(), mock(SmsProvider.class));

        assertThat(catchNoThrow(validator)).isTrue();
    }

    @Test
    void run_inProdProfile_withRealSecretsConfigured_doesNotThrow() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "a-real-password");
        var validator = new ProductionConfigValidator(environment, realJwt(), realEmail(), configuredFirebase(), mock(SmsProvider.class));

        assertThat(catchNoThrow(validator)).isTrue();
    }

    @Test
    void run_outsideProdProfile_neverValidatesAtAll_evenWithPlaceholderDefaults() {
        // Local dev/test/CI must keep working with zero setup -- these placeholder defaults are
        // exactly what makes that possible.
        Environment environment = envWithProfilesAndDbPassword(new String[]{"dev"}, "finora");
        JwtProperties jwt = jwtWith("change-this-to-a-long-random-secret-in-your-env-file-min-32-chars");
        var validator = new ProductionConfigValidator(environment, jwt, emailWith(null), unconfiguredFirebase(), mock(SmsProvider.class));

        assertThat(catchNoThrow(validator)).isTrue();
    }

    @Test
    void run_withNoActiveProfilesAtAll_doesNotThrow() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{}, "finora");
        JwtProperties jwt = jwtWith("change-this-to-a-long-random-secret-in-your-env-file-min-32-chars");
        var validator = new ProductionConfigValidator(environment, jwt, emailWith(null), unconfiguredFirebase(), mock(SmsProvider.class));

        assertThat(catchNoThrow(validator)).isTrue();
    }

    private boolean catchNoThrow(ProductionConfigValidator validator) {
        validator.validate();
        return true;
    }
}
