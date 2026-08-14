package com.finora.integrations.google;

/**
 * The connection exists and its token is valid, but Gmail will not let Finora read the mailbox.
 *
 * <p>Almost always because the user completed consent while declining {@code gmail.readonly} —
 * Google's consent screen permits granting some requested scopes and not others, and the resulting
 * token is perfectly valid for everything else. A connection in this state looks healthy by every
 * measure Finora had before C2: the row says {@code CONNECTED}, the refresh token works, and an
 * access token mints successfully. It simply cannot read a single message.
 *
 * <p>Distinct from {@link GmailReauthRequiredException} because the two mean different things to the
 * user even though both end at the same button. Reauth means "your permission expired or was
 * revoked"; this means "the permission was never given". Keeping them separate lets the UI say
 * which, and lets support tell a re-consent that will work from one that will fail the same way
 * again unless the user ticks the box.
 *
 * <p>Not transient: no retry grants a scope. Only the user re-consenting does.
 */
public class GmailScopeNotGrantedException extends RuntimeException {

    public GmailScopeNotGrantedException(String message) {
        super(message);
    }
}
