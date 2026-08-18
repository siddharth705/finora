package com.finora.integrations.apple.login;

import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.JwkProviderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * Builds the {@link JwkProvider} bean separately from {@link AppleIdTokenVerifierService}, same
 * split as {@code GoogleIdTokenVerifierConfig} / {@code GoogleIdTokenVerifierService} and for the
 * same reason: a test can hand the service a mock provider instead of one that does real network
 * calls against Apple's own key endpoint.
 *
 * <p>Unconditional bean, built regardless of whether Apple sign-in is configured — mirrors
 * {@code GoogleIdTokenVerifierConfig}'s own precedent (and the lesson recorded in its doc comment:
 * a {@code @Bean} method returning {@code null} breaks Spring's constructor-injection entirely,
 * not "injects null" as it looks like it should). Constructing a {@link JwkProviderBuilder} does
 * no network I/O itself — it only builds a client that fetches lazily, on the first {@code get()}
 * call — so there is nothing to gate behind {@link AppleLoginProperties#isConfigured()} here.
 *
 * <p>Cache and rate-limit values follow the defaults this library ships for exactly this endpoint
 * shape (a small, infrequently-rotated key set): a 10-minute in-memory cache and a 10-requests-
 * per-minute ceiling on Apple's endpoint, so a burst of sign-in attempts with a novel {@code kid}
 * (a real key rotation, not an attack) can't turn into a request storm against Apple.
 */
@Configuration
public class AppleIdTokenVerifierConfig {

    private static final String APPLE_JWKS_URL = "https://appleid.apple.com/auth/keys";

    @Bean
    public JwkProvider appleJwkProvider() {
        try {
            return new JwkProviderBuilder(new URL(APPLE_JWKS_URL))
                    .cached(10, 24, TimeUnit.HOURS)
                    .rateLimited(10, 1, TimeUnit.MINUTES)
                    .build();
        } catch (MalformedURLException e) {
            // Unreachable: APPLE_JWKS_URL is a compile-time constant, valid by construction.
            throw new IllegalStateException(e);
        }
    }
}
