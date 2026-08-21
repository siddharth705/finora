package com.finora.imports.storage;

/**
 * How a {@link StoredStatement} row's bytes are encoded once retrieved from wherever they live.
 *
 * <p>Explicit, persisted metadata rather than sniffed from the bytes themselves (e.g. GZIP's magic
 * number) -- a row's own {@code compression_type} column is the single source of truth
 * {@link StatementContentService#read} decompresses by. That is what lets an already-live R2 bucket
 * keep working the moment this shipped: every object written before compression existed has no way
 * to record what it is, so {@code NONE} is both the safe default for those rows and a real, ongoing
 * value -- not a migration-in-progress placeholder -- for anything stored without a provider
 * configured at all (see {@code StatementContentService.store}'s no-provider branch).
 */
public enum CompressionType {
    /** Stored exactly as uploaded -- every pre-existing object, and the database-only fallback. */
    NONE,
    /** GZIP, deterministic -- see {@link GzipCompression}. */
    GZIP
}
