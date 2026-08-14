package com.finora.integrations.google;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Google OAuth client configuration for the Gmail data integration.
 *
 * <p><b>This is not "Sign in with Google".</b> It is a third-party data-access grant against a
 * user who is already authenticated with Finora. The two would request different scopes, show
 * different consent copy, and mean different things — see the scope note at the top of
 * docs/proposals/gmail-transaction-sync-proposal.md. {@code AuthController}'s
 * {@code // TODO Phase 2: /oauth/google callback endpoint} refers to the login feature and is
 * unrelated to this.
 *
 * <p>Unconfigured is a supported state: {@link #isConfigured()} is false, the endpoints answer 503,
 * and nothing else in the application is affected. Same posture as SMS — the feature is absent, not
 * broken. It is deliberately NOT a boot-time requirement the way the encryption key is, because a
 * missing Gmail client degrades one optional integration rather than risking a credential.
 */
@Configuration
@ConfigurationProperties(prefix = "app.integrations.google")
public class GoogleOAuthProperties {

    private String clientId;
    private String clientSecret;

    /**
     * Must match a redirect URI registered on the Google OAuth client EXACTLY — Google compares
     * the full string, so scheme, host, port, and path all count, and a trailing slash is a
     * different URI. Per-environment: production and any dev deployment need separate entries
     * registered, and (per the design doc) ideally separate OAuth clients entirely.
     */
    private String redirectUri;

    /**
     * Defaults are the minimum this integration needs and no more.
     *
     * <ul>
     *   <li>{@code gmail.readonly} — read messages. Never {@code gmail.modify} or {@code gmail.send}:
     *       Finora has no reason to write to anyone's mailbox, and asking for less is both the
     *       correct posture and a lower bar for Google's own verification review.</li>
     *   <li>{@code openid} — yields the stable {@code sub} identifier. Required because email
     *       addresses change and {@code sub} does not; see {@code GmailConnection}.</li>
     *   <li>{@code userinfo.email} — the address to show the user so they can tell WHICH mailbox is
     *       connected. Display only.</li>
     * </ul>
     *
     * <p>{@code gmail.readonly} is a Google "restricted scope": production access beyond 100 test
     * users needs OAuth app verification plus an annual CASA security assessment. That is calendar
     * time Finora does not control and should be started in parallel with the build.
     */
    private List<String> scopes = List.of(
            "openid",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/gmail.readonly");

    /**
     * Where the callback sends the browser when it is done — a page in the Finora frontend.
     *
     * <p>Configuration, never a request parameter. The callback endpoint is unauthenticated by
     * necessity (Google redirects a browser to it), so a redirect target taken from the request
     * would make it an open redirect usable by anyone who can get a browser to follow a link.
     */
    private String postConnectRedirect = "https://app.finoratech.info/settings";

    /**
     * Google's own endpoints. Real values by default — nothing needs to set these.
     *
     * <p>Configurable rather than hardcoded constants for one reason: {@link GoogleOAuthClient}'s
     * handling of Google's error responses is a security control (an {@code invalid_grant} means
     * "this user must reconnect", anything else means "retry"), and a control that cannot be
     * pointed at a test server cannot be verified. They were constants, and the consequence was
     * that the distinction had unit tests around it but the code making it had never executed.
     *
     * <p>Not a production knob. If one of these is ever overridden outside a test, that is a
     * misconfiguration worth noticing.
     */
    private String tokenEndpoint = "https://oauth2.googleapis.com/token";
    private String userinfoEndpoint = "https://openidconnect.googleapis.com/v1/userinfo";
    private String revokeEndpoint = "https://oauth2.googleapis.com/revoke";
    private String authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth";

    /** Gmail API base. Same reasoning as the OAuth endpoints above -- configurable so the client's
     *  error classification can be executed in a test rather than only reasoned about. */
    private String gmailApiBaseUrl = "https://gmail.googleapis.com";

    /** True when a client id, secret, and redirect URI are all present. Anything less cannot
     *  complete an authorization-code exchange, so the endpoints refuse rather than half-work. */
    public boolean isConfigured() {
        return notBlank(clientId) && notBlank(clientSecret) && notBlank(redirectUri);
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
    public List<String> getScopes() { return scopes; }
    public void setScopes(List<String> scopes) { this.scopes = scopes; }
    public String getTokenEndpoint() { return tokenEndpoint; }
    public void setTokenEndpoint(String tokenEndpoint) { this.tokenEndpoint = tokenEndpoint; }
    public String getUserinfoEndpoint() { return userinfoEndpoint; }
    public void setUserinfoEndpoint(String userinfoEndpoint) { this.userinfoEndpoint = userinfoEndpoint; }
    public String getRevokeEndpoint() { return revokeEndpoint; }
    public void setRevokeEndpoint(String revokeEndpoint) { this.revokeEndpoint = revokeEndpoint; }
    public String getAuthorizationEndpoint() { return authorizationEndpoint; }
    public void setAuthorizationEndpoint(String authorizationEndpoint) { this.authorizationEndpoint = authorizationEndpoint; }
    public String getGmailApiBaseUrl() { return gmailApiBaseUrl; }
    public void setGmailApiBaseUrl(String gmailApiBaseUrl) { this.gmailApiBaseUrl = gmailApiBaseUrl; }
    public String getPostConnectRedirect() { return postConnectRedirect; }
    public void setPostConnectRedirect(String postConnectRedirect) { this.postConnectRedirect = postConnectRedirect; }
}
