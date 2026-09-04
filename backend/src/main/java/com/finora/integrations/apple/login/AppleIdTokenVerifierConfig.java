package com.finora.integrations.apple.login;

import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.JwkProviderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.MalformedURLException;
import java.net.URI;
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
 *
 * <p>{@code timeouts(...)} is the other half of that same fetch's safety, same BH-016 class of gap
 * as {@code ResendEmailProvider}/{@code FirebaseConfig}. Checked directly ({@code javap -c} on
 * {@code UrlJwkProvider}, this library's own fetch implementation) rather than assumed: without an
 * explicit value here, both fields stay {@code null} and the timeout-setting calls are skipped
 * entirely, leaving the fetch on {@link java.net.URLConnection}'s own default of no timeout at
 * all. (Google's equivalent, {@code GoogleIdTokenVerifierConfig}, does NOT need the same fix —
 * verified separately that google-http-client's {@code HttpRequest} already defaults
 * connect/read to 20 seconds each, unlike this library.) Same ten/twenty split as the other two
 * for the same reason: a best-effort verification call whose failure already surfaces as a normal
 * request error, so waiting longer buys nothing.
 */
@Configuration
public class AppleIdTokenVerifierConfig {

    private static final String APPLE_JWKS_URL = "https://appleid.apple.com/auth/keys";
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 20_000;

    @Bean
    public JwkProvider appleJwkProvider() {
        try {
            return new JwkProviderBuilder(URI.create(APPLE_JWKS_URL).toURL())
                    .cached(10, 24, TimeUnit.HOURS)
                    .rateLimited(10, 1, TimeUnit.MINUTES)
                    .timeouts(CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS)
                    .build();
        } catch (MalformedURLException e) {
            // Unreachable: APPLE_JWKS_URL is a compile-time constant, valid by construction.
            throw new IllegalStateException(e);
        }
    }
}
