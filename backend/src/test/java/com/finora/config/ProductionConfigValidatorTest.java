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
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(profiles);
        when(environment.getProperty("spring.datasource.password")).thenReturn(dbPassword);
        return environment;
    }

    @Test
    void run_inProdProfile_withThePlaceholderJwtSecretStillSet_throws() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "a-real-password");
        JwtProperties jwt = jwtWith("change-this-to-a-long-random-secret-in-your-env-file-min-32-chars");
        var validator = new ProductionConfigValidator(environment, jwt, realEmail(), configuredFirebase(), mock(SmsProvider.class));

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void run_inProdProfile_withATooShortJwtSecret_throws() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "a-real-password");
        JwtProperties jwt = jwtWith("too-short");
        var validator = new ProductionConfigValidator(environment, jwt, realEmail(), configuredFirebase(), mock(SmsProvider.class));

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void run_inProdProfile_withTheDefaultDbPasswordStillSet_throws() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "finora");
        var validator = new ProductionConfigValidator(environment, realJwt(), realEmail(), configuredFirebase(), mock(SmsProvider.class));

        assertThatThrownBy(() -> validator.run(null))
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

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESEND_API_KEY");
    }

    @Test
    void run_inProdProfile_withABlankResendApiKeyConfigured_throws() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "a-real-password");
        var validator = new ProductionConfigValidator(environment, realJwt(), emailWith("   "), configuredFirebase(), mock(SmsProvider.class));

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESEND_API_KEY");
    }

    @Test
    void run_inProdProfile_withFirebaseNotConfigured_throws() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "a-real-password");
        var validator = new ProductionConfigValidator(environment, realJwt(), realEmail(), unconfiguredFirebase(), mock(SmsProvider.class));

        assertThatThrownBy(() -> validator.run(null))
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
        validator.run(null);
        return true;
    }
}
