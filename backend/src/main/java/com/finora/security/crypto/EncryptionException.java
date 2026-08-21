package com.finora.security.crypto;

/**
 * A value could not be encrypted or decrypted.
 *
 * <p>Unchecked, and deliberately not an {@code ApiException}: this is never a condition a user
 * caused or can act on, so it must not become a 4xx with a message describing cryptographic
 * internals. Callers that can degrade gracefully (e.g. a sync worker treating an unreadable token
 * as "connection needs reauth") should catch it explicitly and translate; everything else should
 * let it propagate to the generic 500 handler rather than guessing.
 *
 * <p><b>Never put the plaintext, the key, or the raw ciphertext in the message.</b> Exception
 * messages reach logs and error trackers; the whole point of this package is that those values do
 * not. The key id is safe to include and is the useful diagnostic.
 */
public class EncryptionException extends RuntimeException {

    public EncryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
