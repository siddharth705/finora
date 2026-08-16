package com.finora.security.crypto;

/**
 * One encrypted value, plus the id of the key that produced it.
 *
 * <p>The {@code keyId} is the whole reason this is a record rather than a bare String. Rotation
 * needs to know which key each ciphertext was written under, or re-encryption becomes a flag-day
 * migration: every row has to be rewritten in one transaction, under a key you may be rotating
 * precisely because you think it is compromised — which is the worst possible moment for a long,
 * all-or-nothing migration. Storing the id alongside the ciphertext lets old and new keys coexist,
 * so re-encryption can proceed a row at a time, or lazily on next write.
 *
 * <p>Callers persist both parts. {@link EncryptionService#decrypt} takes them back in the same
 * shape, which is what keeps the caller from having to know anything about key material.
 *
 * @param keyId      identifies the key this was encrypted under -- see {@link KeyProvider}
 * @param ciphertext base64 of [12-byte IV || GCM ciphertext+tag], as produced by
 *                   {@link EncryptionService#encrypt}
 */
public record EncryptedValue(String keyId, String ciphertext) {
}
