package com.finora.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Server-side state for the OTP-gated Change Password flow (start -> verify-otp -> complete).
 * Each step is validated against this row's own recorded facts rather than trusted from
 * client-asserted "I already did step N" claims -- e.g. /complete refuses to run unless this row
 * itself shows both currentPasswordVerifiedAt and otpVerifiedAt set, not because the request body
 * says so.
 */
@Entity
@Table(name = "password_change_sessions")
public class PasswordChangeSession {

    public enum Status {
        STARTED, OTP_VERIFIED, COMPLETED, EXPIRED
    }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "current_password_verified_at", nullable = false)
    private Instant currentPasswordVerifiedAt;

    @Column(name = "otp_verified_at")
    private Instant otpVerifiedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getCurrentPasswordVerifiedAt() { return currentPasswordVerifiedAt; }
    public void setCurrentPasswordVerifiedAt(Instant currentPasswordVerifiedAt) { this.currentPasswordVerifiedAt = currentPasswordVerifiedAt; }
    public Instant getOtpVerifiedAt() { return otpVerifiedAt; }
    public void setOtpVerifiedAt(Instant otpVerifiedAt) { this.otpVerifiedAt = otpVerifiedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }
}
