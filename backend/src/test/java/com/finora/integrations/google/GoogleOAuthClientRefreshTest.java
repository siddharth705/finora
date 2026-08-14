package com.finora.integrations.google;

import com.finora.exception.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code invalid_grant} distinction, executed rather than mocked.
 *
 * <h2>Why this exists</h2>
 *
 * {@code GmailAccessTokenServiceTest} mocks {@link GoogleOAuthClient#refreshAccessToken}, so the
 * code that actually classifies Google's error responses never ran there — the whole point of C1
 * had tests around it but not on it. Two things were unverifiable by reading:
 *
 * <ul>
 *   <li>whether {@code invalid_grant} is really detected in the response body Google sends;</li>
 *   <li>whether a {@link GmailReauthRequiredException} thrown from inside {@code RestClient}'s
 *       {@code onStatus} handler PROPAGATES, or is wrapped by RestClient and swallowed by the
 *       generic {@code catch (Exception)} — which would silently downgrade every dead grant to a
 *       "transient" failure and retry it forever.</li>
 * </ul>
 *
 * <p>Both are behaviour of a library boundary, so the only way to know is to run it. This stands up
 * a real HTTP server (the JDK's own — no new dependency) and points the client at it via the now
 * configurable endpoint.
 *
 * <p>Getting this wrong is a user-visible bug in both directions: treat a blip as revocation and a
 * working integration disconnects itself; treat revocation as a blip and the mailbox stops syncing
 * with nothing ever explaining why.
 */
class GoogleOAuthClientRefreshTest {

    private HttpServer server;
    private GoogleOAuthClient client;

    /** What the stub answers with — set per test. */
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void startStubGoogle() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            requestCount.incrementAndGet();
            byte[] payload = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();

        GoogleOAuthProperties properties = new GoogleOAuthProperties();
        properties.setClientId("test-client-id");
        properties.setClientSecret("test-client-secret");
        properties.setRedirectUri("https://api.example.test/callback");
        properties.setTokenEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/token");
        client = new GoogleOAuthClient(properties);
    }

    @AfterEach
    void stopStubGoogle() {
        if (server != null) server.stop(0);
    }

    @Test
    void refreshAccessToken_returnsTheAccessTokenOnSuccess() {
        status.set(200);
        body.set("{\"access_token\":\"fresh-token\",\"expires_in\":3599,\"token_type\":\"Bearer\"}");

        assertThat(client.refreshAccessToken("stored-refresh-token").access_token())
                .isEqualTo("fresh-token");
    }

    /**
     * The case the whole design turns on. Google's real shape for a revoked, expired, or
     * password-invalidated grant.
     */
    @Test
    @DisplayName("invalid_grant surfaces as GmailReauthRequiredException, not a generic failure")
    void refreshAccessToken_whenGrantIsDead_throwsReauthRequired() {
        status.set(400);
        body.set("{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}");

        assertThatThrownBy(() -> client.refreshAccessToken("revoked-token"))
                .as("this must survive RestClient's onStatus handling -- if it were wrapped and "
                        + "caught by the generic handler, every dead grant would be retried forever")
                .isInstanceOf(GmailReauthRequiredException.class);
    }

    /**
     * A 4xx that is NOT invalid_grant means the REQUEST is wrong — almost always misconfigured
     * client credentials. Blaming the user's grant for an operator error would tell them to
     * reconnect, which cannot fix it.
     */
    @Test
    @DisplayName("invalid_client is transient-shaped, not a reauth signal")
    void refreshAccessToken_whenTheClientIsMisconfigured_doesNotClaimTheUserMustReconnect() {
        status.set(401);
        body.set("{\"error\":\"invalid_client\",\"error_description\":\"The OAuth client was not found.\"}");

        assertThatThrownBy(() -> client.refreshAccessToken("perfectly-good-token"))
                .isInstanceOf(ApiException.class)
                .isNotInstanceOf(GmailReauthRequiredException.class);
    }

    /** A 5xx says nothing about the grant. Treating it as revocation would disconnect working
     *  integrations whenever Google has a bad minute. */
    @Test
    void refreshAccessToken_whenGoogleErrors_isTransientNotReauth() {
        status.set(503);
        body.set("{\"error\":\"backend_error\"}");

        assertThatThrownBy(() -> client.refreshAccessToken("perfectly-good-token"))
                .isInstanceOf(ApiException.class)
                .isNotInstanceOf(GmailReauthRequiredException.class);
    }

    @Test
    void refreshAccessToken_whenGoogleReturnsNoToken_isTransient() {
        status.set(200);
        body.set("{\"expires_in\":3599}");

        assertThatThrownBy(() -> client.refreshAccessToken("stored-refresh-token"))
                .isInstanceOf(ApiException.class)
                .isNotInstanceOf(GmailReauthRequiredException.class);
    }

    /**
     * A dead grant must cost exactly one request. Retrying inside the client would multiply quota
     * burn against a credential that can never succeed, and would hide the reauth signal behind a
     * delay.
     */
    @Test
    void refreshAccessToken_doesNotRetryADeadGrant() {
        status.set(400);
        body.set("{\"error\":\"invalid_grant\"}");
        requestCount.set(0);

        assertThatThrownBy(() -> client.refreshAccessToken("revoked-token"))
                .isInstanceOf(GmailReauthRequiredException.class);

        assertThat(requestCount.get()).isEqualTo(1);
    }
}
