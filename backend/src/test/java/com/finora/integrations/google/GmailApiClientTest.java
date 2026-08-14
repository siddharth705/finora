package com.finora.integrations.google;

import com.finora.exception.ApiException;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /** The raw request line Gmail would have received — path and query exactly as sent. */
    private final AtomicReference<String> seenUri = new AtomicReference<>();

    /** How many requests Gmail actually received -- the only way an invisible retry layer shows up. */
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void startStubGmail() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpHandler handler = exchange -> {
            seenAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenUri.set(exchange.getRequestURI().toString());
            requestCount.incrementAndGet();
            byte[] payload = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        };
        server.createContext("/gmail/v1/users/me/profile", handler);
        // Prefix match, so this serves both the list endpoint and /messages/{id}.
        server.createContext("/gmail/v1/users/me/messages", handler);
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

    // ---------------------------------------------------------------------------------------
    // listMessages
    // ---------------------------------------------------------------------------------------

    @Test
    void listMessages_returnsTheIdsAndTheTokenForTheNextPage() {
        status.set(200);
        body.set("{\"messages\":[{\"id\":\"m1\",\"threadId\":\"t1\"},{\"id\":\"m2\",\"threadId\":\"t1\"}],"
                + "\"nextPageToken\":\"page-2\",\"resultSizeEstimate\":2}");

        GmailApiClient.MessagePage page = client.listMessages("a-token", "category:purchases", null, 50);

        assertThat(page.messages()).extracting(GmailApiClient.MessageId::id).containsExactly("m1", "m2");
        assertThat(page.nextPageToken()).isEqualTo("page-2");
        assertThat(seenAuthHeader.get()).isEqualTo("Bearer a-token");
    }

    /**
     * The search expression has to survive the trip intact. A Gmail query contains spaces and colons,
     * so it is percent-encoded on the way out — and encoding it twice would send Gmail a literal
     * {@code %20} to search for, silently returning nothing while every status code stayed 200.
     *
     * <p>Asserting on the decoded query the server actually received, rather than on the string the
     * client built, is the only way that failure is visible.
     */
    @Test
    @DisplayName("the Gmail query arrives exactly as written, encoded once")
    void listMessages_sendsTheQueryUnmangled() {
        status.set(200);
        body.set("{\"messages\":[]}");
        String query = "after:2026/05/17 {category:purchases category:updates}";

        client.listMessages("a-token", query, "next-page-token", 25);

        Map<String, String> params = queryParams(seenUri.get());
        assertThat(params).containsEntry("q", query);
        assertThat(params).containsEntry("pageToken", "next-page-token");
        assertThat(params).containsEntry("maxResults", "25");
    }

    /**
     * Gmail omits {@code messages} altogether when a search matches nothing, rather than sending an
     * empty array. Left unnormalised that is a null every caller has to remember to check, and the
     * one that forgets NPEs on an empty mailbox — the most ordinary case there is.
     */
    @Test
    void listMessages_whenNothingMatches_returnsAnEmptyListNotNull() {
        status.set(200);
        body.set("{\"resultSizeEstimate\":0}");

        GmailApiClient.MessagePage page = client.listMessages("a-token", "category:purchases", null, 50);

        assertThat(page.messages()).isEmpty();
        assertThat(page.nextPageToken()).isNull();
    }

    /** The last page carries no token, which is how the caller knows to stop. */
    @Test
    void listMessages_onTheLastPage_returnsNoNextPageToken() {
        status.set(200);
        body.set("{\"messages\":[{\"id\":\"m9\",\"threadId\":\"t9\"}]}");

        assertThat(client.listMessages("a-token", null, null, 50).nextPageToken()).isNull();
    }

    @Test
    void listMessages_whenScopeWasNeverGranted_throwsScopeNotGranted() {
        status.set(403);
        body.set("{\"error\":{\"code\":403,\"message\":\"Insufficient Permission\"}}");

        assertThatThrownBy(() -> client.listMessages("a-token", null, null, 50))
                .isInstanceOf(GmailScopeNotGrantedException.class);
    }

    @Test
    void listMessages_whenGmailErrors_isTransient() {
        status.set(503);
        body.set("{\"error\":{\"code\":503}}");

        assertThatThrownBy(() -> client.listMessages("a-token", null, null, 50))
                .isInstanceOf(ApiException.class)
                .isNotInstanceOf(GmailScopeNotGrantedException.class);
    }

    /**
     * One refusal must cost exactly one request.
     *
     * <p>Apache HttpClient is on the classpath and its default retry strategy re-executes 429 and 503
     * once, after sleeping a second — a layer nothing in this class asks for and no other test can
     * see, because the exception it eventually throws is identical either way. A discovery run makes
     * one request per candidate message, so that invisible default silently doubles Gmail's request
     * count during an outage and spends the run's time budget sleeping.
     *
     * <p>Counting requests server-side is the only assertion that can tell the difference.
     */
    @Test
    @DisplayName("a rate-limited call is not retried behind our back")
    void gmailIsCalledExactlyOncePerRefusal() {
        status.set(429);
        body.set("{\"error\":{\"code\":429,\"message\":\"User-rate limit exceeded.\"}}");

        assertThatThrownBy(() -> client.listMessages("a-token", null, null, 50))
                .isInstanceOf(ApiException.class);

        assertThat(requestCount.get())
                .as("retrying is the run's job -- the checkpoint resumes for free")
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------
    // getMessageHeaders
    // ---------------------------------------------------------------------------------------

    /**
     * The security-relevant assertion in this file.
     *
     * <p>The trust gate reads headers, so the fetch must ask for headers — if this request ever went
     * out without {@code format=metadata}, Gmail would return the full body of every candidate
     * message, including messages from senders the gate is about to reject. Nothing downstream would
     * fail; the process would simply be holding attacker-controlled bytes it had no reason to have.
     * That regression is invisible except here.
     */
    @Test
    @DisplayName("headers are requested without the body, and only the headers the gate needs")
    void getMessageHeaders_requestsMetadataOnly() {
        status.set(200);
        body.set("{\"id\":\"m1\",\"threadId\":\"t1\",\"internalDate\":\"1755100000000\",\"payload\":{\"headers\":[]}}");

        client.getMessageHeaders("a-token", "m1");

        Map<String, String> params = queryParams(seenUri.get());
        assertThat(params).containsEntry("format", "metadata");
        assertThat(seenUri.get()).contains("metadataHeaders=Authentication-Results");
        // No subject and no To: the design proposal's 12.4 reasoning says Finora should not hold
        // what it does not need, and the allowlist is what makes that structural.
        assertThat(seenUri.get()).doesNotContain("Subject").doesNotContain("metadataHeaders=To");
    }

    @Test
    void getMessageHeaders_returnsTheHeadersTheGateReadsFrom() {
        status.set(200);
        body.set("{\"id\":\"m1\",\"threadId\":\"t1\",\"internalDate\":\"1755100000000\",\"payload\":{\"headers\":["
                + "{\"name\":\"From\",\"value\":\"Orders <orders@merchant.example>\"},"
                + "{\"name\":\"Authentication-Results\",\"value\":\"mx.google.com; dkim=pass header.i=@merchant.example\"}"
                + "]}}");

        GmailApiClient.MessageHeaders headers = client.getMessageHeaders("a-token", "m1");

        assertThat(headers.id()).isEqualTo("m1");
        // internalDate is a 64-bit epoch-millis value that Google serialises as a string; pinned
        // because deserialising it as a number would work until a mailbox crossed no boundary at all
        // and simply started arriving null.
        assertThat(headers.internalDate()).isEqualTo("1755100000000");
        assertThat(headers.header("Authentication-Results")).contains("dkim=pass");
    }

    /**
     * RFC 5322 field names are case-insensitive and Gmail echoes whatever casing the sender used, so
     * a sender writing {@code authentication-results} must not be able to make the gate see no
     * authentication header at all — which fails closed, but silently rejects legitimate mail.
     */
    @Test
    void getMessageHeaders_looksUpHeaderNamesCaseInsensitively() {
        status.set(200);
        body.set("{\"id\":\"m1\",\"payload\":{\"headers\":["
                + "{\"name\":\"authentication-results\",\"value\":\"mx.google.com; spf=pass\"}]}}");

        GmailApiClient.MessageHeaders headers = client.getMessageHeaders("a-token", "m1");

        assertThat(headers.header("Authentication-Results")).isEqualTo("mx.google.com; spf=pass");
        assertThat(headers.header("AUTHENTICATION-RESULTS")).isEqualTo("mx.google.com; spf=pass");
    }

    /** A message with no Authentication-Results at all -- the gate's NO_AUTHENTICATION_HEADER case,
     *  which must arrive as a null rather than as an NPE inside the lookup. */
    @Test
    void getMessageHeaders_whenAHeaderIsAbsent_returnsNull() {
        status.set(200);
        body.set("{\"id\":\"m1\",\"payload\":{\"headers\":[{\"name\":\"From\",\"value\":\"a@b.example\"}]}}");

        assertThat(client.getMessageHeaders("a-token", "m1").header("Authentication-Results")).isNull();
    }

    /** Gmail can return a message with no payload at all (drafts, purged messages). The lookup must
     *  survive it, because discovery calls this on ids it did not choose. */
    @Test
    void getMessageHeaders_whenThereIsNoPayload_returnsNullRatherThanFailing() {
        status.set(200);
        body.set("{\"id\":\"m1\",\"threadId\":\"t1\"}");

        assertThat(client.getMessageHeaders("a-token", "m1").header("From")).isNull();
    }

    @Test
    void getMessageHeaders_whenScopeWasNeverGranted_throwsScopeNotGranted() {
        status.set(403);
        body.set("{\"error\":{\"code\":403,\"message\":\"Insufficient Permission\"}}");

        assertThatThrownBy(() -> client.getMessageHeaders("a-token", "m1"))
                .isInstanceOf(GmailScopeNotGrantedException.class);
    }

    /** A message deleted between listing and fetching answers 404. Transient from the worker's point
     *  of view -- one message is skipped, the run continues -- and never a scope problem. */
    @Test
    void getMessageHeaders_whenTheMessageIsGone_isTransientNotAScopeProblem() {
        status.set(404);
        body.set("{\"error\":{\"code\":404,\"message\":\"Requested entity was not found.\"}}");

        assertThatThrownBy(() -> client.getMessageHeaders("a-token", "vanished"))
                .isInstanceOf(ApiException.class)
                .isNotInstanceOf(GmailScopeNotGrantedException.class);
    }

    @Test
    void getMessageHeaders_neverSurfacesGmailsRawErrorBodyToTheCaller() {
        status.set(403);
        body.set("{\"error\":{\"message\":\"secret-looking-internal-detail\"}}");

        assertThatThrownBy(() -> client.getMessageHeaders("a-token", "m1"))
                .hasMessageNotContaining("secret-looking-internal-detail");
    }

    /** Decodes the query string the stub server received, so assertions are about what Gmail would
     *  have read rather than about the string the client happened to build. */
    private static Map<String, String> queryParams(String uri) {
        Map<String, String> params = new LinkedHashMap<>();
        int q = uri.indexOf('?');
        if (q < 0) return params;
        for (String pair : uri.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }
}
