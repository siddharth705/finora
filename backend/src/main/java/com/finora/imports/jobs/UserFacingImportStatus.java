package com.finora.imports.jobs;

import com.finora.entity.ImportJob;
import com.finora.exception.ErrorCode;

/**
 * The five-state vocabulary a user actually sees -- Premium Import Reliability v1, §1, Sprint 4
 * item 20a. {@link ImportJob.Status} is nine states, correctly: it is the system of record and
 * needs the finer grain internally (per-stage timing, guarded transitions, retry-as-return-to-
 * {@code QUEUED}). A user does not need to be shown {@code ANALYZING} vs {@code DEDUPING} vs
 * {@code LEARNING} distinctly, so this collapses them, without replacing or renaming the backend
 * machine itself -- {@link ImportJobDto.Progress#status} and {@link ImportJobDto.Timeline#status}
 * still carry the raw nine-state name unchanged (the import timeline UI genuinely needs that
 * granularity); this is an additional field, not a replacement.
 *
 * <p>{@code ACTION_REQUIRED} is not a backend {@link ImportJob.Status} -- it is {@code FAILED},
 * refined by whether the failure's {@link ErrorCode} has a single, concrete, user-doable fix (a
 * password prompt, "re-check your upload"). See {@link ErrorCode#userActionRequired()} for the
 * governing rule and the full per-code table.
 */
public enum UserFacingImportStatus {
    PROCESSING,
    COMPLETED,
    ACTION_REQUIRED,
    FAILED,

    /**
     * The statement needs work on our side before it can be imported.
     *
     * <p>Deliberately NOT a failure and deliberately NOT given an ETA. Triage is manual and
     * volume-dependent, so any promised deadline would start breaking the moment volume grew, and a
     * missed promise costs more trust than an honest open-ended wait.
     *
     * <p>Equally deliberately, the copy this maps to never suggests the document's authenticity is
     * in question. The real cause is a parser gap on our side; in a financial app, telling users
     * their own bank statement is being checked for genuineness is a trust risk that lands worse
     * than the delay it was meant to excuse. The message has to stay true as well as kind --
     * additional checks genuinely do run, by a human, before the import is retried.
     *
     * <p>Distinct from {@link #ACTION_REQUIRED} because the user has nothing to do, and distinct
     * from {@link #PROCESSING} because nothing is actively running -- it is waiting on us.
     */
    HELD_FOR_REVIEW,

    CANCELLED;

    /**
     * @param status the job's actual state.
     * @param failureCode the job's stored {@code failureCode} (an {@link ErrorCode} enum name, an
     *                     exception's simple class name, or {@code null}) -- ignored unless {@code
     *                     status} is {@link ImportJob.Status#FAILED}, matching every other reader
     *                     of this field ({@link ImportJobDto.Progress#error}, {@link
     *                     ImportJobDto.Timeline#failureCode}) already gating on the same status.
     */
    public static UserFacingImportStatus of(ImportJob.Status status, String failureCode) {
        return switch (status) {
            case QUEUED, PARSING, ANALYZING, DEDUPING, IMPORTING, LEARNING -> PROCESSING;
            case COMPLETED -> COMPLETED;
            case CANCELLED -> CANCELLED;
            // Both holds collapse here, deliberately. Internally they are opposites -- one is a
            // parse that fell over, the other a parse that succeeded and is distrusted -- but from
            // the user's side they are the same situation: nothing running, nothing theirs to fix,
            // a person looking at it. Collapsing internal grain is what this enum is for, and
            // separate copy for the trust hold would be hard to write without leaking "we are
            // checking whether your statement is real", which the doc below forbids.
            case HELD_FOR_REVIEW, HELD_FOR_TRUST_REVIEW -> HELD_FOR_REVIEW;
            case FAILED -> ErrorCode.userActionRequiredOrDefault(failureCode) ? ACTION_REQUIRED : FAILED;
        };
    }
}
