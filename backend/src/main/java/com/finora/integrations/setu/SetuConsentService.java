package com.finora.integrations.setu;

import com.finora.entity.FeatureEntitlement;
import com.finora.exception.ApiException;
import com.finora.service.AuditService;
import com.finora.service.EntitlementService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class SetuConsentService {

    private final AccountAggregatorLinkRepository links;
    private final SetuConsentGateway gateway;
    private final EntitlementService entitlementService;
    private final AuditService auditService;

    public SetuConsentService(AccountAggregatorLinkRepository links, SetuConsentGateway gateway,
                               EntitlementService entitlementService, AuditService auditService) {
        this.links = links;
        this.gateway = gateway;
        this.entitlementService = entitlementService;
        this.auditService = auditService;
    }

    /** @param idempotencyKey client-minted, unique per (user, attempt) -- see
     *                        AccountAggregatorLink's own doc comment. */
    public InitiateLinkResult initiateLink(UUID userId, FiType fiType, String idempotencyKey) {
        if (!entitlementService.hasEntitlement(userId, FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC)) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Account Aggregator sync is a Premium feature.");
        }

        Optional<AccountAggregatorLink> existing =
                links.findByUserIdAndLinkIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            // A retried or double-submitted request for the SAME attempt -- return the row already
            // created, redirectUrl null because there is nothing new to redirect to (the caller
            // already holds one from the original response, or is retrying after losing it, in
            // which case they need to start a fresh attempt with a new key, not reuse a dead one).
            return new InitiateLinkResult(existing.get(), null);
        }

        if (!gateway.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Account Aggregator sync is not available right now.");
        }

        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(userId);
        link.setFiType(fiType);
        link.setLinkIdempotencyKey(idempotencyKey);

        SetuConsentInitiation initiation;
        try {
            initiation = gateway.createConsent(userId.toString(), fiType);
        } catch (RuntimeException e) {
            link.setStatus(AccountAggregatorLinkStatus.LINK_FAILED);
            links.save(link);
            auditService.record(userId, "ACCOUNT_AGGREGATOR_LINK_FAILED", "AccountAggregatorLink", link.getId());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not start linking your account. Please try again.");
        }

        link.setConsentHandleId(initiation.consentHandleId());
        link.setStatus(AccountAggregatorLinkStatus.CONSENT_PENDING);
        try {
            link = links.save(link);
        } catch (DataIntegrityViolationException e) {
            // Two concurrent requests for the same (user, idempotencyKey) both passed the
            // empty-check above before either committed -- the unique index on
            // account_aggregator_links(user_id, link_idempotency_key) is the real guarantee (see
            // that migration's own comment), and this is the second request losing the race. The
            // Setu consent this request just created is an orphan (no link row references it) --
            // acceptable: it costs one extra consent creation on the rare concurrent-double-submit
            // case, which is far cheaper than either a 500 or a duplicate link row would be. Return
            // whichever row actually won, exactly like the existing-key branch above.
            return new InitiateLinkResult(
                    links.findByUserIdAndLinkIdempotencyKey(userId, idempotencyKey).orElseThrow(() -> e), null);
        }
        auditService.record(userId, "ACCOUNT_AGGREGATOR_CONSENT_CREATED", "AccountAggregatorLink", link.getId());

        return new InitiateLinkResult(link, initiation.redirectUrl());
    }

    public record InitiateLinkResult(AccountAggregatorLink link, String redirectUrl) {}
}
