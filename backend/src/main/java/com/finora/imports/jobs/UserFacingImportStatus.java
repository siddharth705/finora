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
            case FAILED -> ErrorCode.userActionRequiredOrDefault(failureCode) ? ACTION_REQUIRED : FAILED;
        };
    }
}
