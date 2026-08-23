package com.finora.health;

import com.finora.integrations.google.GoogleOAuthProperties;
import org.springframework.stereotype.Component;

/**
 * Gmail data-sync (not "Sign in with Google" -- see GoogleOAuthProperties's own doc comment on why
 * the two are separate OAuth clients). DEGRADED, not DOWN, when unconfigured: GoogleOAuthProperties
 * itself documents this as a supported state -- the connect-a-mailbox endpoints answer 503 and
 * nothing else in the application is affected, the same posture as SMS/Email being unconfigured.
 */
@Component
public class GmailIntegrationHealthProvider implements HealthProvider {

    private final GoogleOAuthProperties properties;

    public GmailIntegrationHealthProvider(GoogleOAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "Gmail Sync";
    }

    @Override
    public String category() {
        return "Integrations";
    }

    @Override
    public HealthCheckResult check() {
        if (properties.isConfigured()) {
            return HealthCheckResult.up("Configured -- users can connect a mailbox to auto-detect transactions");
        }
        return HealthCheckResult.degraded("Not configured (client id/secret/redirect URI unset) -- "
                + "the feature is off, not broken: connect-mailbox endpoints answer 503");
    }
}
