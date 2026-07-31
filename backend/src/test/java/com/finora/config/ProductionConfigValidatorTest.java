package com.finora.config;

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
        var validator = new ProductionConfigValidator(environment, jwt);

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void run_inProdProfile_withATooShortJwtSecret_throws() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "a-real-password");
        JwtProperties jwt = jwtWith("too-short");
        var validator = new ProductionConfigValidator(environment, jwt);

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void run_inProdProfile_withTheDefaultDbPasswordStillSet_throws() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "finora");
        JwtProperties jwt = jwtWith("a-genuinely-long-random-secret-value-here-ok");
        var validator = new ProductionConfigValidator(environment, jwt);

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_PASSWORD");
    }

    @Test
    void run_inProdProfile_withRealSecretsConfigured_doesNotThrow() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{"prod"}, "a-real-password");
        JwtProperties jwt = jwtWith("a-genuinely-long-random-secret-value-here-ok");
        var validator = new ProductionConfigValidator(environment, jwt);

        assertThat(catchNoThrow(validator)).isTrue();
    }

    @Test
    void run_outsideProdProfile_neverValidatesAtAll_evenWithPlaceholderDefaults() {
        // Local dev/test/CI must keep working with zero setup -- these placeholder defaults are
        // exactly what makes that possible.
        Environment environment = envWithProfilesAndDbPassword(new String[]{"dev"}, "finora");
        JwtProperties jwt = jwtWith("change-this-to-a-long-random-secret-in-your-env-file-min-32-chars");
        var validator = new ProductionConfigValidator(environment, jwt);

        assertThat(catchNoThrow(validator)).isTrue();
    }

    @Test
    void run_withNoActiveProfilesAtAll_doesNotThrow() {
        Environment environment = envWithProfilesAndDbPassword(new String[]{}, "finora");
        JwtProperties jwt = jwtWith("change-this-to-a-long-random-secret-in-your-env-file-min-32-chars");
        var validator = new ProductionConfigValidator(environment, jwt);

        assertThat(catchNoThrow(validator)).isTrue();
    }

    private boolean catchNoThrow(ProductionConfigValidator validator) {
        validator.run(null);
        return true;
    }
}
