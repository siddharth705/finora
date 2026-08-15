package com.finora.service;

import com.finora.dto.PasswordChangeDtos.*;
import com.finora.entity.PasswordChangeSession;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.PasswordChangeSessionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The OTP-gated, session-based Change Password flow (start -> verify-otp -> complete). OTP
 * verification itself is Firebase Phone Authentication (see PhoneVerificationProvider) --
 * the frontend's own Firebase client SDK sends and confirms the code directly against Firebase;
 * this service only ever sees the resulting ID token. Each step's guard against being run out of
 * order / against another user's session / after expiry is exercised directly, since that's the
 * whole point of persisting server-side session state instead of trusting the request body.
 */
class PasswordChangeServiceTest {

    private UserRepository userRepository;
    private PasswordChangeSessionRepository sessionRepository;
    private PasswordEncoder passwordEncoder;
    private PhoneVerificationProvider phoneVerificationProvider;
    private RefreshTokenService refreshTokenService;
    private AuditService auditService;
    private EmailProvider emailProvider;
    private PasswordChangeService service;
    private final UUID userId = UUID.randomUUID();

    /** The session the request is coming FROM -- the sid claim on the caller's access token. What
     *  "sign out my other devices" has to spare, and what it used to identify by asking the client
     *  to hand back a refresh token it can no longer read (BH-012). */
    private static final UUID THIS_DEVICES_SESSION = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        sessionRepository = mock(PasswordChangeSessionRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        phoneVerificationProvider = mock(PhoneVerificationProvider.class);
        refreshTokenService = mock(RefreshTokenService.class);
        auditService = mock(AuditService.class);
        // complete()'s success path calls emailProvider.sendPasswordChangedEmail(...) and now
        // immediately dereferences the EmailResult it returns -- an unstubbed mock returns null,
        // which would NPE both complete() success tests below.
        emailProvider = mock(EmailProvider.class);
        when(emailProvider.sendPasswordChangedEmail(any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "test-message-id"));

        // Mirrors real JPA behavior: a save() assigns the generated id the first time a
        // never-persisted (id == null) entity is saved.
        when(sessionRepository.save(any(PasswordChangeSession.class))).thenAnswer(inv -> {
            PasswordChangeSession s = inv.getArgument(0);
            if (s.getId() == null) {
                ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
            }
            return s;
        });
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service = new PasswordChangeService(userRepository, sessionRepository, passwordEncoder,
                phoneVerificationProvider, refreshTokenService, auditService, emailProvider,
                mock(PasswordHistoryService.class));
    }

    private User existingUser() {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", userId);
        u.setPasswordHash("hashed-old-password");
        u.setPhoneNumber("+919876543210");
        return u;
    }

    private PasswordChangeSession sessionWith(PasswordChangeSession.Status status, Instant expiresAt) {
        PasswordChangeSession session = new PasswordChangeSession();
        ReflectionTestUtils.setField(session, "id", UUID.randomUUID());
        session.setUserId(userId);
        session.setStatus(status);
        session.setCurrentPasswordVerifiedAt(Instant.now());
        session.setExpiresAt(expiresAt);
        if (status == PasswordChangeSession.Status.OTP_VERIFIED || status == PasswordChangeSession.Status.COMPLETED) {
            session.setOtpVerifiedAt(Instant.now());
        }
        return session;
    }

    // --- start() ---

    @Test
    void start_withCorrectCurrentPassword_createsASessionAndReturnsTheRealPhoneNumberForFirebase() {
        User user = existingUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPass123!", "hashed-old-password")).thenReturn(true);

        var response = service.start(userId, new StartRequest("OldPass123!"));

        assertThat(response.sessionId()).isNotBlank();
        assertThat(response.phoneNumber()).isEqualTo("+919876543210");
        assertThat(response.maskedPhone()).isNotBlank();
        verify(auditService).record(userId, "PASSWORD_CHANGE_STARTED", "User", userId);
        verify(auditService).record(userId, "CURRENT_PASSWORD_VERIFIED", "User", userId);
    }

