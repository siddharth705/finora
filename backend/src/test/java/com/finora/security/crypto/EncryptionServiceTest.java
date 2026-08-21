package com.finora.security.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-009. Covers the properties this class exists to provide rather than the fact that AES works:
 * a value survives a round trip, a MODIFIED value is detected rather than silently mis-decrypted,
 * the same plaintext never produces the same ciphertext twice, and rotation can move a value
 * between keys without either key becoming unreadable in the meantime.
 */
class EncryptionServiceTest {

    /** Two distinct, valid AES-256 keys. Fixed rather than random so a failure is reproducible. */
    private static final String KEY_V1 = base64Key('a');
    private static final String KEY_V2 = base64Key('b');

    private EncryptionService service;
    private RotatableKeyProvider keyProvider;

    private static String base64Key(char fill) {
        byte[] raw = new byte[32];
        java.util.Arrays.fill(raw, (byte) fill);
        return Base64.getEncoder().encodeToString(raw);
    }

    /** A KeyProvider holding both keys, whose active key the test can move -- the shape a real
     *  rotation has, where the old key must stay resolvable while the new one takes over writes. */
    private static class RotatableKeyProvider implements KeyProvider {
        private final Map<String, SecretKey> keys = new HashMap<>();
        private String activeKeyId;

        RotatableKeyProvider(String activeKeyId) {
            this.activeKeyId = activeKeyId;
            keys.put("v1", new SecretKeySpec(Base64.getDecoder().decode(KEY_V1), "AES"));
            keys.put("v2", new SecretKeySpec(Base64.getDecoder().decode(KEY_V2), "AES"));
        }

        void setActiveKeyId(String keyId) { this.activeKeyId = keyId; }

        @Override public String activeKeyId() { return activeKeyId; }
        @Override public SecretKey activeKey() { return keys.get(activeKeyId); }
        @Override public SecretKey keyById(String keyId) {
            SecretKey key = keys.get(keyId);
            if (key == null) throw new IllegalStateException("No key with id " + keyId);
            return key;
        }
    }

    @BeforeEach
    void setUp() {
        keyProvider = new RotatableKeyProvider("v1");
        service = new EncryptionService(keyProvider);
    }

    @Test
    void aValueSurvivesARoundTrip() {
        String secret = "1//0gZm9vYmFyLXJlZnJlc2gtdG9rZW4tZXhhbXBsZQ";

        EncryptedValue encrypted = service.encrypt(secret);

        assertThat(encrypted.ciphertext()).isNotEqualTo(secret);
        assertThat(service.decrypt(encrypted)).isEqualTo(secret);
    }

    @Test
    void theCiphertextRecordsWhichKeyWroteIt() {
        // The field rotation depends on -- without it, a rotated deployment cannot tell which key
        // any given row needs.
        assertThat(service.encrypt("anything").keyId()).isEqualTo("v1");
    }

    @Test
    @DisplayName("the same plaintext encrypts differently every time (fresh IV per call)")
    void theSamePlaintextNeverProducesTheSameCiphertextTwice() {
        // Not a nicety: reusing an IV under one key breaks GCM's integrity guarantee outright, so
        // identical ciphertexts for identical input would be a genuine cryptographic defect, not
        // merely a fingerprinting leak. 50 rather than 2, so an IV generated from something with a
        // small period would still be caught.
        Set<String> ciphertexts = IntStream.range(0, 50)
                .mapToObj(i -> service.encrypt("the same value every time").ciphertext())
                .collect(Collectors.toSet());

        assertThat(ciphertexts).hasSize(50);
    }

    @Test
    @DisplayName("a tampered ciphertext is rejected, not silently mis-decrypted")
    void tamperingIsDetected() {
        // The property AES-GCM is chosen for over AES-CBC. An attacker with write access to the
        // column must not be able to alter a stored credential undetected.
        EncryptedValue original = service.encrypt("refresh-token-worth-tampering-with");

        byte[] raw = Base64.getDecoder().decode(original.ciphertext());
        raw[raw.length - 1] ^= 0x01; // flip one bit of the GCM tag
        EncryptedValue tampered = new EncryptedValue(original.keyId(), Base64.getEncoder().encodeToString(raw));

        assertThatThrownBy(() -> service.decrypt(tampered))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void aValueEncryptedUnderOneKeyCannotBeReadWithAnother() {
        EncryptedValue underV1 = service.encrypt("secret");
        // Same ciphertext, relabelled as if it had been written under v2.
        EncryptedValue mislabelled = new EncryptedValue("v2", underV1.ciphertext());

        assertThatThrownBy(() -> service.decrypt(mislabelled))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void aCiphertextTooShortToContainAnIvIsRejectedClearly() {
        // Guards the explicit length check -- without it this is a NegativeArraySizeException,
        // which says nothing useful in a log.
        EncryptedValue truncated = new EncryptedValue("v1", Base64.getEncoder().encodeToString(new byte[4]));

        assertThatThrownBy(() -> service.decrypt(truncated))
                .isInstanceOf(EncryptionException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void encryptingNullIsARejectedProgrammingError() {
        assertThatThrownBy(() -> service.encrypt(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEmptyStringRoundTrips() {
        // Permitted and documented -- asserted so the behaviour is pinned rather than incidental.
        assertThat(service.decrypt(service.encrypt(""))).isEmpty();
    }

    /**
     * The whole point of {@link EncryptedValue#keyId()}: after rotating the active key, values
     * written under the OLD key must still decrypt. If this fails, a rotation silently destroys
     * every previously stored credential.
     */
    @Test
    @DisplayName("after rotating the active key, old values still decrypt")
    void rotationDoesNotStrandExistingValues() {
        EncryptedValue writtenUnderV1 = service.encrypt("token-from-before-the-rotation");

        keyProvider.setActiveKeyId("v2");

        assertThat(service.decrypt(writtenUnderV1))
                .as("a value written under the previous key must remain readable")
                .isEqualTo("token-from-before-the-rotation");
        assertThat(service.encrypt("new-token").keyId())
                .as("new writes go under the new key")
                .isEqualTo("v2");
    }

    @Test
    void rotateMovesAValueOntoTheActiveKeyAndPreservesItsPlaintext() {
        EncryptedValue underV1 = service.encrypt("token-to-migrate");
        keyProvider.setActiveKeyId("v2");

        EncryptedValue rotated = service.rotate(underV1);

        assertThat(rotated.keyId()).isEqualTo("v2");
        assertThat(rotated.ciphertext()).isNotEqualTo(underV1.ciphertext());
        assertThat(service.decrypt(rotated)).isEqualTo("token-to-migrate");
    }

    @Test
    void rotateLeavesAValueAlreadyOnTheActiveKeyUntouched() {
        // Lets a rotation sweep call rotate() on every row without filtering, and without
        // rewriting rows that do not need it.
        EncryptedValue alreadyCurrent = service.encrypt("already-on-the-active-key");

        assertThat(service.rotate(alreadyCurrent)).isSameAs(alreadyCurrent);
    }
}
