package com.finora.security.crypto;

/**
 * The byte-native twin of {@link EncryptedValue}, for content too large to justify base64's ~33%
 * overhead — statement files, not tokens. Same wire shape otherwise: {@code data} is
 * {@code [12-byte IV || GCM ciphertext+tag]}, exactly as {@link EncryptedValue#ciphertext()}
 * describes, just not base64-encoded because the caller is about to hand it to an object store,
 * not a text column.
 *
 * @param keyId identifies the key this was encrypted under — see {@link KeyProvider}
 * @param data  [12-byte IV || GCM ciphertext+tag], as produced by
 *              {@link EncryptionService#encryptBytes}
 */
public record EncryptedBytes(String keyId, byte[] data) {
}
