package com.finora.integrations.google;

import com.finora.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Reads from the Gmail API. Separate from {@link GoogleOAuthClient}, which only ever talks to
 * Google's OAuth endpoints — different concern, different failure modes, different scopes.
 *
 * <h2>C2 scope: reachability only</h2>
 *
 * One call, {@link #getProfile}, which is the cheapest thing the Gmail API offers. It exists to
 * answer a question nothing could answer before: <b>can Finora actually read this mailbox?</b>
 *
 * <p>That is not the same question as "does the token refresh". A user can complete consent while
 * declining an individual scope, leaving a connection with a perfectly valid token that cannot read
 * a single message. {@code GmailConnection.grantedScopes} was recorded for exactly this reason —
 * its own doc comment says the shortfall "must be visible here rather than discovered on a first
 * sync" — and until now nothing inspected it or tested the access it implies.
 *
 * <p>Message listing and fetching are C3. Adding them here before anything consumes them would
 * repeat the dead-code gap this project has already been caught by twice.
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
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(timeouts))
                .build();
    }

    /**
     * Gmail's profile response. {@code historyId} is the mailbox's current position — C3's
     * incremental sync starts from one of these, which is a second reason this is the right first
     * call to make.
     */
    public record Profile(String emailAddress, Long messagesTotal, Long threadsTotal, String historyId) {}

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
     * @throws GmailScopeNotGrantedException when Gmail refuses on permission grounds
     * @throws ApiException for transient failures
     */
    public Profile getProfile(String accessToken) {
        try {
            Profile profile = restClient.get()
                    .uri(properties.getGmailApiBaseUrl() + "/gmail/v1/users/me/profile")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, res) -> {
                        int status = res.getStatusCode().value();
                        String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        if (status == 403) {
                            // Deliberately not retried and not silently swallowed: a missing scope
                            // is permanent until the user re-consents, and every future sync would
                            // fail identically.
                            throw new GmailScopeNotGrantedException(
                                    "Gmail refused access to this mailbox (403).");
                        }
                        // 401 and everything else 4xx: the token is the likely problem, and a fresh
                        // one may fix it. Not a reason to tell the user their grant is gone.
                        log.warn("Gmail API refused a profile read with {} ({})", status,
                                body.isBlank() ? "no body" : "body suppressed");
                        throw new ApiException(HttpStatus.BAD_GATEWAY,
                                "Gmail refused the request. Try again shortly.");
                    })
                    .body(Profile.class);

            if (profile == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Gmail returned an empty profile.");
            }
            return profile;
        } catch (GmailScopeNotGrantedException | ApiException e) {
            throw e;
        } catch (Exception e) {
            // The message never echoes the exception: it can carry the request, and that request
            // carries a bearer token.
            log.warn("Gmail profile read failed transiently: {}", e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not reach Gmail. Try again shortly.");
        }
    }
}
