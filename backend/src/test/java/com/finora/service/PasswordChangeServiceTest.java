package com.finora.service;

import com.finora.dto.PasswordChangeDtos.*;
import com.finora.entity.PasswordChangeSession;
import com.finora.entity.PhoneOtp;
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
 * The OTP-gated, session-based Change Password flow (start -> verify-otp -> complete) --
 * replaces the older single-step version (see git history for AuthServiceChangePasswordTest,
 * removed alongside AuthService.changePassword()). Each step's guard against being run out of
 * order / against another user's session / after expiry is exercised directly, since that's the
 * whole point of persisting server-side session state instead of trusting the request body.
 */
class PasswordChangeServiceTest {

    private UserRepository userRepository;
    private PasswordChangeSessionRepository sessionRepository;
    private PasswordEncoder passwordEncoder;
    private OtpService otpService;
    private RefreshTokenService refreshTokenService;
    private AuditService auditService;
    private PasswordChangeService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        sessionRepository = mock(PasswordChangeSessionRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        otpService = mock(OtpService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        auditService = mock(AuditService.class);

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

        service = new PasswordChangeService(userRepository, sessionRepository, passwordEncoder, otpService,
                refreshTokenService, auditService);
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
    void start_withCorrectCurrentPassword_createsASessionAndIssuesAPasswordChangeOtp() {
        User user = existingUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPass123!", "hashed-old-password")).thenReturn(true);
        when(otpService.issueOtp(userId, "+919876543210", PhoneOtp.Purpose.PASSWORD_CHANGE))
                .thenReturn(new OtpService.OtpIssueResult("111222", true));

        var response = service.start(userId, new StartRequest("OldPass123!"));

        assertThat(response.sessionId()).isNotBlank();
        assertThat(response.devOtp()).isNull(); // delivered=true -- never echoed back
        assertThat(response.maskedPhone()).isNotBlank();
        verify(auditService).record(userId, "PASSWORD_CHANGE_STARTED", "User", userId);
        verify(auditService).record(userId, "CURRENT_PASSWORD_VERIFIED", "User", userId);
    }

    @Test
    void start_withWrongCurrentPassword_rejectsAndRecordsAnAuditEvent_withoutIssuingAnyOtp() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser()));
        when(passwordEncoder.matches("WrongPassword", "hashed-old-password")).thenReturn(false);

