package com.finora.notification.domain;

import com.finora.security.crypto.EncryptedValue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A device's push token, encrypted at rest.
 *
 * <p>Encrypted, not hashed -- and this is the whole point of the distinction. A password is only
 * ever compared, so a one-way hash works. The dispatcher must hand FCM/APNs the actual token on
 * every send, so it must be recoverable; a hash would make this table useless for its own purpose.
 *
 * <p>Follows GmailConnection exactly: ciphertext and key id stored as a pair, reassembled through
 * {@link #credential()}, with no getter that returns a decrypted value -- reading it requires the
 * EncryptionService, which keeps "who can read this" a wiring question rather than a field
 * visibility question.
 */
@Entity
@Table(name = "device_tokens")
public class DeviceToken {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 16)
    private String platform;

    @Column(name = "encrypted_token", nullable = false, columnDefinition = "TEXT")
    private String encryptedToken;

    @Column(name = "encryption_key_id", nullable = false, length = 64)
    private String encryptionKeyId;

    /**
     * SHA-256 of the raw token, for equality lookups only. The ciphertext cannot be matched
     * directly because AES-GCM uses a fresh random IV per call, so the same token encrypts to a
     * different string every time.
     */
    @Column(name = "token_fingerprint", nullable = false, length = 64)
    private String tokenFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected DeviceToken() {
        // for JPA
    }

    private DeviceToken(UUID userId, String platform, EncryptedValue token, String fingerprint,
            Instant now) {
        this.userId = userId;
        this.platform = platform;
        this.encryptedToken = token.ciphertext();
        this.encryptionKeyId = token.keyId();
        this.tokenFingerprint = fingerprint;
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    public static DeviceToken register(UUID userId, String platform, EncryptedValue token,
            String fingerprint, Instant now) {
        return new DeviceToken(userId, platform, token, fingerprint, now);
    }

    /** Reassembles the stored halves into the shape EncryptionService.decrypt takes. */
    public EncryptedValue credential() {
        return new EncryptedValue(encryptionKeyId, encryptedToken);
    }

    public void touch(Instant now) {
        this.lastSeenAt = now;
        this.revokedAt = null;
    }

    /**
     * Corrects a stale platform on re-registration. A device's install can change platform under
     * the same fingerprint only in contrived scenarios, but a re-registration is the moment
     * {@code DeviceTokenService} knows the true current value -- leaving an old one in place would
     * silently route this device's pushes to the wrong provider (Task 11 dispatches on this field).
     */
    public void updatePlatform(String platform) {
        this.platform = platform;
    }

    /** Soft revoke on logout or uninstall detection -- never a hard delete, so the trail survives. */
    public void revoke(Instant now) {
        this.revokedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getPlatform() {
        return platform;
    }

    public String getEncryptedToken() {
        return encryptedToken;
    }

    public String getEncryptionKeyId() {
        return encryptionKeyId;
    }

    public String getTokenFingerprint() {
        return tokenFingerprint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
