package com.finora.integrations.apple.login;

import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.ProtectedHeader;
import io.jsonwebtoken.security.SecurityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.Key;

/**
 * D-23 Phase 2: verifies an Apple identity token the frontend received from native
 * {@code AuthenticationServices} Sign In with Apple (via {@code expo-apple-authentication}) —
 * same "never trust the client's own success signal" discipline as
 * {@link com.finora.integrations.google.login.GoogleIdTokenVerifierService}, its Google
 * counterpart.
 *
 * <p>Apple, unlike Google, ships no first-party Java verifier, so this class does the work
 * {@code GoogleIdTokenVerifier} does internally by hand: resolve the signing key for this token's
 * {@code kid} via {@link JwkProvider} (built once, in {@link AppleIdTokenVerifierConfig}, and
 * injected here — see that config's own doc comment on why), then let jjwt (already this
 * codebase's own JWT library) verify the signature and standard claims. This class adds the two
 * checks the library doesn't make for you: the audience is one of
 * {@link AppleLoginProperties#getClientIds()} (Apple's own parser only supports a single required
 * value, not membership in a list — see D-26 on why this needs to be a list at all), and the
 * email is verified, mirroring exactly what the Google verifier adds beyond its own library.
 */
@Service
public class AppleIdTokenVerifierService {

    private static final Logger log = LoggerFactory.getLogger(AppleIdTokenVerifierService.class);
    private static final String APPLE_ISSUER = "https://appleid.apple.com";

    private final JwkProvider jwkProvider;
    private final AppleLoginProperties properties;

    public AppleIdTokenVerifierService(JwkProvider jwkProvider, AppleLoginProperties properties) {
        this.jwkProvider = jwkProvider;
        this.properties = properties;
    }

    /**
     * @throws ApiException 503 if Apple sign-in isn't configured in this environment (no client
     *         ids set — see {@link AppleLoginProperties}), or 401 if the token fails verification
     *         for any reason (bad signature, unknown key id, wrong audience/issuer, expired, or an
     *         unverified email) — deliberately one generic failure mode, same posture as the
     *         Google verifier and for the same reason: none of the specific causes are actionable
     *         by whoever is signing in.
     */
    public AppleIdentity verify(String idTokenString) {
        if (!properties.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Sign in with Apple is not configured on this server.");
        }
        Jws<Claims> jws;
        try {
            jws = Jwts.parser()
                    .keyLocator(this::resolveSigningKey)
                    .requireIssuer(APPLE_ISSUER)
                    // Second self-review pass: jjwt's own default is ZERO clock-skew tolerance,
                    // unlike GoogleIdTokenVerifier -- confirmed against google-oauth-client's
                    // actual source, not assumed -- which defaults to 300s
                    // (IdTokenVerifier.DEFAULT_TIME_SKEW_SECONDS) precisely because a JWT's
                    // issuer and this JVM's clock are never perfectly synchronized. Matching that
                    // tolerance here avoids Apple sign-in spuriously failing as "expired" under
                    // ordinary clock drift that Google sign-in would have tolerated.
                    .clockSkewSeconds(300)
                    .build()
                    .parseSignedClaims(idTokenString);
        } catch (JwtException | IllegalArgumentException e) {
            // IllegalArgumentException: a string that isn't even shaped like a JWT, same as the
            // Google verifier's own catch. JwtException covers a bad signature, an unresolvable
            // key id (resolveSigningKey below throws SecurityException, itself a JwtException),
            // a wrong issuer (requireIssuer above) and an expired token (against the real clock,
            // widened by clockSkewSeconds above), all in one place.
            log.warn("Apple ID token verification failed", e);
            throw invalidToken();
        }
        Claims claims = jws.getPayload();
        var audience = claims.getAudience();
        if (audience == null || properties.getClientIds().stream().noneMatch(audience::contains)) {
            log.warn("Apple ID token had a valid signature but an unrecognized audience -- refusing");
            throw invalidToken();
        }
        if (!isEmailVerified(claims)) {
            log.warn("Apple ID token had a valid signature but an unverified email -- refusing");
            throw invalidToken();
        }
        String email = claims.get("email", String.class);
        if (email == null || email.isBlank()) {
            throw invalidToken();
        }
        return new AppleIdentity(email, claims.getSubject());
    }

    private Key resolveSigningKey(Header header) {
        String keyId = header instanceof ProtectedHeader protectedHeader ? protectedHeader.getKeyId() : null;
        if (keyId == null) {
            throw new SecurityException("Apple ID token is missing a key id.");
        }
        try {
            return jwkProvider.get(keyId).getPublicKey();
        } catch (JwkException e) {
            throw new SecurityException("Unable to resolve Apple's signing key for kid=" + keyId, e);
        }
    }

    /**
     * Apple documents {@code email_verified} as a Boolean but has shipped it as the literal
     * string {@code "true"}/{@code "false"} in real tokens for years — a known Apple quirk, not a
     * malformed token. Accept either shape rather than trusting the documented type and rejecting
     * every real-world token.
     */
    private boolean isEmailVerified(Claims claims) {
        Object raw = claims.get("email_verified");
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof String str) {
            return Boolean.parseBoolean(str);
        }
        return false;
    }

    private static ApiException invalidToken() {
        return new ApiException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid Apple sign-in token.");
    }
}
