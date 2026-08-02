package com.finora.service;

import com.finora.dto.PasswordChangeDtos.*;
import com.finora.entity.PasswordChangeSession;
import com.finora.entity.PhoneOtp;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.PasswordChangeSessionRepository;
import com.finora.repository.UserRepository;
import com.finora.util.PhoneMasking;
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

    private static final long SESSION_TTL_MINUTES = 15;

    private final UserRepository userRepository;
    private final PasswordChangeSessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;

    public PasswordChangeService(UserRepository userRepository, PasswordChangeSessionRepository sessionRepository,
                                  PasswordEncoder passwordEncoder, OtpService otpService,
                                  RefreshTokenService refreshTokenService, AuditService auditService) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.otpService = otpService;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
    }

    /** Step 1: verify the current password, open a session, send an OTP to the phone on file.
     *  Atomic in this design -- there's no state where the password was verified but the session
     *  wasn't created, so PASSWORD_CHANGE_STARTED and CURRENT_PASSWORD_VERIFIED are both recorded
     *  here as two distinct facts about the same moment, not two separate opportunities to fail. */
    @Transactional
    public StartResponse start(UUID userId, StartRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            auditService.record(userId, "INVALID_CURRENT_PASSWORD", "User", userId);
            throw new ApiException(HttpStatus.BAD_REQUEST, "Current password is incorrect.");
        }
        if (user.getPhoneNumber() == null || user.getPhoneNumber().isBlank()) {
            // Shouldn't be reachable in practice -- phone number is required at registration --
            // but User.phoneNumber has no NOT NULL constraint at the DB level, same guard
            // AuthService.requestPasswordResetOtp already applies for the identical reason.
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This account has no phone number on file. Contact an administrator for help changing your password.");
        }

        Instant now = Instant.now();
        PasswordChangeSession session = new PasswordChangeSession();
        session.setUserId(userId);
        session.setStatus(PasswordChangeSession.Status.STARTED);
        session.setCurrentPasswordVerifiedAt(now);
        session.setExpiresAt(now.plusSeconds(SESSION_TTL_MINUTES * 60));
        session = sessionRepository.save(session);

        auditService.record(userId, "PASSWORD_CHANGE_STARTED", "User", userId);
        auditService.record(userId, "CURRENT_PASSWORD_VERIFIED", "User", userId);

        // OtpService.issueOtp already records its own purpose-tagged "PHONE_OTP_ISSUED" audit
        // entry -- no separate OTP_SENT event needed here, it would just duplicate that one.
        var otpResult = otpService.issueOtp(userId, user.getPhoneNumber(), PhoneOtp.Purpose.PASSWORD_CHANGE);

        return new StartResponse(session.getId().toString(), PhoneMasking.mask(user.getPhoneNumber()),
                otpResult.delivered() ? null : otpResult.otp());
    }

    /** Step 2: verify the OTP against this specific session. A wrong code returns
     *  {verified: false} (mirroring VerifyOtpResponse's existing convention elsewhere) rather than
     *  throwing, so the frontend can show an inline retry instead of a hard failure. */
    @Transactional
    public VerifyOtpResponse verifyOtp(UUID userId, VerifyOtpRequest request) {
        PasswordChangeSession session = loadActiveSession(userId, request.sessionId(), PasswordChangeSession.Status.STARTED,
                "This step has already been completed, or the session is no longer valid. Please start again.");

        boolean verified = otpService.verifyOtp(userId, request.otp(), PhoneOtp.Purpose.PASSWORD_CHANGE);
        if (!verified) {
            auditService.record(userId, "INVALID_OTP", "User", userId);
            return new VerifyOtpResponse(false, "That code doesn't match — check and try again.");
        }

        session.setOtpVerifiedAt(Instant.now());
        session.setStatus(PasswordChangeSession.Status.OTP_VERIFIED);
        sessionRepository.save(session);

        // OtpService.verifyOtp already records its own purpose-tagged "OTP_VERIFIED" audit entry
        // on success -- nothing further needed here.
        return new VerifyOtpResponse(true, "Verified.");
    }

    /** Step 3: set the new password. Only reachable once the session itself shows OTP_VERIFIED --
     *  server-side state, not a client-supplied flag. The device making this request always stays
     *  signed in (see CompleteRequest.currentRefreshToken's own doc comment); signOutOtherDevices
     *  only controls every OTHER active session. */
    @Transactional
    public CompleteResponse complete(UUID userId, CompleteRequest request) {
        PasswordChangeSession session = loadActiveSession(userId, request.sessionId(), PasswordChangeSession.Status.OTP_VERIFIED,
                "Verify the code sent to your phone before completing this change.");

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));

        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "New password must be different from your current password.");
        }

        Instant now = Instant.now();
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setUpdatedAt(now);
        user.setPasswordChangedAt(now);
        userRepository.save(user);

        session.setStatus(PasswordChangeSession.Status.COMPLETED);
        session.setCompletedAt(now);
        sessionRepository.save(session);

        String message;
        if (request.signOutOtherDevices()) {
            refreshTokenService.revokeAllOtherSessionsForUser(userId, request.currentRefreshToken());
            auditService.record(userId, "OTHER_SESSIONS_REVOKED", "User", userId);
            message = "Your password has been updated. This device stays signed in; every other device has been signed out.";
        } else {
            message = "Your password has been updated. All your devices, including this one, remain signed in.";
        }

        auditService.record(userId, "PASSWORD_CHANGED", "User", userId, Map.of("method", "authenticated_settings_otp_gated"));

        return new CompleteResponse(message, request.signOutOtherDevices());
    }

    /** Shared by verifyOtp() and complete() -- both need the same "does this session belong to
     *  this user, is it in the right state, and has it expired" checks. requiredStatus is what
     *  the session must ALREADY be in for the step being attempted to make sense (e.g. complete()
     *  requires OTP_VERIFIED, the state verifyOtp() itself just set). */
    private PasswordChangeSession loadActiveSession(UUID userId, String rawSessionId,
                                                      PasswordChangeSession.Status requiredStatus, String wrongStateMessage) {
        UUID sessionId;
        try {
            sessionId = UUID.fromString(rawSessionId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid password change session.");
        }
        PasswordChangeSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Invalid password change session."));

        if (session.isExpired() && session.getStatus() != PasswordChangeSession.Status.EXPIRED) {
            session.setStatus(PasswordChangeSession.Status.EXPIRED);
            sessionRepository.save(session);
        }
        if (session.getStatus() == PasswordChangeSession.Status.EXPIRED) {
            auditService.record(userId, "SESSION_EXPIRED", "User", userId);
            throw new ApiException(HttpStatus.BAD_REQUEST, "This password change session has expired. Please start again.");
        }
        if (session.getStatus() != requiredStatus) {
            throw new ApiException(HttpStatus.BAD_REQUEST, wrongStateMessage);
        }
        return session;
    }
}
