package com.finora.security.crypto;

import javax.crypto.SecretKey;

/**
 * Where encryption keys come from — the one seam that changes when key storage moves.
 *
 * <p>This interface exists so that {@link EncryptionService} never learns where a key lives. Today
 * the only implementation reads a base64 key from an environment variable
 * ({@link EnvironmentKeyProvider}), which matches how every other secret in this deployment is
 * handled. When that becomes a managed KMS or Vault, a second implementation is the entire change:
 * no caller, no persisted ciphertext, and no column has to move.
 *
 * <p>Two distinct lookups, and the distinction is what makes rotation possible:
 *
 * <ul>
 *   <li>{@link #activeKeyId()} / {@link #activeKey()} — what NEW ciphertext is written under.
 *       Rotation means pointing these at a new key.</li>
 *   <li>{@link #keyById(String)} — what OLD ciphertext is read with. Must keep resolving retired
 *       keys for as long as any row still references them, or that data is unreadable. A key is
 *       only truly retirable once nothing references its id.</li>
 * </ul>
 */
public interface KeyProvider {

    /** The id recorded on newly written ciphertext (see {@link EncryptedValue#keyId()}). */
    String activeKeyId();

    /** The key new ciphertext is encrypted under. */
    SecretKey activeKey();

    /**
     * Resolves a key by the id stored alongside a ciphertext.
     *
     * @throws IllegalStateException if the id is unknown — deliberately a hard failure rather than
     *         a null or an empty Optional. An unresolvable key id means data that can no longer be
     *         read, which is an operational emergency (a key was retired too early, or config was
     *         changed under a running deployment). Silently returning nothing would let that
     *         surface later as a confusing NPE far from the cause.
     */
    SecretKey keyById(String keyId);
}
