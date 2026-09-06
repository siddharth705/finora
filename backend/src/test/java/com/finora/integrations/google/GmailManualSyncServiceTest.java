package com.finora.integrations.google;

import com.finora.entity.FeatureEntitlement;
import com.finora.exception.ApiException;
import com.finora.integrations.google.merchant.GmailReceiptExtractionService;
import com.finora.service.EntitlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** Phase C5.4, D-15 — "Sync Now". */
class GmailManualSyncServiceTest {

    private static final long COOLDOWN_MS = 60_000L;

    private GmailConnectionService connectionService;
    private GmailMessageDiscoveryService discovery;
    private GmailReceiptExtractionService extraction;
    private EntitlementService entitlementService;
    private GmailManualSyncService manualSync;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        connectionService = mock(GmailConnectionService.class);
        discovery = mock(GmailMessageDiscoveryService.class);
        extraction = mock(GmailReceiptExtractionService.class);
        // Entitled by default -- every existing test here is about sync mechanics (cooldown,
        // error mapping), not billing. The denial test below overrides this.
        entitlementService = mock(EntitlementService.class);
        when(entitlementService.hasEntitlement(userId, FeatureEntitlement.GMAIL_SYNC)).thenReturn(true);
        manualSync = new GmailManualSyncService(connectionService, discovery, extraction, entitlementService, COOLDOWN_MS, 500, 50);
    }

    @Test
    @DisplayName("a caller not entitled to GMAIL_SYNC is refused before the connection is even looked up")
    void notEntitledThrows403() {
        when(entitlementService.hasEntitlement(userId, FeatureEntitlement.GMAIL_SYNC)).thenReturn(false);

        assertThatThrownBy(() -> manualSync.syncNow(userId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(connectionService, discovery, extraction);
    }

    @Test
    @DisplayName("no live connection is a 404, not a silent no-op")
    void noConnectionThrows404() {
        when(connectionService.findLiveConnection(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> manualSync.syncNow(userId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(discovery, extraction);
    }

    @Test
    @DisplayName("a mailbox never checked before (lastDiscoveryAt null) is not rate-limited")
    void neverCheckedIsNotRateLimited() {
        GmailConnection connection = connection(null);
        when(connectionService.findLiveConnection(userId)).thenReturn(Optional.of(connection));

        manualSync.syncNow(userId);

        verify(discovery).discoverFor(connection, 500);
        verify(extraction).extractFor(connection, 50);
    }

    @Test
    @DisplayName("syncing again inside the cooldown window is rejected, not silently rerun")
    void withinCooldownThrows429() {
        GmailConnection connection = connection(Instant.now().minusMillis(COOLDOWN_MS / 2));
        when(connectionService.findLiveConnection(userId)).thenReturn(Optional.of(connection));

        assertThatThrownBy(() -> manualSync.syncNow(userId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verifyNoInteractions(discovery, extraction);
    }

    @Test
    @DisplayName("a mailbox checked before the cooldown window elapsed syncs again")
    void outsideCooldownSyncsAgain() {
        GmailConnection connection = connection(Instant.now().minusMillis(COOLDOWN_MS * 2));
        when(connectionService.findLiveConnection(userId)).thenReturn(Optional.of(connection));

        manualSync.syncNow(userId);

        verify(discovery).discoverFor(connection, 500);
        verify(extraction).extractFor(connection, 50);
    }

    @Test
    @DisplayName("a dead grant surfaces as a clear reconnect-needed error, not a generic failure")
    void reauthRequiredMapsToConflict() {
        GmailConnection connection = connection(null);
        when(connectionService.findLiveConnection(userId)).thenReturn(Optional.of(connection));
        doThrow(new GmailReauthRequiredException("dead grant")).when(discovery).discoverFor(any(), anyInt());

        assertThatThrownBy(() -> manualSync.syncNow(userId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("a missing scope surfaces as a clear reconnect-needed error too")
    void scopeNotGrantedMapsToConflict() {
        GmailConnection connection = connection(null);
        when(connectionService.findLiveConnection(userId)).thenReturn(Optional.of(connection));
        doThrow(new GmailScopeNotGrantedException("missing scope")).when(discovery).discoverFor(any(), anyInt());

        assertThatThrownBy(() -> manualSync.syncNow(userId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("a transient failure surfaces as a retryable error, not swallowed silently")
    void transientFailureMapsToBadGateway() {
        GmailConnection connection = connection(null);
        when(connectionService.findLiveConnection(userId)).thenReturn(Optional.of(connection));
        doThrow(new RuntimeException("timeout")).when(discovery).discoverFor(any(), anyInt());

        assertThatThrownBy(() -> manualSync.syncNow(userId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    private GmailConnection connection(Instant lastDiscoveryAt) {
        GmailConnection connection = new GmailConnection();
        connection.setUserId(userId);
        connection.setLastDiscoveryAt(lastDiscoveryAt);
        return connection;
    }
}
