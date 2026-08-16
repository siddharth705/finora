package com.finora.entity;

import com.finora.service.ProviderType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Server-side state for the OTP-gated Change Phone Number flow (start -> verify-otp -> complete).
 * Each step is validated against this row's own recorded facts rather than trusted from
 * client-asserted "I already did step N" claims, same discipline as {@link PasswordChangeSession}.
 *
 * currentPhoneNumber and requestedPhoneNumber are both captured at start() time rather than reading
 * the account's live phoneNumber column when needed later -- the OTP this flow collects proves
 * control of requestedPhoneNumber specifically, and that fact has to be checked against the exact
 * number this session was opened for, not whatever the users row happens to say by the time
 * verifyOtp() or complete() runs.
 */
@Entity
@Table(name = "phone_change_sessions")
public class PhoneChangeSession {

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

    @Column(name = "current_phone_number", nullable = false, length = 20)
    private String currentPhoneNumber;

    @Column(name = "requested_phone_number", nullable = false, length = 20)
    private String requestedPhoneNumber;

    @Column(name = "otp_verified_at")
    private Instant otpVerifiedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // Set once verifyOtp() succeeds -- FIREBASE today, the only PhoneVerificationProvider
    // implementation that exists.
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_provider", length = 32)
    private ProviderType verificationProvider;

    // The phone number Firebase actually attested, captured at the moment verifyOtp() confirmed it
    // matches requestedPhoneNumber -- useful for debugging a support ticket without cross-referencing
    // the audit log's JSON metadata, same reasoning as PasswordChangeSession's own field of this name.
    @Column(name = "verified_phone_number", length = 20)
    private String verifiedPhoneNumber;

    // Same fix PasswordChangeSession carries (see its own doc comment on this field, added in
    // V48): without it, two concurrent complete() calls for the same session could both pass the
    // OTP_VERIFIED status check before either commits, producing two PHONE_NUMBER_CHANGED audit
    // rows for one user action.
    @Version
    @Column(nullable = false)
    private Long version = 0L;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getCurrentPhoneNumber() { return currentPhoneNumber; }
    public void setCurrentPhoneNumber(String currentPhoneNumber) { this.currentPhoneNumber = currentPhoneNumber; }
    public String getRequestedPhoneNumber() { return requestedPhoneNumber; }
    public void setRequestedPhoneNumber(String requestedPhoneNumber) { this.requestedPhoneNumber = requestedPhoneNumber; }
    public Instant getOtpVerifiedAt() { return otpVerifiedAt; }
    public void setOtpVerifiedAt(Instant otpVerifiedAt) { this.otpVerifiedAt = otpVerifiedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public ProviderType getVerificationProvider() { return verificationProvider; }
    public void setVerificationProvider(ProviderType verificationProvider) { this.verificationProvider = verificationProvider; }
    public String getVerifiedPhoneNumber() { return verifiedPhoneNumber; }
    public void setVerifiedPhoneNumber(String verifiedPhoneNumber) { this.verifiedPhoneNumber = verifiedPhoneNumber; }

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }
}
