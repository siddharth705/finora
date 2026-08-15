package com.finora.integrations.google;

import com.finora.exception.ApiException;
import com.finora.integrations.google.merchant.GmailReceiptExtractionService;
import com.finora.observability.WorkerExecution;
import com.finora.observability.WorkerObservability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase C4 scheduling.
 *
 * <p>One property carries most of the weight here: <b>a broken mailbox must not be able to stop
 * every other mailbox from syncing.</b> Per-connection failures are the normal case for this worker
 * — expired grants, rate limits, transient 5xx — and a loop that let the first one escape would mean
 * the single most likely failure silently starves everyone behind it in the slice.
 */
class GmailDiscoveryWorkerTest {

    private GmailMessageDiscoveryService discovery;
    private GmailReceiptExtractionService extraction;
    private GmailConnectionRepository connections;
    private GmailDiscoveryWorker worker;

    @BeforeEach
    void setUp() {
        discovery = mock(GmailMessageDiscoveryService.class);
        extraction = mock(GmailReceiptExtractionService.class);
        connections = mock(GmailConnectionRepository.class);

        WorkerObservability observability = mock(WorkerObservability.class);
        WorkerExecution execution = mock(WorkerExecution.class);
        when(observability.beginScheduled(anyString(), anyString())).thenReturn(execution);

        worker = new GmailDiscoveryWorker(discovery, extraction, connections, observability,
                true, 25, 500, 50, Duration.ofHours(1).toMillis());
    }

    /**
     * The test this class exists for. Three connections, the first two broken in the two ways that
     * happen most often; the third must still be attempted.
     */
    @Test
    @DisplayName("one broken mailbox does not starve the rest of the slice")
    void aFailingConnectionDoesNotAbortTheTick() {
        GmailConnection deadGrant = connection();
        GmailConnection rateLimited = connection();
        GmailConnection healthy = connection();
        when(connections.findDueForDiscovery(any(), any()))
                .thenReturn(List.of(deadGrant, rateLimited, healthy));

        doThrow(new GmailReauthRequiredException("grant is gone"))
                .when(discovery).discoverFor(eq(deadGrant), anyInt());
        doThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Gmail is unavailable."))
                .when(discovery).discoverFor(eq(rateLimited), anyInt());

        int attempted = worker.runOnce();

        assertThat(attempted).isEqualTo(3);
        verify(discovery).discoverFor(healthy, 500);
        verify(extraction).extractFor(healthy, 50);
    }

    /**
     * The reason discovery and extraction are one loop iteration, not two scheduled passes:
     * mail discovery just found should not wait for a later tick to be extracted.
     */
    @Test
    @DisplayName("extraction runs for a connection right after its own discovery pass, same tick")
    void extractionRunsImmediatelyAfterDiscoveryForEachConnection() {
        GmailConnection connection = connection();
        when(connections.findDueForDiscovery(any(), any())).thenReturn(List.of(connection));

        worker.runOnce();

        InOrder inOrder = inOrder(discovery, extraction);
        inOrder.verify(discovery).discoverFor(connection, 500);
        inOrder.verify(extraction).extractFor(connection, 50);
    }

    /** If discovery failed, there is nothing new on this connection for extraction to find this
     *  tick -- attempting it anyway would just spend a second doomed request. */
    @Test
    @DisplayName("a connection whose discovery failed does not also attempt extraction")
    void extractionIsSkippedWhenDiscoveryFailed() {
        GmailConnection broken = connection();
        when(connections.findDueForDiscovery(any(), any())).thenReturn(List.of(broken));
        doThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Gmail is unavailable."))
                .when(discovery).discoverFor(eq(broken), anyInt());

        worker.runOnce();

        verify(extraction, never()).extractFor(any(), anyInt());
    }

    /**
     * A connection that consented without {@code gmail.readonly} answers 403 to everything and,
     * unlike a dead grant, keeps its {@code CONNECTED} status — so it stays in the due query and
     * recurs every tick. It must not escape the loop either.
     */
    @Test
    void aConnectionMissingTheScopeIsHandledLikeAnyOtherPerConnectionFailure() {
        GmailConnection noScope = connection();
        GmailConnection healthy = connection();
        when(connections.findDueForDiscovery(any(), any())).thenReturn(List.of(noScope, healthy));
        doThrow(new GmailScopeNotGrantedException("no gmail.readonly"))
                .when(discovery).discoverFor(eq(noScope), anyInt());

        worker.runOnce();

        verify(discovery).discoverFor(healthy, 500);
    }

    /**
     * The due query's arguments are the whole rate policy — a mailbox rests for the minimum interval,
     * and a tick takes a bounded slice. Passing the wrong instant here would re-check the same
     * mailboxes every tick, which no assertion on outcomes would notice.
     */
    @Test
    void onlyConnectionsPastTheMinimumIntervalAreDue() {
        when(connections.findDueForDiscovery(any(), any())).thenReturn(List.of());
        Instant before = Instant.now();

        worker.runOnce();

        ArgumentCaptor<Instant> checkedBefore = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(connections).findDueForDiscovery(checkedBefore.capture(), page.capture());

        assertThat(checkedBefore.getValue())
                .isBetween(before.minus(Duration.ofHours(1)).minusSeconds(5),
                           Instant.now().minus(Duration.ofHours(1)));
        assertThat(page.getValue().getPageSize()).isEqualTo(25);
    }

    /**
     * The flag has to gate the SCHEDULED entry point specifically. Gating {@code runOnce} instead
     * would leave tests unable to drive the worker at all, which is why
     * {@code application-test.yml} can turn it off without disabling the tests.
     */
    @Test
    void theScheduledTriggerDoesNothingWhenDisabled() {
        GmailDiscoveryWorker disabled = new GmailDiscoveryWorker(discovery, extraction, connections,
                observabilityStub(), false, 25, 500, 50, 3_600_000L);

        disabled.scheduledDiscovery();

        verifyNoInteractions(connections);
        verifyNoInteractions(discovery);
        verifyNoInteractions(extraction);
    }

    private WorkerObservability observabilityStub() {
        WorkerObservability observability = mock(WorkerObservability.class);
        when(observability.beginScheduled(anyString(), anyString()))
                .thenReturn(mock(WorkerExecution.class));
        return observability;
    }

    private static GmailConnection connection() {
        GmailConnection connection = new GmailConnection();
        connection.setUserId(UUID.randomUUID());
        connection.setGoogleUserId("google-sub-" + UUID.randomUUID());
        connection.setGoogleEmail("mailbox@example.test");
        connection.setGrantedScopes(GmailApiClient.GMAIL_READONLY_SCOPE);
        try {
            var field = GmailConnection.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(connection, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return connection;
    }
}
