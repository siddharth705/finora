package com.finora.observability;

/**
 * How loudly a reported outcome should reach a human, independent of what a worker's own domain
 * calls it. Premium Import Reliability v1, §5.6.
 *
 * <p>Deliberately owned here rather than by any one worker's failure vocabulary (e.g. {@code
 * ErrorCode.RetryPolicy}). {@link WorkerExecution}/{@link WorkerObservability} are documented as
 * "the platform contract every worker implements" -- a second worker with its own, entirely
 * different classification scheme should be able to report severity through the same knob without
 * reaching into an unrelated domain's enum to do it. The translation from a worker's own vocabulary
 * to this one belongs to that worker, not to this package.
 */
public enum AlertSeverity {
    /** Expected, not actionable for an engineer -- do not page. */
    NONE,
    /** Worth knowing, not necessarily worth waking someone: typically an infrastructure condition
     *  that resolves on its own once the dependency recovers. */
    WARNING,
    /** Needs investigation. */
    ERROR,
}
