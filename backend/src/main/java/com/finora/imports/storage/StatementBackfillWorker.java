package com.finora.imports.storage;

import com.finora.entity.ImportSession;
import com.finora.entity.StatementImport;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.StatementImportRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * One row, one transaction. Separate from {@link StatementBackfillService} because Spring's
 * transaction proxying does not apply to self-invocation -- calling a {@code @Transactional} method
 * from another method of the same bean silently runs it without a transaction, which here would
 * mean a whole batch sharing one, and one unreadable file rolling back every good row beside it.
 *
 * {@code REQUIRES_NEW} makes that explicit rather than incidental: each row commits or fails on its
 * own, and a failure leaves the row exactly as it was -- bytes intact, no address -- so the next
 * run retries it.
 */
@Component
public class StatementBackfillWorker {

    private final StatementImportRepository statementImportRepository;
    private final ImportSessionRepository importSessionRepository;
    /** Optional for the same reason everywhere else in this package: with no provider configured
     *  there is no bean, and this component must not stop the application from starting. The
     *  service guards before ever calling in here, so absence at this point is a programming
     *  error rather than a state to handle gracefully. */
    private final java.util.Optional<StatementStorage> storage;

    public StatementBackfillWorker(StatementImportRepository statementImportRepository,
                                    ImportSessionRepository importSessionRepository,
                                    java.util.Optional<StatementStorage> storage) {
        this.statementImportRepository = statementImportRepository;
        this.importSessionRepository = importSessionRepository;
        this.storage = storage;
    }

    /** @return true if this row's content was newly written, false if an identical object existed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean addressStatementImport(UUID id) {
        StatementImport row = statementImportRepository.findByIdIncludingDeleted(id)
                .orElseThrow(() -> new StatementStorageException("Statement import " + id + " disappeared mid-backfill"));
        byte[] content = requireContent(row.getFileContent(), "statement import " + id);

        boolean isNew = write(row.getContentHash(), content, row::setContentHash, row::setObjectKey);
        statementImportRepository.save(row);
        return isNew;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean addressImportSession(UUID id) {
        ImportSession row = importSessionRepository.findById(id)
                .orElseThrow(() -> new StatementStorageException("Import session " + id + " disappeared mid-backfill"));
        byte[] content = requireContent(row.getFileContent(), "import session " + id);

        boolean isNew = write(row.getContentHash(), content, row::setContentHash, row::setObjectKey);
        importSessionRepository.save(row);
        return isNew;
    }

    /**
     * Stores the bytes and records the address, reporting whether an object was actually written.
     *
     * {@code exists} is checked BEFORE storing purely to tell "newly stored" from "already there".
     * It is not a guard -- {@link StatementStorage#store} is idempotent, so a race between the
     * check and the write is at worst a miscount, never a duplicate object or a lost one. Getting
     * that distinction wrong would only misreport how much duplication was reclaimed, which is the
     * one number this migration produces that is interesting rather than operationally load-bearing.
     */
    private boolean write(String existingHash, byte[] content,
                           java.util.function.Consumer<String> setHash,
                           java.util.function.Consumer<String> setKey) {
        if (existingHash != null) {
            // Addressed by a concurrent batch between the id query and this transaction. Nothing to
            // do, and counting it as newly stored would overstate the work done.
            return false;
        }
        StatementStorage active = storage.orElseThrow(() ->
                new IllegalStateException("Backfill worker reached without a configured storage provider"));
        ContentAddress address = ContentAddress.forContent(content);
        boolean alreadyStored = active.exists(address);
        ContentAddress stored = active.store(content);

        // Read back and verify before recording the address. Phase 4 deletes file_content on the
        // strength of these rows, so this is the last moment the database copy still exists to
        // compare against -- after that a mis-stored object is undetectable and unrecoverable.
        //
        // It matters most in the alreadyStored branch, which is the majority of rows: there
        // store() wrote nothing and the address points at an object some EARLIER row put there.
        // Without this, the backfill would be attesting to bytes it never actually looked at.
        //
        // Cost is one extra read plus a hash per row, on a one-off migration that already reads
        // every row once. Doubling the I/O of a background job is a fair price for the irreversible
        // step downstream being able to trust its own precondition.
        ContentAddress.requireMatches(active.retrieve(stored), stored.hash(),
                "back-filled object " + stored.hash());

        setHash.accept(stored.hash());
        setKey.accept(stored.key());
        return !alreadyStored;
    }

    private byte[] requireContent(byte[] content, String what) {
        if (content == null || content.length == 0) {
            // Phase 4 has not run, so a row here must still have its bytes. Empty means something
            // else emptied it, and inventing an address for zero bytes would quietly make every
            // such row resolve to the same empty object.
            throw new StatementStorageException("No file content to back-fill for " + what);
        }
        return content;
    }
}
