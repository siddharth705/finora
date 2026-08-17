package com.finora.integrations.google.login;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * D-23: "Sign in with Google" client configuration — deliberately its own config block, not a
 * reuse of {@code app.integrations.google} (the Gmail data-integration OAuth client). The two
 * mean different things to Google, need different scopes/consent copy, and in practice are
 * usually even different registered OAuth clients (Google issues a separate client id per
 * platform for a given app anyway) — see {@code GoogleOAuthProperties}'s own doc comment for the
 * fuller reasoning.
 *
 * <p>{@code clientIds} is a list, not a single value, because Google's own
 * {@code GoogleIdTokenVerifier} accepts multiple valid audiences at once — D-23's own plan is phased (web now, a native mobile
 * client id added in Phase 2), and a token from either platform needs to verify successfully
 * against the SAME backend endpoint without a code change when Phase 2 lands, only a config one.
 *
 * <p>Unconfigured (empty list) is a supported state, same posture as the Gmail integration:
 * {@link #isConfigured()} is false, the endpoint answers 503, and nothing else in the application
 * is affected.
 */
@Configuration
@ConfigurationProperties(prefix = "app.auth.google-login")
public class GoogleLoginProperties {

    /** Comma-separated in the environment (Spring's standard List<String> binding) — e.g.
     *  {@code GOOGLE_LOGIN_CLIENT_IDS=xxx.apps.googleusercontent.com}. One entry today (web);
     *  Phase 2 adds the native mobile client id(s) alongside it, not in place of it. */
    private List<String> clientIds = List.of();

    public boolean isConfigured() {
        return clientIds != null && !clientIds.isEmpty();
    }

    public List<String> getClientIds() { return clientIds; }
    public void setClientIds(List<String> clientIds) { this.clientIds = clientIds; }
}
