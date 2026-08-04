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
 * <h2>There is deliberately no delete</h2>
 * Not an oversight. Once storage is content-addressed, objects are SHARED: a staged session and the
 * import it confirms into hold identical bytes and therefore resolve to the same address, and
 * multi-section and re-imported statements share one object by design. Deleting a row must
 * therefore NOT delete its object -- another row may still need it.
 *
 * Exposing a delete here would make that mistake easy and its consequences invisible: the delete
 * succeeds, and some unrelated statement's download or re-import breaks days later with nothing
 * connecting cause to effect. Reclaiming unreferenced objects is a separate sweep that reasons over
 * every reference at once, not a per-row operation. See §3.2 of the migration doc.
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

    /** Whether an object is present. For the migration's progress reporting and the future sweep --
     *  not a pre-flight check for {@link #retrieve}, which would be a race, not a safeguard. */
    boolean exists(ContentAddress address);
}
