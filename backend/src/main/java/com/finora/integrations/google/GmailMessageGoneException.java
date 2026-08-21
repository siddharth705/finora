package com.finora.integrations.google;

/**
 * Gmail no longer has this message. The mailbox is fine; one id in it is not.
 *
 * <p>Ordinary rather than exceptional: discovery lists ids and then fetches them one at a time, and
 * a user can delete a message — or empty their trash — in between. The id is then permanently dead.
 *
 * <h2>Why this is not simply a transient failure</h2>
 *
 * A 404 looks like every other 4xx from the outside, and treating it as transient produces a
 * <b>livelock</b> rather than a visible error. The run aborts on the dead id, records nothing for
 * it, and resumes on the next tick — from the same window, reaching the same id, failing the same
 * way. The mailbox never advances past it, and every message behind it stops being examined. The
 * logs show a transient failure recurring, which reads as a Gmail problem rather than a message
 * that no longer exists.
 *
 * <p>So this is separated from {@link com.finora.exception.ApiException} at the client boundary, and
 * discovery skips the id and carries on. Nothing is recorded for it: a message that vanished before
 * it was read was never decided about, and inventing an outcome would put a claim in the provenance
 * table that no evidence supports.
 */
public class GmailMessageGoneException extends RuntimeException {

    public GmailMessageGoneException(String message) {
        super(message);
    }
}
