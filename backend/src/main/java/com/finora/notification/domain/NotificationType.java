package com.finora.notification.domain;

/**
 * Semantic trigger names. A caller names the event, never a title/body string -- copy lives in
 * notification_templates so it is reviewable in one place and reusable across channels.
 *
 * <p>Add a value here together with its notification_templates rows; a type with no template row
 * cannot render and will dead-letter.
 */
public enum NotificationType {
    PASSWORD_CHANGED,
    IMPORT_STATEMENT_READY,
    // Fired once, the first time a statement is held (parser gap or trust review) -- see
    // ImportJobWorker.recordFailure and HeldStatementService.openHold. One shared type for both
    // triggers, deliberately: the frontend's own held-state copy (importJob.ts's detail()) already
    // treats the two reasons identically from the user's point of view -- "the two states differ
    // in why we are looking, never in what the user is waiting for" -- so the notification says
    // the same thing either way, and a reprocess that fails and re-holds the same job does not
    // send a second one (same idempotency key both call sites use, "IMPORT_HELD_" + job id).
    IMPORT_STATEMENT_HELD
}