        assertThatThrownBy(() -> service.start(userId, new StartRequest("WrongPassword")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Current password is incorrect");

        verify(auditService).record(userId, "INVALID_CURRENT_PASSWORD", "User", userId);
        verify(otpService, never()).issueOtp(any(), any(), any());
        verify(sessionRepository, never()).save(any());
    }

    // --- verifyOtp() ---

    @Test
    void verifyOtp_withCorrectCode_advancesTheSessionToOtpVerified() {
        PasswordChangeSession session = sessionWith(PasswordChangeSession.Status.STARTED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        when(otpService.verifyOtp(userId, "654321", PhoneOtp.Purpose.PASSWORD_CHANGE)).thenReturn(true);

        var response = service.verifyOtp(userId, new VerifyOtpRequest(session.getId().toString(), "654321"));

        assertThat(response.verified()).isTrue();
        assertThat(session.getStatus()).isEqualTo(PasswordChangeSession.Status.OTP_VERIFIED);
        assertThat(session.getOtpVerifiedAt()).isNotNull();
    }

    @Test
    void verifyOtp_withWrongCode_returnsUnverifiedWithoutThrowing_andLeavesSessionInStarted() {
        PasswordChangeSession session = sessionWith(PasswordChangeSession.Status.STARTED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        when(otpService.verifyOtp(userId, "000000", PhoneOtp.Purpose.PASSWORD_CHANGE)).thenReturn(false);

        var response = service.verifyOtp(userId, new VerifyOtpRequest(session.getId().toString(), "000000"));

        assertThat(response.verified()).isFalse();
        assertThat(session.getStatus()).isEqualTo(PasswordChangeSession.Status.STARTED);
        verify(auditService).record(userId, "INVALID_OTP", "User", userId);
    }

    @Test
    void verifyOtp_onASessionThatAlreadyCompletedThisStep_rejectsWithoutCallingOtpServiceAgain() {
        PasswordChangeSession session = sessionWith(PasswordChangeSession.Status.OTP_VERIFIED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.verifyOtp(userId, new VerifyOtpRequest(session.getId().toString(), "654321")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been completed");
        verify(otpService, never()).verifyOtp(any(), any(), any());
    }

    @Test
    void verifyOtp_onAnExpiredSession_rejectsAndMarksTheSessionExpired() {
        PasswordChangeSession session = sessionWith(PasswordChangeSession.Status.STARTED, Instant.now().minusSeconds(60));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.verifyOtp(userId, new VerifyOtpRequest(session.getId().toString(), "654321")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");

        assertThat(session.getStatus()).isEqualTo(PasswordChangeSession.Status.EXPIRED);
        verify(auditService).record(userId, "SESSION_EXPIRED", "User", userId);
    }

    @Test
    void verifyOtp_onAnUnknownOrForeignSessionId_rejectsCleanly() {
        when(sessionRepository.findByIdAndUserId(any(), eq(userId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyOtp(userId, new VerifyOtpRequest(UUID.randomUUID().toString(), "654321")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid password change session");
    }

    @Test
    void verifyOtp_withAMalformedSessionId_rejectsCleanlyInsteadOfThrowingAnUnhandledException() {
        assertThatThrownBy(() -> service.verifyOtp(userId, new VerifyOtpRequest("not-a-uuid", "654321")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid password change session");
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
                new CompleteRequest(session.getId().toString(), "NewPass456!", false, "this-devices-refresh-token"));

        assertThat(user.getPasswordHash()).isEqualTo("hashed-new-password");
        assertThat(user.getPasswordChangedAt()).isNotNull();
        assertThat(session.getStatus()).isEqualTo(PasswordChangeSession.Status.COMPLETED);
        assertThat(response.otherDevicesSignedOut()).isFalse();
        verify(refreshTokenService, never()).revokeAllOtherSessionsForUser(any(), any());
        verify(auditService, never()).record(any(), eq("OTHER_SESSIONS_REVOKED"), any(), any());
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
                new CompleteRequest(session.getId().toString(), "NewPass456!", true, "this-devices-refresh-token"));

        assertThat(response.otherDevicesSignedOut()).isTrue();
        verify(refreshTokenService).revokeAllOtherSessionsForUser(userId, "this-devices-refresh-token");
        verify(auditService).record(userId, "OTHER_SESSIONS_REVOKED", "User", userId);
    }

    @Test
    void complete_rejectsANewPasswordIdenticalToTheCurrentOne() {
        PasswordChangeSession session = sessionWith(PasswordChangeSession.Status.OTP_VERIFIED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        User user = existingUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("SamePass123!", "hashed-old-password")).thenReturn(true);

        assertThatThrownBy(() -> service.complete(userId,
                new CompleteRequest(session.getId().toString(), "SamePass123!", false, "token")))
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
                new CompleteRequest(session.getId().toString(), "NewPass456!", false, "token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Verify the code");

        verify(userRepository, never()).save(any());
    }

    @Test
    void complete_onASessionAlreadyCompleted_rejectsAsReplayProtection() {
        PasswordChangeSession session = sessionWith(PasswordChangeSession.Status.COMPLETED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.complete(userId,
                new CompleteRequest(session.getId().toString(), "NewPass456!", false, "token")))
                .isInstanceOf(ApiException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void complete_onAnExpiredSession_rejectsAndMarksItExpired() {
        PasswordChangeSession session = sessionWith(PasswordChangeSession.Status.OTP_VERIFIED, Instant.now().minusSeconds(60));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.complete(userId,
                new CompleteRequest(session.getId().toString(), "NewPass456!", false, "token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");

        assertThat(session.getStatus()).isEqualTo(PasswordChangeSession.Status.EXPIRED);
    }

    @Test
    void verifyOtp_onAnotherUsersSessionId_isNotFound_sinceTheLookupIsScopedToTheCallingUser() {
        UUID otherUsersSessionId = UUID.randomUUID();
        when(sessionRepository.findByIdAndUserId(otherUsersSessionId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyOtp(userId, new VerifyOtpRequest(otherUsersSessionId.toString(), "654321")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid password change session");
    }
}
