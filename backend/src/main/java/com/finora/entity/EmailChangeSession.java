package com.finora.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Server-side state for the step-up-gated Change Email flow (start -> verify -> complete). Each
 * step is validated against this row's own recorded facts rather than trusted from client-asserted
 * "I already did step N" claims, same discipline as {@link PhoneChangeSession} and
 * {@link PasswordChangeSession}.
 *
 * currentEmail and requestedEmail are both captured at start() time rather than reading the
 * account's live email column when needed later -- the verification link this flow sends proves
 * control of requestedEmail specifically, and that fact has to be checked against the exact address
 * this session was opened for, not whatever the users row happens to say by the time verify() or
 * complete() runs. Same reasoning as PhoneChangeSession's identically-shaped fields.
 */
@Entity
@Table(name = "email_change_sessions")
public class EmailChangeSession {

    public enum Status {
        STARTED, EMAIL_VERIFIED, COMPLETED, EXPIRED
    }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "current_email", nullable = false)
    private String currentEmail;

    @Column(name = "requested_email", nullable = false)
    private String requestedEmail;

    // sha256(rawToken), same TokenHasher pattern as email_verification_tokens/
    // password_reset_tokens -- the raw token only ever exists in the emailed link and the
    // client's URL bar, never persisted.
    @Column(name = "verification_token_hash", nullable = false, length = 64)
    private String verificationTokenHash;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // Same fix PhoneChangeSession/PasswordChangeSession carry -- without it, two concurrent
    // complete() calls for the same session could both pass the EMAIL_VERIFIED status check
    // before either commits, producing two EMAIL_CHANGED audit rows for one user action.
    @Version
    @Column(nullable = false)
    private Long version = 0L;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getCurrentEmail() { return currentEmail; }
    public void setCurrentEmail(String currentEmail) { this.currentEmail = currentEmail; }
    public String getRequestedEmail() { return requestedEmail; }
    public void setRequestedEmail(String requestedEmail) { this.requestedEmail = requestedEmail; }
    public String getVerificationTokenHash() { return verificationTokenHash; }
    public void setVerificationTokenHash(String verificationTokenHash) { this.verificationTokenHash = verificationTokenHash; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public void setEmailVerifiedAt(Instant emailVerifiedAt) { this.emailVerifiedAt = emailVerifiedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }
}
