package com.finora.integrations.setu;

/**
 * Where one linked FI account is in its life. See the design spec's "Consent lifecycle" section
 * (docs/superpowers/specs/2026-09-12-account-aggregator-sync-design.md) for the full state diagram
 * and every transition's trigger.
 */
public enum AccountAggregatorLinkStatus {
    /** Consent request created at Setu, waiting for the user to approve or decline it in their
     *  own AA app. */
    CONSENT_PENDING,
    /** The user approved consent, but AccountAggregatorIdentityResolutionService could only reach
     *  a PROBABLE match against an existing account -- never auto-attached, waiting for the user
     *  to pick "this existing account" or "a new one" via AccountAggregatorLinkController. */
    PENDING_ACCOUNT_CONFIRMATION,
    /** Usable: attached to an Account, syncing (once Plan 2 exists). */
    ACTIVE,
    /** Consent may still be valid at Setu, but the user's plan no longer grants
     *  ACCOUNT_AGGREGATOR_SYNC -- syncing stops without tearing down the consent. */
    PAUSED,
    /** The user revoked consent in their own AA app (out-of-band -- Fynora only observes this via
     *  webhook, it cannot force a revoke). Terminal. */
    REVOKED,
    /** The AA consent's own validity window elapsed. Terminal. */
    EXPIRED,
    /** The user declined consent inside their AA app. A normal, common outcome, not an error.
     *  Terminal. */
    REJECTED,
    /** Setu's consent-creation call itself failed, or CONSENT_PENDING/
     *  PENDING_ACCOUNT_CONFIRMATION sat unresolved past AccountAggregatorLinkSweepService's TTL.
     *  Terminal -- the user retries by starting a new link, not by recovering this row. */
    LINK_FAILED
}
