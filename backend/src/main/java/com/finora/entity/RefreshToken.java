package com.finora.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // Device-session metadata -- best-effort labels captured from the request that issued/rotated
    // this token (see UserAgentParser), not a persistent cross-rotation device fingerprint (this
    // app has no client-side device ID to correlate rotations of the "same" device with). Each
    // row's own values simply reflect whichever request most recently created/rotated it.
    @Column(length = 64)
    private String browser;

    @Column(length = 64)
    private String device;

    @Column(name = "last_seen_ip", length = 64)
    private String lastSeenIp;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public String getBrowser() { return browser; }
    public void setBrowser(String browser) { this.browser = browser; }
    public String getDevice() { return device; }
    public void setDevice(String device) { this.device = device; }
    public String getLastSeenIp() { return lastSeenIp; }
    public void setLastSeenIp(String lastSeenIp) { this.lastSeenIp = lastSeenIp; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
