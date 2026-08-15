package com.finora.integrations.google;

import com.finora.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase C4 discovery.
 *
 * <p>The properties worth pinning here are not "does it loop" — they are the ones whose violation is
 * <b>silent</b>. A discovery service that fetched bodies, or advanced its window before recording
 * outcomes, or re-fetched headers for mail it had already decided about, would produce exactly the
 * same logs and exactly the same green suite as one that did not. Each test below targets one of
 * those.
 */
class GmailMessageDiscoveryServiceTest {

    private static final String TOKEN = "an-access-token";
    private static final String TRUSTED_HEADER = "mx.google.com; dkim=pass header.i=@merchant.example";
    private static final String SPOOFED_HEADER = "mx.google.com; dkim=fail header.i=@merchant.example";

    private GmailApiClient gmail;
    private GmailAccessTokenService accessTokens;
    private SenderAuthenticationService gate;
    private GmailProcessedMessageRepository processedMessages;
    private GmailConnectionRepository connections;
    private GmailMessageDiscoveryService service;

    private GmailConnection connection;

    @BeforeEach
    void setUp() {
        gmail = mock(GmailApiClient.class);
        accessTokens = mock(GmailAccessTokenService.class);
        gate = mock(SenderAuthenticationService.class);
        processedMessages = mock(GmailProcessedMessageRepository.class);
        connections = mock(GmailConnectionRepository.class);

        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, java.util.function.Consumer.class).accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        service = new GmailMessageDiscoveryService(gmail, accessTokens, gate, processedMessages,
                connections, transactionTemplate);

