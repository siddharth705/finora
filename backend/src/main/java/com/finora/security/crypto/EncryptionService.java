package com.finora.security.crypto;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Reversible encryption at rest for secrets Finora must be able to READ BACK — third-party OAuth
 * refresh tokens first, and every credential-bearing integration after that (bank APIs, brokers,
 * account aggregators).
 *
 * <h2>Why this exists at all, given {@code TokenHasher} already exists</h2>
 *
 * They solve opposite problems and are not interchangeable. {@link com.finora.util.TokenHasher} is
 * one-way SHA-256, and that is correct for Finora's OWN session tokens: the server only ever needs
 * to compare a presented token against a stored digest, never to reproduce it. A third-party
 * refresh token is the reverse — it must be handed back to the provider in plaintext on every
 * refresh, so hashing it destroys the only thing it is for. Anything that must be presented later
 * belongs here; anything that only needs comparing belongs in {@code TokenHasher}. Choosing wrongly
 * in either direction is a real defect: hashing makes the value useless, and encrypting a password
 * where a hash belongs makes a breach recoverable to the attacker.
 *
 * <h2>AES-256-GCM, deliberately</h2>
 *
 * GCM is authenticated encryption: it produces a tag that makes tampering detectable, so a
 * ciphertext modified in the database fails to decrypt instead of silently yielding different
 * plaintext. AES-CBC would encrypt just as well and detect nothing — with an unauthenticated mode,
 * an attacker with write access to the column can flip bits and the application cannot tell.
 * For a value that is fed straight to a third party as a credential, undetected modification is
 * exactly the failure worth engineering against.
 *
 * <h2>A fresh IV per encryption, never reused</h2>
 *
 * {@link #IV_LENGTH_BYTES} random bytes from {@link SecureRandom} per call, prepended to the
 * ciphertext. This is not incidental: <b>reusing an IV under the same key breaks GCM
 * catastrophically</b> — not "weakens", breaks. Two messages sharing a key and IV leak the XOR of
 * their plaintexts and can expose the authentication subkey, which forfeits integrity for every
 * message under that key. Hence a fresh random IV every time, and hence the IV travelling with the
 * ciphertext rather than being stored or derived once. The IV is not secret; it only has to be
 * unique.
 *
 * <h2>What callers store</h2>
 *
 * {@link EncryptedValue} — the base64 blob AND the key id that produced it. Persist both. See
 * {@link KeyProvider} for why the id is what makes rotation incremental rather than a flag day.
 *
 * <p>This class deliberately knows nothing about where keys live; {@link KeyProvider} owns that.
 */
@Service
public class EncryptionService {

    /** 96 bits, the size NIST SP 800-38D recommends for GCM: it is the length the mode is defined
     *  to use directly, so any other length is run through an extra derivation step for no benefit
     *  and slightly higher collision risk across many messages. */
    private static final int IV_LENGTH_BYTES = 12;

    /** 128 bits, the maximum (and standard) GCM tag length. Shorter tags weaken forgery resistance
     *  and buy 4-12 bytes -- not a trade worth making on a credential. */
    private static final int TAG_LENGTH_BITS = 128;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** Thread-safe, and seeded by the platform -- shared rather than per-call, since constructing
     *  a SecureRandom per encryption is pure overhead. */
    private final SecureRandom secureRandom = new SecureRandom();

    private final KeyProvider keyProvider;

    public EncryptionService(KeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    /**
     * Encrypts under the provider's ACTIVE key.
     *
     * @param plaintext the secret; must not be null. An empty string is permitted and round-trips
     *                  to an empty string -- callers that treat "no value" as meaningful should
     *                  check before calling rather than relying on this.
     * @return the ciphertext and the key id it was written under -- persist both
     */
    public EncryptedValue encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("Cannot encrypt null; a caller with no value should not call encrypt.");
        }
        String keyId = keyProvider.activeKeyId();
        byte[] combined = encryptCombined(plaintext.getBytes(StandardCharsets.UTF_8), keyId);
        return new EncryptedValue(keyId, Base64.getEncoder().encodeToString(combined));
    }

    /**
     * The byte-native twin of {@link #encrypt(String)} — see {@link EncryptedBytes} for why a
     * statement file goes through this instead of base64.
     *
     * @param plaintext the bytes to encrypt; must not be null.
     */
    public EncryptedBytes encryptBytes(byte[] plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("Cannot encrypt null; a caller with no value should not call encryptBytes.");
        }
        String keyId = keyProvider.activeKeyId();
        return new EncryptedBytes(keyId, encryptCombined(plaintext, keyId));
    }

    /**
     * The streaming twin of {@link #encryptBytes}, for content too large to hold fully in memory —
     * see {@link EncryptingStream}. Unlike the two methods above, encryption happens lazily as
     * {@link EncryptingStream#stream()} is read, not eagerly here — this method only generates the
     * IV and initializes the cipher, both of which are needed up front to compute
     * {@link EncryptingStream#length()} before the caller has read a single byte.
     *
     * @param plaintext       the source to encrypt as it is read; must not be null.
     * @param plaintextLength the exact byte count {@code plaintext} will yield.
     */
    public EncryptingStream encryptStream(InputStream plaintext, long plaintextLength) {
        if (plaintext == null) {
            throw new IllegalArgumentException("Cannot encrypt a null stream.");
        }
        String keyId = keyProvider.activeKeyId();
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider.activeKey(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            // IV first, then the encrypted body -- the same [IV || ciphertext+tag] shape
            // encryptCombined produces in one array, assembled here from two streams instead
            // because the body is too large to materialize as one.
            InputStream encrypted = new SequenceInputStream(
                    new ByteArrayInputStream(iv), new CipherInputStream(plaintext, cipher));
            long totalLength = IV_LENGTH_BYTES + plaintextLength + (TAG_LENGTH_BITS / 8);
            return new EncryptingStream(keyId, encrypted, totalLength);
        } catch (Exception e) {
            throw new EncryptionException("Failed to start streaming encryption under key " + keyId, e);
        }
    }

    /** Shared by {@link #encrypt(String)} and {@link #encryptBytes}: encrypts under the active key
     *  and returns {@code [IV || ciphertext+tag]} as one array. {@code keyId} is passed in rather
     *  than re-read from {@link #keyProvider} so a caller's exception message and persisted key id
     *  are guaranteed to name the same key, even if a rotation lands between the two calls. */
    private byte[] encryptCombined(byte[] plaintext, String keyId) {
        SecretKey key = keyProvider.activeKey();
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            // IV || ciphertext+tag, as one blob. Storing them together means one column (or one
            // object) and no way for a caller to pair the wrong IV with a ciphertext.
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return combined;
        } catch (Exception e) {
            // Deliberately does not include the plaintext, the key, or the exception's own message
            // in anything that could reach a log at a lower level -- a failure here is structural
            // (bad key length, missing JCE provider), not data-dependent, so the type is the useful
            // signal and the value is not.
            throw new EncryptionException("Failed to encrypt a value under key " + keyId, e);
        }
    }

    /**
     * Decrypts a value using the key it was originally written under.
     *
     * @throws EncryptionException if the ciphertext is malformed, was modified, or was produced
     *         under a different key. GCM cannot distinguish these cases and neither can this method
     *         -- all three mean "this value is not readable", which is the only distinction a
     *         caller can act on.
     */
    public String decrypt(EncryptedValue value) {
        if (value == null) {
            throw new IllegalArgumentException("Cannot decrypt null.");
        }
        byte[] combined;
        try {
            combined = Base64.getDecoder().decode(value.ciphertext());
        } catch (IllegalArgumentException e) {
            // Matches decryptCombined's own EncryptionException-for-everything contract -- a
            // caller catching EncryptionException must not also need to catch this separately.
            throw new EncryptionException("Ciphertext under key " + value.keyId() + " is not valid base64.", e);
        }
        return new String(decryptCombined(combined, value.keyId()), StandardCharsets.UTF_8);
    }

    /** The byte-native twin of {@link #decrypt(EncryptedValue)} — see {@link EncryptedBytes}. */
    public byte[] decryptBytes(EncryptedBytes value) {
        if (value == null) {
            throw new IllegalArgumentException("Cannot decrypt null.");
        }
        return decryptCombined(value.data(), value.keyId());
    }

    /** Shared by {@link #decrypt(EncryptedValue)} and {@link #decryptBytes}: splits
     *  {@code [IV || ciphertext+tag]} and decrypts under the key {@code keyId} names. */
    private byte[] decryptCombined(byte[] combined, String keyId) {
        SecretKey key = keyProvider.keyById(keyId);
        try {
            if (combined.length <= IV_LENGTH_BYTES) {
                // Too short to contain an IV plus any ciphertext at all -- caught explicitly
                // because the alternative is a confusing NegativeArraySizeException below.
                throw new EncryptionException("Ciphertext under key " + keyId
                        + " is too short to be valid.", null);
            }

            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);
            byte[] ciphertext = new byte[combined.length - IV_LENGTH_BYTES];
            System.arraycopy(combined, IV_LENGTH_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (EncryptionException e) {
            throw e;
        } catch (Exception e) {
            throw new EncryptionException("Failed to decrypt a value written under key "
                    + keyId + " -- it may have been modified, or the key may be wrong.", e);
        }
    }

    /**
     * Re-encrypts an existing value under the current active key -- the operation a rotation is
     * made of.
     *
     * <p>Returns the input unchanged when it is already under the active key, so a rotation sweep
     * can call this on every row without needing to filter first, and without rewriting rows that
     * do not need it.
     */
    public EncryptedValue rotate(EncryptedValue value) {
        if (value.keyId().equals(keyProvider.activeKeyId())) {
            return value;
        }
        return encrypt(decrypt(value));
    }
}
