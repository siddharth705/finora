package com.finora.imports.storage;

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
     * Stores content and returns its address. Idempotent: storing identical bytes twice yields the
     * same address and does not duplicate the object.
     *
     * @throws StatementStorageException if the content could not be durably stored. Callers must
     *         treat this as fatal for the request and must NOT persist a row -- a row referencing
     *         an object that was never written is the one failure this design cannot recover from.
     */
    ContentAddress store(byte[] content);

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
