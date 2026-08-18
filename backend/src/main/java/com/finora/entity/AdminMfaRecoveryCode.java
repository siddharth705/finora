package com.finora.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** SEC-03 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). One-time-use backup
 *  codes minted as a batch by {@link com.finora.service.AdminMfaService#confirm} -- see V98's own
 *  migration comment for why these are their own table rather than an array column. */
@Entity
@Table(name = "admin_mfa_recovery_codes")
public class AdminMfaRecoveryCode {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** SHA-256 (TokenHasher) -- a recovery code is only ever compared, never reproduced, same as
     *  a password-reset token. The raw code exists only in AdminMfaService.confirm's response. */
    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public Instant getUsedAt() { return usedAt; }
    public void markUsed() { this.usedAt = Instant.now(); }
    public Instant getCreatedAt() { return createdAt; }
}
