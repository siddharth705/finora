package com.finora.entity;

import com.finora.service.ProviderType;
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
        STARTED, OTP_VERIFIED, COMPLETED, EXPIRED,
        /** Consumed by UserAccountLifecycleService.requestDeletion's re-auth gate, not an actual
         *  password change -- deliberately distinct from COMPLETED, so a stray replay into
         *  PasswordChangeService.complete() is rejected rather than mistaken for a real password
         *  change reusing COMPLETED's idempotent-replay branch. See
         *  PasswordChangeService.consumeForAccountDeletion. */
        DELETION_CONFIRMED
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

    // Set once verifyOtp() succeeds -- FIREBASE today, the only PhoneVerificationProvider
    // implementation that exists.
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_provider", length = 32)
    private ProviderType verificationProvider;

    // The phone number Firebase actually attested, captured at the moment verifyOtp() confirmed it
    // matches the account's own number -- useful for debugging a support ticket without having to
    // cross-reference the audit log's JSON metadata.
    @Column(name = "verified_phone_number", length = 20)
    private String verifiedPhoneNumber;

    // Set once complete() runs -- lets a replayed/retried complete() call (see isCompleted()) return
    // the same outcome it returned the first time, instead of re-deriving it from the request body
    // (which a legitimate idempotent retry sends unchanged anyway, but this is the authoritative,
    // already-persisted answer either way).
    @Column(name = "signed_out_other_devices")
    private Boolean signedOutOtherDevices;

    // Bug fix: complete() reads this row, checks status == OTP_VERIFIED, then writes COMPLETED --
    // with no locking, two concurrent complete() calls for the same session (double-submit) could
    // both pass the check before either commit, producing two password writes, two audit rows,
    // and two "your password was changed" emails for one user action. Same fix as RefreshToken's
    // own @Version field -- see that entity's doc comment.
    @Version
    @Column(nullable = false)
    private Long version = 0L;

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
    public ProviderType getVerificationProvider() { return verificationProvider; }
    public void setVerificationProvider(ProviderType verificationProvider) { this.verificationProvider = verificationProvider; }
    public String getVerifiedPhoneNumber() { return verifiedPhoneNumber; }
    public void setVerifiedPhoneNumber(String verifiedPhoneNumber) { this.verifiedPhoneNumber = verifiedPhoneNumber; }
    public Boolean getSignedOutOtherDevices() { return signedOutOtherDevices; }
    public void setSignedOutOtherDevices(Boolean signedOutOtherDevices) { this.signedOutOtherDevices = signedOutOtherDevices; }

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }
}
