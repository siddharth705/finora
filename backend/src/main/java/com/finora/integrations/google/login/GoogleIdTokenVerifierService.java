package com.finora.integrations.google.login;

import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * D-23: verifies a Google ID token the frontend received from Google Identity Services (web) or
 * a native Google Sign-In SDK (mobile, Phase 2) — never trusts the frontend's own "this succeeded"
 * signal, the same discipline {@code ResetPasswordRequest.firebaseIdToken} already applies to a
 * Firebase-issued token elsewhere in this codebase.
 *
 * <p>{@link GoogleIdTokenVerifier} (built once, in {@link GoogleIdTokenVerifierConfig}, and
 * injected here rather than constructed by this class -- see that config's own doc comment on
 * why) does the real work: signature verification against Google's published JWKS (with correct
 * caching, key rotation and clock-skew handling -- none of which this class re-implements), plus
 * issuer ({@code accounts.google.com} / {@code https://accounts.google.com}) and audience checks
 * against {@link GoogleLoginProperties#getClientIds()}. This class adds exactly one more check
 * the library doesn't make for you: refusing an unverified email, since D-23's whole auto-link
 * design (see {@code AuthService#loginWithGoogle}) depends on the email genuinely being
 * Google-confirmed, not just Google-asserted.
 */
@Service
public class GoogleIdTokenVerifierService {

    private static final Logger log = LoggerFactory.getLogger(GoogleIdTokenVerifierService.class);

    private final GoogleIdTokenVerifier verifier;
    private final GoogleLoginProperties properties;

    public GoogleIdTokenVerifierService(GoogleIdTokenVerifier verifier, GoogleLoginProperties properties) {
        this.verifier = verifier;
        this.properties = properties;
    }

    /**
     * @throws ApiException 503 if Google sign-in isn't configured in this environment (no client
     *         ids set — see {@link GoogleLoginProperties}), or 401 if the token fails verification
     *         for any reason (bad signature, wrong audience/issuer, expired, or an unverified
     *         email) — deliberately one generic failure mode rather than distinguishing them to the
     *         caller, since none of the specific reasons are actionable by whoever is signing in.
     */
    public GoogleIdentity verify(String idTokenString) {
        if (!properties.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Sign in with Google is not configured on this server.");
        }
        GoogleIdToken idToken;
        try {
            idToken = verifier.verify(idTokenString);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            // IllegalArgumentException: the library's own reaction to a string that isn't even
            // shaped like a JWT -- worth catching alongside the documented checked exceptions
            // rather than letting a malformed request surface as an unhandled 500.
            log.warn("Google ID token verification failed", e);
            throw invalidToken();
        }
        if (idToken == null) {
            throw invalidToken();
        }
        GoogleIdToken.Payload payload = idToken.getPayload();
        Boolean emailVerified = payload.getEmailVerified();
        if (emailVerified == null || !emailVerified) {
            log.warn("Google ID token had a valid signature but an unverified email -- refusing");
            throw invalidToken();
        }
        String email = payload.getEmail();
        if (email == null || email.isBlank()) {
            throw invalidToken();
        }
        Object name = payload.get("name");
        return new GoogleIdentity(email, name == null ? null : name.toString());
    }

    private static ApiException invalidToken() {
        return new ApiException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid Google sign-in token.");
    }
}
