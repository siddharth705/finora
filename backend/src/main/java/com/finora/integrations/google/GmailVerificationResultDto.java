package com.finora.integrations.google;

/**
 * The answer to "is my Gmail connection actually working?" — checked against Google, not inferred
 * from a stored status.
 *
 * <p>The distinction matters: {@code GET /status} reports what the database believes, which stays
 * {@code CONNECTED} right up until something tries to use the credential and finds it dead. Nothing
 * in Finora discovers a revoked grant until it attempts a refresh, so a user whose token was
 * revoked at Google's end sees a healthy status until the first sync fails. This endpoint is how
 * they (and support) can find out on demand.
 *
 * <p>Carries no credential material — see {@link GmailConnectionStatusDto}'s own note. The access
 * token minted during verification is used to prove the grant works and then discarded; it is never
 * stored and never returned.
 *
 * @param healthy        whether Google honoured the stored credential just now
 * @param status         the connection's status AFTER verification — a failed check may have moved
 *                       it to REAUTH_REQUIRED, and the caller should render that rather than the
 *                       status it held before
 * @param message        plain-language outcome, safe to show a user
 * @param actionRequired true when only the user can fix this (reconnect); false when it is worth
 *                       retrying, so the UI can offer "try again" rather than sending someone
 *                       through a consent screen for a transient blip
 */
public record GmailVerificationResultDto(
        boolean healthy,
        String status,
        String message,
        boolean actionRequired
) {

    static GmailVerificationResultDto healthy(GmailConnection connection) {
        return new GmailVerificationResultDto(true, connection.getStatus().name(),
                "Your Gmail connection is working.", false);
    }

    /** The grant is gone. Only the user can restore it, so the UI should offer Reconnect. */
    static GmailVerificationResultDto reauthRequired() {
        return new GmailVerificationResultDto(false, GmailConnection.Status.REAUTH_REQUIRED.name(),
                "Google no longer accepts this connection. Reconnect your Gmail account to continue.",
                true);
    }

    /**
     * Google could not be reached, or answered with something that says nothing about the grant.
     * Deliberately reports the connection's EXISTING status rather than a failure state — the
     * connection has not been changed, and telling a user to reconnect over a timeout would send
     * them through a consent screen to fix nothing.
     */
    static GmailVerificationResultDto temporarilyUnavailable(GmailConnection connection) {
        return new GmailVerificationResultDto(false, connection.getStatus().name(),
                "Could not reach Google to check this connection. Try again shortly.", false);
    }
}
