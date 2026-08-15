package com.finora.integrations.google;

import com.finora.exception.ApiException;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Reads from the Gmail API. Separate from {@link GoogleOAuthClient}, which only ever talks to
 * Google's OAuth endpoints — different concern, different failure modes, different scopes.
 *
 * <h2>What this client can and cannot read</h2>
 *
 * Three calls, and the ceiling on all of them is deliberate: {@link #getProfile} proves the mailbox
 * is reachable, {@link #listMessages} returns ids, and {@link #getMessageHeaders} returns headers.
 * <b>Nothing here can fetch a message body.</b>
 *
 * <p>That ceiling is the integration's main safety property, not an omission. Discovery decides
 * whether a sender is trusted from headers alone, so a client that could not download a body cannot
 * download the body of mail from a sender the gate is about to reject. See
 * {@link #getMessageHeaders} for the full reasoning; C5 adds body fetching as its own explicit step,
 * for cleared messages only.
 *
 * <p>{@link #getProfile} answers a question nothing else can: <b>can Finora actually read this
 * mailbox?</b> That is not the same question as "does the token refresh". A user can complete
 * consent while declining an individual scope, leaving a connection with a perfectly valid token
 * that cannot read a single message. {@code GmailConnection.grantedScopes} was recorded for exactly
 * this reason — its own doc comment says the shortfall "must be visible here rather than discovered
 * on a first sync".
 */
@Component
public class GmailApiClient {

    private static final Logger log = LoggerFactory.getLogger(GmailApiClient.class);

    /** The scope this integration cannot function without. */
    public static final String GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private final GoogleOAuthProperties properties;
    private final RestClient restClient;

    public GmailApiClient(GoogleOAuthProperties properties) {
        this.properties = properties;
        ClientHttpRequestFactorySettings timeouts = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder()
                // Apache HttpClient is picked up from the classpath, and its default retry strategy
                // re-executes 429 and 503 once after a one-second sleep. That default is wrong here
                // in both directions:
                //
                //   - A 429 IS Gmail saying the per-user rate limit is spent. Retrying a second later
                //     adds load to a mailbox that is already over quota.
                //   - A discovery run makes one request per candidate message. During an outage the
                //     hidden retry doubles the request count and spends the run's wall-clock budget
                //     sleeping, so a run that should have failed fast and resumed from its checkpoint
                //     instead burns its whole window making calls that cannot succeed.
                //
                // Retrying is the RUN's job, not the request's: the checkpoint in
                // gmail_processed_messages makes the next scheduled tick resume for free, which is
                // strictly cheaper than any inline retry. Made explicit rather than left to the
                // classpath, because a transitive dependency should not decide a quota policy.
                .requestFactory(ClientHttpRequestFactoryBuilder.httpComponents()
                        .withHttpClientCustomizer(HttpClientBuilder::disableAutomaticRetries)
                        .build(timeouts))
                .build();
    }

    /**
     * Gmail's profile response. {@code historyId} is the mailbox's current position — incremental
     * sync starts from one of these, which is a second reason this is the right first call to make.
     */
    public record Profile(String emailAddress, Long messagesTotal, Long threadsTotal, String historyId) {}

    /** One page of message ids. Gmail's list endpoint returns ids only — no content. */
    public record MessagePage(List<MessageId> messages, String nextPageToken) {}

    public record MessageId(String id, String threadId) {}

    /**
     * A message's headers, without its body.
     *
     * <p>{@code internalDate} is Gmail's own receipt timestamp in epoch milliseconds, as a string —
     * the field is a 64-bit value and Google serialises those as strings in JSON.
     */
    public record MessageHeaders(String id, String threadId, String internalDate, Payload payload) {

        public record Payload(List<Header> headers) {}
        public record Header(String name, String value) {}

        /** Case-insensitive header lookup — RFC 5322 field names are case-insensitive, and Gmail
         *  echoes whatever casing the sender used. */
        public String header(String name) {
            if (payload == null || payload.headers() == null) return null;
            for (Header h : payload.headers()) {
                if (h.name() != null && h.name().equalsIgnoreCase(name)) return h.value();
            }
            return null;
        }
    }

    /**
     * Reads the mailbox profile — proof that the granted token can actually reach Gmail.
     *
     * <p>Classification matters as much as the call, and mirrors
     * {@link GoogleOAuthClient#refreshAccessToken}'s reasoning:
     *
     * <ul>
     *   <li><b>401</b> — the access token is bad or expired. Recoverable by minting a new one, so
     *       this is transient from the caller's point of view; if the underlying grant is dead,
     *       {@code refreshAccessToken} is where that surfaces.</li>
     *   <li><b>403</b> — authenticated, but not permitted. In practice this means the scope was
     *       never granted (or the Gmail API is disabled on the Cloud project). Only the user can
     *       fix the first by reconnecting and granting it, so it is NOT transient.</li>
     *   <li><b>429 / 5xx</b> — rate limits and outages. Transient by definition.</li>
     * </ul>
     *
     * <p>Classification lives in {@link #get}, which every call in this class shares.
     *
     * @throws GmailScopeNotGrantedException when Gmail refuses on permission grounds
     * @throws ApiException for transient failures
     */
    public Profile getProfile(String accessToken) {
        return get(accessToken, properties.getGmailApiBaseUrl() + "/gmail/v1/users/me/profile",
                Profile.class, "profile read");
    }

    /**
     * Lists candidate message ids. Returns <b>ids only</b> — Gmail's list endpoint carries no
     * content, which is why discovery starts here rather than with a search that returns snippets.
     *
     * @param query      a Gmail search expression, e.g. {@code after:2026/05/17 category:purchases}
     * @param pageToken  {@code null} for the first page, else the previous page's token
     * @param maxResults page size, bounded by the caller's per-run cap
     */
    public MessagePage listMessages(String accessToken, String query, String pageToken, int maxResults) {
        StringBuilder uri = new StringBuilder(properties.getGmailApiBaseUrl())
                .append("/gmail/v1/users/me/messages?maxResults=").append(maxResults);
        if (query != null && !query.isBlank()) {
            uri.append("&q=").append(encode(query));
        }
        if (pageToken != null && !pageToken.isBlank()) {
            uri.append("&pageToken=").append(encode(pageToken));
        }

        MessagePage page = get(accessToken, uri.toString(), MessagePage.class, "message list");

        // Gmail omits `messages` entirely on an empty result rather than sending []. Normalising
        // here keeps every caller from having to null-check a collection.
        return page.messages() == null ? new MessagePage(List.of(), page.nextPageToken()) : page;
    }

    /**
     * Fetches a message's headers and <b>deliberately not its body</b>.
     *
     * <h2>Why format=metadata is the security-relevant part of this method</h2>
     *
     * Discovery has to know who sent a message before it can decide whether that sender is trusted —
     * and the trust decision is made from headers alone ({@code Authentication-Results}). Fetching
     * the full message first would mean downloading the content of mail from senders the gate is
     * about to reject: attacker-controlled bytes entering the process for no reason, plus the
     * bandwidth and quota of every non-receipt in a mailbox.
     *
     * <p>{@code format=metadata} makes that structural rather than a matter of discipline. There is
     * no body in the response to mishandle, so no later change can accidentally start parsing one.
     * When C5 needs a body, it fetches it again — explicitly, and only for a message the gate has
     * already cleared.
     *
     * <p>The header allowlist narrows it further: Gmail returns only the fields named, so the
     * response cannot carry a subject or recipient list that nothing asked for and §12.4 says should
     * not be held.
     */
    public MessageHeaders getMessageHeaders(String accessToken, String messageId) {
        String uri = properties.getGmailApiBaseUrl()
                + "/gmail/v1/users/me/messages/" + encode(messageId)
                + "?format=metadata"
                + "&metadataHeaders=Authentication-Results"
                + "&metadataHeaders=From"
                + "&metadataHeaders=Date";

        return get(accessToken, uri, MessageHeaders.class, "message headers");
    }

    /**
     * Percent-encodes one URI component.
     *
     * <p>{@link URLEncoder} implements form encoding, where a space becomes {@code +}. That is only
     * correct inside a query value, and this method also encodes a path segment (the message id), so
     * the {@code +} is rewritten to {@code %20} — which means the same thing in both places.
     */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * The shared GET path, so every Gmail call classifies refusals identically.
     *
     * <p>Extracted rather than duplicated because the classification IS the contract — a 403 that
     * one method treated as transient while another treated it as a scope problem would make the
     * caller's handling depend on which call happened to fail first.
     *
     * <p>The URI is passed as a {@link URI}, not a String. {@code RestClient.uri(String)} treats its
     * argument as a URI <i>template</i> and encodes it, which would percent-encode the {@code %} of
     * an already-encoded value and send Gmail a query nobody wrote. A parsed {@code URI} is used
     * verbatim, so {@link #encode} stays the single place encoding happens.
     */
    private <T> T get(String accessToken, String uri, Class<T> type, String what) {
        try {
            T body = restClient.get()
                    .uri(URI.create(uri))
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, res) -> {
                        int status = res.getStatusCode().value();
                        if (status == 403) {
                            throw new GmailScopeNotGrantedException(
                                    "Gmail refused access to this mailbox (403).");
                        }
                        if (status == 404) {
                            // Separated from the transient bucket deliberately: retrying a message
                            // the user deleted never succeeds, and a run that aborts on it resumes
                            // into the same id every tick. See GmailMessageGoneException.
                            throw new GmailMessageGoneException(
                                    "Gmail no longer has this message (404).");
                        }
                        log.warn("Gmail API refused a {} with {}", what, status);
                        throw new ApiException(HttpStatus.BAD_GATEWAY,
                                "Gmail refused the request. Try again shortly.");
                    })
                    .body(type);

            if (body == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Gmail returned an empty " + what + ".");
            }
            return body;
        } catch (GmailScopeNotGrantedException | GmailMessageGoneException | ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Gmail {} failed transiently: {}", what, e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach Gmail. Try again shortly.");
        }
    }
}
