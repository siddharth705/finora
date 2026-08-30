package com.finora.integrations.apple.login;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * D-23 Phase 2: "Sign in with Apple" client configuration — the Apple-side counterpart to
 * {@code GoogleLoginProperties}, same shape and same reasoning for why it's a list (D-26 scoped
 * Phase 2 to iOS getting both Google and Apple; Android has no Apple entry at all, but a future
 * additional iOS build target or a web/Services-ID flow would be a second audience, not a
 * replacement — see that class's own doc comment).
 *
 * <p>Unlike Google's {@code clientIds} (an OAuth client id per platform), Apple's {@code aud}
 * claim on a token minted by the native {@code AuthenticationServices} framework (what
 * {@code expo-apple-authentication} wraps) is the app's own bundle identifier — there is no
 * separate "client id" to register for native sign-in the way Google issues one. The property is
 * still named {@code clientIds} for parity with the Google config and because a Services ID
 * would belong in the same list if a web flow is ever added later, but in practice this holds
 * bundle identifiers (e.g. {@code com.fynora.app}).
 *
 * <p>Unconfigured (empty list) is a supported state, same posture as
 * {@link com.finora.integrations.google.login.GoogleLoginProperties}: {@link #isConfigured()} is
 * false, {@code POST /api/v1/auth/apple} answers 503, and nothing else in the application is
 * affected.
 */
@Configuration
@ConfigurationProperties(prefix = "app.auth.apple-login")
public class AppleLoginProperties {

    /** Comma-separated in the environment (Spring's standard List<String> binding) — e.g.
     *  {@code APPLE_LOGIN_CLIENT_IDS=com.fynora.app}. */
    private List<String> clientIds = List.of();

    public boolean isConfigured() {
        return clientIds != null && !clientIds.isEmpty();
    }

    public List<String> getClientIds() { return clientIds; }
    public void setClientIds(List<String> clientIds) { this.clientIds = clientIds; }
}
