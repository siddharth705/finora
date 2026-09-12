package com.finora.integrations.setu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Mirrors ImportSessionService.scheduledSweep's TTL pattern: a CONSENT_PENDING row means the user
 * never finished approving consent in their AA app; a PENDING_ACCOUNT_CONFIRMATION row means they
 * approved consent but never came back to confirm which account it belongs to. Both are the same
 * failure mode -- an abandoned attempt -- and both need reaping so they don't sit as zombie rows
 * forever. Not REJECTED (a real, informative terminal state the user should still be able to see
 * for a while) -- LINK_FAILED, since from the system's point of view this attempt simply never
 * completed, same semantics as a synchronous gateway failure at initiate time.
 */
@Component
public class AccountAggregatorLinkSweepService {

    private static final Logger log = LoggerFactory.getLogger(AccountAggregatorLinkSweepService.class);

    private static final List<AccountAggregatorLinkStatus> SWEEPABLE_STATUSES = List.of(
            AccountAggregatorLinkStatus.CONSENT_PENDING, AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION);

    private final AccountAggregatorLinkRepository links;
    private final Duration ttl;

    public AccountAggregatorLinkSweepService(AccountAggregatorLinkRepository links,
            @Value("${app.integrations.setu.link-ttl-hours:48}") long ttlHours) {
        this.links = links;
        this.ttl = Duration.ofHours(ttlHours);
    }

    @Scheduled(fixedDelayString = "${app.integrations.setu.sweep-interval-ms:900000}")
    public void scheduledSweep() {
        int reaped = sweepStaleLinks();
        if (reaped > 0) {
            log.info("Reaped {} stale Account Aggregator link(s) past their {}h TTL.", reaped, ttl.toHours());
        }
    }

    public int sweepStaleLinks() {
        List<AccountAggregatorLink> stale = links.findByStatusInAndCreatedAtBefore(
                SWEEPABLE_STATUSES, Instant.now().minus(ttl));
        for (AccountAggregatorLink link : stale) {
            link.setStatus(AccountAggregatorLinkStatus.LINK_FAILED);
            links.save(link);
        }
        return stale.size();
    }
}
