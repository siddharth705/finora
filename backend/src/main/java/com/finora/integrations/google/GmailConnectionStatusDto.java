package com.finora.integrations.google;

import java.time.Instant;
import java.util.List;

/**
 * What the user is told about their Gmail connection.
 *
 * <p><b>Carries no credential material of any kind</b> — no token, no ciphertext, not even the
 * encryption key id. This is the shape that reaches a browser, and the only reason any of those
 * fields exist is so that they never leave the server. Operational metadata only, which is exactly
 * what the design doc's user-facing connection panel asks for.
 *
 * @param connected      whether a usable connection exists right now
 * @param status         CONNECTED / REAUTH_REQUIRED / DISCONNECTED / REVOKED, or null if never connected
 * @param googleEmail    which mailbox, so the user can tell WHICH account is linked
 * @param grantedScopes  what Google actually granted — visible so "read-only" is verifiable, not just claimed
 * @param connectedAt    when the link was established
 * @param lastSyncedAt   null throughout Phase B; nothing syncs yet
 * @param available      whether this deployment has Gmail configured at all — lets the UI hide the
 *                       entry point instead of offering a button that answers 503
 */
public record GmailConnectionStatusDto(
        boolean connected,
        String status,
        String googleEmail,
        List<String> grantedScopes,
        Instant connectedAt,
        Instant lastSyncedAt,
        boolean available
) {

    public static GmailConnectionStatusDto notConnected(boolean available) {
        return new GmailConnectionStatusDto(false, null, null, List.of(), null, null, available);
    }

    public static GmailConnectionStatusDto of(GmailConnection connection, boolean available) {
        List<String> scopes = connection.getGrantedScopes() == null || connection.getGrantedScopes().isBlank()
                ? List.of()
                : List.of(connection.getGrantedScopes().split(" "));
        return new GmailConnectionStatusDto(
                connection.getStatus() == GmailConnection.Status.CONNECTED,
                connection.getStatus().name(),
                connection.getGoogleEmail(),
                scopes,
                connection.getConnectedAt(),
                connection.getLastSyncedAt(),
                available);
    }
}
