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
import java.time.Instant;
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

    @org.springframework.beans.factory.annotation.Value("${app.import.queue.enabled:false}")
    private boolean queueEnabled;

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
        // Storage is a hard gate; the queue flag deliberately is not.
        //
        // Without storage the job could never run anywhere: it holds a content address and there is
        // nothing to resolve it against. Without THIS instance's queue flag it may still run
        // perfectly well -- phase 6 of the design splits the API and the workers into separate
        // services, and in that topology the API has the flag off precisely because it should not
        // run workers. Refusing here would break the deployment the roadmap is heading for, to
        // protect against a single-instance misconfiguration that {@link #availability} already
        // reports and that no client following it will hit.
        StatementStorage active = storage.orElseThrow(() -> new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Asynchronous import needs object storage, which is not configured on this "
                        + "deployment. Set app.statement-storage.provider, or use the synchronous "
                        + "import endpoint."));

        // Outside any transaction this method opens on the database's behalf -- see the class
        // comment. Spring's @Transactional wraps the whole method, but the store call is a network
        // write that does not touch the database, so the connection is not doing anything during it.
        ContentAddress address = active.store(file.getBytes());

        // BH-019. The same document submitted twice used to become two jobs and two staged
        // sessions, and confirming both imported the statement twice. A double-clicked upload
        // button and a client retrying a request whose 202 was lost both produce exactly that.
        //
        // Returning the EXISTING job rather than refusing: the caller wanted this document
        // imported, that is already happening, and handing back the same jobId means their poll
        // follows the real work. An error would be technically defensible and practically useless.
        //
        // Checked after the store because storage is content-addressed and idempotent -- a repeat
        // costs one HEAD and no upload -- and because the address is what carries the identity to
        // check against.
        //
        // This check is not the guarantee. It is a read followed by a write, so two genuinely
        // simultaneous uploads can both miss it; idx_import_jobs_live_content (V74) is what
        // decides then, and the loser gets the 409 GlobalExceptionHandler already answers for a
        // constraint violation. Same reasoning V67 gives for preferring constraints to checks.
        Optional<ImportJob> alreadyQueued = repository
                .findFirstByUserIdAndContentHashAndStatusNotInOrderByCreatedAtDesc(
                        userId, address.hash(), ImportJob.Status.TERMINAL);
        if (alreadyQueued.isPresent()) {
            return alreadyQueued.get();
        }

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
     * Whether a client should send uploads here at all.
     *
     * <p>Exists because the alternative is worse. Without it a client either hardcodes an assumption
     * about a per-deployment setting, or discovers the answer by uploading and reading the 503 —
     * and by then the whole file has crossed the network, so falling back to the synchronous
     * endpoint would send every byte a second time. One cheap GET before the upload replaces that.
     *
     * <p>Stricter than {@link #accept}, on purpose. Accept refuses only what could never run
     * anywhere (no storage); this also reports false when <em>this instance</em> runs no workers,
     * because a single-service deployment with the queue off would take the upload and leave it
     * QUEUED forever behind a progress endpoint that never changes. In the split API/worker topology
     * phase 6 describes, this instance is the wrong one to ask — which is a reason to revisit the
     * signal then, not a reason to let a single-instance deployment silently swallow uploads now.
     */
    public ImportJobDto.Availability availability() {
        return new ImportJobDto.Availability(queueEnabled && storage.isPresent());
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

    /**
     * The caller's recent jobs, newest first.
     *
     * <p>BH-008: this clamped only the UPPER bound, so {@code ?limit=0} (or any negative value)
     * reached {@code PageRequest.of(0, 0)}, which Spring Data rejects with
     * {@code IllegalArgumentException} -- a 500 for what is plainly a bad query parameter.
     * {@link com.finora.util.PageBounds} is the clamp every other paginated endpoint here already
     * uses and exists for exactly this; this one simply never adopted it.
     */
    public List<ImportJobDto.Progress> recent(UUID userId, int limit) {
        int size = com.finora.util.PageBounds.safeSize(limit, 50);
        return repository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, size))
                .stream().map(ImportJobDto.Progress::of).toList();
    }

    /**
     * Stops a job its owner no longer wants.
     *
     * <p>Someone who uploaded the wrong file should not have to wait for it, and before this the
     * only options were to wait or to let it complete and discard the session afterwards.
     *
     * <p><b>What cancelling actually guarantees.</b> A {@code QUEUED} job never starts: claims only
     * look at {@code QUEUED}, so flipping the status is enough. A job a worker is already holding
     * stops at its next stage boundary — the parse in flight runs to the end of its current stage,
     * because interrupting PDFBox mid-document needs cooperative cancellation the parser does not
     * have. Either way no session is created and nothing reaches the user's ledger, which is the
     * promise the button makes.
     *
     * <p>409 rather than a silent no-op on a job that is past {@link ImportJob#isCancellable()}:
     * "your import was already finishing" is a different outcome from "cancelled", and a UI that
     * cannot tell them apart will claim the wrong one. The message names the state, because the
     * honest answer to "why couldn't you stop it" is where it had got to.
     */
    @Transactional
    public ImportJobDto.Progress cancel(UUID userId, UUID jobId) {
        ImportJob job = repository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Import job not found."));

        if (job.getStatus() == ImportJob.Status.CANCELLED) {
            // Idempotent on purpose: a double-click, or a retry of a request whose response was
            // lost, should report the state the user asked for rather than an error about it.
            return ImportJobDto.Progress.of(job);
        }
        if (!job.isCancellable()) {
            throw new ApiException(HttpStatus.CONFLICT, switch (job.getStatus()) {
                case COMPLETED -> "This import already finished. Discard the staged import instead "
                        + "if you don't want it.";
                case FAILED -> "This import already failed, so there is nothing left to cancel.";
                // IMPORTING and later: transactions exist, and removing them is the ledger's job,
                // not the queue's.
                default -> "This import is already writing to your accounts and can no longer be "
                        + "cancelled. Delete the statement import if you want it reversed.";
            });
        }

        job.cancel(Instant.now());
        return ImportJobDto.Progress.of(repository.save(job));
    }
}
