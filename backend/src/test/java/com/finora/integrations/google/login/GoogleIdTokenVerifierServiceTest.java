package com.finora.integrations.google.login;

import com.finora.exception.ApiException;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D-23. {@link GoogleIdTokenVerifier}'s own signature/JWKS verification is Google's own,
 * well-audited code and is not what these tests exercise -- they cover only what
 * {@link GoogleIdTokenVerifierService} adds on top of it: refusing an unverified email, refusing
 * an unconfigured (null verifier) or a malformed/rejected token, and mapping a valid, verified
 * payload onto {@link GoogleIdentity} correctly.
 */
class GoogleIdTokenVerifierServiceTest {

    private static GoogleIdToken.Payload payload(String email, Boolean emailVerified, String name) {
        var payload = new GoogleIdToken.Payload();
        payload.setEmail(email);
        payload.setEmailVerified(emailVerified);
        if (name != null) {
            payload.set("name", name);
        }
        return payload;
    }

    private static GoogleLoginProperties configuredProperties() {
        var properties = new GoogleLoginProperties();
        properties.setClientIds(java.util.List.of("web-client-id.apps.googleusercontent.com"));
        return properties;
    }

    @Test
    @DisplayName("unconfigured (no client ids) answers 503, not a 500 or an NPE")
    void unconfiguredAnswers503() {
        var service = new GoogleIdTokenVerifierService(mock(GoogleIdTokenVerifier.class), new GoogleLoginProperties());

        assertThatThrownBy(() -> service.verify("any-token"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("a verified email maps onto GoogleIdentity with the name claim carried through")
    void verifiedEmailMapsToIdentity() throws Exception {
        GoogleIdTokenVerifier verifier = mock(GoogleIdTokenVerifier.class);
        GoogleIdToken idToken = mock(GoogleIdToken.class);
        when(idToken.getPayload()).thenReturn(payload("amy@example.test", true, "Amy Santiago"));
        when(verifier.verify("real-token")).thenReturn(idToken);

        var service = new GoogleIdTokenVerifierService(verifier, configuredProperties());
        GoogleIdentity identity = service.verify("real-token");

        assertThat(identity.email()).isEqualTo("amy@example.test");
        assertThat(identity.name()).isEqualTo("Amy Santiago");
    }

    @Test
    @DisplayName("a missing name claim is tolerated -- name is best-effort, never required")
    void missingNameClaimIsTolerated() throws Exception {
        GoogleIdTokenVerifier verifier = mock(GoogleIdTokenVerifier.class);
        GoogleIdToken idToken = mock(GoogleIdToken.class);
        when(idToken.getPayload()).thenReturn(payload("amy@example.test", true, null));
        when(verifier.verify("real-token")).thenReturn(idToken);

        var service = new GoogleIdTokenVerifierService(verifier, configuredProperties());
        GoogleIdentity identity = service.verify("real-token");

        assertThat(identity.email()).isEqualTo("amy@example.test");
        assertThat(identity.name()).isNull();
    }

    @Test
    @DisplayName("an unverified email is refused -- the entire auto-link design depends on this")
    void unverifiedEmailIsRefused() throws Exception {
        GoogleIdTokenVerifier verifier = mock(GoogleIdTokenVerifier.class);
        GoogleIdToken idToken = mock(GoogleIdToken.class);
        when(idToken.getPayload()).thenReturn(payload("amy@example.test", false, "Amy Santiago"));
        when(verifier.verify("real-token")).thenReturn(idToken);

        var service = new GoogleIdTokenVerifierService(verifier, configuredProperties());

        assertThatThrownBy(() -> service.verify("real-token"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @DisplayName("a null emailVerified claim (never asserted either way) is refused, not defaulted to trusted")
    void nullEmailVerifiedIsRefused() throws Exception {
        GoogleIdTokenVerifier verifier = mock(GoogleIdTokenVerifier.class);
        GoogleIdToken idToken = mock(GoogleIdToken.class);
        when(idToken.getPayload()).thenReturn(payload("amy@example.test", null, "Amy Santiago"));
        when(verifier.verify("real-token")).thenReturn(idToken);

        var service = new GoogleIdTokenVerifierService(verifier, configuredProperties());

        assertThatThrownBy(() -> service.verify("real-token")).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a token the library rejects (bad signature, expired, wrong audience) maps to 401")
    void rejectedTokenMapsTo401() throws Exception {
        GoogleIdTokenVerifier verifier = mock(GoogleIdTokenVerifier.class);
        when(verifier.verify("bad-token")).thenReturn(null);

        var service = new GoogleIdTokenVerifierService(verifier, configuredProperties());

        assertThatThrownBy(() -> service.verify("bad-token"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @DisplayName("a malformed token string (not even shaped like a JWT) is refused, not a 500")
    void malformedTokenIsRefused() throws Exception {
        GoogleIdTokenVerifier verifier = mock(GoogleIdTokenVerifier.class);
        when(verifier.verify("not-a-jwt")).thenThrow(new IllegalArgumentException("bad token format"));

        var service = new GoogleIdTokenVerifierService(verifier, configuredProperties());

        assertThatThrownBy(() -> service.verify("not-a-jwt"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
