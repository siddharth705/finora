package com.finora.integrations.google;

import java.time.Instant;
import java.util.List;

/**
 * What the user is told about their Gmail connection.
 *
 * <p>Carries no credential material of any kind — no token, no ciphertext, not even the
 * encryption key id. This is the shape that reaches a browser, and the only reason any of those
 * fields exist is so that they never leave the server. Operational metadata only, which is exactly
 * what the design doc's user-facing connection panel asks for.
 *
 * @param connected         whether a usable connection exists right now
 * @param status            CONNECTED / REAUTH_REQUIRED / DISCONNECTED / REVOKED, or null if never connected
 * @param needsReconnect    true for REAUTH_REQUIRED/REVOKED specifically -- connected is already
 *                          false for these, but a client also needs to tell "never connected" apart
 *                          from "was connected, involuntarily isn't now" to show the right prompt.
 *                          Computed here from {@link GmailConnection.Status#needsReconnect()} so a
 *                          client never has to duplicate that enum's semantics itself.
 * @param googleEmail       which mailbox, so the user can tell WHICH account is linked
 * @param grantedScopes     what Google actually granted — visible so "read-only" is verifiable, not just claimed
 * @param connectedAt       when the link was established
 * @param lastSyncedAt      still null always — {@link GmailConnection}'s own doc reserves this for
 *                          actual transaction sync, which nothing sets yet. Kept rather than removed,
 *                          since an older client may already read it; {@code lastDiscoveryAt} below
 *                          is the live signal the connection panel (C5.4) actually shows as "Last synced".
 * @param lastDiscoveryAt   when discovery+extraction last ran for this mailbox — C5.4. Null means
 *                          never checked.
 * @param transactionsFound how many receipts this mailbox has ever produced (PARSED outcome),
 *                          regardless of review state — C5.4.
 * @param needsReview       how many staged Gmail sessions are still waiting in the review queue — C5.4.
 * @param available         whether this deployment has Gmail configured at all — lets the UI hide the
 *                          entry point instead of offering a button that answers 503
 */
public record GmailConnectionStatusDto(
        boolean connected,
        String status,
        boolean needsReconnect,
        String googleEmail,
        List<String> grantedScopes,
        Instant connectedAt,
        Instant lastSyncedAt,
        Instant lastDiscoveryAt,
        int transactionsFound,
        int needsReview,
        boolean available
) {

    public static GmailConnectionStatusDto notConnected(boolean available) {
        return new GmailConnectionStatusDto(false, null, false, null, List.of(), null, null, null, 0, 0, available);
    }

    public static GmailConnectionStatusDto of(GmailConnection connection, boolean available,
                                               int transactionsFound, int needsReview) {
        List<String> scopes = connection.getGrantedScopes() == null || connection.getGrantedScopes().isBlank()
                ? List.of()
                : List.of(connection.getGrantedScopes().split(" "));
        return new GmailConnectionStatusDto(
                connection.getStatus() == GmailConnection.Status.CONNECTED,
                connection.getStatus().name(),
                connection.getStatus().needsReconnect(),
                connection.getGoogleEmail(),
                scopes,
                connection.getConnectedAt(),
                connection.getLastSyncedAt(),
                connection.getLastDiscoveryAt(),
                transactionsFound,
                needsReview,
                available);
    }
}
