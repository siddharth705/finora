package com.finora.integrations.google;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import com.finora.security.crypto.EncryptionService;
import com.finora.util.TokenHasher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The single-use guarantee, verified against a real database.
 *
 * <h2>Why this exists separately from {@code GmailConnectionServiceTest}</h2>
 *
 * That class mocks {@code GmailOAuthStateRepository}, so
 * {@link GmailOAuthStateRepository#claimForRedemption} is only ever a stubbed return value there —
 * every one of its assertions holds even if the JPQL is semantically wrong. And the claim IS the
 * security control: the callback is deliberately unauthenticated, and {@code state} is the only
 * thing binding it to a Finora user (CWE-367, Strix review of PR #104). A control that has never
 * executed against the database it relies on is not a verified control.
 *
 * <p>So these tests run the real query against real Postgres and assert what the predicate actually
 * does — that a second redemption of the same state affects zero rows, that an expired state cannot
 * be claimed at all, and that the row really is marked consumed. Seam verification rather than
 * per-layer testing.
 *
 * <p>Only Google is faked. Everything between the service and the database is real.
 */
@Import(GmailOAuthStateRedemptionIT.FakeGoogleConfig.class)
class GmailOAuthStateRedemptionIT extends AbstractIntegrationTest {

    private static final String REFRESH_TOKEN = "1//0g-refresh-token-for-the-seam-test";

    /**
     * Google is deliberately UNconfigured in {@code application-test.yml} — {@code
     * GmailOAuthEndpointIT} asserts the unconfigured behaviour, so that must stay the default.
     * These tests need the opposite, so the client is configured for this class only. The values
     * are inert: {@link FakeGoogleConfig} stands in for the real client and nothing here reaches
     * the network.
     */
    @org.springframework.test.context.DynamicPropertySource
    static void configureGoogleClient(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("app.integrations.google.client-id", () -> "seam-test-client-id");
        registry.add("app.integrations.google.client-secret", () -> "seam-test-client-secret");
        registry.add("app.integrations.google.redirect-uri",
                () -> "https://api.example.test/api/v1/integrations/google/gmail/callback");
    }

    /**
     * A stand-in for Google. {@code @Primary} so it wins over the real {@link GoogleOAuthClient} in
     * the same context — the idiom {@code TestPhoneVerificationConfig} already uses. Nothing here
     * reaches the network; the point is to exercise the database, not Google.
     */
    @TestConfiguration
    static class FakeGoogleConfig {
        @Bean
        @Primary
        GoogleOAuthClient fakeGoogleOAuthClient() {
            GoogleOAuthClient fake = mock(GoogleOAuthClient.class);
            when(fake.exchangeCode(anyString())).thenReturn(new GoogleOAuthClient.TokenResponse(
                    "access-token", REFRESH_TOKEN,
                    "openid https://www.googleapis.com/auth/gmail.readonly", "Bearer", 3599));
            when(fake.grantedScopes(any())).thenReturn(
                    List.of("openid", "https://www.googleapis.com/auth/gmail.readonly"));
            when(fake.buildAuthorizationUrl(anyString()))
                    .thenReturn("https://accounts.google.com/o/oauth2/v2/auth?fake=1");
            return fake;
        }
    }

    @Autowired private GmailOAuthStateRepository states;
    @Autowired private GmailConnectionRepository connections;
    @Autowired private GmailConnectionService service;
    @Autowired private UserRepository userRepository;
    @Autowired private EncryptionService encryptionService;
    @Autowired private GoogleOAuthClient fakeGoogle;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    /**
     * Runs one claim in its own committed transaction — which is what production does, and what the
     * single-use property actually depends on: two callbacks are two separate transactions, not two
     * statements inside one. A {@code @Transactional} test method would instead run both claims in a
     * single rolled-back transaction and prove something weaker.
     *
     * <p>Also required mechanically: a {@code @Modifying} query has no transaction of its own.
     */
    private int claimInOwnTransaction(String stateHash) {
        return transactionTemplate.execute(tx -> states.claimForRedemption(stateHash, Instant.now()));
    }

    private User newUser() {
        User user = new User();
        user.setEmail("gmail-redemption-it-" + UUID.randomUUID() + "@example.test");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Gmail Redemption IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /** A pending state, persisted exactly as beginConnect would leave it. */
    private String persistState(UUID userId, Instant expiresAt) {
        String raw = "state-" + UUID.randomUUID();
        GmailOAuthState state = new GmailOAuthState();
        state.setStateHash(TokenHasher.sha256(raw));
        state.setUserId(userId);
        state.setExpiresAt(expiresAt);
        states.saveAndFlush(state);
        return raw;
    }

    @Test
    @DisplayName("the atomic claim succeeds exactly once for a given state")
    void claimForRedemption_succeedsOnceAndNeverAgain() {
        User user = newUser();
        String raw = persistState(user.getId(), Instant.now().plus(10, ChronoUnit.MINUTES));
        String hash = TokenHasher.sha256(raw);

        assertThat(claimInOwnTransaction(hash))
                .as("the first caller claims the state")
                .isEqualTo(1);

        assertThat(claimInOwnTransaction(hash))
                .as("a second redemption of the same state must affect no rows -- this is the "
                        + "single-use guarantee, and the reason the callback cannot be replayed")
                .isZero();
    }

    @Test
    void claimForRedemption_actuallyMarksTheRowConsumed() {
        User user = newUser();
        String raw = persistState(user.getId(), Instant.now().plus(10, ChronoUnit.MINUTES));

        claimInOwnTransaction(TokenHasher.sha256(raw));

        GmailOAuthState reloaded = states.findByStateHash(TokenHasher.sha256(raw)).orElseThrow();
        assertThat(reloaded.getConsumedAt())
                .as("the UPDATE must persist, not merely report a row count")
                .isNotNull();
        assertThat(reloaded.isConsumed()).isTrue();
    }

    /**
     * Expiry lives inside the same predicate as the consumed check, deliberately — evaluating it
     * separately in Java would reintroduce a smaller version of the race the claim exists to close.
     * This proves the database is the thing enforcing it.
     */
    @Test
    void claimForRedemption_refusesAnExpiredState() {
        User user = newUser();
        String raw = persistState(user.getId(), Instant.now().minus(1, ChronoUnit.MINUTES));

        assertThat(claimInOwnTransaction(TokenHasher.sha256(raw))).isZero();
        assertThat(states.findByStateHash(TokenHasher.sha256(raw)).orElseThrow().isConsumed())
                .as("a refused claim must not consume the row either")
                .isFalse();
    }

    @Test
    void claimForRedemption_refusesAnUnknownState() {
        assertThat(claimInOwnTransaction(TokenHasher.sha256("never-issued"))).isZero();
    }

    // ---- the same property, through the whole service path ----

    /**
     * End to end: the second callback presenting an already-redeemed state is rejected, and — the
     * part that matters — it is rejected <em>before</em> the code is exchanged, so a replayed URL
     * never reaches Google at all.
     */
    @Test
    @DisplayName("a replayed callback is rejected end to end, without contacting Google")
    void completeConnect_cannotBeReplayed() {
        User user = newUser();
        String raw = persistState(user.getId(), Instant.now().plus(10, ChronoUnit.MINUTES));
        when(fakeGoogle.fetchUserInfo(anyString())).thenReturn(
                new GoogleOAuthClient.UserInfo("google-sub-" + UUID.randomUUID(), "mailbox@example.test"));

        GmailConnection connected = service.completeConnect(raw, "auth-code");
        assertThat(connected.getStatus()).isEqualTo(GmailConnection.Status.CONNECTED);

        clearInvocations(fakeGoogle);

        assertThatThrownBy(() -> service.completeConnect(raw, "auth-code"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired or was already used");

        verify(fakeGoogle, never()).exchangeCode(anyString());
    }

    /**
     * The credential is unreadable in the database and recoverable only through the encryption
     * service — asserted against a real persisted row rather than an in-memory entity, since the
     * column is what an attacker with a database dump would actually see.
     */
    @Test
    void completeConnect_persistsTheRefreshTokenEncrypted() {
        User user = newUser();
        String raw = persistState(user.getId(), Instant.now().plus(10, ChronoUnit.MINUTES));
        when(fakeGoogle.fetchUserInfo(anyString())).thenReturn(
                new GoogleOAuthClient.UserInfo("google-sub-" + UUID.randomUUID(), "mailbox@example.test"));

        GmailConnection saved = service.completeConnect(raw, "auth-code");

        GmailConnection reloaded = connections.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getEncryptedRefreshToken())
                .isNotNull()
                .isNotEqualTo(REFRESH_TOKEN)
                .doesNotContain(REFRESH_TOKEN);
        assertThat(reloaded.getEncryptionKeyId()).isNotBlank();
        assertThat(encryptionService.decrypt(reloaded.credential())).isEqualTo(REFRESH_TOKEN);
    }

    /**
     * Reconnecting after a disconnect must work. The unique indexes in V80 are PARTIAL — scoped to
     * the live statuses — precisely so a user's own historical rows do not lock them out of their
     * own mailbox. Verified against the real indexes, which is the only place that is decided.
     */
    @Test
    @DisplayName("a user can reconnect the same mailbox after disconnecting")
    void reconnectAfterDisconnect_isAllowedByThePartialUniqueIndexes() {
        User user = newUser();
        String googleSub = "google-sub-" + UUID.randomUUID();
        when(fakeGoogle.fetchUserInfo(anyString())).thenReturn(
                new GoogleOAuthClient.UserInfo(googleSub, "mailbox@example.test"));
        when(fakeGoogle.tryRevoke(anyString())).thenReturn(true);

        String first = persistState(user.getId(), Instant.now().plus(10, ChronoUnit.MINUTES));
        service.completeConnect(first, "auth-code");
        service.disconnect(user.getId());

        String second = persistState(user.getId(), Instant.now().plus(10, ChronoUnit.MINUTES));
        GmailConnection reconnected = service.completeConnect(second, "auth-code");

        assertThat(reconnected.getStatus()).isEqualTo(GmailConnection.Status.CONNECTED);
        assertThat(connections.findByUserIdOrderByCreatedAtDesc(user.getId()))
                .as("the disconnected row is kept for its audit trail, alongside the new one")
                .hasSize(2);
    }

    /** The sweep runs its real query against real rows, not a mocked page. */
    @Test
    void sweepExpiredStates_removesOnlyExpiredRows() {
        User user = newUser();
        String live = persistState(user.getId(), Instant.now().plus(10, ChronoUnit.MINUTES));
        String stale = persistState(user.getId(), Instant.now().minus(1, ChronoUnit.HOURS));

        service.sweepExpiredStates();

        assertThat(states.findByStateHash(TokenHasher.sha256(stale)))
                .as("expired states are swept")
                .isEmpty();
        assertThat(states.findByStateHash(TokenHasher.sha256(live)))
                .as("a state still inside its TTL must survive -- sweeping it would break a user "
                        + "mid-consent")
                .isPresent();
    }
}
