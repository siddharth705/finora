package com.finora.integrations.google;

import com.finora.exception.ApiException;
import com.finora.security.crypto.CryptoProperties;
import com.finora.security.crypto.EncryptedValue;
import com.finora.security.crypto.EncryptionService;
import com.finora.security.crypto.EnvironmentKeyProvider;
import com.finora.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Phase C1. The property under test is a single distinction, and everything else follows from it:
 * <b>a dead grant and a failed request must not be treated the same way.</b>
 *
 * <p>Wrong in one direction, a network blip disconnects a working integration and tells the user to
 * reconnect something that was never broken. Wrong in the other, Finora retries forever against a
 * grant that can never succeed, and the user's mailbox silently stops syncing with nothing ever
 * saying why.
 */
class GmailAccessTokenServiceTest {

    private static final String REFRESH_TOKEN = "1//0g-stored-refresh-token";

    private GmailConnectionRepository connections;
    private GoogleOAuthClient googleClient;
    private AuditService auditService;
    private EncryptionService encryptionService;
    private GmailAccessTokenService service;

    @BeforeEach
    void setUp() {
        connections = mock(GmailConnectionRepository.class);
        googleClient = mock(GoogleOAuthClient.class);
        auditService = mock(AuditService.class);

        // A real EncryptionService: the round trip from stored ciphertext back to the token Google
        // is presented with is exactly what C1 exists to prove, and a mock would assert nothing.
        CryptoProperties crypto = new CryptoProperties();
        crypto.setActiveKeyId("v1");
        Map<String, String> keys = new LinkedHashMap<>();
        byte[] raw = new byte[32];
        java.util.Arrays.fill(raw, (byte) 23);
        keys.put("v1", Base64.getEncoder().encodeToString(raw));
        crypto.setKeys(keys);
        encryptionService = new EncryptionService(new EnvironmentKeyProvider(crypto));

        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        doAnswer(inv -> {
            ((java.util.function.Consumer<TransactionStatus>) inv.getArgument(0))
                    .accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        service = new GmailAccessTokenService(connections, googleClient, encryptionService,
                auditService, transactionTemplate);

        when(connections.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private GmailConnection liveConnection() {
        GmailConnection connection = new GmailConnection();
        org.springframework.test.util.ReflectionTestUtils.setField(connection, "id", UUID.randomUUID());
        connection.setUserId(UUID.randomUUID());
        connection.setGoogleUserId("google-sub-12345");
        connection.setGoogleEmail("mailbox@example.test");
        connection.setGrantedScopes("openid https://www.googleapis.com/auth/gmail.readonly");
        connection.setStatus(GmailConnection.Status.CONNECTED);
        connection.storeCredential(encryptionService.encrypt(REFRESH_TOKEN));
        when(connections.findById(connection.getId())).thenReturn(Optional.of(connection));
        return connection;
    }

    @Test
    @DisplayName("the stored credential round-trips into a token request and yields an access token")
    void accessTokenFor_decryptsTheStoredCredentialAndReturnsAFreshToken() {
        GmailConnection connection = liveConnection();
        when(googleClient.refreshAccessToken(anyString())).thenReturn(
                new GoogleOAuthClient.TokenResponse("fresh-access-token", null, "openid", "Bearer", 3599));

        String token = service.accessTokenFor(connection);

        assertThat(token).isEqualTo("fresh-access-token");
        // Google is presented with the DECRYPTED token, not the ciphertext -- the thing that would
        // silently break if encryption and this call disagreed about which value is which.
        verify(googleClient).refreshAccessToken(REFRESH_TOKEN);
        assertThat(connection.getStatus()).isEqualTo(GmailConnection.Status.CONNECTED);
    }

    /** The transition {@code REAUTH_REQUIRED} exists for, and which nothing produced before C1. */
    @Test
    @DisplayName("invalid_grant flips the connection to REAUTH_REQUIRED and audits why")
    void accessTokenFor_whenGoogleSaysTheGrantIsDead_marksReauthRequired() {
        GmailConnection connection = liveConnection();
        when(googleClient.refreshAccessToken(anyString()))
                .thenThrow(new GmailReauthRequiredException("invalid_grant"));

        assertThatThrownBy(() -> service.accessTokenFor(connection))
                .isInstanceOf(GmailReauthRequiredException.class);

        assertThat(connection.getStatus()).isEqualTo(GmailConnection.Status.REAUTH_REQUIRED);
        verify(auditService).record(eq(connection.getUserId()), eq("GMAIL_REAUTH_REQUIRED"),
                eq("GmailConnection"), eq(connection.getId()), any());
    }

    /**
     * The other half, and the one that would be tempting to get wrong: a transient failure must
     * leave the connection alone. Disconnecting a working integration because Google had a bad
     * minute is a worse outcome than a sync that fails and retries.
     */
    @Test
    @DisplayName("a transient failure does NOT touch the connection's status")
    void accessTokenFor_whenGoogleIsMomentarilyUnreachable_leavesTheConnectionConnected() {
        GmailConnection connection = liveConnection();
        when(googleClient.refreshAccessToken(anyString()))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "Could not reach Google"));

        assertThatThrownBy(() -> service.accessTokenFor(connection))
                .isInstanceOf(ApiException.class);

        assertThat(connection.getStatus())
                .as("a timeout says nothing about whether the grant is still valid")
                .isEqualTo(GmailConnection.Status.CONNECTED);
        verify(connections, never()).save(any());
        verify(auditService, never()).record(any(), anyString(), anyString(), any(), any());
    }

    /**
     * A credential encrypted under a key that is no longer configured — the encryption runbook's
     * lost-key scenario. Reconnecting is the remedy the runbook prescribes, so REAUTH_REQUIRED is
     * the honest state rather than a 500 on every future sync.
     */
    @Test
    void accessTokenFor_whenTheCredentialCannotBeDecrypted_marksReauthRequired() {
        GmailConnection connection = liveConnection();
        connection.storeCredential(new EncryptedValue("v1", "not-valid-ciphertext"));

        assertThatThrownBy(() -> service.accessTokenFor(connection))
                .isInstanceOf(GmailReauthRequiredException.class);

        assertThat(connection.getStatus()).isEqualTo(GmailConnection.Status.REAUTH_REQUIRED);
        verify(googleClient, never()).refreshAccessToken(anyString());
    }

    @Test
    void accessTokenFor_refusesAConnectionThatIsNotLive() {
        GmailConnection connection = liveConnection();
        connection.close(GmailConnection.Status.DISCONNECTED);

        assertThatThrownBy(() -> service.accessTokenFor(connection))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not active");
        verify(googleClient, never()).refreshAccessToken(anyString());
    }

    /**
     * Re-reading inside the transaction matters: between the caller loading the connection and the
     * refresh failing, the user may have disconnected. Resurrecting that row into REAUTH_REQUIRED
     * would put a dead connection back in front of them asking to be reconnected.
     */
    @Test
    @DisplayName("a connection disconnected in the meantime is not resurrected into REAUTH_REQUIRED")
    void accessTokenFor_doesNotReviveAConnectionTheUserDisconnectedMidFlight() {
        GmailConnection stale = liveConnection();
        GmailConnection nowDisconnected = new GmailConnection();
        org.springframework.test.util.ReflectionTestUtils.setField(nowDisconnected, "id", stale.getId());
        nowDisconnected.setUserId(stale.getUserId());
        nowDisconnected.setGoogleUserId("google-sub-12345");
        nowDisconnected.setGrantedScopes("openid");
        nowDisconnected.close(GmailConnection.Status.DISCONNECTED);
        when(connections.findById(stale.getId())).thenReturn(Optional.of(nowDisconnected));

        when(googleClient.refreshAccessToken(anyString()))
                .thenThrow(new GmailReauthRequiredException("invalid_grant"));

        assertThatThrownBy(() -> service.accessTokenFor(stale))
                .isInstanceOf(GmailReauthRequiredException.class);

        assertThat(nowDisconnected.getStatus()).isEqualTo(GmailConnection.Status.DISCONNECTED);
        verify(connections, never()).save(any());
    }
}
