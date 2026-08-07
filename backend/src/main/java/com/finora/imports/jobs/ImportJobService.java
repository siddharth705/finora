package com.finora.imports.jobs;

import com.finora.entity.ImportJob;
import com.finora.exception.ApiException;
import com.finora.imports.StatementUpload;
import com.finora.imports.storage.ContentAddress;
import com.finora.imports.storage.StatementStorage;
import com.finora.repository.ImportJobRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Accepting an upload as work to do, rather than doing it on the request thread.
 *
 * <h2>Order of operations, and why it is this order</h2>
 *
 * <ol>
 *   <li><b>Validate</b> — cheap, and a rejected file should never reach storage.</li>
 *   <li><b>Store the bytes</b> — outside the transaction. Object storage cannot participate in one,
 *       and holding a database transaction open across a network upload would tie up a connection
 *       from a pool capped at 10 for the duration.</li>
 *   <li><b>Enqueue</b> — inside the transaction, joining the caller's.</li>
 * </ol>
 *
 * <p>A failure between 2 and 3 leaves an orphaned object with no job. That is the deliberately
 * chosen failure: an unreferenced object is a reclaimable cost, while a job pointing at bytes that
 * were never written is unrecoverable. It is the same trade {@code StatementContentService} already
 * documents, and the same one the storage migration made.
 *
 * <h2>Storage is required here, and the endpoint says so</h2>
 *
 * <p>The synchronous path can keep bytes in the database, so it works with no provider configured.
 * The asynchronous path cannot: the worker runs later, in another thread and possibly another
 * process, and has nothing to read but the content address. Rather than accept an upload that is
 * certain to fail on the first attempt, {@link #accept} refuses up front with a message naming the
 * missing configuration.
 *
 * <p>That check is why this path is opt-in per environment rather than a silent replacement.
 */
@Service
public class ImportJobService {

    private final ImportJobStore jobStore;
    private final ImportJobRepository repository;
    private final Optional<StatementStorage> storage;
    private final ImportJobWorker worker;

    public ImportJobService(ImportJobStore jobStore,
                             ImportJobRepository repository,
                             Optional<StatementStorage> storage,
                             ImportJobWorker worker) {
        this.jobStore = jobStore;
        this.repository = repository;
        this.storage = storage;
        this.worker = worker;
    }

    /**
     * Which parser a job will use, decided once.
     *
     * <p>The endpoint validates against this and the worker parses according to it. If the two
     * disagreed, a file accepted as CSV could be handed to the PDF parser minutes later -- a
     * failure the user would see as an unexplained job error long after the upload succeeded.
     * Filename-based because that is all the worker has: it holds a content address, not the
     * multipart part the endpoint saw.
     */
    public static StatementUpload.Format formatOf(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".pdf")
                ? StatementUpload.Format.PDF
                : StatementUpload.Format.CSV;
    }

    /**
     * Records an upload as a queued job and returns immediately.
     *
     * @throws ApiException 503 if object storage is not configured — see the class comment
     */
    @Transactional
    public ImportJob accept(UUID userId, MultipartFile file) throws IOException {
        StatementStorage active = storage.orElseThrow(() -> new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Asynchronous import needs object storage, which is not configured on this "
                        + "deployment. Set app.statement-storage.provider, or use the synchronous "
                        + "import endpoint."));

        // Outside any transaction this method opens on the database's behalf -- see the class
        // comment. Spring's @Transactional wraps the whole method, but the store call is a network
        // write that does not touch the database, so the connection is not doing anything during it.
        ContentAddress address = active.store(file.getBytes());

        ImportJob job = jobStore.enqueue(
                userId, file.getOriginalFilename(), address.hash(), address.key());

        // AFTER commit, deliberately. Nudging before it would let a worker claim a job whose
        // transaction has not committed -- it would read the row as absent and the nudge would be
        // wasted, or worse, it would read a row the upload then rolled back. The poller is the
        // backstop, so a missed nudge costs latency and never correctness.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { worker.nudge(); }
            });
        }
        return job;
    }

    /**
     * Progress for one job, scoped to its owner.
     *
     * <p>Scoped by user rather than checked afterwards: a job id alone must never be enough to read
     * someone else's import, and a 404 rather than a 403 avoids confirming that an id exists.
     */
    public ImportJobDto.Progress progress(UUID userId, UUID jobId) {
        return repository.findByIdAndUserId(jobId, userId)
                .map(ImportJobDto.Progress::of)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Import job not found."));
    }

    /** The caller's recent jobs, newest first. */
    public List<ImportJobDto.Progress> recent(UUID userId, int limit) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, Math.min(limit, 50)))
                .stream().map(ImportJobDto.Progress::of).toList();
    }
}
