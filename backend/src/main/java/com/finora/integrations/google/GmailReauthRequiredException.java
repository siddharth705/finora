package com.finora.integrations.google;

/**
 * The stored Gmail credential is permanently dead — Google answered {@code invalid_grant}.
 *
 * <p>A distinct type rather than a flag on a generic failure, because the two call for opposite
 * responses and confusing them is a real defect in both directions:
 *
 * <ul>
 *   <li>Treating a transient failure as this would disconnect a working integration because Google
 *       had a bad minute, and tell the user to reconnect something that was never broken.</li>
 *   <li>Treating this as transient would retry forever against a grant that can never succeed,
 *       burning quota and leaving the user with a connection that silently stops working and never
 *       says why.</li>
 * </ul>
 *
 * <p>Causes, all of which need the USER and none of which a retry fixes: they changed their Google
 * password, removed Finora from their Google account permissions, the token expired through disuse,
 * or Google locked the account.
 *
 * <p>Unchecked and deliberately not an {@code ApiException}: the caller that knows what to do with
 * it is the connection layer, which flips the connection to {@code REAUTH_REQUIRED}. It should not
 * become an HTTP status on its own — a sync worker hitting this has no user waiting on a response.
 */
public class GmailReauthRequiredException extends RuntimeException {

    public GmailReauthRequiredException(String message) {
        super(message);
    }
}
