package com.finora.integrations.google;

import com.finora.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Everything that talks to Google — the only class in this package that makes an outbound call.
 *
 * <h2>No Google SDK, deliberately</h2>
 *
 * Phase B needs three things: build an authorization URL (string assembly), exchange a code for
 * tokens (one form POST), and read the account's identity (one GET). All three are plain HTTP.
 * Pulling in {@code google-api-client} and {@code google-api-services-gmail} for that would add a
 * large dependency tree to do what {@code RestClient} already does — and this codebase's only other
 * outbound integration ({@code ResendEmailProvider}) sets the precedent of a thin client over
 * {@code RestClient}. The Gmail SDK becomes worth its weight when Phase C actually reads mailboxes.
 *
 * <h2>Identity comes from userinfo, not from parsing the ID token</h2>
 *
 * The {@code openid} scope means the token response also carries an {@code id_token} JWT whose
 * payload holds {@code sub}. Reading it would mean either verifying Google's signature (fetching
 * and caching JWKS, handling rotation) or decoding without verification. Calling the userinfo
 * endpoint with the access token gets the same {@code sub} as ordinary JSON, authenticated by the
 * bearer token itself over TLS — no key handling, no unverified JWT parsing, no new failure mode.
 * It costs one extra request per connect, which happens once per user.
 *
 * <h2>Timeouts</h2>
 *
 * Bounded for the same reason {@code ResendEmailProvider}'s are (BH-016): an unbounded outbound
 * call pins a request thread for as long as the far end keeps the socket open. A user is waiting on
 * this one, so the read timeout is tighter than the email client's twenty seconds.
 */
@Component
public class GoogleOAuthClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthClient.class);

    private static final String AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_ENDPOINT = "https://openidconnect.googleapis.com/v1/userinfo";
    private static final String REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private final GoogleOAuthProperties properties;
    private final RestClient restClient;

    public GoogleOAuthClient(GoogleOAuthProperties properties) {
        this.properties = properties;
        ClientHttpRequestFactorySettings timeouts = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(timeouts))
                .build();
    }

    /** Google's token response. Only the fields this integration reads are modeled. */
    record TokenResponse(String access_token, String refresh_token, String scope,
                         String token_type, Integer expires_in) {}

    /** Google's userinfo response — {@code sub} is the stable account identifier. */
    record UserInfo(String sub, String email) {}

    /**
     * The URL to send the user's browser to.
     *
     * <p>{@code access_type=offline} plus {@code prompt=consent} is what actually yields a refresh
     * token. Google returns one only on the FIRST authorization for a given user+client unless
     * consent is re-requested — so without {@code prompt=consent}, a user who previously authorized
     * Finora and then disconnected would reconnect and receive no refresh token at all, leaving a
     * connection that works until the access token expires an hour later and then silently cannot
     * be renewed. Re-prompting costs one extra consent screen and removes that whole failure mode.
     */
    public String buildAuthorizationUrl(String state) {
        String scope = String.join(" ", properties.getScopes());
        return AUTHORIZATION_ENDPOINT
                + "?client_id=" + encode(properties.getClientId())
                + "&redirect_uri=" + encode(properties.getRedirectUri())
                + "&response_type=code"
                + "&scope=" + encode(scope)
                + "&access_type=offline"
                + "&prompt=consent"
                + "&include_granted_scopes=true"
                + "&state=" + encode(state);
    }

    /**
     * Exchanges an authorization code for tokens. Server-to-server: the client secret never reaches
     * a browser.
     *
     * @throws ApiException if Google rejects the exchange — an expired, reused, or forged code
     */
    public TokenResponse exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        form.add("redirect_uri", properties.getRedirectUri());
        form.add("grant_type", "authorization_code");

        try {
            TokenResponse response = restClient.post()
                    .uri(TOKEN_ENDPOINT)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);

            if (response == null || response.access_token() == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "Google did not return a usable token. Try connecting again.");
            }
            return response;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            // The exception's message is deliberately not surfaced to the caller: it can echo the
            // request, and that request contains the client secret.
            log.warn("Gmail OAuth code exchange failed: {}", e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not complete the Google connection. Try again.");
        }
    }

    /**
     * Reads the connected account's stable id and address.
     *
     * <p>{@code sub} is what identity is keyed on — see {@link GmailConnection}. The email is for
     * display, so the user can tell which mailbox they connected.
     */
    public UserInfo fetchUserInfo(String accessToken) {
        try {
            UserInfo info = restClient.get()
                    .uri(USERINFO_ENDPOINT)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(UserInfo.class);

            if (info == null || info.sub() == null || info.sub().isBlank()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "Google did not identify the connected account. Try again.");
            }
            return info;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Gmail userinfo lookup failed: {}", e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not read the connected Google account. Try again.");
        }
    }

    /**
     * Asks Google to invalidate the token, so disconnecting in Finora actually ends the grant
     * rather than only forgetting it locally.
     *
     * <p>Best-effort by design: a failure here must not block the user's disconnect. Whatever
     * Google says, Finora still clears its own copy — the user asked to disconnect, and refusing on
     * the strength of a third party's error would leave a credential we were told to drop.
     *
     * @return whether Google confirmed the revocation, for logging and status reporting only
     */
    public boolean tryRevoke(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", refreshToken);
        try {
            restClient.post()
                    .uri(REVOKE_ENDPOINT)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            // Already-invalid tokens answer 400 here, which is a success in every sense that
            // matters -- the grant is gone.
            log.info("Google token revocation returned {} -- clearing the local credential regardless.",
                    e.getClass().getSimpleName());
            return false;
        }
    }

    /** Scopes Google actually granted, which can be narrower than what was asked for. */
    public List<String> grantedScopes(TokenResponse response) {
        if (response.scope() == null || response.scope().isBlank()) return List.of();
        return List.of(response.scope().split(" "));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
