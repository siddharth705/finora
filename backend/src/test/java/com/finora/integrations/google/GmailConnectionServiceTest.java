package com.finora.integrations.google;

import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import com.finora.security.crypto.CryptoProperties;
import com.finora.security.crypto.EncryptedValue;
import com.finora.security.crypto.EncryptionService;
import com.finora.security.crypto.EnvironmentKeyProvider;
import com.finora.service.AuditService;
import com.finora.util.TokenHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Phase B of the Gmail Transaction Sync design. These tests cover the properties the OAuth
 * connection flow exists to guarantee rather than the plumbing: that a callback cannot be replayed,
 * cannot be redeemed after expiry, cannot be pointed at another user, and that the refresh token is
 * never persisted in a form anyone reading the database could use.
 */
class GmailConnectionServiceTest {

    private GmailConnectionRepository connections;
    private GmailOAuthStateRepository states;
    private GoogleOAuthClient googleClient;
    private GoogleOAuthProperties properties;
    private EncryptionService encryptionService;
    private UserRepository userRepository;
    private AuditService auditService;
    private GmailAccessTokenService accessTokenService;
    private GmailApiClient gmailApiClient;
    private GmailConnectionService service;

    private final UUID userId = UUID.randomUUID();

    private static final String REFRESH_TOKEN = "1//0g-a-real-looking-google-refresh-token";

