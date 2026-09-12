package com.finora.integrations.setu;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountAggregatorLinkRepository extends JpaRepository<AccountAggregatorLink, UUID> {

    Optional<AccountAggregatorLink> findByUserIdAndLinkIdempotencyKey(UUID userId, String linkIdempotencyKey);

    Optional<AccountAggregatorLink> findByConsentHandleId(String consentHandleId);

    Optional<AccountAggregatorLink> findByAccountIdAndStatus(UUID accountId, AccountAggregatorLinkStatus status);

    /** For AccountAggregatorLinkSweepService's stale-row TTL check (Task 12) -- rows stuck in an
     *  in-progress status past a cutoff. */
    List<AccountAggregatorLink> findByStatusInAndCreatedAtBefore(
            List<AccountAggregatorLinkStatus> statuses, Instant cutoff);
}