    @Test
    void start_withWrongCurrentPassword_rejectsAndRecordsAnAuditEvent() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser()));
        when(passwordEncoder.matches("WrongPassword", "hashed-old-password")).thenReturn(false);

        assertThatThrownBy(() -> service.start(userId, new StartRequest("WrongPassword")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Current password is incorrect");

        verify(auditService).record(userId, "INVALID_CURRENT_PASSWORD", "User", userId);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void start_onASuspendedAccount_isRejectedBeforeEvenCheckingThePassword() {
        User user = existingUser();
        user.setStatus("SUSPENDED");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.start(userId, new StartRequest("OldPass123!")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("suspended");

        verify(passwordEncoder, never()).matches(any(), any());
        verify(sessionRepository, never()).save(any());
    }

    // --- verifyOtp() ---

    @Test
    void verifyOtp_withAMatchingFirebaseToken_advancesTheSessionToOtpVerified() {
        PasswordChangeSession session = sessionWith(PasswordChangeSession.Status.STARTED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser()));
        when(phoneVerificationProvider.verifyAndGetPhoneNumber("valid-firebase-token")).thenReturn("+919876543210");

        var response = service.verifyOtp(userId, new VerifyOtpRequest(session.getId().toString(), "valid-firebase-token"));

        assertThat(response.message()).isNotBlank();
        assertThat(session.getStatus()).isEqualTo(PasswordChangeSession.Status.OTP_VERIFIED);
        assertThat(session.getOtpVerifiedAt()).isNotNull();
        assertThat(session.getVerificationProvider()).isEqualTo(ProviderType.FIREBASE);
        assertThat(session.getVerifiedPhoneNumber()).isEqualTo("+919876543210");
        verify(auditService).record(userId, "FIREBASE_PHONE_VERIFIED", "User", userId);
    }

    @Test
    void verifyOtp_withATokenForAMismatchedPhoneNumber_throwsAndLeavesSessionInStarted() {
        PasswordChangeSession session = sessionWith(PasswordChangeSession.Status.STARTED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser()));
        when(phoneVerificationProvider.verifyAndGetPhoneNumber("someone-elses-token")).thenReturn("+911111111111");

        assertThatThrownBy(() -> service.verifyOtp(userId, new VerifyOtpRequest(session.getId().toString(), "someone-elses-token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("doesn't match");

        assertThat(session.getStatus()).isEqualTo(PasswordChangeSession.Status.STARTED);
        verify(auditService).record(userId, "INVALID_OTP", "User", userId);
    }

    @Test
    void verifyOtp_onASessionThatAlreadyCompletedThisStep_rejectsWithoutCallingFirebaseAgain() {
        PasswordChangeSession session = sessionWith(PasswordChangeSession.Status.OTP_VERIFIED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.verifyOtp(userId, new VerifyOtpRequest(session.getId().toString(), "some-token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been completed");
        verify(phoneVerificationProvider, never()).verifyAndGetPhoneNumber(any());
    }

    @Test
    void verifyOtp_onAnExpiredSession_rejectsAndMarksTheSessionExpired() {
        PasswordChangeSession session = sessionWith(PasswordChangeSession.Status.STARTED, Instant.now().minusSeconds(60));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.verifyOtp(userId, new VerifyOtpRequest(session.getId().toString(), "some-token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");

        assertThat(session.getStatus()).isEqualTo(PasswordChangeSession.Status.EXPIRED);
        verify(auditService).record(userId, "SESSION_EXPIRED", "User", userId);
    }

    @Test
    void verifyOtp_onAnUnknownOrForeignSessionId_rejectsCleanly() {
        when(sessionRepository.findByIdAndUserId(any(), eq(userId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyOtp(userId, new VerifyOtpRequest(UUID.randomUUID().toString(), "some-token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid password change session");
    }

    @Test
    void verifyOtp_withAMalformedSessionId_rejectsCleanlyInsteadOfThrowingAnUnhandledException() {
        assertThatThrownBy(() -> service.verifyOtp(userId, new VerifyOtpRequest("not-a-uuid", "some-token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid password change session");
    }

    /** Regression test: only start() used to re-check account status, so a session opened while
     *  ACTIVE could still advance through verifyOtp() (and on to complete()) after the account was
     *  suspended or deactivated mid-flow -- the still-valid access token that opened the session
     *  keeps working for up to 15 minutes past the status change, same reason start() checks this
     *  at all. */
    @Test
    void verifyOtp_onASuspendedAccount_isRejectedBeforeCallingFirebase() {
        PasswordChangeSession session = sessionWith(PasswordChangeSession.Status.STARTED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        User user = existingUser();
        user.setStatus("SUSPENDED");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.verifyOtp(userId, new VerifyOtpRequest(session.getId().toString(), "some-token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("suspended");

        verify(phoneVerificationProvider, never()).verifyAndGetPhoneNumber(any());
        assertThat(session.getStatus()).isEqualTo(PasswordChangeSession.Status.STARTED);
    }

    @Test
    void verifyOtp_onADeactivatedAccount_isRejectedBeforeCallingFirebase() {
        PasswordChangeSession session = sessionWith(PasswordChangeSession.Status.STARTED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        User user = existingUser();
        user.setStatus(User.STATUS_DEACTIVATED);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.verifyOtp(userId, new VerifyOtpRequest(session.getId().toString(), "some-token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("deactivated");

        verify(phoneVerificationProvider, never()).verifyAndGetPhoneNumber(any());
    }

    // --- complete() ---

    @Test
    void complete_afterOtpVerified_updatesThePasswordAndKeepsThisDeviceSignedIn_whenSignOutOtherDevicesIsFalse() {
        PasswordChangeSession session = sessionWith(PasswordChangeSession.Status.OTP_VERIFIED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        User user = existingUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("NewPass456!", "hashed-old-password")).thenReturn(false);
        when(passwordEncoder.encode("NewPass456!")).thenReturn("hashed-new-password");

        var response = service.complete(userId,
                new CompleteRequest(session.getId().toString(), "NewPass456!", false, null),
                THIS_DEVICES_SESSION);

        assertThat(user.getPasswordHash()).isEqualTo("hashed-new-password");
        assertThat(user.getPasswordChangedAt()).isNotNull();
        assertThat(session.getStatus()).isEqualTo(PasswordChangeSession.Status.COMPLETED);
        assertThat(session.getSignedOutOtherDevices()).isFalse();
        assertThat(response.otherDevicesSignedOut()).isFalse();
        verify(refreshTokenService, never()).revokeAllOtherSessionsForUser(any(), any());
        verify(auditService, never()).record(any(), eq("OTHER_SESSIONS_REVOKED"), any(), any());
        verify(auditService).record(userId, "OTHER_SESSIONS_PRESERVED", "User", userId);
        verify(auditService).record(eq(userId), eq("EMAIL_SENT"), eq("User"), eq(userId),
                argThat(metadata -> "password_changed".equals(metadata.get("type")) && Boolean.TRUE.equals(metadata.get("success"))));
    }

    @Test
    void complete_withSignOutOtherDevicesTrue_revokesOnlyOtherSessions_keepingThisDevicesTokenAlive() {
        PasswordChangeSession session = sessionWith(PasswordChangeSession.Status.OTP_VERIFIED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        User user = existingUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), eq("hashed-old-password"))).thenReturn(false);
        when(passwordEncoder.encode("NewPass456!")).thenReturn("hashed-new-password");

        var response = service.complete(userId,
                new CompleteRequest(session.getId().toString(), "NewPass456!", true, null),
                THIS_DEVICES_SESSION);

        assertThat(response.otherDevicesSignedOut()).isTrue();
        assertThat(session.getSignedOutOtherDevices()).isTrue();
        // BH-012: keyed on the session making the request (the access token's sid claim), not on a
        // refresh token the client had to be able to read out of localStorage.
        verify(refreshTokenService).revokeAllOtherSessionsForUser(userId, THIS_DEVICES_SESSION);
        verify(auditService).record(userId, "OTHER_SESSIONS_REVOKED", "User", userId);
        verify(auditService, never()).record(any(), eq("OTHER_SESSIONS_PRESERVED"), any(), any());
    }

    @Test
    void complete_rejectsANewPasswordIdenticalToTheCurrentOne() {
        PasswordChangeSession session = sessionWith(PasswordChangeSession.Status.OTP_VERIFIED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        User user = existingUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("SamePass123!", "hashed-old-password")).thenReturn(true);

        assertThatThrownBy(() -> service.complete(userId,
                new CompleteRequest(session.getId().toString(), "SamePass123!", false, null),
                THIS_DEVICES_SESSION))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("different from your current password");

        assertThat(user.getPasswordHash()).isEqualTo("hashed-old-password");
        assertThat(session.getStatus()).isEqualTo(PasswordChangeSession.Status.OTP_VERIFIED);
    }

    @Test
    void complete_beforeOtpHasBeenVerified_rejectsRegardlessOfWhatTheRequestClaims() {
        // Still in STARTED -- otpVerifiedAt was never set server-side, no matter what the
        // frontend thinks happened.
        PasswordChangeSession session = sessionWith(PasswordChangeSession.Status.STARTED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.complete(userId,
                new CompleteRequest(session.getId().toString(), "NewPass456!", false, null),
                THIS_DEVICES_SESSION))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Verify the code");

        verify(userRepository, never()).save(any());
    }

    /** Idempotency, not replay rejection: a session already COMPLETED (the frontend retried after
     *  a timeout/network hiccup without ever seeing the first response) must return the SAME
     *  outcome it returned the first time -- not throw, and not re-run the password update or
     *  session-revocation side effects a second time. */
    @Test
    void complete_onASessionAlreadyCompleted_returnsTheOriginalOutcomeInsteadOfRepeatingTheSideEffects() {
        PasswordChangeSession session = sessionWith(PasswordChangeSession.Status.COMPLETED, Instant.now().plusSeconds(600));
        session.setSignedOutOtherDevices(true);
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));

        var response = service.complete(userId,
                new CompleteRequest(session.getId().toString(), "NewPass456!", false, null),
                THIS_DEVICES_SESSION);

        assertThat(response.otherDevicesSignedOut()).isTrue();
        assertThat(response.message()).contains("every other device has been signed out");
        verify(userRepository, never()).save(any());
        verify(refreshTokenService, never()).revokeAllOtherSessionsForUser(any(), any());
        verify(auditService, never()).record(any(), eq("PASSWORD_CHANGED"), any(), any(), any());
    }

    /** Same regression as verifyOtp_onASuspendedAccount_... -- complete() is the step that
     *  actually writes the new password, so this is the more consequential half of the gap. */
    @Test
    void complete_onASuspendedAccount_isRejectedWithoutWritingTheNewPassword() {
        PasswordChangeSession session = sessionWith(PasswordChangeSession.Status.OTP_VERIFIED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        User user = existingUser();
        user.setStatus("SUSPENDED");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.complete(userId,
                new CompleteRequest(session.getId().toString(), "NewPass456!", false, null),
                THIS_DEVICES_SESSION))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("suspended");

        assertThat(user.getPasswordHash()).isEqualTo("hashed-old-password");
        assertThat(session.getStatus()).isEqualTo(PasswordChangeSession.Status.OTP_VERIFIED);
        verify(userRepository, never()).save(any());
    }

    @Test
    void complete_onADeactivatedAccount_isRejectedWithoutWritingTheNewPassword() {
        PasswordChangeSession session = sessionWith(PasswordChangeSession.Status.OTP_VERIFIED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        User user = existingUser();
        user.setStatus(User.STATUS_DEACTIVATED);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.complete(userId,
                new CompleteRequest(session.getId().toString(), "NewPass456!", false, null),
                THIS_DEVICES_SESSION))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("deactivated");

        assertThat(user.getPasswordHash()).isEqualTo("hashed-old-password");
        verify(userRepository, never()).save(any());
    }

    /** The status check must NOT reach far enough to break the existing idempotent-replay
     *  guarantee: a session that already succeeded has to keep returning its original outcome even
     *  if the account's status changed afterward (e.g. the user deactivated their own account
     *  right after changing their password) -- there is nothing left to gate. */
    @Test
    void complete_onASessionAlreadyCompleted_returnsTheOriginalOutcome_evenIfTheAccountIsNowDeactivated() {
        PasswordChangeSession session = sessionWith(PasswordChangeSession.Status.COMPLETED, Instant.now().plusSeconds(600));
        session.setSignedOutOtherDevices(true);
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));

        var response = service.complete(userId,
                new CompleteRequest(session.getId().toString(), "NewPass456!", false, null),
                THIS_DEVICES_SESSION);

        assertThat(response.otherDevicesSignedOut()).isTrue();
        verify(userRepository, never()).findById(any());
    }

    @Test
    void complete_onAnExpiredSession_rejectsAndMarksItExpired() {
        PasswordChangeSession session = sessionWith(PasswordChangeSession.Status.OTP_VERIFIED, Instant.now().minusSeconds(60));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.complete(userId,
                new CompleteRequest(session.getId().toString(), "NewPass456!", false, null),
                THIS_DEVICES_SESSION))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");

        assertThat(session.getStatus()).isEqualTo(PasswordChangeSession.Status.EXPIRED);
    }

    @Test
    void verifyOtp_onAnotherUsersSessionId_isNotFound_sinceTheLookupIsScopedToTheCallingUser() {
        UUID otherUsersSessionId = UUID.randomUUID();
        when(sessionRepository.findByIdAndUserId(otherUsersSessionId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyOtp(userId, new VerifyOtpRequest(otherUsersSessionId.toString(), "some-token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid password change session");
    }
}
