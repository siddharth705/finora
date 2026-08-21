package com.finora.integrations.apple.login;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.finora.exception.ApiException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * There is no way to obtain a token genuinely signed by Apple's real private key in a test, so
 * this signs its own with a locally generated RSA keypair and a mocked {@link JwkProvider} that
 * hands back the matching public key for the token's {@code kid} -- exactly the seam {@link
 * AppleIdTokenVerifierConfig} exists to make mockable (see that class's own doc comment). What's
 * under test is the verification logic itself: signature, issuer, audience membership, expiry,
 * kid resolution, and the {@code email_verified} quirk-handling -- not Apple's own key
 * infrastructure, which {@code jwks-rsa} and jjwt already own.
 */
class AppleIdTokenVerifierServiceTest {

    private static final String KEY_ID = "test-key-1";
    private static final String BUNDLE_ID = "com.finoratech.app";
    private static final String ISSUER = "https://appleid.apple.com";

    private RSAPrivateKey privateKey;
    private JwkProvider jwkProvider;
    private AppleIdTokenVerifierService service;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();

        jwkProvider = mock(JwkProvider.class);
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(publicKey);
        when(jwkProvider.get(KEY_ID)).thenReturn(jwk);

        AppleLoginProperties properties = new AppleLoginProperties();
        properties.setClientIds(List.of(BUNDLE_ID));

        service = new AppleIdTokenVerifierService(jwkProvider, properties);
    }

    /** A token shaped exactly like a real Apple identity token, valid unless {@code customizer}
     *  changes something. */
    private String validToken(UnaryOperator<JwtBuilder> customizer) {
        JwtBuilder builder = Jwts.builder()
                .setHeaderParam("kid", KEY_ID)
                .issuer(ISSUER)
                .setAudience(BUNDLE_ID)
                .subject("apple-sub-123")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .claim("email", "amy@example.test")
                .claim("email_verified", "true");
        return customizer.apply(builder).signWith(privateKey).compact();
    }

    @Test
    @DisplayName("a genuinely valid token verifies, returning the email and Apple's stable subject id")
    void validToken_verifiesAndReturnsIdentity() {
        AppleIdentity identity = service.verify(validToken(b -> b));

        assertThat(identity.email()).isEqualTo("amy@example.test");
        assertThat(identity.sub()).isEqualTo("apple-sub-123");
    }

    @Test
    @DisplayName("email_verified shipped as the literal string \"true\" (Apple's real, documented quirk) still verifies")
    void emailVerifiedAsStringTrue_stillVerifies() {
        String token = validToken(b -> b.claim("email_verified", "true"));

        assertThat(service.verify(token).email()).isEqualTo("amy@example.test");
    }

    @Test
    @DisplayName("email_verified as a real Boolean also verifies -- accepts either documented shape")
    void emailVerifiedAsBoolean_stillVerifies() {
        String token = validToken(b -> b.claim("email_verified", Boolean.TRUE));

        assertThat(service.verify(token).email()).isEqualTo("amy@example.test");
    }

    @Test
    @DisplayName("email_verified = false is refused")
    void emailNotVerified_isRefused() {
        String token = validToken(b -> b.claim("email_verified", "false"));

        assertThatThrownBy(() -> service.verify(token)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("wrong issuer is refused")
    void wrongIssuer_isRefused() {
        String token = validToken(b -> b.issuer("https://evil.example.com"));

        assertThatThrownBy(() -> service.verify(token)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("an audience not in the configured client ids is refused")
    void unrecognizedAudience_isRefused() {
        String token = validToken(b -> b.setAudience("com.someone.else"));

        assertThatThrownBy(() -> service.verify(token)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("an expired token is refused")
    void expiredToken_isRefused() {
        // Must be older than the verifier's own clockSkewSeconds(300) tolerance -- see that
        // constant's own comment for why it exists -- or this asserts the wrong thing entirely.
        String token = validToken(b -> b.expiration(new Date(System.currentTimeMillis() - 600_000)));

        assertThatThrownBy(() -> service.verify(token)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a token expired only moments ago is still accepted -- within the clock-skew tolerance, same as Google's own verifier")
    void tokenExpiredWithinClockSkewTolerance_stillVerifies() {
        String token = validToken(b -> b.expiration(new Date(System.currentTimeMillis() - 60_000)));

        assertThat(service.verify(token).email()).isEqualTo("amy@example.test");
    }

    @Test
    @DisplayName("a token signed by a DIFFERENT key than the one its kid claims is refused -- proves signature verification is real, not skipped")
    void wrongSigningKey_isRefused() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPrivateKey attackerKey = (RSAPrivateKey) generator.generateKeyPair().getPrivate();

        String token = Jwts.builder()
                .setHeaderParam("kid", KEY_ID)
                .issuer(ISSUER)
                .setAudience(BUNDLE_ID)
                .subject("apple-sub-123")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .claim("email", "amy@example.test")
                .claim("email_verified", "true")
                .signWith(attackerKey)
                .compact();

        assertThatThrownBy(() -> service.verify(token)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("an unresolvable key id is refused, not a 500")
    void unknownKeyId_isRefused() throws JwkException {
        when(jwkProvider.get("unknown-kid")).thenThrow(new JwkException("no such key"));
        String token = validToken(b -> b.setHeaderParam("kid", "unknown-kid"));

        assertThatThrownBy(() -> service.verify(token)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("unconfigured (no client ids set) answers 503, never reaches token parsing")
    void unconfigured_answers503() {
        AppleIdTokenVerifierService unconfigured =
                new AppleIdTokenVerifierService(jwkProvider, new AppleLoginProperties());

        assertThatThrownBy(() -> unconfigured.verify(validToken(b -> b)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(jwkProvider);
    }
}
