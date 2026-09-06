package com.finora.integrations.google;

import com.finora.entity.FeatureEntitlement;
import com.finora.integrations.google.merchant.GmailReceiptExtractionService;
import com.finora.observability.WorkerExecution;
import com.finora.observability.WorkerObservability;
import com.finora.service.EntitlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Decides <b>when</b> discovery runs and <b>for whom</b> — Phase C4.
 *
 * <p>Split from {@link GmailMessageDiscoveryService}, which decides what a single mailbox contains.
 * The seam is deliberate: everything Gmail-shaped lives on the far side of it, so this class knows
 * about scheduling, batching and limits and nothing about the Gmail API. That is also what makes the
 * discovery logic testable without HTTP.
 *
 * <h2>Discovery, then extraction, per connection — not two separate ticks</h2>
 *
 * C5-B added {@link GmailReceiptExtractionService} immediately after {@code discoverFor} in the same
 * loop iteration, not as a second scheduled pass over the whole connection list. A connection whose
 * discovery just found new trusted mail gets that mail extracted in the SAME tick, rather than
 * waiting for a later pass to notice the {@code DETECTED_NOT_STAGED} backlog discovery just created.
 * The two remain separate classes (this file only orchestrates; neither knows about the other's
 * internals) — see each class's own doc comment for why they are split at all.
 *
 * <h2>One failed connection does not fail the tick</h2>
 *
 * A run's failures are per-connection: an expired grant, a mailbox over quota, a transient 5xx. Each
 * is caught here so the remaining connections in the slice still get their pass. A worker that
 * aborted the tick on the first bad mailbox would let one broken connection starve every other user
 * — and the broken one is precisely the one most likely to fail again next tick. Discovery and
 * extraction share one try/catch per connection deliberately: if discovery fails, there is nothing
 * new for extraction to find on this connection this tick anyway, so attempting it would just spend
 * a second doomed request.
 *
 * <h2>No retry loop</h2>
 *
 * Nothing here retries. The run IS the unit of retry: {@code gmail_processed_messages} records what
 * was already decided, so the next tick resumes rather than restarts. Retrying inside the loop would
 * spend requests on a mailbox that is rate-limited or on a grant that is dead, which are the two
 * most common failures.
 */
@Component
public class GmailDiscoveryWorker {

    private static final Logger log = LoggerFactory.getLogger(GmailDiscoveryWorker.class);

    private static final String WORKER = "gmail-discovery";
    private static final String JOB_KIND = "mailbox-scan";

    private final GmailMessageDiscoveryService discovery;
    private final GmailReceiptExtractionService extraction;
    private final GmailConnectionRepository connections;
    private final WorkerObservability observability;
    private final EntitlementService entitlementService;

    private final boolean enabled;
    private final int connectionsPerTick;
    private final int messagesPerConnection;
    private final int extractionMessagesPerConnection;
    private final Duration minimumInterval;

    public GmailDiscoveryWorker(
            GmailMessageDiscoveryService discovery,
            GmailReceiptExtractionService extraction,
            GmailConnectionRepository connections,
            WorkerObservability observability,
            EntitlementService entitlementService,
            @Value("${app.integrations.google.discovery.enabled:true}") boolean enabled,
            @Value("${app.integrations.google.discovery.connections-per-tick:25}") int connectionsPerTick,
            @Value("${app.integrations.google.discovery.messages-per-connection:500}") int messagesPerConnection,
            // Smaller than discovery's own cap on purpose: a discovery message costs one header
            // fetch, an extraction message costs a body fetch plus parsing plus a staging write --
            // meaningfully more expensive per message, so its own ceiling is lower.
            @Value("${app.integrations.google.discovery.extraction-messages-per-connection:50}") int extractionMessagesPerConnection,
            @Value("${app.integrations.google.discovery.minimum-interval-ms:3600000}") long minimumIntervalMs) {
        this.discovery = discovery;
        this.extraction = extraction;
        this.connections = connections;
        this.observability = observability;
        this.entitlementService = entitlementService;
        this.enabled = enabled;
        this.connectionsPerTick = connectionsPerTick;
        this.messagesPerConnection = messagesPerConnection;
        this.extractionMessagesPerConnection = extractionMessagesPerConnection;
        this.minimumInterval = Duration.ofMillis(minimumIntervalMs);
    }

