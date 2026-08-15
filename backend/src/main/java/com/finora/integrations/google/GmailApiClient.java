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
import java.util.Base64;
import java.util.List;

/**
 * Reads from the Gmail API. Separate from {@link GoogleOAuthClient}, which only ever talks to
 * Google's OAuth endpoints — different concern, different failure modes, different scopes.
 *
 * <h2>What this client reads, and in what order it is meant to be called</h2>
 *
 * {@link #getProfile} proves the mailbox is reachable. {@link #listMessages} returns ids — no
 * content. {@link #getMessageHeaders} returns headers — still no content, and specifically the
 * headers the C3 trust gate needs. Only {@link #getMessageBody}, added in C5, can return a
 * message's actual content, and its own doc comment states the one rule that makes that safe:
 * it is for messages the gate has already cleared, never for deciding whether to clear one.
 *
 * <p>The type system does not enforce that ordering — a {@code String} messageId is a
 * {@code String} messageId, and nothing stops a future caller from passing an unvetted one to
 * {@link #getMessageBody}. The methods are ordered here, and their doc comments say so
 * explicitly, because that is what is available: this class cannot make itself the trust
 * decision, since it has no access to {@code SenderAuthenticationService} or the processed-message
 * table discovery already checked against. Enforcement lives one layer up, in whatever calls this
 * client — currently {@code GmailMessageDiscoveryService} for headers, and C5-B's staging bridge
 * for bodies, which is expected to call {@link #getMessageBody} only for a message it already
 * knows is {@code GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED}.
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

    /** A message's body — C5. See {@link #getMessageBody}'s doc comment for who may call this and
     *  when. {@code html}/{@code plainText} are independently nullable: a plain-text-only message
     *  has no {@code html}, and (rarely) the reverse. */
    public record MessageBody(String html, String plainText) {}

    /**
     * The raw shape of Gmail's {@code format=full} response — used only inside
     * {@link #getMessageBody}, never returned. Gmail returns every header the message carries under
     * {@code format=full}, with no allowlist equivalent to {@code metadataHeaders}; this type does
     * not model a headers field at all, so there is nowhere for a Subject or a recipient list to be
     * held even transiently, let alone leak into {@link MessageBody}.
     */
    private record RawMessage(RawPart payload) {
        private record RawPart(String mimeType, RawBody body, List<RawPart> parts) {}
        private record RawBody(String data) {}
    }

    /**
     * Fetches a message's body — html preferred, plain text as a fallback, and only those two.
     *
     * <h2>The rule this method exists under, not just documents</h2>
     *
     * <b>Call this only for a message {@code SenderAuthenticationService} has already marked
     * trusted.</b> Everything from {@link #getMessageHeaders} onward in this class exists to answer
     * that question BEFORE any content is downloaded; calling this for an unvetted message id
     * defeats the entire point of C3 and C4 — the content of mail from a sender the gate would have
     * rejected reaches the process anyway, just one call later than it would have without the gate
     * at all. Nothing in the type system stops that call; see the class doc for why, and where
     * enforcement actually lives.
     *
     * <h2>Why {@code format=full} is safe here specifically</h2>
     *
     * {@link #getMessageHeaders} avoids {@code format=full} because it would return headers the
     * gate does not need and this codebase should not hold ({@code Subject}, {@code To}). That
     * concern does not disappear here — it is handled differently: {@link RawMessage} has no field
     * for headers at all, so {@link #findPart} can only ever extract {@code body} content, and
     * nothing this method returns can carry a header value even by accident.
     *
     * <p>The body itself is still attacker-shaped content from the sender's point of view — this
     * method fetches it, it does not sanitize it. {@code MerchantEmailSanitizer} is the mandatory
     * next step for anything this returns; see its class doc.
     *
     * @return html and/or plainText, whichever the message actually carries; either may be null
     */
    public MessageBody getMessageBody(String accessToken, String messageId) {
        String uri = properties.getGmailApiBaseUrl()
                + "/gmail/v1/users/me/messages/" + encode(messageId) + "?format=full";

        RawMessage raw = get(accessToken, uri, RawMessage.class, "message body");
        return new MessageBody(findPart(raw.payload(), "text/html"),
                findPart(raw.payload(), "text/plain"));
    }

    /**
     * Depth-first search for the first part of the given MIME type. Gmail nests a message's real
     * content under {@code multipart/alternative} (html + plain-text siblings) and often another
     * layer of {@code multipart/mixed} or {@code multipart/related} above that (attachments, inline
     * images) — a single-level scan of {@code parts} would miss content most real messages carry.
     *
     * <p>Stops at the first match rather than collecting all of them: a message can legitimately
     * carry more than one {@code text/html} part (a multipart/related structure with inline-image
     * alternatives), and this method's job is "the body", not an inventory of every part Gmail sent.
     *
     * <p>A part with no inline {@code body.data} — an attachment, referenced by {@code attachmentId}
     * instead — is skipped rather than fetched. Attachment handling is explicitly out of scope (the
     * design proposal's own exclusion list), and skipping here is what keeps that true structurally:
     * there is no code path in this method that can reach an attachment's bytes at all.
     */
    private static String findPart(RawMessage.RawPart part, String mimeType) {
        if (part == null) return null;
        if (mimeType.equals(part.mimeType()) && part.body() != null && part.body().data() != null) {
            return decodeBase64Url(part.body().data());
        }
        if (part.parts() != null) {
            for (RawMessage.RawPart child : part.parts()) {
                String found = findPart(child, mimeType);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Gmail's {@code body.data} is base64url without padding. {@link java.util.Base64}'s decoder
     *  is not universally lenient about missing padding across the values Gmail actually sends, so
     *  padding is restored explicitly rather than relying on that. */
    private static String decodeBase64Url(String unpadded) {
        return new String(Base64.getUrlDecoder().decode(unpadded), StandardCharsets.UTF_8);
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
