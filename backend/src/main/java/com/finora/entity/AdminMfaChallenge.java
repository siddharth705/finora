package com.finora.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** SEC-03 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Minted by
 *  {@code AuthService.login()} the instant a SCOPE_ADMIN account's password checks out AND it has
 *  MFA enabled -- consumed by {@code AuthService.completeMfaLogin} to finish issuing the real
 *  session tokens. See V98's own migration comment for why this reuses the hashed-opaque-token
 *  shape every other short-lived credential in this codebase already uses. */
@Entity
@Table(name = "admin_mfa_challenges")
public class AdminMfaChallenge {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public void markUsed() { this.usedAt = Instant.now(); }
    public Instant getCreatedAt() { return createdAt; }
}
