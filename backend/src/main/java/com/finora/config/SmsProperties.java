package com.finora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 2Factor is used for real-time transaction alert SMS only (TWO_FACTOR_API_KEY) -- never for
 * authentication OTPs, which are Firebase Phone Authentication's job (see
 * PhoneVerificationProvider's own doc comment). Unlike RESEND_API_KEY, this is NOT a hard
 * boot-time requirement in prod -- an unconfigured deployment just means transaction alerts
 * silently no-op (NoOpSmsProvider), a degraded notification, not a security gap.
 */
@Configuration
@ConfigurationProperties(prefix = "app.sms.two-factor")
public class SmsProperties {

    private String apiKey;

    public String getTwoFactorApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
}
