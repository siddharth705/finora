package com.finora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.email")
public class EmailProperties {

    private String apiKey;
    private String fromAddress;
    // Optional display name shown alongside fromAddress (e.g. "Finora <onboarding@resend.dev>")
    // -- unset means every real mail client just shows the bare address, exactly today's behavior.
    private String fromName;
    // Kept as "appBaseUrl"/APP_BASE_URL for backward compatibility with existing deployments —
    // this is specifically the USER-facing frontend's base URL now that adminAppBaseUrl exists
    // as a separate setting (see that field's own doc comment for why this split was needed).
    private String appBaseUrl;
    // Real bug this fixes: the user frontend and admin portal are two separate deployed apps at
    // two different origins (e.g. finora-cng.pages.dev vs finora-admin.pages.dev), each with its
    // OWN /reset-password page — but AuthService.forgotPassword() is one shared method for both
    // (there's no separate admin auth service; admin accounts authenticate through the same
    // AuthController), and it used to build every reset link from the single appBaseUrl
    // unconditionally. An admin using "Forgot Password" got an email linking to the USER app's
    // reset-password page, not the admin portal's own -- functionally the backend endpoint
    // itself doesn't care which app calls it, but the admin would land in the wrong app entirely
    // with no obvious way back to the one they actually needed. resolveBaseUrl() below picks the
    // right one based on which origin the request actually came from.
    private String adminAppBaseUrl;

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }
    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }
    public String getAppBaseUrl() { return appBaseUrl; }
    public void setAppBaseUrl(String appBaseUrl) { this.appBaseUrl = appBaseUrl; }
    public String getAdminAppBaseUrl() { return adminAppBaseUrl; }
    public void setAdminAppBaseUrl(String adminAppBaseUrl) { this.adminAppBaseUrl = adminAppBaseUrl; }

    /**
     * Picks which frontend's base URL a generated link (password reset, etc.) should point at,
     * based on the Origin header of the request that asked for it. Falls back to the user
     * frontend's base URL whenever the caller's origin isn't recognized as the admin portal's
     * (including when ADMIN_APP_BASE_URL was never configured at all, or the Origin header is
     * missing/blank) -- the safe default is the one every existing deployment already relies on,
     * not a hard failure over a missing optional setting.
     */
    public String resolveBaseUrl(String requestOrigin) {
        if (requestOrigin != null && adminAppBaseUrl != null
                && stripTrailingSlash(requestOrigin).equalsIgnoreCase(stripTrailingSlash(adminAppBaseUrl))) {
            return adminAppBaseUrl;
        }
        return appBaseUrl != null ? appBaseUrl : "";
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
