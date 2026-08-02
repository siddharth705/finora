package com.finora.health;

import com.finora.service.EmailProvider;
import com.finora.service.SilentProductionFallback;
import org.springframework.stereotype.Component;

/**
 * Mirrors the exact isConfigured() check ProductionConfigValidator hard-fails the prod profile on
 * for a missing RESEND_API_KEY (see that class's own doc comment on why: no real email provider
 * means AuthService.forgotPassword() falls back to returning the raw reset link directly in the
 * API response -- a full account-takeover primitive for anyone who knows a victim's email). DOWN
 * here mirrors that same hard-fail severity: an instance where this shows unconfigured either
 * never should have reached prod, or its RESEND_API_KEY silently stopped working after boot --
 * both worth surfacing loudly, not softly.
 */
@Component
public class EmailProviderHealthProvider implements HealthProvider {

    private final EmailProvider emailProvider;

    public EmailProviderHealthProvider(EmailProvider emailProvider) {
        this.emailProvider = emailProvider;
    }

    @Override
    public String name() {
        return "Email Provider";
    }

    @Override
    public String category() {
        return "Notifications";
    }

    @Override
    public HealthCheckResult check() {
        if (emailProvider.isConfigured()) {
            return HealthCheckResult.up("Configured -- password reset, welcome, and password-changed emails are sent for real");
        }
        String hint = emailProvider instanceof SilentProductionFallback fallback
                ? fallback.requiredConfigHint() : "its API key";
        return HealthCheckResult.down("Not configured (" + hint + " unset) -- password reset links are returned "
                + "directly in API responses instead of emailed");
    }
}
