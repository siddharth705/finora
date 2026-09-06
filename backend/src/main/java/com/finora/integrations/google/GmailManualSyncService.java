package com.finora.integrations.google;

import com.finora.entity.FeatureEntitlement;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.integrations.google.merchant.GmailReceiptExtractionService;
import com.finora.service.EntitlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * "Sync Now" — C5.4. The one-user, one-request equivalent of {@link GmailDiscoveryWorker}'s tick:
 * same two calls, same per-connection try/catch shape, run synchronously for the caller's own
 * connection instead of a scheduled slice of everyone's. Deliberately a separate class rather than
 * a new method on {@code GmailDiscoveryWorker} — that class's own doc comment scopes it to
 * "when discovery runs and for whom" on a schedule; this is a different trigger (a user action) and
 * a different quantity (one connection, blocking), not a variant of the same concern.
 *
 * <h2>Why its own cooldown, not the worker's {@code minimum-interval-ms}</h2>
 *
 * The worker's interval (default 1 hour) governs background cost across every mailbox. A user who
 * just pressed "Sync Now" is not asking to wait an hour before pressing it again — they are asking
 * "check right now", and a much shorter cooldown is enough to stop double-click/refresh-spam from
 * turning one click into a burst of Gmail API calls for the same mailbox.
 */
@Service
public class GmailManualSyncService {

    private static final Logger log = LoggerFactory.getLogger(GmailManualSyncService.class);

    private final GmailConnectionService connectionService;
    private final GmailMessageDiscoveryService discovery;
    private final GmailReceiptExtractionService extraction;
    private final EntitlementService entitlementService;
    private final Duration cooldown;
    private final int messagesPerConnection;
    private final int extractionMessagesPerConnection;

    public GmailManualSyncService(
            GmailConnectionService connectionService,
            GmailMessageDiscoveryService discovery,
            GmailReceiptExtractionService extraction,
            EntitlementService entitlementService,
            @Value("${app.integrations.google.discovery.manual-sync-cooldown-ms:60000}") long cooldownMs,
            @Value("${app.integrations.google.discovery.messages-per-connection:500}") int messagesPerConnection,
            @Value("${app.integrations.google.discovery.extraction-messages-per-connection:50}") int extractionMessagesPerConnection) {
        this.connectionService = connectionService;
        this.discovery = discovery;
        this.extraction = extraction;
        this.entitlementService = entitlementService;
        this.cooldown = Duration.ofMillis(cooldownMs);
        this.messagesPerConnection = messagesPerConnection;
        this.extractionMessagesPerConnection = extractionMessagesPerConnection;
    }

    /**
     * Runs discovery then extraction for this user's live connection, right now.
     *
     * <p>Entitlement is checked before the connection lookup, deliberately: a Free/Plus user who
     * downgraded after connecting (or reaches this endpoint directly) should be told to upgrade,
     * not "no connected account" -- the account may well still exist, just no longer usable here.
     *
     * @throws ApiException 403 if the caller isn't entitled to GMAIL_SYNC, 404 if there is no live
     *         connection, 429 if the cooldown hasn't elapsed
     */
    public void syncNow(UUID userId) {
        if (!entitlementService.hasEntitlement(userId, FeatureEntitlement.GMAIL_SYNC)) {
            throw new ApiException(ErrorCode.ENTITLEMENT_REQUIRED);
        }

        GmailConnection connection = connectionService.findLiveConnection(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No connected Gmail account."));

        Instant lastDiscovery = connection.getLastDiscoveryAt();
        if (lastDiscovery != null && lastDiscovery.isAfter(Instant.now().minus(cooldown))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Gmail was synced recently -- try again in a moment.");
        }

        try {
            discovery.discoverFor(connection, messagesPerConnection);
            extraction.extractFor(connection, extractionMessagesPerConnection);
        } catch (GmailReauthRequiredException e) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This Gmail connection needs to be reconnected before syncing.");
        } catch (GmailScopeNotGrantedException e) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This Gmail connection is missing the permission needed to read mail -- reconnect to grant it.");
        } catch (RuntimeException e) {
            // Transient by elimination, same reasoning as GmailDiscoveryWorker's own catch --
            // logged, not swallowed, since this is a synchronous user-facing call and the
            // exception becomes a 502 rather than a silently-skipped background tick.
            log.warn("Manual Gmail sync failed for connection {}: {}",
                    connection.getId(), e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Gmail sync didn't complete -- try again in a moment.");
        }
    }
}
