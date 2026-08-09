package com.finora.service;

import com.finora.dto.PasswordChangeDtos.*;
import com.finora.entity.PasswordChangeSession;
import com.finora.entity.User;
import com.finora.util.AfterCommit;
import com.finora.exception.ApiException;
import com.finora.repository.PasswordChangeSessionRepository;
import com.finora.repository.UserRepository;
import com.finora.util.PhoneMasking;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The authenticated Change Password flow, Phase 2: current password -> OTP -> new password,
 * replacing the earlier single-step version. Each step is validated against a persisted
 * PasswordChangeSession row rather than trusted from client-asserted "I already did step N"
 * claims -- complete() refuses to run unless the session itself shows OTP_VERIFIED, regardless
 * of what the request body says.
 *
 * Deliberately its own service rather than folded into AuthService (already at 12 constructor
 * dependencies covering register/login/reset) -- this is a focused, self-contained slice with a
 * narrower dependency set.
 */
@Service
public class PasswordChangeService {

    // Configurable rather than hardcoded -- see app.security.password-change-session-expiry-minutes
    // in application.yml's own comment.
    @Value("${app.security.password-change-session-expiry-minutes:15}")
    private long sessionTtlMinutes;

    private final UserRepository userRepository;
    private final PasswordChangeSessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final PhoneVerificationProvider phoneVerificationProvider;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;
    private final EmailProvider emailProvider;
    private final PasswordHistoryService passwordHistoryService;

