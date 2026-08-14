package com.finora.imports.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Where a statement's original bytes live. The only place in the codebase that knows.
 *
 * Phase 1 of docs/engineering/statement-storage-migration.md: statements are currently held as
 * {@code BYTEA} on {@code statement_imports} and {@code import_sessions}, which makes the database
 * grow by the full size of every file ever uploaded -- and, because the bytes are copied per
 * account section and again on every re-import, faster than uploads alone would suggest.
 *
 * Implementations are chosen by configuration, and business logic must never know which one is
 * active. R2 speaks the S3 API, so keeping this boundary S3-shaped is what allows a later move to
 * S3, MinIO, Wasabi or Spaces to be configuration rather than a rewrite.
 *
 * <h2>Delete exists, but it is not a per-row operation</h2>
 * BH-017: once {@code app.statement-storage.provider} is actually configured in production, no
 * code path ever reclaimed an object -- the 48h {@code import_sessions} TTL, a user deleting a
 * statement, and {@code ON DELETE CASCADE} on user deletion all drop the referencing DB row and
 * leave the R2 object behind forever, silently making the documented retention window false.
 *
 * {@link #delete} is deliberately still not something a row-deletion code path may call directly.
 * Objects are SHARED by design: a staged session and the import it confirms into hold identical
 * bytes and resolve to the same address, and multi-section and re-imported statements share one
 * object too. Deleting a row must therefore NOT delete its object on the spot -- another row may
 * still need it, and the failure that would cause is silent and delayed: the delete succeeds, and
 * some unrelated statement's download or re-import breaks days later with nothing connecting
 * cause to effect.
 *
 * The only caller of {@link #delete} is {@code StatementStorageSweepService}, which reasons over
 * every reference across BOTH {@code statement_imports} and {@code import_sessions} before calling
 * it, and only for an object whose reference count has been zero for longer than the configured
 * re-importability window (90 days by default -- {@code app.statement-storage.sweep.retention-days}).
 * See §3.2 of the migration doc and BH-017's writeup in
 * docs/engineering/reviews/2026-08-08-repo-wide-bug-hunt.md.
 *
 * This also matches the agreed failure semantics: an unreferenced object is a tolerable,
 * reclaimable cost; a row pointing at a missing object is unrecoverable.
 */
public interface StatementStorage {

    /**
     * Stores content, read as a stream, and returns its address. Idempotent: storing identical
     * content twice yields the same address and does not duplicate the object.
     *
     * <p>BH-018. This is the real entry point every implementation provides -- {@code
     * store(byte[])} below is a convenience built on top of it, not a second implementation to
     * keep in sync. Use this overload directly whenever the content doesn't already need to be
     * fully resident in memory for some other reason: {@code ImportJobService.accept()} is the
     * motivating case, an upload endpoint that used to call {@code MultipartFile.getBytes()}
     * purely to satisfy this interface, holding up to 10 MB on the heap per concurrent upload for
     * no reason connected to what accept() itself does with it.
     *
     * @param contentLength the exact byte count {@code content} will yield. Implementations that
     *         need it upfront (an S3-compatible {@code PutObject} call, for one) rely on this
     *         being accurate -- it is not re-derived by counting, since that would mean reading
     *         the stream twice.
     * @throws StatementStorageException if the content could not be durably stored. Callers must
     *         treat this as fatal for the request and must NOT persist a row -- a row referencing
     *         an object that was never written is the one failure this design cannot recover from.
     */
    ContentAddress store(InputStream content, long contentLength);

    /**
     * Convenience for the two callers ({@code ImportService.persistSection},
     * {@code ImportSessionService.storeContent}) that already hold the full content in memory for
     * reasons of their own -- parsing needed it long before either reaches this call, so a stream
     * would buy them nothing. Delegates to {@link #store(InputStream, long)}; adds nothing beyond
     * wrapping the array, and every implementation gets it for free rather than reimplementing it.
     *
     * @throws StatementStorageException if the content could not be durably stored. Same contract
     *         as the streaming overload.
     */
    default ContentAddress store(byte[] content) {
        return store(new ByteArrayInputStream(content), content.length);
    }

    /**
     * Reads content back.
     *
     * @throws StatementStorageException if the object is missing or unreadable. Missing is not
     *         modelled as an empty result on purpose: every caller of this reached it from a row
     *         that claims the object exists, so absence is a broken invariant rather than an
     *         ordinary outcome to branch on.
     */
    byte[] retrieve(ContentAddress address);

    /** Whether an object is present. For the migration's progress reporting and the sweep --
     *  not a pre-flight check for {@link #retrieve}, which would be a race, not a safeguard. */
    boolean exists(ContentAddress address);

    /**
     * Permanently removes the object at {@code objectKey}. Idempotent: deleting a key that is
     * already absent is not an error, matching S3/R2's own DeleteObject semantics.
     *
     * <p><b>Callers must have already established that no row in either {@code statement_imports}
     * or {@code import_sessions} references this key, and that it has been unreferenced for at
     * least the configured retention window.</b> This method does no reference checking itself --
     * it is the storage primitive the sweep is built on, not a safe-by-construction operation. See
     * this interface's class doc and {@code StatementStorageSweepService}.
     *
     * @throws StatementStorageException if the object could not be durably removed
     */
    void delete(String objectKey);
}
