package com.finora.integrations.google;

import com.finora.integrations.google.merchant.GmailReviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The callback's redirect target -- found via a live reproduction where a real Gmail connection
 * succeeded (confirmed by the backend's own log) but the browser still landed somewhere that read
 * as "signed out". Root cause: {@link GoogleOAuthProperties#getPostConnectRedirect()}'s default
 * pointed at {@code /settings}, a path the frontend router has never served -- the real route is
 * {@code /app/settings} (App.tsx's route table), and the router's catch-all sends anything else to
 * the public marketing homepage, which shows a logged-out nav regardless of session state.
 */
class GoogleOAuthControllerTest {

    private GoogleOAuthController controller() {
        return new GoogleOAuthController(
                mock(GmailConnectionService.class),
                new GoogleOAuthProperties(), // real instance -- the default value is the point
                mock(com.finora.security.CurrentUser.class),
                mock(GmailReviewService.class),
                mock(GmailManualSyncService.class));
    }

    @Test
    @DisplayName("the default post-connect redirect targets the real frontend route, /app/settings")
    void defaultPostConnectRedirectTargetsTheRealRoute() {
        assertThat(new GoogleOAuthProperties().getPostConnectRedirect())
                .isEqualTo("https://app.finoratech.info/app/settings");
    }

    @Test
    @DisplayName("an invalid callback redirects to the real /app/settings route, not the bare /settings 404")
    void invalidCallbackRedirectsToTheRealSettingsRoute() {
        ResponseEntity<Void> response = controller().callback(null, null, null);

        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().toString())
                .startsWith("https://app.finoratech.info/app/settings")
                .contains("gmail=invalid");
    }

    @Test
    @DisplayName("a declined consent redirects to the real /app/settings route")
    void declinedConsentRedirectsToTheRealSettingsRoute() {
        ResponseEntity<Void> response = controller().callback(null, null, "access_denied");

        assertThat(response.getHeaders().getLocation().toString())
                .startsWith("https://app.finoratech.info/app/settings")
                .contains("gmail=declined");
    }
}
