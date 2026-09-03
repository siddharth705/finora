package com.finora.dto;

import com.finora.entity.ImportJob;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the admin held-imports triage queue.
 *
 * <p><b>Deliberately carries no {@code lastError}.</b> That field is {@code
 * ImportJobWorker.describe(Exception)} -- a class name plus a raw exception message -- and a parser
 * failure's message routinely quotes the input that defeated it ("no header row in: ..."). On a
 * bank statement, that input is a real person's financial data. The list view is a page an operator
 * leaves open; it gets the curated {@code failureCode} only, which is an {@code ErrorCode} name or
 * an exception's simple class name and carries no customer content.
 *
 * <p>{@link Detail} carries the raw text, and every read of it is audited. That split is the whole
 * privacy design: browsing the queue is cheap and anonymous, opening one statement's diagnostics is
 * recorded against the admin who did it.
 */
public record HeldImportDto(
        UUID id,
        UUID userId,
        String fileName,
        String sourceFormat,
        String failureCode,
        int attemptCount,
        int recoveryCount,
        Instant createdAt,
        Instant heldAt) {

    public static HeldImportDto from(ImportJob job) {
        return new HeldImportDto(
                job.getId(),
                job.getUserId(),
                job.getFileName(),
                job.getSourceFormat(),
                job.getFailureCode(),
                job.getAttemptCount(),
                job.getRecoveryCount(),
                job.getCreatedAt(),
                job.getFinishedAt());
    }

    /**
     * A held import plus the diagnostic text an engineer actually needs to fix the parser.
     *
     * <p>Every construction of this is audited by {@code AdminHeldImportService.detail} -- see this
     * class's own doc for why the row form withholds {@code lastError} and this one does not.
     */
    public record Detail(HeldImportDto job, String lastError, String correlationId, String objectKey) {

        public static Detail from(ImportJob job) {
            return new Detail(
                    HeldImportDto.from(job),
                    job.getLastError(),
                    job.getCorrelationId(),
                    job.getObjectKey());
        }
    }

    /**
     * The queue's counts.
     *
     * <p>{@code held} drives the sidebar badge and the "is there anything to do" question.
     * {@code reprocessing} counts jobs an admin has already sent back to the queue that have not
     * finished yet, so a second admin does not reprocess the same thing again; it is deliberately
     * a count of QUEUED jobs that were once held, not of all QUEUED jobs.
     */
    public record Summary(long held, long reprocessing) {}
}
