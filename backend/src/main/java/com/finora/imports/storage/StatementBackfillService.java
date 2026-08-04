package com.finora.imports.storage;

import com.finora.repository.ImportSessionRepository;
import com.finora.repository.StatementImportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 3 of docs/engineering/statement-storage-migration.md: moves the bytes of rows that predate
 * Phase 2 into object storage, deduplicating as it goes.
 *
 * <h2>Batched and resumable, not a big-bang</h2>
 * This codebase has no background job infrastructure (see ImportSession's own comment on why
 * session cleanup is opportunistic rather than scheduled), so the backfill is driven by repeated
 * calls to {@link #runBatch} from an admin endpoint rather than by a scheduler. That is a virtue
 * here rather than a workaround: a migration over potentially gigabytes of PDFs should be
 * interruptible, observable between batches, and safe to stop the moment something looks wrong.
 *
 * Every batch selects rows that still have no address, so running it twice is not a problem and a
 * crash mid-run loses nothing. {@link #status} is the progress report -- rows addressed versus rows
 * remaining -- and reaching zero remaining is the precondition for Phase 4.
 *
 * <h2>One row per transaction, one blob in memory at a time</h2>
 * Rows are selected as IDs and loaded individually. A page of entities would pull a page of
 * multi-megabyte blobs into heap at once, and a single transaction around the whole batch would
 * mean one unreadable row rolls back every good row beside it.
 *
 * <h2>Deduplication is a consequence, not a step</h2>
 * Nothing here compares files. Storing is content-addressed, so the second and subsequent rows
 * holding identical bytes resolve to an address that already exists and no object is written. The
 * counts distinguish the two so the effect is visible: {@code stored} versus {@code deduplicated}
 * is the answer to "how much of our database was the same file over and over".
 */
@Service
public class StatementBackfillService {

    private static final Logger log = LoggerFactory.getLogger(StatementBackfillService.class);

    /** Deliberately small. Each row can carry 10MB, and a batch that takes minutes is a batch
     *  nobody can watch. Callers can ask for less; the cap stops anyone asking for a heap dump. */
    private static final int DEFAULT_BATCH = 25;
    private static final int MAX_BATCH = 200;

    /** Progress, and whether a run is even possible. */
    public record BackfillStatus(
            boolean storageConfigured,
            long importsTotal, long importsRemaining,
            long sessionsRemaining
    ) {
        public long importsAddressed() { return importsTotal - importsRemaining; }
        /** Phase 4 -- dropping file_content -- is only safe once this is true. */
        public boolean complete() { return importsRemaining == 0 && sessionsRemaining == 0; }
    }

    /**
     * @param stored        rows whose content was written to storage for the first time
     * @param deduplicated  rows whose content was ALREADY stored -- the duplication being reclaimed
     * @param failed        rows that could not be addressed; they keep their bytes and are retried
     *                      on the next run
     * @param failures      one line per failure, capped, for an operator to act on
     */
    public record BackfillBatchResult(int stored, int deduplicated, int failed,
                                       List<String> failures, long remaining) {
        public int processed() { return stored + deduplicated; }
    }

    private final StatementImportRepository statementImportRepository;
    private final ImportSessionRepository importSessionRepository;
    private final StatementBackfillWorker worker;
    private final java.util.Optional<StatementStorage> storage;

    public StatementBackfillService(StatementImportRepository statementImportRepository,
                                     ImportSessionRepository importSessionRepository,
                                     StatementBackfillWorker worker,
                                     java.util.Optional<StatementStorage> storage) {
        this.statementImportRepository = statementImportRepository;
        this.importSessionRepository = importSessionRepository;
        this.worker = worker;
        this.storage = storage;
    }

    public BackfillStatus status() {
        return new BackfillStatus(
                storage.isPresent(),
                statementImportRepository.countAllIncludingDeleted(),
                statementImportRepository.countWithoutContentAddress(),
                importSessionRepository.countWithoutContentAddress());
    }

    /**
     * Addresses up to {@code limit} statement imports, then up to {@code limit} import sessions.
     *
     * Call repeatedly until {@link BackfillStatus#complete()}. Sessions are done second and matter
     * less -- they expire within 48 hours, so a slow backfill simply outlives most of them.
     */
    public BackfillBatchResult runBatch(Integer limit) {
        if (storage.isEmpty()) {
            throw new StatementStorageException(
                    "No statement storage provider is configured -- set app.statement-storage.provider "
                            + "before running the backfill, or it has nowhere to write");
        }
        int size = Math.min(limit == null || limit < 1 ? DEFAULT_BATCH : limit, MAX_BATCH);

        int stored = 0, deduplicated = 0, failed = 0;
        List<String> failures = new ArrayList<>();

        for (UUID id : statementImportRepository.findIdsWithoutContentAddress(PageRequest.of(0, size))) {
            try {
                if (worker.addressStatementImport(id)) stored++;
                else deduplicated++;
            } catch (RuntimeException e) {
                failed++;
                // The row keeps its bytes and no address, so the next run retries it. Recorded
                // rather than rethrown so one unreadable file cannot stall the whole migration.
                if (failures.size() < 20) failures.add("statement_import " + id + ": " + e.getMessage());
                log.warn("Backfill failed for statement import {}", id, e);
            }
        }

        for (UUID id : importSessionRepository.findIdsWithoutContentAddress(PageRequest.of(0, size))) {
            try {
                if (worker.addressImportSession(id)) stored++;
                else deduplicated++;
            } catch (RuntimeException e) {
                failed++;
                if (failures.size() < 20) failures.add("import_session " + id + ": " + e.getMessage());
                log.warn("Backfill failed for import session {}", id, e);
            }
        }

        BackfillStatus after = status();
        long remaining = after.importsRemaining() + after.sessionsRemaining();
        log.info("Backfill batch: {} stored, {} already present, {} failed, {} remaining",
                stored, deduplicated, failed, remaining);
        return new BackfillBatchResult(stored, deduplicated, failed, failures, remaining);
    }
}
