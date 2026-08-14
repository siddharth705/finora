package com.finora.imports.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
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
     * Fails unless {@code content} actually hashes to {@code expectedHash}.
     *
     * <p>The single implementation of the integrity check, called on every read through
     * {@code StatementContentService}. Content addressing's whole premise is that the hash IS the
     * identity; a store that never re-derives it is asserting that premise rather than checking it,
     * and returns wrong bytes as confidently as right ones.
     *
     * <p>What this actually catches: bit-rot, a provider handing back the wrong object for a key,
     * a key collision after a layout change, and a botched migration or restore. None of those are
     * hypothetical enough to skip on a system whose payload is someone's bank statement -- the
     * failure mode is not a corrupt download, it is a wrong statement parsed into a real ledger.
     *
     * <p>Cost is a SHA-256 over the retrieved bytes. Every read site is user-initiated (import
     * confirm, download, re-import), where this is invisible beside object-store latency and PDF
     * parsing -- it is not on any hot path.
     *
     * <p>The message carries hashes only, never content: the expected and actual digests are what
     * an operator needs to tell "wrong object" from "damaged object", and both are safe to log.
     *
     * @throws StatementIntegrityException if the content does not match
     */
    public static void requireMatches(byte[] content, String expectedHash, String what) {
        String actual = hashOf(content);
        if (!actual.equalsIgnoreCase(expectedHash)) {
            throw new StatementIntegrityException(
                    "Integrity check failed for " + what + ": storage returned " + content.length
                    + " bytes hashing to " + actual + ", but the row claims " + expectedHash
                    + ". The object is present but is not the document this row addresses -- do not "
                    + "parse it. Investigate the storage provider before retrying.");
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

    /**
     * BH-018. The streaming twin of {@link #forContent}: copies {@code content} to {@code out}
     * while computing its SHA-256 incrementally, so the caller never needs the whole upload
     * resident in memory at once to learn its address -- only whatever buffer size the copy loop
     * uses (a few KB), regardless of whether the source is a 10 KB CSV or a 10 MB PDF.
     *
     * <p>The address can only be known once every byte has been read, so this is necessarily a
     * two-phase operation for any caller that also needs to place the content somewhere permanent
     * (a content-addressed path or object key derived from the hash): copy-and-hash to a temporary
     * location first via this method, then move or upload from there once the address is known.
     * Each {@link StatementStorage} implementation owns that second phase itself, because where a
     * safe temporary location is differs by backend -- see
     * {@code FilesystemStatementStorage#store(InputStream, long)}'s own comment on why its scratch
     * file must share a filesystem with the final target, which {@code R2StatementStorage} has no
     * equivalent constraint for.
     *
     * @throws IOException if reading {@code content} or writing {@code out} fails. Callers must
     *         treat this as fatal for the request, the same as {@link StatementStorageException}
     *         from the store methods this feeds -- partial content written to {@code out} must
     *         never be promoted to a permanent location.
     */
    public static ContentAddress copyAndAddress(InputStream content, OutputStream out) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // Same reasoning as hashOf(byte[]) above: SHA-256 is JDK-mandated, so absence means a
            // broken runtime, not a case to handle.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        // DigestInputStream updates the digest as each read() is served, so transferTo's internal
        // copy loop -- a fixed-size buffer, not the whole stream at once -- is what actually
        // performs both the hash and the copy in the same pass.
        new DigestInputStream(content, digest).transferTo(out);
        String hash = HexFormat.of().formatHex(digest.digest());
        return new ContentAddress(hash, "statements/" + hash.substring(0, 2) + "/" + hash.substring(2, 4) + "/" + hash + ".bin");
    }
}