    @BeforeEach
    void setUp() {
        connections = mock(GmailConnectionRepository.class);
        states = mock(GmailOAuthStateRepository.class);
        googleClient = mock(GoogleOAuthClient.class);
        userRepository = mock(UserRepository.class);
        auditService = mock(AuditService.class);

        properties = new GoogleOAuthProperties();
        properties.setClientId("test-client-id");
        properties.setClientSecret("test-client-secret");
        properties.setRedirectUri("https://api.example.test/api/v1/integrations/google/gmail/callback");

        // A real EncryptionService, not a mock -- the assertion that matters most here is that what
        // lands in the entity is genuinely unreadable ciphertext, and a mock would happily "encrypt"
        // by returning the input.
        CryptoProperties crypto = new CryptoProperties();
        crypto.setActiveKeyId("v1");
        Map<String, String> keys = new LinkedHashMap<>();
        byte[] raw = new byte[32];
        java.util.Arrays.fill(raw, (byte) 11);
        keys.put("v1", Base64.getEncoder().encodeToString(raw));
        crypto.setKeys(keys);
        encryptionService = new EncryptionService(new EnvironmentKeyProvider(crypto));

        // A real TransactionTemplate would need a live transaction manager; these are unit tests,
        // so the template is stubbed to run its callback inline. That keeps the assertions about
        // BEHAVIOUR -- what gets saved, what is refused -- rather than about transaction plumbing,
        // which GmailOAuthEndpointIT exercises for real against Postgres.
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        doAnswer(inv -> {
            ((java.util.function.Consumer<TransactionStatus>) inv.getArgument(0))
                    .accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        accessTokenService = mock(GmailAccessTokenService.class);
        gmailApiClient = mock(GmailApiClient.class);
        service = new GmailConnectionService(connections, states, googleClient, properties,
                encryptionService, userRepository, auditService, accessTokenService,
                gmailApiClient, transactionTemplate);

        when(connections.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(states.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private GmailOAuthState pendingState(UUID owner, Instant expiresAt, Instant consumedAt) {
        GmailOAuthState state = new GmailOAuthState();
        state.setUserId(owner);
        state.setStateHash("irrelevant-the-lookup-is-mocked");
        state.setExpiresAt(expiresAt);
        if (consumedAt != null) state.consume(consumedAt);
        return state;
    }

    /** The atomic claim succeeds -- this caller won the race. */
    private void stateIsClaimable() {
        when(states.claimForRedemption(anyString(), any())).thenReturn(1);
    }

    /** The atomic claim affected no rows: unknown, already consumed, or expired -- indistinguishable
     *  by design, and all rejected identically. */
    private void stateIsNotClaimable() {
        when(states.claimForRedemption(anyString(), any())).thenReturn(0);
    }

    private void googleReturnsAValidGrant() {
        when(googleClient.exchangeCode(anyString())).thenReturn(
                new GoogleOAuthClient.TokenResponse("access-token", REFRESH_TOKEN,
                        "openid https://www.googleapis.com/auth/gmail.readonly", "Bearer", 3599));
        when(googleClient.fetchUserInfo(anyString())).thenReturn(
                new GoogleOAuthClient.UserInfo("google-sub-12345", "connected-mailbox@example.test"));
        when(googleClient.grantedScopes(any())).thenReturn(
                List.of("openid", "https://www.googleapis.com/auth/gmail.readonly"));
        when(userRepository.existsById(userId)).thenReturn(true);
    }

    // ---------- begin ----------

    @Test
    void beginConnect_returnsGooglesAuthorizationUrl_andStoresOnlyAHashOfTheState() {
        when(connections.findByUserIdAndStatusIn(eq(userId), any())).thenReturn(Optional.empty());
        when(googleClient.buildAuthorizationUrl(anyString())).thenReturn("https://accounts.google.com/o/oauth2/v2/auth?x=1");

        String url = service.beginConnect(userId);

        assertThat(url).startsWith("https://accounts.google.com/");

        ArgumentCaptor<String> rawState = ArgumentCaptor.forClass(String.class);
        verify(googleClient).buildAuthorizationUrl(rawState.capture());
        ArgumentCaptor<GmailOAuthState> stored = ArgumentCaptor.forClass(GmailOAuthState.class);
        verify(states).save(stored.capture());

        // The raw state goes to Google; only its digest is persisted. It is a bearer value -- anyone
        // holding it could complete a link for this user -- and Finora only ever needs to compare it.
        assertThat(stored.getValue().getStateHash())
                .isEqualTo(TokenHasher.sha256(rawState.getValue()))
                .isNotEqualTo(rawState.getValue());
        assertThat(stored.getValue().getUserId()).isEqualTo(userId);
        assertThat(stored.getValue().getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void beginConnect_isRefusedWhenAMailboxIsAlreadyConnected() {
        GmailConnection existing = new GmailConnection();
        existing.setUserId(userId);
        existing.setGoogleEmail("existing-mailbox@example.test");
        when(connections.findByUserIdAndStatusIn(eq(userId), any())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.beginConnect(userId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already connected");
        verify(states, never()).save(any());
    }

    @Test
    void beginConnect_isUnavailableWhenGoogleIsNotConfigured() {
        properties.setClientId(null);

        assertThatThrownBy(() -> service.beginConnect(userId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not available");
    }

    // ---------- callback: the security properties ----------

    /**
     * The property the whole state table exists for. A callback URL lands in browser history,
     * referrer headers, and anything the user pastes; without single-use redemption, replaying it
     * re-runs the link.
     */
    @Test
    @DisplayName("a state cannot be redeemed twice")
    void completeConnect_rejectsAnAlreadyConsumedState() {
        GmailOAuthState consumed = pendingState(userId, Instant.now().plusSeconds(300), Instant.now());
        stateIsNotClaimable();
        when(states.findByStateHash(anyString())).thenReturn(Optional.of(consumed));

        assertThatThrownBy(() -> service.completeConnect("some-state", "some-code"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired or was already used");

        // Nothing was spent finding out what the code was worth.
        verify(googleClient, never()).exchangeCode(anyString());
        verify(connections, never()).save(any());
    }

    @Test
    void completeConnect_rejectsAnExpiredState() {
        GmailOAuthState expired = pendingState(userId, Instant.now().minusSeconds(1), null);
        stateIsNotClaimable();
        when(states.findByStateHash(anyString())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.completeConnect("some-state", "some-code"))
                .isInstanceOf(ApiException.class);
        verify(googleClient, never()).exchangeCode(anyString());
    }

    @Test
    void completeConnect_rejectsAnUnknownState() {
        stateIsNotClaimable();
        when(states.findByStateHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeConnect("forged", "some-code"))
                .isInstanceOf(ApiException.class);
        verify(googleClient, never()).exchangeCode(anyString());
    }

    /**
     * Strix security review, CWE-367 — the case an atomic claim exists for, and the one a
     * read-then-check-then-save could not catch.
     *
     * <p>Here the row still LOOKS redeemable on a read: unconsumed, unexpired. The claim returns 0
     * anyway, because a concurrent callback carrying the same state won the UPDATE a moment
     * earlier. The loser must stop immediately — before exchanging the code, before binding
     * anything to a user. Directly mirrors
     * {@code ImportSessionServiceTest.claimForConfirmation_rejectsALostRace_whenTheAtomicUpdateAffectsZeroRows}.
     *
     * <p>This matters more than an ordinary double-submit: the callback is deliberately
     * unauthenticated and the state is the only thing binding it to a Finora user, so a lost race
     * that proceeded anyway would let someone holding a victim's callback URL bind their own mailbox
     * to the victim's account.
     */
    @Test
    @DisplayName("a state that loses the atomic claim is rejected, even though a read still shows it redeemable")
    void completeConnect_rejectsALostRace_whenTheAtomicClaimAffectsZeroRows() {
        GmailOAuthState stillLooksFine = pendingState(userId, Instant.now().plusSeconds(300), null);
        when(states.claimForRedemption(anyString(), any())).thenReturn(0);
        when(states.findByStateHash(anyString())).thenReturn(Optional.of(stillLooksFine));

        assertThatThrownBy(() -> service.completeConnect("state", "code"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired or was already used");

        verify(googleClient, never()).exchangeCode(anyString());
        verify(connections, never()).save(any());
    }

    /**
     * Identity comes from the state, never from the request. A caller who presents someone else's
     * state links THAT user's account -- which is exactly why the state is 256 random bits and
     * single-use rather than anything guessable.
     */
    @Test
    void completeConnect_bindsTheConnectionToTheStatesOwner_notToAnyoneElse() {
        UUID stateOwner = UUID.randomUUID();
        stateIsClaimable();
        when(states.findByStateHash(anyString()))
                .thenReturn(Optional.of(pendingState(stateOwner, Instant.now().plusSeconds(300), null)));
        when(userRepository.existsById(stateOwner)).thenReturn(true);
        when(connections.findByGoogleUserIdAndStatusIn(anyString(), any())).thenReturn(Optional.empty());
        when(googleClient.exchangeCode(anyString())).thenReturn(
                new GoogleOAuthClient.TokenResponse("a", REFRESH_TOKEN, "openid", "Bearer", 3599));
        when(googleClient.fetchUserInfo(anyString())).thenReturn(
                new GoogleOAuthClient.UserInfo("google-sub-12345", "connected-mailbox@example.test"));
        when(googleClient.grantedScopes(any())).thenReturn(List.of("openid"));

        GmailConnection saved = service.completeConnect("state", "code");

        assertThat(saved.getUserId()).isEqualTo(stateOwner);
    }

    /** The point of ADR-007 reaching this feature at all. */
    @Test
    @DisplayName("the refresh token is stored encrypted, never in plaintext")
    void completeConnect_encryptsTheRefreshToken() {
        stateIsClaimable();
        when(states.findByStateHash(anyString()))
                .thenReturn(Optional.of(pendingState(userId, Instant.now().plusSeconds(300), null)));
        when(connections.findByGoogleUserIdAndStatusIn(anyString(), any())).thenReturn(Optional.empty());
        googleReturnsAValidGrant();

        GmailConnection saved = service.completeConnect("state", "code");

        assertThat(saved.getEncryptedRefreshToken())
                .as("what lands in the column must not be the token itself")
                .isNotNull()
                .isNotEqualTo(REFRESH_TOKEN)
                .doesNotContain(REFRESH_TOKEN);
        assertThat(saved.getEncryptionKeyId())
                .as("the key id travels with the ciphertext so rotation can find it")
                .isEqualTo("v1");

        // And it is genuinely recoverable -- encrypted, not merely mangled.
        EncryptedValue stored = new EncryptedValue(saved.getEncryptionKeyId(), saved.getEncryptedRefreshToken());
        assertThat(encryptionService.decrypt(stored)).isEqualTo(REFRESH_TOKEN);
    }

    /**
     * Google returns a refresh token only when it considers the grant new. Storing a connection
     * without one produces something that works for about an hour and then cannot be renewed --
     * a failure that would surface much later, far from its cause.
     */
    @Test
    void completeConnect_refusesAGrantWithNoRefreshToken() {
        stateIsClaimable();
        when(states.findByStateHash(anyString()))
                .thenReturn(Optional.of(pendingState(userId, Instant.now().plusSeconds(300), null)));
        when(userRepository.existsById(userId)).thenReturn(true);
        when(googleClient.exchangeCode(anyString())).thenReturn(
                new GoogleOAuthClient.TokenResponse("access-only", null, "openid", "Bearer", 3599));
        when(googleClient.fetchUserInfo(anyString())).thenReturn(
                new GoogleOAuthClient.UserInfo("google-sub-12345", "connected-mailbox@example.test"));

        assertThatThrownBy(() -> service.completeConnect("state", "code"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("refresh token");
        verify(connections, never()).save(any());
    }

    @Test
    void completeConnect_refusesAMailboxAlreadyConnectedToADifferentFinoraAccount() {
        // Two accounts ingesting the same receipts would attribute one person's spending to two.
        GmailConnection somebodyElses = new GmailConnection();
        somebodyElses.setUserId(UUID.randomUUID());
        stateIsClaimable();
        when(states.findByStateHash(anyString()))
                .thenReturn(Optional.of(pendingState(userId, Instant.now().plusSeconds(300), null)));
        when(connections.findByGoogleUserIdAndStatusIn(anyString(), any()))
                .thenReturn(Optional.of(somebodyElses));
        googleReturnsAValidGrant();

        assertThatThrownBy(() -> service.completeConnect("state", "code"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("another Finora account");
    }

    /**
     * The state is claimed before Google is contacted at all.
     *
     * <p>Asserts the ORDER, not just that both happened: the claim is what makes the callback
     * single-use, so an implementation that exchanged the code first and burned the state afterwards
     * would leave a window where a replayed URL still reached Google.
     *
     * <p>Previously this asserted {@code pending.isConsumed()} on the in-memory entity. That check
     * became meaningless once redemption moved into an atomic UPDATE — the row is consumed in the
     * database and the loaded entity is never mutated, so the old assertion would fail on correct
     * code and pass on code that mutated the object without persisting it. It was testing the
     * mechanism rather than the property.
     */
    @Test
    void completeConnect_claimsTheStateBeforeContactingGoogle() {
        GmailOAuthState pending = pendingState(userId, Instant.now().plusSeconds(300), null);
        stateIsClaimable();
        when(states.findByStateHash(anyString())).thenReturn(Optional.of(pending));
        when(connections.findByGoogleUserIdAndStatusIn(anyString(), any())).thenReturn(Optional.empty());
        googleReturnsAValidGrant();

        service.completeConnect("state", "code");

        InOrder inOrder = inOrder(states, googleClient);
        inOrder.verify(states).claimForRedemption(anyString(), any());
        inOrder.verify(googleClient).exchangeCode(anyString());
    }

    // ---------- verify ----------

    /**
     * The gap this endpoint closes: {@code GET /status} reports the stored status, which stays
     * CONNECTED after a user revokes Finora from their own Google account — nothing learns of that
     * until the credential is used. Verification asks Google.
     */
    @Test
    void verifyConnection_reportsHealthyWhenGoogleHonoursTheCredential() {
        GmailConnection connection = new GmailConnection();
        connection.setUserId(userId);
        connection.setGoogleUserId("google-sub-12345");
        connection.setGrantedScopes("openid " + GmailApiClient.GMAIL_READONLY_SCOPE);
        when(connections.findByUserIdAndStatusIn(eq(userId), any())).thenReturn(Optional.of(connection));
        when(accessTokenService.accessTokenFor(connection)).thenReturn("fresh-token");
        when(gmailApiClient.getProfile("fresh-token")).thenReturn(
                new GmailApiClient.Profile("mailbox@example.test", 10L, 5L, "123"));

        GmailVerificationResultDto result = service.verifyConnection(userId);

        assertThat(result.healthy()).isTrue();
        assertThat(result.actionRequired()).isFalse();
    }

    @Test
    @DisplayName("a dead grant reports actionRequired, so the UI offers Reconnect")
    void verifyConnection_whenTheGrantIsDead_reportsReauthRequired() {
        GmailConnection connection = new GmailConnection();
        connection.setUserId(userId);
        connection.setGoogleUserId("google-sub-12345");
        connection.setGrantedScopes("openid " + GmailApiClient.GMAIL_READONLY_SCOPE);
        when(connections.findByUserIdAndStatusIn(eq(userId), any())).thenReturn(Optional.of(connection));
        when(accessTokenService.accessTokenFor(connection))
                .thenThrow(new GmailReauthRequiredException("invalid_grant"));

        GmailVerificationResultDto result = service.verifyConnection(userId);

        assertThat(result.healthy()).isFalse();
        assertThat(result.actionRequired())
                .as("only the user can fix a revoked grant, so the UI must send them to reconnect")
                .isTrue();
        assertThat(result.status()).isEqualTo("REAUTH_REQUIRED");
    }

    /**
     * The direction that would be easy to get wrong: a transient failure must NOT tell the user to
     * reconnect. Sending someone through a consent screen because Google timed out fixes nothing
     * and costs them their trust in the signal.
     */
    @Test
    @DisplayName("a transient failure does not ask the user to reconnect")
    void verifyConnection_whenGoogleIsUnreachable_doesNotDemandReconnection() {
        GmailConnection connection = new GmailConnection();
        connection.setUserId(userId);
        connection.setGoogleUserId("google-sub-12345");
        connection.setGrantedScopes("openid " + GmailApiClient.GMAIL_READONLY_SCOPE);
        connection.setStatus(GmailConnection.Status.CONNECTED);
        when(connections.findByUserIdAndStatusIn(eq(userId), any())).thenReturn(Optional.of(connection));
        when(accessTokenService.accessTokenFor(connection))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "unreachable"));

        GmailVerificationResultDto result = service.verifyConnection(userId);

        assertThat(result.healthy()).isFalse();
        assertThat(result.actionRequired()).isFalse();
        assertThat(result.status())
                .as("the connection was not changed, so its existing status is what to report")
                .isEqualTo("CONNECTED");
    }

    @Test
    void verifyConnection_whenNothingIsConnected_is404() {
        when(connections.findByUserIdAndStatusIn(eq(userId), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyConnection(userId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No Gmail account is connected");
    }

    /**
     * C2. The gap Phase B's own doc comment predicted and nothing checked: a user can complete
     * consent while declining gmail.readonly, leaving a CONNECTED row with a working refresh token
     * that cannot read a single message.
     */
    @Test
    @DisplayName("a connection without gmail.readonly is reported as unusable, without calling Gmail")
    void verifyConnection_whenTheScopeWasNeverGranted_saysSo() {
        GmailConnection connection = new GmailConnection();
        connection.setUserId(userId);
        connection.setGoogleUserId("google-sub-12345");
        connection.setGrantedScopes("openid https://www.googleapis.com/auth/userinfo.email");
        when(connections.findByUserIdAndStatusIn(eq(userId), any())).thenReturn(Optional.of(connection));

        GmailVerificationResultDto result = service.verifyConnection(userId);

        assertThat(result.healthy()).isFalse();
        assertThat(result.actionRequired()).isTrue();
        assertThat(result.message()).contains("permission");
        // The recorded scope already answers this -- spending a request to be told 403 would cost a
        // round trip to learn what the row says.
        verify(accessTokenService, never()).accessTokenFor(any());
        verify(gmailApiClient, never()).getProfile(anyString());
    }

    /**
     * Verification must prove Gmail will honour the token, not merely that the token refreshes.
     * Those are different facts, and only the second was checked before C2.
     */
    @Test
    void verifyConnection_readsTheMailboxProfile_notJustTheToken() {
        GmailConnection connection = new GmailConnection();
        connection.setUserId(userId);
        connection.setGoogleUserId("google-sub-12345");
        connection.setGrantedScopes("openid " + GmailApiClient.GMAIL_READONLY_SCOPE);
        when(connections.findByUserIdAndStatusIn(eq(userId), any())).thenReturn(Optional.of(connection));
        when(accessTokenService.accessTokenFor(connection)).thenReturn("fresh-token");
        when(gmailApiClient.getProfile("fresh-token")).thenReturn(
                new GmailApiClient.Profile("mailbox@example.test", 10L, 5L, "123"));

        assertThat(service.verifyConnection(userId).healthy()).isTrue();
        verify(gmailApiClient).getProfile("fresh-token");
    }

    /** Gmail refusing on permission grounds despite a recorded scope -- e.g. the Gmail API disabled
     *  on the Cloud project. Reported as a permission problem, not "reconnect", because a
     *  re-consent that changes nothing would fail identically. */
    @Test
    void verifyConnection_whenGmailRefusesOnPermission_reportsAScopeProblem() {
        GmailConnection connection = new GmailConnection();
        connection.setUserId(userId);
        connection.setGoogleUserId("google-sub-12345");
        connection.setGrantedScopes("openid " + GmailApiClient.GMAIL_READONLY_SCOPE);
        when(connections.findByUserIdAndStatusIn(eq(userId), any())).thenReturn(Optional.of(connection));
        when(accessTokenService.accessTokenFor(connection)).thenReturn("fresh-token");
        when(gmailApiClient.getProfile(anyString()))
                .thenThrow(new GmailScopeNotGrantedException("403"));

        GmailVerificationResultDto result = service.verifyConnection(userId);

        assertThat(result.healthy()).isFalse();
        assertThat(result.actionRequired()).isTrue();
        assertThat(result.message()).contains("permission");
    }

    // ---------- disconnect ----------

    @Test
    void disconnect_revokesAtGoogle_thenClearsTheStoredCredential() {
        GmailConnection connection = new GmailConnection();
        connection.setUserId(userId);
        connection.setGoogleUserId("google-sub-12345");
        connection.setGrantedScopes("openid");
        connection.storeCredential(encryptionService.encrypt(REFRESH_TOKEN));
        when(connections.findByUserIdAndStatusIn(eq(userId), any())).thenReturn(Optional.of(connection));
        when(googleClient.tryRevoke(anyString())).thenReturn(true);

        service.disconnect(userId);

        // Revoked with the real token, not the ciphertext.
        verify(googleClient).tryRevoke(REFRESH_TOKEN);
        assertThat(connection.getStatus()).isEqualTo(GmailConnection.Status.DISCONNECTED);
        assertThat(connection.getEncryptedRefreshToken()).isNull();
        assertThat(connection.getEncryptionKeyId()).isNull();
    }

    /**
     * The user asked to disconnect. Refusing because a third party returned an error would leave a
     * credential Finora was told to drop.
     */
    @Test
    @DisplayName("disconnect still clears the credential when Google's revocation fails")
    void disconnect_clearsTheCredentialEvenIfRevocationFails() {
        GmailConnection connection = new GmailConnection();
        connection.setUserId(userId);
        connection.setGoogleUserId("google-sub-12345");
        connection.setGrantedScopes("openid");
        connection.storeCredential(encryptionService.encrypt(REFRESH_TOKEN));
        when(connections.findByUserIdAndStatusIn(eq(userId), any())).thenReturn(Optional.of(connection));
        when(googleClient.tryRevoke(anyString())).thenReturn(false);

        service.disconnect(userId);

        assertThat(connection.getStatus()).isEqualTo(GmailConnection.Status.DISCONNECTED);
        assertThat(connection.getEncryptedRefreshToken()).isNull();
    }

    /**
     * A credential encrypted under a key that is no longer configured (see the encryption runbook's
     * lost-key section) must not trap the user in a connection they cannot remove.
     */
    @Test
    void disconnect_succeedsEvenWhenTheStoredCredentialCannotBeDecrypted() {
        GmailConnection connection = new GmailConnection();
        connection.setUserId(userId);
        connection.setGoogleUserId("google-sub-12345");
        connection.setGrantedScopes("openid");
        connection.storeCredential(new EncryptedValue("v1", "not-valid-ciphertext"));
        when(connections.findByUserIdAndStatusIn(eq(userId), any())).thenReturn(Optional.of(connection));

        service.disconnect(userId);

        assertThat(connection.getStatus()).isEqualTo(GmailConnection.Status.DISCONNECTED);
        assertThat(connection.getEncryptedRefreshToken()).isNull();
        verify(googleClient, never()).tryRevoke(anyString());
    }

    /**
     * Without a sweep, gmail_oauth_states only grows: every abandoned consent screen leaves a row
     * and nothing on the connect/callback path ever removes one. Caught by the pre-commit gap
     * check -- the repository method existed but nothing called it.
     */
    @Test
    void sweepExpiredStates_removesExpiredRowsInABoundedBatch() {
        GmailOAuthState stale = pendingState(userId, Instant.now().minusSeconds(60), null);
        when(states.findByExpiresAtBeforeOrderByExpiresAtAsc(any(), any())).thenReturn(List.of(stale));

        assertThat(service.sweepExpiredStates()).isEqualTo(1);
        verify(states).deleteAll(List.of(stale));
    }

    @Test
    void sweepExpiredStates_doesNothingWhenThereIsNothingToRemove() {
        when(states.findByExpiresAtBeforeOrderByExpiresAtAsc(any(), any())).thenReturn(List.of());

        assertThat(service.sweepExpiredStates()).isZero();
        verify(states, never()).deleteAll(any());
    }

    @Test
    void disconnect_whenNothingIsConnected_is404() {
        when(connections.findByUserIdAndStatusIn(eq(userId), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.disconnect(userId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No Gmail account is connected");
    }

    // findCurrentConnection -- unlike findLiveConnection, this is what GET /status now reads from,
    // specifically so REVOKED (invisible to findLiveConnection's LIVE-only query) is reachable.

    @Test
    @DisplayName("findCurrentConnection surfaces a REVOKED connection, which findLiveConnection cannot")
    void findCurrentConnection_surfacesRevoked() {
        GmailConnection revoked = connectionWithStatus(GmailConnection.Status.REVOKED);
        when(connections.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(revoked));

        assertThat(service.findCurrentConnection(userId)).contains(revoked);
    }

    @Test
    @DisplayName("findCurrentConnection surfaces a DISCONNECTED connection too")
    void findCurrentConnection_surfacesDisconnected() {
        GmailConnection disconnected = connectionWithStatus(GmailConnection.Status.DISCONNECTED);
        when(connections.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(disconnected));

        assertThat(service.findCurrentConnection(userId)).contains(disconnected);
    }

    @Test
    @DisplayName("findCurrentConnection picks the most recently created row when several exist")
    void findCurrentConnection_picksTheNewestRow() {
        // findByUserIdOrderByCreatedAtDesc's own contract is newest-first -- this asserts the
        // service takes the head of that list rather than, say, the live one or the oldest.
        GmailConnection newest = connectionWithStatus(GmailConnection.Status.REAUTH_REQUIRED);
        GmailConnection older = connectionWithStatus(GmailConnection.Status.DISCONNECTED);
        when(connections.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(newest, older));

        assertThat(service.findCurrentConnection(userId)).contains(newest);
    }

    @Test
    @DisplayName("findCurrentConnection is empty for a user who has never connected")
    void findCurrentConnection_emptyWhenNeverConnected() {
        when(connections.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

        assertThat(service.findCurrentConnection(userId)).isEmpty();
    }

    private GmailConnection connectionWithStatus(GmailConnection.Status status) {
        GmailConnection connection = new GmailConnection();
        connection.setUserId(userId);
        connection.setGoogleEmail("amy@gmail.example.test");
        connection.setGrantedScopes("https://www.googleapis.com/auth/gmail.readonly");
        connection.setStatus(status);
        return connection;
    }
}
