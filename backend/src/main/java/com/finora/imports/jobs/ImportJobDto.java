package com.finora.imports.jobs;

import com.finora.entity.ImportJob;
import com.finora.exception.ErrorCode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire shapes for the asynchronous import path. */
public final class ImportJobDto {

    private ImportJobDto() {}

    /**
     * The 202 response: enough to poll with, and nothing else.
     *
     * <p>{@code statusUrl} is returned rather than left for the client to construct, so the polling
     * route can move without every client needing to be updated in step.
     */
    public record Accepted(UUID jobId, String statusUrl) {
        public static Accepted of(ImportJob job) {
            return new Accepted(job.getId(), "/api/v1/import/jobs/" + job.getId());
        }
    }

    /**
     * Whether this deployment can take queued uploads.
     *
     * <p>A record rather than a bare boolean so a second capability can be added without changing
     * the response type out from under every client — the same reason {@code Accepted} carries a
     * {@code statusUrl} rather than leaving the client to build one.
     */
    public record Availability(boolean asyncImportAvailable) {}

    /**
     * Progress, for polling at 1-2s.
     *
     * <p>{@code rowsTotal} is deliberately nullable rather than defaulted to zero: null means
     * "still reading the statement", which lets the UI say so instead of showing "0 of 0" and
     * looking stuck. A zero would be indistinguishable from an empty file.
     *
     * <p>{@code error} carries the job's {@code last_error} only once the job has actually FAILED.
     * A job that failed once and is retrying is not something to alarm the user about -- it is the
     * system working -- so a transient error is deliberately not surfaced mid-flight.
     *
     * <p>{@code status} stays the raw {@link ImportJob.Status} name -- unchanged, since the import
     * timeline UI needs that granularity. {@code userStatus} is additive: Sprint 4 item 20a's
     * five-state mapping ({@link UserFacingImportStatus}), for a caller that wants "processing /
     * completed / action required / failed / cancelled" without re-deriving it from the raw value
     * and {@code ErrorCode} metadata itself.
     */
    public record Progress(
            UUID jobId,
            String fileName,
            String status,
            UserFacingImportStatus userStatus,
            Integer rowsTotal,
            int rowsProcessed,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            UUID importSessionId,
            String error,
            String correlationId
    ) {
        public static Progress of(ImportJob job) {
            return new Progress(
                    job.getId(),
                    job.getFileName(),
                    job.getStatus().name(),
                    UserFacingImportStatus.of(job.getStatus(), job.getFailureCode()),
                    job.getRowsTotal(),
                    job.getRowsProcessed(),
                    job.getCreatedAt(),
                    job.getStartedAt(),
                    job.getFinishedAt(),
                    job.getImportSessionId(),
                    job.getStatus() == ImportJob.Status.FAILED ? job.getLastError() : null,
                    // Given to the client so a support conversation can start from an id that ties
                    // together the worker's logs, its audit rows and any Sentry event.
                    job.getCorrelationId());
        }
    }

    /**
     * One stage's transition, for the customer-facing import timeline -- Premium Import
     * Reliability v1, §3.1. {@code attempt} is carried on every row (not collapsed to "latest
     * attempt only") -- a job that failed once and auto-retried successfully is worth showing, now
     * that Sprint 2 made automatic retries a real, common case rather than hiding it.
     */
    public record TimelineStage(
            String stage, int attempt, String outcome,
            Instant startedAt, Instant endedAt, Long durationMs
    ) {
        static TimelineStage of(com.finora.imports.jobs.ImportJobStage row) {
            return new TimelineStage(row.getStage().name(), row.getAttempt(), row.getOutcome().name(),
                    row.getStartedAt(), row.getEndedAt(), row.getDurationMs());
        }
    }

    /**
     * The full timeline for one job the caller owns -- every {@link ImportJobStage} row across
     * every attempt, chronological, plus a curated failure reason if the job ended in one.
     *
     * <p>{@code failureCode} is the wire code (translated via {@link ErrorCode#wireCodeOrNull}),
     * matching {@code ImportFailureSummaryDto}'s existing convention -- not the raw stored enum
     * name/exception class name {@code ImportJob.failureCode} actually holds. Populated only once
     * the job has FAILED, same rule {@link Progress#error} already follows: a job that failed once
     * and is retrying should not alarm the user with a reason mid-flight for a problem the system
     * may still resolve on its own.
     *
     * <p>{@code status} stays the raw {@link ImportJob.Status} name, same reasoning as {@link
     * Progress#status}; {@code userStatus} is the same additive Sprint 4 item 20a mapping.
     */
    public record Timeline(
            UUID jobId, String status, UserFacingImportStatus userStatus,
            String failureCode, List<TimelineStage> stages
    ) {
        public static Timeline of(ImportJob job, List<com.finora.imports.jobs.ImportJobStage> rows) {
            String failureCode = job.getStatus() == ImportJob.Status.FAILED
                    ? ErrorCode.wireCodeOrNull(job.getFailureCode())
                    : null;
            return new Timeline(job.getId(), job.getStatus().name(),
                    UserFacingImportStatus.of(job.getStatus(), job.getFailureCode()),
                    failureCode, rows.stream().map(TimelineStage::of).toList());
        }
    }
}
