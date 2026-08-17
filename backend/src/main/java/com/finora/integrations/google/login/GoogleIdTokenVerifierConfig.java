package com.finora.integrations.google.login;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Builds the {@link GoogleIdTokenVerifier} bean separately from
 * {@link GoogleIdTokenVerifierService}, so a test can construct that service directly with a
 * mock verifier instead of needing a real network-backed one — {@code GoogleIdTokenVerifier}
 * itself does real cryptographic work and isn't something a unit test should exercise for real.
 *
 * <p>Always builds a real instance, even when unconfigured (empty {@code clientIds}) — matching
 * {@link com.finora.integrations.google.GoogleOAuthClient}'s own precedent of being an
 * unconditional bean whose callers check {@link GoogleLoginProperties#isConfigured()} before use,
 * rather than a bean that's sometimes null. Building this does no network I/O (that only happens
 * inside {@code verify()}), and {@link GoogleIdTokenVerifierService} never calls {@code verify()}
 * without checking {@code isConfigured()} first, so an empty audience list here is never actually
 * exercised. A null-returning {@code @Bean} method was tried first and doesn't work: Spring's
 * constructor-injection machinery treats a factory method returning {@code null} as "no autowire
 * candidate" for a required dependency and refuses to start the whole application context, not
 * "inject null" as this class originally assumed.
 */
@Configuration
public class GoogleIdTokenVerifierConfig {

    @Bean
    public GoogleIdTokenVerifier googleIdTokenVerifier(GoogleLoginProperties properties) {
        return new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(properties.isConfigured() ? properties.getClientIds() : List.of("unconfigured"))
                .build();
    }
}
