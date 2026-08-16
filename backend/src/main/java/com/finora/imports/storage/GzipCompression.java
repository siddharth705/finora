package com.finora.imports.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * GZIP, made deterministic.
 *
 * <p>Plain {@code GZIPOutputStream} embeds a modification-time field in its header, sourced from
 * wall-clock time -- so compressing the SAME bytes twice, moments apart, produces two DIFFERENT
 * compressed byte streams (differing only in those four header bytes). That breaks the exact
 * property content-addressing exists for ({@link ContentAddress}'s own class doc): identical
 * content stored twice should resolve to the SAME object, not a second one -- a session and the
 * import it confirms into, or a file re-uploaded after a retry, would otherwise each get their own
 * copy of the compressed bytes despite being the same document.
 *
 * <p>RFC 1952's MTIME field (header offset 4, 4 bytes) is zeroed unconditionally after compressing,
 * regardless of what the JDK happened to write there -- not relied upon as "probably already zero".
 * Every gzip-compatible reader, {@link GZIPInputStream} included, accepts MTIME=0 as "timestamp not
 * recorded", the same convention {@code gzip -n} uses for reproducible output; it is not a format
 * violation.
 */
final class GzipCompression {

    private GzipCompression() {}

    private static final int MTIME_OFFSET = 4;
    private static final int MTIME_LENGTH = 4;

    /** Compresses {@code content}. Deterministic: identical input always yields identical output. */
    static byte[] compress(byte[] content) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(32, content.length / 2));
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(content);
        } catch (IOException e) {
            // Both streams are in-memory (ByteArrayOutputStream, and GZIPOutputStream's own
            // deflate over it) -- there is no real I/O here to fail on, so reaching this means a
            // JVM-level problem, not a data problem.
            throw new StatementStorageException("Could not compress statement content", e);
        }
        byte[] compressed = buffer.toByteArray();
        for (int i = 0; i < MTIME_LENGTH; i++) {
            compressed[MTIME_OFFSET + i] = 0;
        }
        return compressed;
    }

    /** Reverses {@link #compress}. */
    static byte[] decompress(byte[] compressed) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return gzip.readAllBytes();
        } catch (IOException e) {
            throw new StatementStorageException("Could not decompress statement content", e);
        }
    }
}
