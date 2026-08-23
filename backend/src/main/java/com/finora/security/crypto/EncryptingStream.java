package com.finora.security.crypto;

import java.io.InputStream;

/**
 * The streaming twin of {@link EncryptedBytes}, for content too large to hold fully in memory —
 * see {@link EncryptionService#encryptStream}.
 *
 * @param keyId  identifies the key this was encrypted under — persist alongside whatever address
 *               the caller stores {@link #stream()}'s bytes under, the same as
 *               {@link EncryptedBytes#keyId()}.
 * @param stream yields exactly {@code [12-byte IV || GCM ciphertext+tag]} as the plaintext source
 *               is read — the wire shape {@link EncryptedBytes#data()} describes, produced
 *               incrementally rather than all at once.
 * @param length the exact byte count {@link #stream()} will yield. Fixed and computable up front
 *               ({@code plaintextLength + 28}: 12-byte IV + 16-byte GCM tag, GCM/NoPadding adds no
 *               other padding) — needed by callers like an S3-compatible {@code PutObject} that
 *               must declare a content length before the body is read.
 */
public record EncryptingStream(String keyId, InputStream stream, long length) {
}
