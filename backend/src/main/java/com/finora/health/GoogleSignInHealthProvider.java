package com.finora.health;

import com.finora.integrations.google.login.GoogleLoginProperties;
import org.springframework.stereotype.Component;

/**
 * "Sign in with Google" -- its own OAuth client, separate from Gmail Sync (see
 * GoogleLoginProperties's own doc comment). DEGRADED, not DOWN, when unconfigured: it's an
 * additional sign-in method, not the only one -- email/password and phone auth are unaffected.
 */
@Component
public class GoogleSignInHealthProvider implements HealthProvider {

    private final GoogleLoginProperties properties;

    public GoogleSignInHealthProvider(GoogleLoginProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "Google Sign-In";
    }

    @Override
    public String category() {
        return "Integrations";
    }

    @Override
    public HealthCheckResult check() {
        if (properties.isConfigured()) {
            return HealthCheckResult.up("Configured -- users can sign in with a Google ID token");
        }
        return HealthCheckResult.degraded("No client ids configured -- Google is unavailable as a "
                + "sign-in method, email/password and phone auth are unaffected");
    }
}
