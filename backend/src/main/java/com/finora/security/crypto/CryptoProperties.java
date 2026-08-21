package com.finora.security.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encryption key configuration. See {@link EnvironmentKeyProvider} for the shape and why keys are a
 * map rather than a single value.
 *
 * <p>Like {@code SmsProperties}, this holds no default for the secret itself — an unset key must
 * fail rather than fall back to something. Unlike SMS (where an unconfigured provider degrades to a
 * silent no-op), there is no safe degraded mode here: writing an integration token without
 * encryption would put a live third-party credential in the database in plaintext.
 * {@code ProductionConfigValidator} enforces this at boot for the prod profile.
 */
@Configuration
@ConfigurationProperties(prefix = "finora.security.encryption")
public class CryptoProperties {

    /** Which configured key new ciphertext is written under. */
    private String activeKeyId;

    /** id -> base64-encoded 32-byte key. LinkedHashMap so the ids report in configuration order in
     *  the startup error messages, which is easier to reconcile against a config file. */
    private Map<String, String> keys = new LinkedHashMap<>();

    public String getActiveKeyId() { return activeKeyId; }
    public void setActiveKeyId(String activeKeyId) { this.activeKeyId = activeKeyId; }

    public Map<String, String> getKeys() { return keys; }
    public void setKeys(Map<String, String> keys) { this.keys = keys; }
}
