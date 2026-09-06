package com.finora.integrations.google;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.SubscriptionService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Reproduces a real production failure end-to-end against a real Postgres, the way
 * {@link GmailOAuthEndpointIT} does for the endpoint boundary — this one for the database boundary
 * a mocked-repository unit test cannot see at all.
 *
 * <p><b>The bug.</b> {@code GmailConnectionService.persistConnection} closes a stale
 * {@code REAUTH_REQUIRED} row and inserts a fresh {@code CONNECTED} one in the same transaction.
 * With a plain {@code save()} on the close, this failed in production with
 * {@code DataIntegrityViolationException: duplicate key value violates unique constraint
 * "uq_gmail_connections_active_user"} — confirmed from real Railway logs, not guessed. Root cause:
 * Hibernate's default flush order runs ALL insertions before ANY updates, regardless of the order
 * {@code save()} was called in, so the fresh row's INSERT reached Postgres while the stale row was
 * still live. {@code GmailConnectionServiceTest} mocks the repository, so it cannot model flush
 * ordering at all — it passed both before and after the bug existed. Only a real Hibernate session
 * against a real database, which this class provides via {@link AbstractIntegrationTest}, can catch
 * this class of bug.
 */
@TestPropertySource(properties = {
        "app.integrations.google.client-id=it-test-client-id",
        "app.integrations.google.client-secret=it-test-client-secret",
        "app.integrations.google.redirect-uri=http://localhost/callback",
})
class GmailReconnectFlushOrderingIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/integrations/google/gmail";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private GmailConnectionRepository connections;
    @Autowired private SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // The real client would call Google over the network; this test only needs the DB half of the
    // flow to be real, so the client is the one thing worth replacing.
    @MockitoBean private GoogleOAuthClient googleClient;

    @LocalServerPort private int port;

    /** Premium, not just any subscription -- GMAIL_SYNC (V161) is the flush-ordering bug's
     *  precondition (a live connect flow), and this test predates and is unrelated to that gate;
     *  it needs a user who can actually reach beginConnect, not a Free/Plus one refused at 403. */
    private User createUser() {
        User user = new User();
        user.setEmail("gmail-flush-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Gmail Flush IT User");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(user.getId());
        subscriptionService.changePlan(user.getId(), "PREMIUM", "test-fixture", user.getId());
        return user;
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    /** Same reasoning and same construction as {@link GmailOAuthEndpointIT}'s own helper: the 302
     *  and its Location header ARE the thing under test. */
    private ResponseEntity<Void> callbackWithoutFollowingRedirects(String query) {
        SimpleClientHttpRequestFactory noFollow = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        RestTemplate plain = new RestTemplate(noFollow);
        plain.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) { return false; }
        });
        return plain.exchange("http://localhost:" + port + BASE + "/callback" + query,
                HttpMethod.GET, HttpEntity.EMPTY, Void.class);
    }

    @Test
    @DisplayName("reconnecting the same Google account after REAUTH_REQUIRED replaces the stale row instead of colliding with it")
    void reconnect_sameGoogleAccount_afterReauthRequired_succeeds() throws Exception {
        User user = createUser();
        String googleSub = "same-google-sub-" + UUID.randomUUID();

        GmailConnection stale = new GmailConnection();
        stale.setUserId(user.getId());
        stale.setGoogleUserId(googleSub);
        stale.setGoogleEmail("reconnect-it@example.test");
        stale.setGrantedScopes("openid https://www.googleapis.com/auth/gmail.readonly");
        stale.setStatus(GmailConnection.Status.REAUTH_REQUIRED);
        stale.setConnectedAt(Instant.now().minusSeconds(86_400));
        stale = connections.save(stale);
        UUID staleId = stale.getId();

        when(googleClient.buildAuthorizationUrl(anyString()))
                .thenAnswer(inv -> "https://accounts.google.com/mock-auth?state=" + inv.getArgument(0));
        when(googleClient.exchangeCode(anyString())).thenReturn(
                new GoogleOAuthClient.TokenResponse("access-token", "a-fresh-refresh-token",
                        "openid https://www.googleapis.com/auth/gmail.readonly", "Bearer", 3599));
        when(googleClient.fetchUserInfo(anyString()))
                .thenReturn(new GoogleOAuthClient.UserInfo(googleSub, "reconnect-it@example.test"));
        when(googleClient.grantedScopes(any()))
                .thenReturn(List.of("openid", "https://www.googleapis.com/auth/gmail.readonly"));

        ResponseEntity<String> connectResponse = restTemplate.exchange(
                BASE + "/connect", HttpMethod.POST, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(connectResponse.getStatusCode())
                .as("REAUTH_REQUIRED must not be refused the same way CONNECTED is (PR #986)")
                .isEqualTo(HttpStatus.OK);

        String authorizationUrl = objectMapper.readTree(connectResponse.getBody())
                .at("/data/authorizationUrl").asText();
        String rawState = UriComponentsBuilder.fromUriString(authorizationUrl)
                .build().getQueryParams().getFirst("state");

        ResponseEntity<Void> callbackResponse =
                callbackWithoutFollowingRedirects("?code=mock-code&state=" + rawState);

        assertThat(callbackResponse.getHeaders().getLocation())
                .as("the exact production symptom: a real Hibernate flush-ordering bug redirected "
                        + "here with gmail=failed instead of gmail=connected")
                .isNotNull();
        assertThat(callbackResponse.getHeaders().getLocation().toString()).contains("gmail=connected");

        List<GmailConnection> rows = connections.findByUserIdOrderByCreatedAtDesc(user.getId());
        assertThat(rows).hasSize(2);
        GmailConnection fresh = rows.stream().filter(c -> !c.getId().equals(staleId)).findFirst().orElseThrow();
        GmailConnection retired = rows.stream().filter(c -> c.getId().equals(staleId)).findFirst().orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo(GmailConnection.Status.CONNECTED);
        assertThat(retired.getStatus())
                .as("the stale row must actually be retired in the database -- not just in an "
                        + "unflushed persistence context")
                .isEqualTo(GmailConnection.Status.DISCONNECTED);
    }
}
