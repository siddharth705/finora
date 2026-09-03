package com.finora.imports.jobs;

import com.finora.entity.ImportJob;
import com.finora.exception.ApiException;
import com.finora.imports.StatementUpload;
import com.finora.imports.storage.ContentAddress;
import com.finora.imports.storage.StatementStorage;
import com.finora.repository.ImportJobRepository;
import com.finora.security.crypto.EncryptingStream;
import com.finora.security.crypto.EncryptionService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
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
 * <p><b>The limit of step 2's guarantee (BH-018).</b> {@link #accept} is not {@code @Transactional}
 * and the boundary opens after the upload, so this class does not hold a transaction across it.
 * That is as far as it goes: a <em>caller</em> that wrapped {@code accept()} in its own transaction
 * would hold one, and nothing here prevents that. No caller does — the upload endpoint is not
 * transactional — and {@code ImportJobStoreOutsideTransactionIT} covers both directions, including
 * that one, so the boundary of the claim is asserted rather than assumed. This paragraph exists
 * because the list above was previously true of the design and false of the code, which is the
 * failure mode worth naming twice.
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
    private final ImportJobStageRepository stageRepository;
    private final Optional<StatementStorage> storage;
    private final ImportJobWorker worker;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final EncryptionService encryptionService;

    @org.springframework.beans.factory.annotation.Value("${app.import.queue.enabled:false}")
    private boolean queueEnabled;

    public ImportJobService(ImportJobStore jobStore,
                             ImportJobRepository repository,
                             ImportJobStageRepository stageRepository,
                             Optional<StatementStorage> storage,
                             ImportJobWorker worker,
                             org.springframework.transaction.support.TransactionTemplate transactionTemplate,
                             EncryptionService encryptionService) {
        this.jobStore = jobStore;
        this.repository = repository;
        this.stageRepository = stageRepository;
        this.storage = storage;
        this.worker = worker;
        this.transactionTemplate = transactionTemplate;
        this.encryptionService = encryptionService;
    }

    /**
     * Which parser a job will use, decided once.
     *
     * <p>The endpoint validates against this and the worker parses according to it. If the two
     * disagreed, a file accepted as CSV could be handed to the PDF parser minutes later -- a
     * failure the user would see as an unexplained job error long after the upload succeeded.
     * Filename-based because that is all there is to go on at the moment of decision: the worker
     * holds a content address, not the multipart part the endpoint saw.
     *
     * <p><b>BH-029.</b> "Decided once" is now literally true. This used to be called twice --
     * here at upload validation, and again in {@code ImportJobWorker.stage()} against
     * {@code job.getFileName()} minutes later -- and the two agreed only because they read the
     * same string through the same function. That is agreement by construction, not by record: it
     * held exactly as long as nobody changed how a filename is stored (it is truncated elsewhere
     * in this package) or what this method does. The answer is now written to
     * {@code import_jobs.source_format} at upload and read from there, the same shape
     * {@code statement_imports.source_format} (V36) already uses after re-inferring a format from
     * a filename routed a PDF's bytes through {@code CsvParser}.
     */
    public static StatementUpload.Format formatOf(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".pdf")
                ? StatementUpload.Format.PDF
                : StatementUpload.Format.CSV;
    }

    /**
     * Records an upload as a queued job and returns immediately.
     *
     * @param sourceFormat the format the caller validated the bytes against. Passed in rather than
     *                     recomputed here so that the format a job is <em>accepted</em> as and the
     *                     format it is <em>stored</em> as are the same evaluation, not two that
     *                     have to be kept in agreement.
     * @throws ApiException 503 if object storage is not configured — see the class comment
     */
    public ImportJob accept(UUID userId, MultipartFile file, StatementUpload.Format sourceFormat)
            throws IOException {
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

        // BH-018. Outside the transaction, structurally -- this method is no longer @Transactional
        // and the boundary opens below, after the upload has finished.
        //
        // It used to be @Transactional over the whole body, with a comment conceding the store
        // happened inside and arguing it was harmless "because the store call is a network write
        // that does not touch the database, so the connection is not doing anything during it".
        // That argument is true only while no JDBC statement has been issued first -- it rests on
        // Hibernate's delayed connection acquisition, which is a property of every caller above
        // this method and of Hibernate's configuration, not of anything visible here. Nothing
        // stated that, and nothing enforced it. A future caller that opened a transaction and read
        // one row before calling accept() would have made the class comment's own warning come
        // true (a connection from a pool capped at 10 held across a 10 MB network upload) with no
        // test failing and no comment becoming wrong.
        //
        // The repository's rule is that a comment asserting a guarantee the code lacks is worse
        // than silence. The guarantee was the correct one; the code now provides it.
        //
        // BH-018's other half. file.getBytes() used to materialise the whole upload -- up to
        // 10 MB -- on the heap here, held for the whole store() call, with nothing gating how
        // many concurrent uploads could each be doing that at once. getInputStream()+getSize()
        // instead: StatementStorage.store(InputStream, long) spools through a fixed-size buffer
        // regardless of file size, so a burst of concurrent uploads costs buffer-sized memory
        // each, not file-sized.
        //
        // Security review (V107): encrypted the same way StatementContentService.store encrypts
        // the synchronous path, but streaming rather than in memory, for the same BH-018 reason
        // buffering the whole upload here would undo. content_hash must still identify the
        // ORIGINAL bytes (BH-019's dedup and StatementImport/ImportSession both key off it), which
        // is why the digest is taken on the PLAINTEXT side of the encrypting stream, not on what
        // active.store() itself hashes for its key layout (the ciphertext) -- the exact
        // hash-before-transform split StatementContentService.store's own "Compression" doc
        // section documents, done here via streams instead of an in-memory array.
        MessageDigest originalDigest;
        try {
            originalDigest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        DigestInputStream digested = new DigestInputStream(file.getInputStream(), originalDigest);
        EncryptingStream encrypting = encryptionService.encryptStream(digested, file.getSize());
        ContentAddress stored = active.store(encrypting.stream(), encrypting.length());
        // Safe only once active.store() has returned: that method reads its input stream to EOF
        // (ContentAddress.copyAndAddress's transferTo), which is what guarantees every original
        // byte has already passed through digested by this point.
        String originalHash = HexFormat.of().formatHex(originalDigest.digest());
        ContentAddress address = new ContentAddress(originalHash, stored.key());

        // Step 3, and everything that touches the database is inside it.
        //
        // A TransactionTemplate rather than an @Transactional method extracted from this one,
        // because extracting it and calling it on `this` would bypass Spring's proxy and quietly
        // apply no transaction at all -- the dedup check and the enqueue would stop being atomic,
        // and isSynchronizationActive() below would be false, so the post-commit nudge would never
        // register and every upload would wait for the next poll. Reaching a second bean or a
        // self-injected proxy would work, and buys nothing here: this template makes the boundary
        // visible on the two lines where BH-018 says it was invisible.
        //
        // Default propagation, so this still joins a caller's transaction if one ever exists --
        // which is the guarantee ImportJobStore.enqueue's own comment depends on, and it is
        // unchanged.
        return transactionTemplate.execute(status ->
                enqueueStoredUpload(userId, file.getOriginalFilename(), address, sourceFormat, encrypting.keyId()));
    }

    private ImportJob enqueueStoredUpload(UUID userId, String fileName, ContentAddress address,
                                          StatementUpload.Format sourceFormat, String encryptionKeyId) {
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
                userId, fileName, address.hash(), address.key(), sourceFormat, encryptionKeyId);

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
     * The full stage-by-stage timeline for one job, scoped to its owner -- Premium Import
     * Reliability v1, §3.1. Same ownership rule as {@link #progress}: a job id alone must never be
     * enough to read someone else's import, and a 404 rather than a 403 avoids confirming that an
     * id exists.
     *
     * <p>{@link ImportJobStageRepository#findByJobIdOrderByRecordedAtAsc} already returns every
     * stage across every attempt in the right order -- this method's only job is the ownership
     * check and the entity-to-DTO assembly.
     *
     * <p>{@code @Transactional(readOnly = true)}, added by a post-ship review: this is two
     * independent SELECTs (the job row, then its stage rows), and without a shared transaction a
     * write landing between them -- a stage closing, or the job dead-lettering -- could hand back a
     * response whose {@code status}/{@code failureCode} and {@code stages} describe two different
     * moments. The window is narrow in practice (one worker owns a job's writes at a time), but
     * costs nothing to close and matches {@link #cancel}'s own defensive use of the same annotation
     * a few lines below.
     */
    @Transactional(readOnly = true)
    public ImportJobDto.Timeline timeline(UUID userId, UUID jobId) {
        ImportJob job = repository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Import job not found."));
        return ImportJobDto.Timeline.of(job, stageRepository.findByJobIdOrderByRecordedAtAsc(jobId));
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
            throw new ApiException(HttpStatus.CONFLICT, uncancellableReason(job.getStatus()));
        }

        job.cancel(Instant.now());
        return ImportJobDto.Progress.of(repository.save(job));
    }

    /**
     * Why Cancel came too late, in words the user can act on.
     *
     * <p>Exhaustive with no {@code default} on purpose. This was a default arm, and it has already
     * been wrong once: {@code HELD_FOR_REVIEW} fell into it and told people their import "is
     * already writing to your accounts" when the job had never reached IMPORTING and had written
     * nothing at all. Giving that one status its own case fixed the symptom and left the trap armed
     * for the next status added -- which {@code HELD_FOR_TRUST_REVIEW} then walked into. Without a
     * default, the compiler raises it instead of a user discovering it.
     *
     * <p>Only IMPORTING and LEARNING may claim the ledger was touched. Everything else either
     * finished, failed, is waiting on us, or never started.
     *
     * <p>Extracted from {@link #cancel} so the wording is testable without standing up the service
     * and its seven collaborators to assert one string --
     * {@code ImportJobCancelReasonTest} is what holds the rules above.
     */
    static String uncancellableReason(ImportJob.Status status) {
        return switch (status) {
            case COMPLETED -> "This import already finished. Discard the staged import instead "
                    + "if you don't want it.";
            case FAILED -> "This import already failed, so there is nothing left to cancel.";
            // Both holds, one answer -- matching UserFacingImportStatus's own collapse. No ETA, and
            // no suggestion the statement itself is in question: the doubt behind a trust hold is
            // about our extraction, not their document, and this is the message most at risk of
            // saying otherwise.
            case HELD_FOR_REVIEW, HELD_FOR_TRUST_REVIEW ->
                    "We're still running some additional checks on this statement. There's nothing "
                            + "to cancel yet; we'll let you know once it's ready.";
            // Transactions exist by now, and removing them is the ledger's job, not the queue's.
            case IMPORTING, LEARNING ->
                    "This import is already writing to your accounts and can no longer be "
                            + "cancelled. Delete the statement import if you want it reversed.";
            // Unreachable from cancel(): CANCELLED returns idempotently above, and the four stages
            // below IMPORTING are exactly what isCancellable() admits. Answered truthfully anyway
            // rather than thrown -- a 500 on an unexpected state would be a worse outcome than a
            // plain sentence, and these are the states where nothing has been written.
            case CANCELLED -> "This import was already cancelled.";
            case QUEUED, PARSING, ANALYZING, DEDUPING -> "This import is still being processed.";
        };
    }
}
