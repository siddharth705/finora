package com.finora.security.crypto;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Keys from configuration — the initial {@link KeyProvider}, matching how every other secret in
 * this deployment is supplied (Railway environment variable, injected as a Spring property).
 *
 * <h2>Configuration shape</h2>
 *
 * <pre>
 *   finora.security.encryption.active-key-id: v1
 *   finora.security.encryption.keys.v1: ${FINORA_ENCRYPTION_KEY}
 *   finora.security.encryption.keys.v2: ${FINORA_ENCRYPTION_KEY_V2}   # only during a rotation
 * </pre>
 *
 * A map rather than a single key, because rotation needs two keys resolvable at once: new writes
 * go under the active key while old rows are still readable under the previous one. Collapsing this
 * to one key would make rotation a flag-day migration, which is the thing
 * {@link EncryptedValue#keyId()} exists to avoid.
 *
 * <h2>Validation happens at startup, not at first use</h2>
 *
 * Every key is decoded and length-checked in the constructor. A malformed key is a configuration
 * error, and the useful time to discover it is at boot — not hours later when the first user tries
 * to connect an integration and gets a 500. This mirrors {@code ProductionConfigValidator}'s stance
 * that a misconfigured production deployment should refuse to start rather than serve requests it
 * will fail.
 */
@Component
public class EnvironmentKeyProvider implements KeyProvider {

    /** AES-256. 32 bytes, checked rather than assumed: a 16-byte value is a perfectly valid AES-128
     *  key, so it would work silently at half the intended strength -- exactly the kind of downgrade
     *  nobody notices. */
    private static final int REQUIRED_KEY_LENGTH_BYTES = 32;

    private final String activeKeyId;
    private final Map<String, SecretKey> keys;

    public EnvironmentKeyProvider(CryptoProperties properties) {
        this.activeKeyId = properties.getActiveKeyId();

        if (activeKeyId == null || activeKeyId.isBlank()) {
            throw new IllegalStateException(
                    "finora.security.encryption.active-key-id is not set. Encryption cannot start "
                            + "without knowing which key to write new values under.");
        }

        Map<String, SecretKey> decoded = new HashMap<>();
        properties.getKeys().forEach((id, base64) -> decoded.put(id, decodeKey(id, base64)));
        this.keys = Map.copyOf(decoded);

        if (!keys.containsKey(activeKeyId)) {
            throw new IllegalStateException(
                    "finora.security.encryption.active-key-id is '" + activeKeyId
                            + "' but no key with that id is configured. Configured ids: " + keys.keySet());
        }
    }

    private static SecretKey decodeKey(String keyId, String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException("Encryption key '" + keyId + "' is configured but empty.");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            // The value itself is never echoed -- it is a key.
            throw new IllegalStateException("Encryption key '" + keyId + "' is not valid base64.", e);
        }
        if (raw.length != REQUIRED_KEY_LENGTH_BYTES) {
            throw new IllegalStateException("Encryption key '" + keyId + "' must decode to "
                    + REQUIRED_KEY_LENGTH_BYTES + " bytes for AES-256, but was " + raw.length
                    + ". Generate one with: openssl rand -base64 32");
        }
        return new SecretKeySpec(raw, "AES");
    }

    @Override
    public String activeKeyId() {
        return activeKeyId;
    }

    @Override
    public SecretKey activeKey() {
        return keys.get(activeKeyId);
    }

    @Override
    public SecretKey keyById(String keyId) {
        SecretKey key = keys.get(keyId);
        if (key == null) {
            // Hard failure by design -- see KeyProvider#keyById. Data written under this id cannot
            // be read, and that needs to be loud.
            throw new IllegalStateException("No encryption key configured with id '" + keyId
                    + "'. Values written under it cannot be decrypted until it is restored. "
                    + "Configured ids: " + keys.keySet());
        }
        return key;
    }
}
