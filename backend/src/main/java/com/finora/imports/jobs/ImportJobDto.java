package com.finora.imports.jobs;

import com.finora.entity.ImportJob;

import java.time.Instant;
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
     */
    public record Progress(
            UUID jobId,
            String status,
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
                    job.getStatus().name(),
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
}
