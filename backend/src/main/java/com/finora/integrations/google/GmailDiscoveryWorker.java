package com.finora.integrations.google;

import com.finora.observability.WorkerExecution;
import com.finora.observability.WorkerObservability;
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
 * <h2>One failed connection does not fail the tick</h2>
 *
 * A run's failures are per-connection: an expired grant, a mailbox over quota, a transient 5xx. Each
 * is caught here so the remaining connections in the slice still get their pass. A worker that
 * aborted the tick on the first bad mailbox would let one broken connection starve every other user
 * — and the broken one is precisely the one most likely to fail again next tick.
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
    private final GmailConnectionRepository connections;
    private final WorkerObservability observability;

    private final boolean enabled;
    private final int connectionsPerTick;
    private final int messagesPerConnection;
    private final Duration minimumInterval;

    public GmailDiscoveryWorker(
            GmailMessageDiscoveryService discovery,
            GmailConnectionRepository connections,
            WorkerObservability observability,
            @Value("${app.integrations.google.discovery.enabled:true}") boolean enabled,
            @Value("${app.integrations.google.discovery.connections-per-tick:25}") int connectionsPerTick,
            @Value("${app.integrations.google.discovery.messages-per-connection:500}") int messagesPerConnection,
            @Value("${app.integrations.google.discovery.minimum-interval-ms:3600000}") long minimumIntervalMs) {
        this.discovery = discovery;
        this.connections = connections;
        this.observability = observability;
        this.enabled = enabled;
        this.connectionsPerTick = connectionsPerTick;
        this.messagesPerConnection = messagesPerConnection;
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
                try {
                    discovery.discoverFor(connection, messagesPerConnection);
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
