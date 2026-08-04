package com.finora.imports.storage;

/**
 * A row that holds a statement's original bytes — implemented by both {@code StatementImport} and
 * {@code ImportSession}, which store the same file for different lifetimes.
 *
 * Exists so {@link StatementContentService} has one read path instead of one per entity. During the
 * migration a row can be in either of two states, and every reader has to handle both:
 *
 * <ul>
 *   <li><b>Addressed</b> — {@code contentHash}/{@code objectKey} set, bytes in object storage.
 *       Every row written since Phase 2, once a provider is configured.</li>
 *   <li><b>Legacy</b> — address null, bytes in {@code fileContent}. Every row predating Phase 2,
 *       until Phase 3 backfills it.</li>
 * </ul>
 *
 * Both are normal for the duration of the migration, which is why resolution lives in one service
 * rather than being re-decided at each of the five call sites that read statement bytes.
 */
public interface StoredStatement {

    /** Hex SHA-256 of the original file, or null for a row predating Phase 2. */
    String getContentHash();

    /** Provider-internal key for {@link #getContentHash()}, or null. */
    String getObjectKey();

    /** The bytes, when still held in the database. Null once Phase 4 drops the column. */
    byte[] getFileContent();
}
