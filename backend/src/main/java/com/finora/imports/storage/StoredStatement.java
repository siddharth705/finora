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
 *       {@code file_content} is deliberately left {@code NULL} rather than duplicated into
 *       {@code BYTEA} (V76, BH-025/BH-046). As of the compression/lifecycle change described on
 *       {@code ImportService.persistSection}, only a {@code StatementImport} row — created at
 *       CONFIRM time — ever reaches this state; a session never does (see next).</li>
 *   <li><b>Legacy</b> — address null, bytes in {@code fileContent}. Every {@code ImportSession}
 *       row, always: staging keeps a file in temporary (database) storage until the user confirms,
 *       deliberately never writing to object storage before then. Also every
 *       {@code StatementImport} row written while no storage provider is configured — pre-V54 rows
 *       unconditionally, and any row since then created with
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

    /**
     * How the addressed object's bytes are encoded -- {@link StatementContentService#read} decodes
     * by this, not by inspecting the bytes. Meaningless for a legacy row ({@link #getObjectKey()}
     * null); implementations that never address an object at all may return
     * {@link CompressionType#NONE} unconditionally rather than persisting a column nothing reads.
     */
    CompressionType getCompressionType();

    /**
     * The {@code EncryptionService}/{@code KeyProvider} key id the addressed object's bytes were
     * encrypted under, or null. Null means one of two things, both meaning "do not decrypt": a
     * legacy row ({@link #getObjectKey()} null), or an addressed row written before encryption
     * shipped (V107) -- exactly the same either-or {@link #getCompressionType()} already handles
     * for compression, and decided the same way: explicit per-row metadata, not a guess from the
     * bytes. Implementations that never address an object at all may return null unconditionally,
     * same as {@link #getCompressionType()}'s equivalent carve-out.
     */
    String getEncryptionKeyId();
}
