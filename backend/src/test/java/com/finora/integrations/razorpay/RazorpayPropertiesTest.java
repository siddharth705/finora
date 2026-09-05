package com.finora.integrations.razorpay;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpayPropertiesTest {

    @Test
    void unconfiguredWhenAnyFieldIsMissing() {
        RazorpayProperties properties = new RazorpayProperties();
        assertThat(properties.isConfigured()).isFalse();

        properties.setKeyId("rzp_test_123");
        assertThat(properties.isConfigured()).isFalse();

        properties.setKeySecret("secret");
        assertThat(properties.isConfigured()).isFalse();

        properties.setWebhookSecret("whsec");
        assertThat(properties.isConfigured()).isTrue();
    }

    @Test
    void blankIsTreatedAsMissing() {
        RazorpayProperties properties = new RazorpayProperties();
        properties.setKeyId("");
        properties.setKeySecret("secret");
        properties.setWebhookSecret("whsec");

        assertThat(properties.isConfigured()).isFalse();
    }
}
