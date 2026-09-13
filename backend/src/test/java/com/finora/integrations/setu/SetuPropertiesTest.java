package com.finora.integrations.setu;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SetuPropertiesTest {

    @Test
    void notConfiguredWithoutCredentials() {
        SetuProperties properties = new SetuProperties();
        assertThat(properties.isConfigured()).isFalse();
    }

    @Test
    void configuredOnceAllThreeCredentialsArePresent() {
        SetuProperties properties = new SetuProperties();
        properties.setClientId("client-id");
        properties.setClientSecret("client-secret");
        properties.setWebhookSecret("webhook-secret");

        assertThat(properties.isConfigured()).isTrue();
    }

    @Test
    void notConfiguredWhenOnlySomeCredentialsArePresent() {
        SetuProperties properties = new SetuProperties();
        properties.setClientId("client-id");
        // clientSecret and webhookSecret left unset

        assertThat(properties.isConfigured()).isFalse();
    }
}
