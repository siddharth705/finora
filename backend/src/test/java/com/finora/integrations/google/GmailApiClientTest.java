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
 * The Gmail API boundary, executed against a real HTTP server rather than mocked.
 *
 * <p>Same standard as {@code GoogleOAuthClientRefreshTest}: the value of this client is its
 * classification of Gmail's refusals, and a classifier whose only execution is a stubbed return
 * value is unverified. The distinction it draws — 403 means a permission the user must grant, 401
 * and 5xx mean try again — decides whether Finora tells someone to re-consent or to wait.
 */
class GmailApiClientTest {

    private HttpServer server;
    private GmailApiClient client;

    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicReference<String> seenAuthHeader = new AtomicReference<>();

    @BeforeEach
    void startStubGmail() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gmail/v1/users/me/profile", exchange -> {
            seenAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] payload = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();

        GoogleOAuthProperties properties = new GoogleOAuthProperties();
        properties.setGmailApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        client = new GmailApiClient(properties);
    }

    @AfterEach
    void stopStubGmail() {
        if (server != null) server.stop(0);
    }

    @Test
    void getProfile_returnsTheMailboxProfileAndSendsTheBearerToken() {
        status.set(200);
        body.set("{\"emailAddress\":\"mailbox@example.test\",\"messagesTotal\":1234,"
                + "\"threadsTotal\":567,\"historyId\":\"98765\"}");

        GmailApiClient.Profile profile = client.getProfile("an-access-token");

        assertThat(profile.emailAddress()).isEqualTo("mailbox@example.test");
        assertThat(profile.messagesTotal()).isEqualTo(1234);
        // historyId is what C3's incremental sync will start from -- worth pinning that it survives
        // deserialization rather than silently arriving null.
        assertThat(profile.historyId()).isEqualTo("98765");
        assertThat(seenAuthHeader.get()).isEqualTo("Bearer an-access-token");
    }

    /**
     * The case C2 exists for. A user who declined {@code gmail.readonly} at the consent screen has a
     * perfectly valid token and a CONNECTED row, and Gmail answers 403 to everything. Retrying never
     * grants a scope, so this must not look transient.
     */
    @Test
    @DisplayName("403 is a permission problem the user must fix, not something to retry")
    void getProfile_whenGmailRefusesOnPermission_throwsScopeNotGranted() {
        status.set(403);
        body.set("{\"error\":{\"code\":403,\"message\":\"Request had insufficient authentication scopes.\"}}");

        assertThatThrownBy(() -> client.getProfile("token-without-gmail-scope"))
                .isInstanceOf(GmailScopeNotGrantedException.class);
    }

    /**
     * 401 means the access token is stale, which minting a new one fixes. Treating it as a scope
     * problem would tell the user to re-consent over an expired token.
     */
    @Test
    void getProfile_whenTheTokenIsRejected_isTransientNotAScopeProblem() {
        status.set(401);
        body.set("{\"error\":{\"code\":401,\"message\":\"Invalid Credentials\"}}");

        assertThatThrownBy(() -> client.getProfile("stale-token"))
                .isInstanceOf(ApiException.class)
                .isNotInstanceOf(GmailScopeNotGrantedException.class);
    }

    @Test
    void getProfile_whenRateLimited_isTransient() {
        status.set(429);
        body.set("{\"error\":{\"code\":429,\"message\":\"User-rate limit exceeded.\"}}");

        assertThatThrownBy(() -> client.getProfile("a-token"))
                .isInstanceOf(ApiException.class)
                .isNotInstanceOf(GmailScopeNotGrantedException.class);
    }

    @Test
    void getProfile_whenGmailErrors_isTransient() {
        status.set(503);
        body.set("{\"error\":{\"code\":503}}");

        assertThatThrownBy(() -> client.getProfile("a-token"))
                .isInstanceOf(ApiException.class)
                .isNotInstanceOf(GmailScopeNotGrantedException.class);
    }

    /** Gmail adds response fields over time. If unknown properties were ever rejected, a field
     *  Google introduces would break every profile read in production while tests kept passing. */
    @Test
    void getProfile_toleratesFieldsGmailAddsThatWeDoNotModel() {
        status.set(200);
        body.set("{\"emailAddress\":\"mailbox@example.test\",\"messagesTotal\":1,"
                + "\"threadsTotal\":1,\"historyId\":\"5\",\"somethingGoogleAddedLater\":true}");

        assertThat(client.getProfile("a-token").emailAddress()).isEqualTo("mailbox@example.test");
    }

    /** No part of a refusal body reaches the caller -- Gmail error payloads can echo request
     *  context, and the bearer token travels in that request. */
    @Test
    void getProfile_neverSurfacesGmailsRawErrorBodyToTheCaller() {
        status.set(403);
        body.set("{\"error\":{\"message\":\"secret-looking-internal-detail\"}}");

        assertThatThrownBy(() -> client.getProfile("a-token"))
                .hasMessageNotContaining("secret-looking-internal-detail");
    }
}