    /**
     * The scheduled trigger.
     *
     * <p>{@code fixedDelay}, never {@code fixedRate}: a tick that runs long must not have the next
     * one start on top of it. Overlapping ticks would double Gmail's request rate for the same
     * mailboxes at exactly the moment the API is already slow.
     *
     * <p>Flag-gated for the reason {@code GmailConnectionService.scheduledStateSweep} gives: an
     * integration suite needs deterministic state, and a background thread making outbound HTTP
     * calls mid-test is the cross-test pollution BH-058 was about. {@code application-test.yml}
     * turns it off and tests call {@link #runOnce()} directly.
     */
    @Scheduled(fixedDelayString = "${app.integrations.google.discovery.interval-ms:900000}",
               initialDelayString = "${app.integrations.google.discovery.initial-delay-ms:180000}")
    public void scheduledDiscovery() {
        if (!enabled) return;
        runOnce();
    }

    /**
     * Runs one pass over a bounded slice of connections.
     *
     * <p>Public and synchronous so tests can drive it deterministically rather than waiting on a
     * scheduler — the same reason {@code ImportJobWorker.drainOnce} is.
     *
     * @return how many connections were attempted
     */
    public int runOnce() {
        try (WorkerExecution execution = observability.beginScheduled(WORKER, JOB_KIND)) {
            // Connections checked within the minimum interval are not due. Without this the slice
            // would return the same first N mailboxes every tick and mailboxes beyond the slice
            // would never be reached -- the ordering makes that starvation quiet rather than
            // visible, which is worse.
            List<GmailConnection> due = connections.findDueForDiscovery(
                    Instant.now().minus(minimumInterval),
                    PageRequest.of(0, connectionsPerTick));
            execution.claimed(due.size());

            for (GmailConnection connection : due) {
                // A connection stays live across a plan downgrade -- GmailConnectionService only
                // ever refuses a NEW connect, it never tears an existing one down. Without this
                // check, a Premium user who downgrades keeps getting free background sync forever,
                // which is exactly the ongoing cost GMAIL_SYNC exists to gate. Checked here rather
                // than added to findDueForDiscovery's own query, to keep entitlement lookups
                // (EntitlementService, the billing domain) out of a plain connection repository.
                if (!entitlementService.hasEntitlement(connection.getUserId(), FeatureEntitlement.GMAIL_SYNC)) {
                    log.info("Gmail connection {} is no longer entitled to GMAIL_SYNC; skipping discovery.",
                            connection.getId());
                    continue;
                }
                try {
                    discovery.discoverFor(connection, messagesPerConnection);
                    extraction.extractFor(connection, extractionMessagesPerConnection);
                    execution.completed(connection.getId());
                } catch (GmailReauthRequiredException e) {
                    // Expected, not exceptional. GmailAccessTokenService has already flipped the
                    // connection to REAUTH_REQUIRED, which is what removes it from the due query --
                    // so this resolves itself and needs no alert.
                    log.info("Gmail connection {} needs reconnecting; skipping discovery.",
                            connection.getId());
                } catch (GmailScopeNotGrantedException e) {
                    // The user completed consent without gmail.readonly. Also permanent until they
                    // reconnect, but unlike a dead grant it does NOT change the status, so this WILL
                    // recur every tick. Logged at warn so a rising count is visible rather than
                    // silently costing a request per connection per tick.
                    log.warn("Gmail connection {} lacks the readonly scope; discovery cannot run.",
                            connection.getId());
                } catch (RuntimeException e) {
                    // Transient by elimination: a timeout, a 5xx, a rate limit. The next tick
                    // resumes from what was recorded, so this is a delay rather than a loss.
                    log.warn("Gmail discovery failed for connection {}: {}",
                            connection.getId(), e.getClass().getSimpleName());
                }
            }
            return due.size();
        }
    }
}
