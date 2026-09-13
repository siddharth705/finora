package com.finora.integrations.setu;

/** What starting a consent request returns: Setu's handle for it, and the URL to redirect the
 *  user to their own AA app. Neither is persisted as-is on AccountAggregatorLink except
 *  consentHandleId -- redirectUrl is single-use, returned straight to the caller. */
public record SetuConsentInitiation(String consentHandleId, String redirectUrl) {}
