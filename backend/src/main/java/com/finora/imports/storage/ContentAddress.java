package com.finora.imports.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A stored statement's identity, and separately, where it happens to live.
 *
 * <h2>The hash is the identity; the key is an implementation detail</h2>
 * These are two fields rather than one on purpose. {@code hash} is what the application knows a
 * document by, forever. {@code key} is a private layout decision belonging to whichever
 * {@link StatementStorage} produced it, and it is the thing most likely to change later -- prefix
 * sharding, a different extension convention, a move between buckets or providers.
 *
 * If the key WERE the identity, none of that could change without rewriting how every row
 * identifies its document. Keeping them apart makes a re-layout a background rewrite of keys while
 * identity holds still.
 *
 * <h2>Why content-addressing</h2>
 * The same statement is currently stored many times over: {@code confirmMultiSection()} writes one
 * copy per detected account section, and every re-import writes another. Addressing by content
 * means N sections and M re-imports of one file resolve to one object instead of N+M copies -- see
 * docs/engineering/statement-storage-migration.md §2.1.
 *
 * It also makes retries idempotent: re-storing identical bytes after a partial failure lands on the
 * same address rather than creating a second object.
 */
public record ContentAddress(String hash, String key) {

    /** SHA-256, hex, lowercase. Long enough that a collision is not a practical concern for a
     *  content-addressed store, and standard enough to be re-derivable by anything else later. */
    private static final String ALGORITHM = "SHA-256";

    public ContentAddress {
        if (hash == null || hash.isBlank()) throw new IllegalArgumentException("hash is required");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key is required");
    }

    /** Hex SHA-256 of the given bytes -- the identity half, independent of any storage layout. */
    public static String hashOf(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(ALGORITHM).digest(content));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK spec; absence means a broken runtime, not a case to
            // handle.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Default layout: {@code statements/a8/d3/a8d34f9….bin}.
     *
     * Two levels of prefix sharding because a single flat prefix is a known hot-spotting problem on
     * object stores that partition by key range, and it also keeps any filesystem-backed
     * implementation from putting every statement in one directory.
     *
     * No original file extension: the extension is user-supplied metadata that belongs on the
     * database row, not in the key. Deriving the key from it would let a renamed upload change
     * where identical content lands, which is exactly what content-addressing exists to prevent.
     */
    public static ContentAddress forContent(byte[] content) {
        String hash = hashOf(content);
        return new ContentAddress(hash, "statements/" + hash.substring(0, 2) + "/" + hash.substring(2, 4) + "/" + hash + ".bin");
    }
}
