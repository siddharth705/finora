package com.finora.health;

import com.finora.integrations.apple.login.AppleLoginProperties;
import org.springframework.stereotype.Component;

/**
 * "Sign in with Apple" -- mirrors GoogleSignInHealthProvider's reasoning exactly (see
 * AppleLoginProperties's own doc comment). DEGRADED, not DOWN, when unconfigured: an additional
 * sign-in method being unavailable doesn't affect email/password or phone auth.
 */
@Component
public class AppleSignInHealthProvider implements HealthProvider {

    private final AppleLoginProperties properties;

    public AppleSignInHealthProvider(AppleLoginProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "Apple Sign-In";
    }

    @Override
    public String category() {
        return "Integrations";
    }

    @Override
    public HealthCheckResult check() {
        if (properties.isConfigured()) {
            return HealthCheckResult.up("Configured -- users can sign in with Sign in with Apple");
        }
        return HealthCheckResult.degraded("No client ids configured -- Apple is unavailable as a "
                + "sign-in method, email/password and phone auth are unaffected");
    }
}