        connection = connectedMailbox();
        when(accessTokens.accessTokenFor(connection)).thenReturn(TOKEN);
        when(processedMessages.findAlreadyProcessedIds(any(), any())).thenReturn(Set.of());
        when(connections.findById(connection.getId())).thenReturn(Optional.of(connection));
    }

    // ---------------------------------------------------------------------------------------
    // The gate decides, and the decision is recorded
    // ---------------------------------------------------------------------------------------

    @Test
    void aTrustedMessageIsRecordedWithTheDomainThatActuallyAuthenticated() {
        listReturns(page(List.of("m1"), null));
        headersFor("m1", TRUSTED_HEADER);
        when(gate.evaluate(TRUSTED_HEADER)).thenReturn(trusted("merchant.example"));

        GmailMessageDiscoveryService.DiscoveryResult result = service.discoverFor(connection, 100);

        assertThat(result.trusted()).isEqualTo(1);
        GmailProcessedMessage saved = captureSaved();
        assertThat(saved.getGmailMessageId()).isEqualTo("m1");
        // DETECTED_NOT_STAGED, not PARSED: C4 has no parsers, and claiming otherwise would make the
        // provenance table lie about why no transaction appeared.
        assertThat(saved.getOutcome())
                .isEqualTo(GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED);
        assertThat(saved.getAuthenticatedDomain()).isEqualTo("merchant.example");
        assertThat(saved.getSkipReason())
                .as("a trusted message is not a skip")
                .isNull();
    }

    /**
     * The spoof case, which is the whole reason C3 exists. A message claiming to be from a trusted
     * merchant but failing DKIM must be recorded as refused — and the reason must survive, because
     * "not authenticated" (a spoof or a delivery problem) and "domain not on the registry" (a
     * merchant nobody added yet) call for completely different responses.
     */
    @Test
    @DisplayName("a message the gate refuses is recorded with the verdict that refused it")
    void anUntrustedMessageIsRecordedWithItsRefusalReason() {
        listReturns(page(List.of("m1"), null));
        headersFor("m1", SPOOFED_HEADER);
        when(gate.evaluate(SPOOFED_HEADER)).thenReturn(new SenderAuthenticationService.Result(
                SenderAuthenticationService.Verdict.NOT_AUTHENTICATED, null));

        GmailMessageDiscoveryService.DiscoveryResult result = service.discoverFor(connection, 100);

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.trusted()).isZero();
        GmailProcessedMessage saved = captureSaved();
        assertThat(saved.getOutcome())
                .isEqualTo(GmailProcessedMessage.Outcome.SKIPPED_UNTRUSTED_SENDER);
        assertThat(saved.getSkipReason()).isEqualTo("NOT_AUTHENTICATED");
    }

    /**
     * A domain that authenticated cleanly but is not on the registry keeps its domain, because that
     * is the "which parser should we write next" signal. Losing it would make the whole
     * DOMAIN_NOT_TRUSTED population indistinguishable.
     */
    @Test
    void anUnregisteredButAuthenticDomainIsKept() {
        listReturns(page(List.of("m1"), null));
        headersFor("m1", "mx.google.com; dkim=pass header.i=@newshop.example");
        when(gate.evaluate(anyString())).thenReturn(new SenderAuthenticationService.Result(
                SenderAuthenticationService.Verdict.DOMAIN_NOT_TRUSTED, "newshop.example"));

        service.discoverFor(connection, 100);

        GmailProcessedMessage saved = captureSaved();
        assertThat(saved.getAuthenticatedDomain()).isEqualTo("newshop.example");
        assertThat(saved.getSkipReason()).isEqualTo("DOMAIN_NOT_TRUSTED");
    }

    // ---------------------------------------------------------------------------------------
    // Quota: the expensive call must not happen for mail already decided about
    // ---------------------------------------------------------------------------------------

    /**
     * Listing ids is cheap; fetching headers is not. The processed table exists so a re-listed
     * message costs nothing — and if that subtraction ever stopped working, everything would still
     * be correct, because the unique index would reject the duplicate write. The only visible
     * symptom would be a quietly multiplied Gmail bill.
     */
    @Test
    @DisplayName("an already-decided message is never fetched again")
    void alreadyProcessedMessagesAreNotFetched() {
        listReturns(page(List.of("seen-before", "brand-new"), null));
        when(processedMessages.findAlreadyProcessedIds(connection.getId(),
                List.of("seen-before", "brand-new"))).thenReturn(Set.of("seen-before"));
        headersFor("brand-new", TRUSTED_HEADER);
        when(gate.evaluate(anyString())).thenReturn(trusted("merchant.example"));

        GmailMessageDiscoveryService.DiscoveryResult result = service.discoverFor(connection, 100);

        verify(gmail).getMessageHeaders(TOKEN, "brand-new");
        verify(gmail, never()).getMessageHeaders(TOKEN, "seen-before");
        assertThat(result.alreadySeen()).isEqualTo(1);
        assertThat(result.examined()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------
    // Ordering: record, then advance
    // ---------------------------------------------------------------------------------------

    /**
     * The checkpoint ordering, asserted as an ordering rather than inferred.
     *
     * <p>If {@code last_discovery_at} moved before outcomes were written, a crash mid-run would
     * advance the window past mail that was never recorded — and that mail becomes permanently
     * invisible, with nothing anywhere to indicate it was skipped. The reverse costs a re-examined
     * message, which the processed table makes free.
     */
    @Test
    @DisplayName("outcomes are recorded before the window advances")
    void theWindowAdvancesOnlyAfterOutcomesAreRecorded() {
        listReturns(page(List.of("m1"), null));
        headersFor("m1", TRUSTED_HEADER);
        when(gate.evaluate(anyString())).thenReturn(trusted("merchant.example"));

        service.discoverFor(connection, 100);

        InOrder inOrder = inOrder(processedMessages, connections);
        inOrder.verify(processedMessages).saveAndFlush(any(GmailProcessedMessage.class));
        inOrder.verify(connections).save(any(GmailConnection.class));
    }

    @Test
    void aRunThatExaminesNothingStillRecordsThatWeLooked() {
        listReturns(page(List.of(), null));

        service.discoverFor(connection, 100);

        verify(connections).save(any(GmailConnection.class));
        assertThat(connection.getLastDiscoveryAt()).isNotNull();
    }

    // ---------------------------------------------------------------------------------------
    // The Gmail query window
    // ---------------------------------------------------------------------------------------

    @Test
    void aFirstRunAsksForABoundedWindowRatherThanTheWholeMailbox() {
        listReturns(page(List.of(), null));

        service.discoverFor(connection, 100);

        assertThat(capturedQuery())
                .isEqualTo("after:" + gmailDate(Instant.now().minus(GmailMessageDiscoveryService.INITIAL_WINDOW)));
    }

    /**
     * A subsequent run asks from the last check minus an overlap. The overlap is the point: Gmail's
     * {@code after:} is a whole-day filter, so a zero overlap can drop a message delivered near the
     * boundary — and a dropped message is invisible.
     */
    @Test
    void aSubsequentRunAsksFromTheLastCheckMinusAnOverlap() {
        Instant lastChecked = Instant.now().minus(Duration.ofDays(1));
        connection.setLastDiscoveryAt(lastChecked);
        listReturns(page(List.of(), null));

        service.discoverFor(connection, 100);

        assertThat(capturedQuery())
                .isEqualTo("after:" + gmailDate(lastChecked.minus(GmailMessageDiscoveryService.WINDOW_OVERLAP)));
    }

    // ---------------------------------------------------------------------------------------
    // Limits and pagination
    // ---------------------------------------------------------------------------------------

    @Test
    void paginationFollowsGmailsPageToken() {
        when(gmail.listMessages(eq(TOKEN), anyString(), isNull(), anyInt()))
                .thenReturn(page(List.of("m1"), "page-2"));
        when(gmail.listMessages(eq(TOKEN), anyString(), eq("page-2"), anyInt()))
                .thenReturn(page(List.of("m2"), null));
        headersFor("m1", TRUSTED_HEADER);
        headersFor("m2", TRUSTED_HEADER);
        when(gate.evaluate(anyString())).thenReturn(trusted("merchant.example"));

        GmailMessageDiscoveryService.DiscoveryResult result = service.discoverFor(connection, 100);

        assertThat(result.examined()).isEqualTo(2);
    }

    /**
     * The cap is a pause, not a failure. One large mailbox must not be able to consume a whole tick,
     * and the processed table is what makes stopping early safe — the next run resumes rather than
     * restarting.
     */
    @Test
    void thePerRunCapStopsTheRunAndSaysSo() {
        listReturns(page(List.of("m1", "m2", "m3"), "page-2"));
        headersFor("m1", TRUSTED_HEADER);
        headersFor("m2", TRUSTED_HEADER);
        when(gate.evaluate(anyString())).thenReturn(trusted("merchant.example"));

        GmailMessageDiscoveryService.DiscoveryResult result = service.discoverFor(connection, 2);

        assertThat(result.examined()).isEqualTo(2);
        assertThat(result.hitLimit()).isTrue();
        verify(gmail, never()).getMessageHeaders(TOKEN, "m3");
    }

    /**
     * The cap and the window interact, and getting this wrong loses mail with no symptom at all.
     *
     * <p>A first run looks back ninety days. If it stops at its per-run cap, most of that window is
     * still unexamined — so advancing the checkpoint would make the next run ask for "the last two
     * days" and the other eighty-eight would never be listed again. Nothing fails; those receipts
     * simply never exist as far as Finora is concerned.
     */
    @Test
    @DisplayName("a run that stopped at its cap does not advance the window past what it skipped")
    void aCappedRunLeavesTheCheckpointWhereItWas() {
        listReturns(page(List.of("m1", "m2", "m3"), "page-2"));
        headersFor("m1", TRUSTED_HEADER);
        headersFor("m2", TRUSTED_HEADER);
        when(gate.evaluate(anyString())).thenReturn(trusted("merchant.example"));

        GmailMessageDiscoveryService.DiscoveryResult result = service.discoverFor(connection, 2);

        assertThat(result.hitLimit()).isTrue();
        verify(connections, never()).save(any(GmailConnection.class));
        assertThat(connection.getLastDiscoveryAt())
                .as("the next run must re-list the same window and continue where this one stopped")
                .isNull();
    }

    // ---------------------------------------------------------------------------------------
    // A message that vanished between listing and fetching
    // ---------------------------------------------------------------------------------------

    /**
     * The livelock this prevents is the point.
     *
     * <p>A user can delete a message between the list and the fetch, and that id is then permanently
     * dead. Treated as a transient failure it would abort the run, and the next run would reach the
     * same id and abort again — so every message behind it stops being examined, forever, while the
     * logs show a recurring "transient" Gmail error.
     */
    @Test
    @DisplayName("a deleted message is skipped, and the rest of the run continues")
    void aVanishedMessageDoesNotStopTheRun() {
        listReturns(page(List.of("deleted", "still-there"), null));
        when(gmail.getMessageHeaders(TOKEN, "deleted"))
                .thenThrow(new GmailMessageGoneException("Gmail no longer has this message (404)."));
        headersFor("still-there", TRUSTED_HEADER);
        when(gate.evaluate(anyString())).thenReturn(trusted("merchant.example"));

        GmailMessageDiscoveryService.DiscoveryResult result = service.discoverFor(connection, 100);

        assertThat(result.vanished()).isEqualTo(1);
        assertThat(result.trusted()).isEqualTo(1);
        // Nothing recorded for the dead id: a message that vanished before it was read was never
        // decided about, and an invented outcome would be a claim no evidence supports.
        assertThat(captureSaved().getGmailMessageId()).isEqualTo("still-there");
    }

    /** A vanished id must not consume the per-run budget either -- otherwise a mailbox with a run of
     *  recently-deleted mail would spend its whole cap examining nothing. */
    @Test
    void aVanishedMessageDoesNotConsumeTheRunBudget() {
        listReturns(page(List.of("deleted", "m1", "m2"), null));
        when(gmail.getMessageHeaders(TOKEN, "deleted"))
                .thenThrow(new GmailMessageGoneException("gone"));
        headersFor("m1", TRUSTED_HEADER);
        headersFor("m2", TRUSTED_HEADER);
        when(gate.evaluate(anyString())).thenReturn(trusted("merchant.example"));

        GmailMessageDiscoveryService.DiscoveryResult result = service.discoverFor(connection, 2);

        assertThat(result.examined()).isEqualTo(2);
        assertThat(result.hitLimit()).isFalse();
        verify(gmail).getMessageHeaders(TOKEN, "m2");
    }

    // ---------------------------------------------------------------------------------------
    // The at-least-once guarantee at the write
    // ---------------------------------------------------------------------------------------

    /**
     * Two overlapping runs can reach the same message. The unique index is what makes the second
     * write a no-op — and this class has to treat that rejection as the design working, not as a
     * failure, or the run would die on exactly the case the index was added to make safe.
     */
    @Test
    @DisplayName("a message another run already recorded is a no-op, not a failed run")
    void aDuplicateRecordIsSwallowed() {
        listReturns(page(List.of("m1", "m2"), null));
        headersFor("m1", TRUSTED_HEADER);
        headersFor("m2", TRUSTED_HEADER);
        when(gate.evaluate(anyString())).thenReturn(trusted("merchant.example"));
        when(processedMessages.saveAndFlush(any(GmailProcessedMessage.class)))
                .thenThrow(new DataIntegrityViolationException("uq_gmail_processed_message"))
                .thenReturn(null);

        GmailMessageDiscoveryService.DiscoveryResult result = service.discoverFor(connection, 100);

        assertThat(result.examined()).isEqualTo(2);
        verify(connections).save(any(GmailConnection.class));
    }

    // ---------------------------------------------------------------------------------------
    // Connections that must not be touched at all
    // ---------------------------------------------------------------------------------------

    /**
     * A dead grant costs a token-refresh request to rediscover, every tick, forever. Checking the
     * status first is what keeps {@code REAUTH_REQUIRED} from being a standing tax.
     */
    @Test
    void aConnectionNeedingReauthIsNotTouchedAtAll() {
        connection.markReauthRequired();

        GmailMessageDiscoveryService.DiscoveryResult result = service.discoverFor(connection, 100);

        assertThat(result.examined()).isZero();
        verifyNoInteractions(gmail);
        verify(accessTokens, never()).accessTokenFor(any());
    }

    /**
     * Consent completed without {@code gmail.readonly}. Gmail answers 403 to every call, so without
     * this check a single connection would burn one refused request per message per tick.
     */
    @Test
    void aConnectionWithoutTheReadScopeIsNotTouchedAtAll() {
        connection.setGrantedScopes("https://www.googleapis.com/auth/userinfo.email");

        service.discoverFor(connection, 100);

        verifyNoInteractions(gmail);
        verify(accessTokens, never()).accessTokenFor(any());
    }

    // ---------------------------------------------------------------------------------------
    // Failure propagation
    // ---------------------------------------------------------------------------------------

    /**
     * A transient failure must reach the worker rather than being swallowed into a "successful" run
     * — swallowing it would advance the window over mail that was never examined.
     */
    @Test
    void aTransientGmailFailurePropagatesInsteadOfAdvancingTheWindow() {
        when(gmail.listMessages(any(), any(), any(), anyInt()))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Gmail is unavailable."));

        assertThatThrownBy(() -> service.discoverFor(connection, 100))
                .isInstanceOf(ApiException.class);

        verify(connections, never()).save(any(GmailConnection.class));
    }

    // ---------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------

    private static GmailConnection connectedMailbox() {
        GmailConnection connection = new GmailConnection();
        connection.setUserId(UUID.randomUUID());
        connection.setGoogleUserId("google-sub-1");
        connection.setGoogleEmail("mailbox@example.test");
        connection.setGrantedScopes(GmailApiClient.GMAIL_READONLY_SCOPE);
        connection.setStatus(GmailConnection.Status.CONNECTED);
        // The entity's id is normally assigned by JPA; discovery uses it as a key, so it has to be
        // present for the mocks to line up.
        try {
            var field = GmailConnection.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(connection, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return connection;
    }

    private static SenderAuthenticationService.Result trusted(String domain) {
        return new SenderAuthenticationService.Result(
                SenderAuthenticationService.Verdict.TRUSTED, domain);
    }

    private static GmailApiClient.MessagePage page(List<String> ids, String nextPageToken) {
        return new GmailApiClient.MessagePage(
                ids.stream().map(id -> new GmailApiClient.MessageId(id, "thread")).toList(),
                nextPageToken);
    }

    private void listReturns(GmailApiClient.MessagePage page) {
        when(gmail.listMessages(any(), any(), any(), anyInt())).thenReturn(page);
    }

    private void headersFor(String messageId, String authenticationResults) {
        when(gmail.getMessageHeaders(TOKEN, messageId)).thenReturn(new GmailApiClient.MessageHeaders(
                messageId, "thread", "1755100000000",
                new GmailApiClient.MessageHeaders.Payload(List.of(
                        new GmailApiClient.MessageHeaders.Header(
                                "Authentication-Results", authenticationResults)))));
    }

    private GmailProcessedMessage captureSaved() {
        ArgumentCaptor<GmailProcessedMessage> captor =
                ArgumentCaptor.forClass(GmailProcessedMessage.class);
        verify(processedMessages).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private String capturedQuery() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(gmail).listMessages(eq(TOKEN), captor.capture(), isNull(), anyInt());
        return captor.getValue();
    }

    private static String gmailDate(Instant instant) {
        return DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC).format(instant);
    }
}
