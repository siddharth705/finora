package com.finora.service;

import com.finora.dto.PhoneChangeDtos.*;
import com.finora.entity.PhoneChangeSession;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.PhoneChangeSessionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The OTP-gated, session-based Change Phone Number flow (start -> verify-otp -> complete).
 * Mirrors PasswordChangeServiceTest's breadth and structure -- same session-state-machine
 * discipline, same account-status guard at every step -- with one substantive difference this
 * flow's own tests have to cover that PasswordChangeServiceTest does not: the Firebase token in
 * verifyOtp() must match the REQUESTED (new) number, not the account's existing one.
 */
class PhoneChangeServiceTest {

    private UserRepository userRepository;
    private PhoneChangeSessionRepository sessionRepository;
    private PhoneVerificationProvider phoneVerificationProvider;
    private AuditService auditService;
    private PhoneChangeService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        sessionRepository = mock(PhoneChangeSessionRepository.class);
        phoneVerificationProvider = mock(PhoneVerificationProvider.class);
        auditService = mock(AuditService.class);

        // Mirrors real JPA behavior: a save() assigns the generated id the first time a
        // never-persisted (id == null) entity is saved.
        when(sessionRepository.save(any(PhoneChangeSession.class))).thenAnswer(inv -> {
            PhoneChangeSession s = inv.getArgument(0);
            if (s.getId() == null) {
                ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
            }
            return s;
        });
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service = new PhoneChangeService(userRepository, sessionRepository, phoneVerificationProvider, auditService);
    }

    private User existingUser() {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", userId);
        u.setPhoneNumber("+919888888888");
        u.setPhoneVerified(false);
        return u;
    }

    private PhoneChangeSession sessionWith(PhoneChangeSession.Status status, Instant expiresAt) {
        PhoneChangeSession session = new PhoneChangeSession();
        ReflectionTestUtils.setField(session, "id", UUID.randomUUID());
        session.setUserId(userId);
        session.setStatus(status);
        session.setCurrentPhoneNumber("+919888888888");
        session.setRequestedPhoneNumber("+919999999999");
        session.setExpiresAt(expiresAt);
        if (status == PhoneChangeSession.Status.OTP_VERIFIED || status == PhoneChangeSession.Status.COMPLETED) {
            session.setOtpVerifiedAt(Instant.now());
        }
        return session;
    }

    // --- start() ---

    @Test
    void start_withANewUnclaimedNumber_createsASessionAndReturnsAMaskedPhone() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser()));
        when(userRepository.existsByPhoneNumberAndAccountScope(eq("+919999999999"), any())).thenReturn(false);

        var response = service.start(userId, new StartRequest("+919999999999"));

        assertThat(response.sessionId()).isNotBlank();
        assertThat(response.maskedPhone()).isNotBlank().doesNotContain("9999999999");
        verify(auditService).record(userId, "PHONE_CHANGE_STARTED", "User", userId);
    }

    @Test
    void start_normalizesABareTenDigitNumberBeforeCheckingUniquenessAndSavingTheSession() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser()));
        when(userRepository.existsByPhoneNumberAndAccountScope(eq("+919999999999"), any())).thenReturn(false);

        service.start(userId, new StartRequest("9999999999"));

        verify(userRepository).existsByPhoneNumberAndAccountScope(eq("+919999999999"), any());
        verify(sessionRepository).save(argThat(s -> "+919999999999".equals(s.getRequestedPhoneNumber())));
    }

    @Test
    void start_withTheSameNumberAlreadyOnTheAccount_isRejectedAsANoOp() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser()));

        assertThatThrownBy(() -> service.start(userId, new StartRequest("+919888888888")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already the number on your account");

        verify(userRepository, never()).existsByPhoneNumberAndAccountScope(any(), any());
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void start_withANumberAlreadyClaimedByAnotherAccountInScope_rejectsWithConflictAndAudits() {
        User user = existingUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhoneNumberAndAccountScope("+919999999999", user.getAccountScope())).thenReturn(true);

        assertThatThrownBy(() -> service.start(userId, new StartRequest("+919999999999")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already exists");

        verify(auditService).record(userId, "PHONE_CHANGE_REJECTED_DUPLICATE", "User", userId);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void start_onASuspendedAccount_isRejectedBeforeCheckingUniqueness() {
        User user = existingUser();
        user.setStatus(User.STATUS_SUSPENDED);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.start(userId, new StartRequest("+919999999999")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("suspended");

        verify(userRepository, never()).existsByPhoneNumberAndAccountScope(any(), any());
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void start_onADeactivatedAccount_isRejected() {
        User user = existingUser();
        user.setStatus(User.STATUS_DEACTIVATED);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.start(userId, new StartRequest("+919999999999")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("deactivated");

        verify(sessionRepository, never()).save(any());
    }

    @Test
    void start_onAPendingDeletionAccount_isRejected() {
        User user = existingUser();
        user.setStatus(User.STATUS_PENDING_DELETION);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.start(userId, new StartRequest("+919999999999")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("scheduled for deletion");
    }

    /** Defensive: User.phoneNumber has no NOT NULL constraint at the DB level, but
     *  PhoneChangeSession.currentPhoneNumber does -- without this guard, a null here would
     *  surface as an opaque DB-constraint 409 instead of a message naming the actual problem. */
    @Test
    void start_onAnAccountWithNoPhoneNumberOnFile_isRejectedWithAClearMessage() {
        User user = existingUser();
        user.setPhoneNumber(null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.start(userId, new StartRequest("+919999999999")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no phone number on file");

        verify(sessionRepository, never()).save(any());
    }

    // --- verifyOtp() ---

    @Test
    void verifyOtp_withATokenForTheRequestedNumber_advancesTheSessionToOtpVerified() {
        PhoneChangeSession session = sessionWith(PhoneChangeSession.Status.STARTED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser()));
        when(phoneVerificationProvider.verifyAndGetPhoneNumber("valid-firebase-token")).thenReturn("+919999999999");

        var response = service.verifyOtp(userId, new VerifyOtpRequest(session.getId().toString(), "valid-firebase-token"));

        assertThat(response.message()).isNotBlank();
        assertThat(session.getStatus()).isEqualTo(PhoneChangeSession.Status.OTP_VERIFIED);
        assertThat(session.getOtpVerifiedAt()).isNotNull();
        assertThat(session.getVerificationProvider()).isEqualTo(ProviderType.FIREBASE);
        assertThat(session.getVerifiedPhoneNumber()).isEqualTo("+919999999999");
        verify(auditService).record(userId, "PHONE_CHANGE_OTP_VERIFIED", "User", userId);
    }

    /** The one substantive difference from PasswordChangeService.verifyOtp(): a token proving the
     *  account's EXISTING number (not the requested one) must still be rejected here -- proving you
     *  still control the old number says nothing about whether you control the new one. */
    @Test
    void verifyOtp_withATokenForTheOldNumberInsteadOfTheRequestedOne_isRejected() {
        PhoneChangeSession session = sessionWith(PhoneChangeSession.Status.STARTED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser()));
        when(phoneVerificationProvider.verifyAndGetPhoneNumber("old-number-token")).thenReturn("+919888888888");

        assertThatThrownBy(() -> service.verifyOtp(userId, new VerifyOtpRequest(session.getId().toString(), "old-number-token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("doesn't match");

        assertThat(session.getStatus()).isEqualTo(PhoneChangeSession.Status.STARTED);
        verify(auditService).record(userId, "INVALID_OTP", "User", userId);
    }

    @Test
    void verifyOtp_onASessionThatAlreadyCompletedThisStep_rejectsWithoutCallingFirebaseAgain() {
        PhoneChangeSession session = sessionWith(PhoneChangeSession.Status.OTP_VERIFIED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.verifyOtp(userId, new VerifyOtpRequest(session.getId().toString(), "some-token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been completed");
        verify(phoneVerificationProvider, never()).verifyAndGetPhoneNumber(any());
    }

    @Test
    void verifyOtp_onAnExpiredSession_rejectsAndMarksTheSessionExpired() {
        PhoneChangeSession session = sessionWith(PhoneChangeSession.Status.STARTED, Instant.now().minusSeconds(60));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.verifyOtp(userId, new VerifyOtpRequest(session.getId().toString(), "some-token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");

        assertThat(session.getStatus()).isEqualTo(PhoneChangeSession.Status.EXPIRED);
        verify(auditService).record(userId, "SESSION_EXPIRED", "User", userId);
    }

    @Test
    void verifyOtp_onAnUnknownOrForeignSessionId_rejectsCleanly() {
        when(sessionRepository.findByIdAndUserId(any(), eq(userId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyOtp(userId, new VerifyOtpRequest(UUID.randomUUID().toString(), "some-token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid phone number change session");
    }

    @Test
    void verifyOtp_withAMalformedSessionId_rejectsCleanlyInsteadOfThrowingAnUnhandledException() {
        assertThatThrownBy(() -> service.verifyOtp(userId, new VerifyOtpRequest("not-a-uuid", "some-token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid phone number change session");
    }

    @Test
    void verifyOtp_onASuspendedAccount_isRejectedBeforeCallingFirebase() {
        PhoneChangeSession session = sessionWith(PhoneChangeSession.Status.STARTED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        User user = existingUser();
        user.setStatus(User.STATUS_SUSPENDED);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.verifyOtp(userId, new VerifyOtpRequest(session.getId().toString(), "some-token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("suspended");

        verify(phoneVerificationProvider, never()).verifyAndGetPhoneNumber(any());
        assertThat(session.getStatus()).isEqualTo(PhoneChangeSession.Status.STARTED);
    }

    // --- complete() ---

    @Test
    void complete_afterOtpVerified_writesTheNewPhoneNumberAndMarksPhoneVerified() {
        PhoneChangeSession session = sessionWith(PhoneChangeSession.Status.OTP_VERIFIED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        User user = existingUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        var response = service.complete(userId, new CompleteRequest(session.getId().toString()));

        assertThat(user.getPhoneNumber()).isEqualTo("+919999999999");
        assertThat(user.isPhoneVerified()).isTrue();
        assertThat(session.getStatus()).isEqualTo(PhoneChangeSession.Status.COMPLETED);
        assertThat(session.getCompletedAt()).isNotNull();
        assertThat(response.phoneNumber()).isEqualTo("+919999999999");
        verify(auditService).record(eq(userId), eq("PHONE_NUMBER_CHANGED"), eq("User"), eq(userId), any());
    }

    @Test
    void complete_beforeOtpHasBeenVerified_rejectsRegardlessOfWhatTheRequestClaims() {
        PhoneChangeSession session = sessionWith(PhoneChangeSession.Status.STARTED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.complete(userId, new CompleteRequest(session.getId().toString())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Verify the code");

        verify(userRepository, never()).save(any());
    }

    /** Idempotency, not replay rejection: a session already COMPLETED (the frontend retried after a
     *  timeout/network hiccup without ever seeing the first response) must return the same outcome
     *  again -- not throw, and not re-run the phone-number write or audit entry a second time. */
    @Test
    void complete_onASessionAlreadyCompleted_returnsTheOriginalOutcomeInsteadOfRepeatingTheSideEffects() {
        PhoneChangeSession session = sessionWith(PhoneChangeSession.Status.COMPLETED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));

        var response = service.complete(userId, new CompleteRequest(session.getId().toString()));

        assertThat(response.phoneNumber()).isEqualTo("+919999999999");
        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
        verify(auditService, never()).record(any(), eq("PHONE_NUMBER_CHANGED"), any(), any(), any());
    }

    @Test
    void complete_onASuspendedAccount_isRejectedWithoutWritingTheNewNumber() {
        PhoneChangeSession session = sessionWith(PhoneChangeSession.Status.OTP_VERIFIED, Instant.now().plusSeconds(600));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        User user = existingUser();
        user.setStatus(User.STATUS_SUSPENDED);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.complete(userId, new CompleteRequest(session.getId().toString())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("suspended");

        assertThat(user.getPhoneNumber()).isEqualTo("+919888888888");
        verify(userRepository, never()).save(any());
    }

    @Test
    void complete_onAnExpiredSession_rejectsAndMarksItExpired() {
        PhoneChangeSession session = sessionWith(PhoneChangeSession.Status.OTP_VERIFIED, Instant.now().minusSeconds(60));
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.complete(userId, new CompleteRequest(session.getId().toString())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");

        assertThat(session.getStatus()).isEqualTo(PhoneChangeSession.Status.EXPIRED);
    }

    @Test
    void verifyOtp_onAnotherUsersSessionId_isNotFound_sinceTheLookupIsScopedToTheCallingUser() {
        UUID otherUsersSessionId = UUID.randomUUID();
        when(sessionRepository.findByIdAndUserId(otherUsersSessionId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyOtp(userId, new VerifyOtpRequest(otherUsersSessionId.toString(), "some-token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid phone number change session");
    }
}
