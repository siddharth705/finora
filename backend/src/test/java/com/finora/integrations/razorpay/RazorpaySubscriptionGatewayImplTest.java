package com.finora.integrations.razorpay;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpaySubscriptionGatewayImplTest {

    @Test
    void isConfiguredDelegatesToProperties() {
        RazorpayProperties properties = new RazorpayProperties();
        RazorpaySubscriptionGatewayImpl gateway = new RazorpaySubscriptionGatewayImpl(properties);

        assertThat(gateway.isConfigured()).isFalse();

        properties.setKeyId("rzp_test_123");
        properties.setKeySecret("secret");
        properties.setWebhookSecret("whsec");

        assertThat(gateway.isConfigured()).isTrue();
    }

    @Test
    void createSubscriptionRefusesWhenUnconfigured() {
        RazorpaySubscriptionGatewayImpl gateway = new RazorpaySubscriptionGatewayImpl(new RazorpayProperties());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> gateway.createSubscription("plan_123", "MONTHLY", java.util.Map.of()));
    }
}