    public PasswordChangeService(UserRepository userRepository, PasswordChangeSessionRepository sessionRepository,
                                  PasswordEncoder passwordEncoder, PhoneVerificationProvider phoneVerificationProvider,
                                  RefreshTokenService refreshTokenService, AuditService auditService,
                                  EmailProvider emailProvider, PasswordHistoryService passwordHistoryService) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.phoneVerificationProvider = phoneVerificationProvider;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
        this.emailProvider = emailProvider;
        this.passwordHistoryService = passwordHistoryService;
    }

    /** Step 1: verify the current password and open a session. Atomic in this design -- there's
     *  no state where the password was verified but the session wasn't created, so
     *  PASSWORD_CHANGE_STARTED and CURRENT_PASSWORD_VERIFIED are both recorded here as two
     *  distinct facts about the same moment, not two separate opportunities to fail. Returns the
     *  real phone number so the frontend can hand it straight to Firebase's
     *  signInWithPhoneNumber() -- this backend never sends the OTP itself (see
     *  PhoneVerificationProvider's own doc comment). */
    @Transactional(noRollbackFor = ApiException.class)
    public StartResponse start(UUID userId, StartRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));

        // Same check AuthService already applies at login and password reset -- a suspended
        // account's own still-valid JWT (issued before suspension took effect; JWTs aren't
        // revoked on suspension) must not be usable to change the password out from under an
        // account an admin has deliberately locked out.
        if (user.isSuspended()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This account has been suspended.");
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            auditService.record(userId, "INVALID_CURRENT_PASSWORD", "User", userId);
            throw new ApiException(HttpStatus.BAD_REQUEST, "Current password is incorrect.");
        }
        if (user.getPhoneNumber() == null || user.getPhoneNumber().isBlank()) {
            // Shouldn't be reachable in practice -- phone number is required at registration --
            // but User.phoneNumber has no NOT NULL constraint at the DB level, same guard
            // AuthService.resolveResetPasswordPhone already applies for the identical reason.
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This account has no phone number on file. Contact an administrator for help changing your password.");
        }

        Instant now = Instant.now();
        PasswordChangeSession session = new PasswordChangeSession();
        session.setUserId(userId);
        session.setStatus(PasswordChangeSession.Status.STARTED);
        session.setCurrentPasswordVerifiedAt(now);
        session.setExpiresAt(now.plusSeconds(sessionTtlMinutes * 60));
        session = sessionRepository.save(session);

        auditService.record(userId, "PASSWORD_CHANGE_STARTED", "User", userId);
        auditService.record(userId, "CURRENT_PASSWORD_VERIFIED", "User", userId);

        return new StartResponse(session.getId().toString(), user.getPhoneNumber(), PhoneMasking.mask(user.getPhoneNumber()));
    }

    /** Step 2: verify the Firebase ID token against this specific session and the account's own
     *  phone number. By the time a token exists at all, Firebase has already confirmed the code
     *  client-side -- a wrong code never produces one to send here; the only failure modes left
     *  are an invalid/expired token or one that proves the WRONG phone number, both of which are
     *  real errors, not a "try again" state, so this throws rather than returning a soft
     *  verified:false the way the OTP-based version used to. */
    @Transactional(noRollbackFor = ApiException.class)
    public VerifyOtpResponse verifyOtp(UUID userId, VerifyOtpRequest request) {
        PasswordChangeSession session = loadActiveSession(userId, request.sessionId(), PasswordChangeSession.Status.STARTED,
                "This step has already been completed, or the session is no longer valid. Please start again.");
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));

        String verifiedPhone = phoneVerificationProvider.verifyAndGetPhoneNumber(request.firebaseIdToken());
        if (!phoneNumbersMatch(verifiedPhone, user.getPhoneNumber())) {
            auditService.record(userId, "INVALID_OTP", "User", userId);
            throw new ApiException(HttpStatus.BAD_REQUEST, "The verified phone number doesn't match the one on this account.");
        }

        session.setOtpVerifiedAt(Instant.now());
        session.setStatus(PasswordChangeSession.Status.OTP_VERIFIED);
        session.setVerificationProvider(ProviderType.FIREBASE);
        session.setVerifiedPhoneNumber(verifiedPhone);
        sessionRepository.save(session);

        auditService.record(userId, "FIREBASE_PHONE_VERIFIED", "User", userId);
        return new VerifyOtpResponse("Verified.");
    }

    /** Firebase's phone_number claim is always E.164 ("+919876543210"); User.phoneNumber may or
     *  may not carry the leading "+" depending on how it was typed at registration -- compares
     *  digits only so that difference alone never causes a false mismatch. Same helper as
     *  AuthService's own phoneNumbersMatch(); not shared beyond copy-paste since both are small,
     *  private, and each service already keeps its own dependency set narrow by design. */
    private boolean phoneNumbersMatch(String a, String b) {
        if (a == null || b == null) return false;
        return a.replaceAll("[^0-9]", "").equals(b.replaceAll("[^0-9]", ""));
    }

    /** Step 3: set the new password. Only reachable once the session itself shows OTP_VERIFIED --
     *  server-side state, not a client-supplied flag. The device making this request always stays
     *  signed in (identified by its own sid claim, see revokeAllOtherSessionsForUser); signOutOtherDevices
     *  only controls every OTHER active session.
     *
     *  Idempotency: a session that's already COMPLETED (the frontend retried after a timeout, or
     *  network hiccup, without ever seeing the first response) returns the SAME outcome again
     *  rather than throwing the generic "wrong state" error or -- far worse -- re-running the
     *  side effects (double password hash write, a second round of session revocation, a
     *  duplicate PASSWORD_CHANGED audit entry). The session's own persisted
     *  signedOutOtherDevices is what makes the replayed response correct even if the caller's
     *  request body somehow disagreed with what actually happened the first time. */
    @Transactional(noRollbackFor = ApiException.class)
    public CompleteResponse complete(UUID userId, CompleteRequest request, UUID currentSessionId) {
        PasswordChangeSession session = resolveSession(userId, request.sessionId());

        if (session.getStatus() == PasswordChangeSession.Status.COMPLETED) {
            boolean signedOutOtherDevices = Boolean.TRUE.equals(session.getSignedOutOtherDevices());
            return new CompleteResponse(completeMessage(signedOutOtherDevices), signedOutOtherDevices);
        }
        if (session.getStatus() != PasswordChangeSession.Status.OTP_VERIFIED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Verify the code sent to your phone before completing this change.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));

        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "New password must be different from your current password.");
        }
        passwordHistoryService.rejectIfRecentlyUsed(userId, request.newPassword());

        Instant now = Instant.now();
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setUpdatedAt(now);
        user.setPasswordChangedAt(now);
        userRepository.save(user);
        passwordHistoryService.record(userId, user.getPasswordHash());

        session.setStatus(PasswordChangeSession.Status.COMPLETED);
        session.setCompletedAt(now);
        session.setSignedOutOtherDevices(request.signOutOtherDevices());
        sessionRepository.save(session);

        if (request.signOutOtherDevices()) {
            // The session making this request, from the access token's sid claim -- not a token
            // the client had to be able to read. See revokeAllOtherSessionsForUser.
            refreshTokenService.revokeAllOtherSessionsForUser(userId, currentSessionId);
            auditService.record(userId, "OTHER_SESSIONS_REVOKED", "User", userId);
        } else {
            auditService.record(userId, "OTHER_SESSIONS_PRESERVED", "User", userId);
        }

        auditService.record(userId, "PASSWORD_CHANGED", "User", userId, Map.of("method", "authenticated_settings_otp_gated"));
        // BH-016: after commit. Same reasoning as AuthService's three sends -- the provider is an
        // HTTP call with no read timeout and this method holds a pooled connection, and a
        // "your password was changed" email for a change that then rolled back is worse than none.
        String changedEmail = user.getEmail();
        AfterCommit.run("password changed email", () -> {
            EmailResult changedEmailResult = emailProvider.sendPasswordChangedEmail(changedEmail);
            auditService.record(userId, "EMAIL_SENT", "User", userId, Map.of(
                    "type", "password_changed", "provider", changedEmailResult.provider().name(),
                    "success", changedEmailResult.success()));
        });

        return new CompleteResponse(completeMessage(request.signOutOtherDevices()), request.signOutOtherDevices());
    }

    private String completeMessage(boolean signedOutOtherDevices) {
        return signedOutOtherDevices
                ? "Your password has been updated. This device stays signed in; every other device has been signed out."
                : "Your password has been updated. All your devices, including this one, remain signed in.";
    }

    /** Shared by verifyOtp() and complete() -- both need the same "does this session belong to
     *  this user, is it in the right state, and has it expired" checks. requiredStatus is what
     *  the session must ALREADY be in for the step being attempted to make sense (e.g. verifyOtp()
     *  requires STARTED, the state start() itself set). complete() resolves the session itself
     *  (via resolveSession) since it needs to special-case an already-COMPLETED session for
     *  idempotency rather than treating it as just another wrong-state error. */
    private PasswordChangeSession loadActiveSession(UUID userId, String rawSessionId,
                                                      PasswordChangeSession.Status requiredStatus, String wrongStateMessage) {
        PasswordChangeSession session = resolveSession(userId, rawSessionId);
        if (session.getStatus() != requiredStatus) {
            throw new ApiException(HttpStatus.BAD_REQUEST, wrongStateMessage);
        }
        return session;
    }

    /** Looks up the session and lazily transitions it to EXPIRED if its TTL has passed -- shared by
     *  loadActiveSession() (for verifyOtp()'s single required-status check) and complete() (which
     *  needs the raw session before deciding whether this is a fresh completion or an idempotent
     *  replay of an already-COMPLETED one). */
    private PasswordChangeSession resolveSession(UUID userId, String rawSessionId) {
        UUID sessionId;
        try {
            sessionId = UUID.fromString(rawSessionId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid password change session.");
        }
        PasswordChangeSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Invalid password change session."));

        // A COMPLETED session is a terminal state, never re-derived into EXPIRED just because
        // enough wall-clock time has since passed -- otherwise a genuinely delayed idempotent
        // retry of complete() (see complete()'s own doc comment) would lose the ability to return
        // its original outcome and instead see a confusing "session has expired" error for a
        // change that, in fact, already succeeded.
        boolean stillInProgress = session.getStatus() == PasswordChangeSession.Status.STARTED
                || session.getStatus() == PasswordChangeSession.Status.OTP_VERIFIED;
        if (stillInProgress && session.isExpired()) {
            session.setStatus(PasswordChangeSession.Status.EXPIRED);
            sessionRepository.save(session);
        }
        if (session.getStatus() == PasswordChangeSession.Status.EXPIRED) {
            auditService.record(userId, "SESSION_EXPIRED", "User", userId);
            throw new ApiException(HttpStatus.BAD_REQUEST, "This password change session has expired. Please start again.");
        }
        return session;
    }
}
