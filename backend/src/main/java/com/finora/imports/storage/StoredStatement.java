package com.finora.imports.storage;

/**
 * A row that holds a statement's original bytes — implemented by both {@code StatementImport} and
 * {@code ImportSession}, which store the same file for different lifetimes.
 *
 * Exists so {@link StatementContentService} has one read path instead of one per entity. A row is
 * always in exactly one of two states, and every reader has to handle both:
 *
 * <ul>
 *   <li><b>Addressed</b> — {@code contentHash}/{@code objectKey} set, bytes in object storage only.
 *       Every row written while a storage provider is configured, as of V75 (BH-025/BH-046):
 *       {@code file_content} is deliberately left {@code NULL} rather than duplicated into
 *       {@code BYTEA}.</li>
 *   <li><b>Legacy</b> — address null, bytes in {@code fileContent}. Every row written while no
 *       provider is configured — pre-V54 rows unconditionally, and any row since then created with
 *       {@code app.statement-storage.provider} unset.</li>
 * </ul>
 *
 * Both are the normal, permanent shape of a row — not a migration-in-progress state waiting on a
 * backfill. (Phase 3, which would have backfilled legacy rows, was deleted for having nothing to
 * migrate; see docs/engineering/statement-storage-migration.md §5.0.) Resolution still lives in one
 * service rather than being re-decided at each of the five call sites that read statement bytes.
 */
public interface StoredStatement {

    /** Hex SHA-256 of the original file, or null for a legacy (no-provider) row. */
    String getContentHash();

    /** Provider-internal key for {@link #getContentHash()}, or null. */
    String getObjectKey();

    /** The bytes, when still held in the database — null for an addressed row (see class doc). */
    byte[] getFileContent();
}
