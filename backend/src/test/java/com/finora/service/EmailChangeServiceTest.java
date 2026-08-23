package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.dto.EmailChangeDtos.*;
import com.finora.entity.EmailChangeSession;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.integrations.apple.login.AppleIdTokenVerifierService;
import com.finora.integrations.google.login.GoogleIdTokenVerifierService;
import com.finora.integrations.google.login.GoogleIdentity;
import com.finora.repository.EmailChangeSessionRepository;
import com.finora.repository.UserRepository;
import com.finora.util.TokenHasher;
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
 * The step-up-gated, session-based Change Email flow (start -> verify -> complete). Mirrors
 * PasswordChangeServiceTest's step-up coverage (password/Google/Apple branches) combined with
 * PhoneChangeServiceTest's session-state-machine coverage (out-of-order steps, expiry, foreign
 * sessions, idempotent completion) -- see EmailChangeService's own doc comment for why this flow
 * needs both halves that neither sibling needs alone.
 */
class EmailChangeServiceTest {

    private UserRepository userRepository;
    private EmailChangeSessionRepository sessionRepository;
    private PasswordEncoder passwordEncoder;
    private GoogleIdTokenVerifierService googleIdTokenVerifierService;
    private AppleIdTokenVerifierService appleIdTokenVerifierService;
    private RefreshTokenService refreshTokenService;
    private AuditService auditService;
    private EmailProvider emailProvider;
    private EmailChangeService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID thisDevicesSession = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        sessionRepository = mock(EmailChangeSessionRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        googleIdTokenVerifierService = mock(GoogleIdTokenVerifierService.class);
        appleIdTokenVerifierService = mock(AppleIdTokenVerifierService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        auditService = mock(AuditService.class);
        emailProvider = mock(EmailProvider.class);
        when(emailProvider.isConfigured()).thenReturn(true);
        when(emailProvider.sendEmailChangeVerificationEmail(any(), any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "test-message-id"));

        when(sessionRepository.save(any(EmailChangeSession.class))).thenAnswer(inv -> {
            EmailChangeSession s = inv.getArgument(0);
            if (s.getId() == null) {
                ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
            }
            return s;
        });
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        EmailProperties emailProperties = new EmailProperties();
        emailProperties.setAppBaseUrl("https://app.finora.test");

        service = new EmailChangeService(userRepository, sessionRepository,
                new GoogleReauthVerifier(passwordEncoder, googleIdTokenVerifierService, appleIdTokenVerifierService),
                refreshTokenService, auditService, emailProvider, emailProperties);
        // AfterCommit.run executes immediately when no Spring transaction synchronization is
        // active -- true for every test here, which calls the service directly with no real
        // @Transactional in play. See AfterCommit's own doc comment.
    }

    private User existingUser() {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", userId);
        u.setEmail("jane@example.com");
        u.setPasswordHash("hashed-old-password");
        return u;
    }

    private EmailChangeSession sessionWith(EmailChangeSession.Status status, Instant expiresAt, String rawToken) {
        EmailChangeSession session = new EmailChangeSession();
        ReflectionTestUtils.setField(session, "id", UUID.randomUUID());
        session.setUserId(userId);
        session.setStatus(status);
        session.setCurrentEmail("jane@example.com");
        session.setRequestedEmail("jane.new@example.com");
        session.setVerificationTokenHash(TokenHasher.sha256(rawToken));
        session.setExpiresAt(expiresAt);
        if (status == EmailChangeSession.Status.EMAIL_VERIFIED || status == EmailChangeSession.Status.COMPLETED) {
            session.setEmailVerifiedAt(Instant.now());
        }
        return session;
    }

    // --- start() ---

    @Test
    void start_withCorrectPassword_opensASessionAndEmailsTheVerificationLink() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser()));
        when(passwordEncoder.matches("CorrectPassword", "hashed-old-password")).thenReturn(true);
        when(userRepository.existsByEmailIgnoreCaseAndAccountScope(eq("jane.new@example.com"), any())).thenReturn(false);

        var response = service.start(userId, new StartRequest("CorrectPassword", null, null, "jane.new@example.com"));

        assertThat(response.sessionId()).isNotBlank();
        assertThat(response.devVerifyLink()).isNull();
        verify(sessionRepository).save(argThat(s -> "jane@example.com".equals(s.getCurrentEmail())
                && "jane.new@example.com".equals(s.getRequestedEmail())
                && s.getVerificationTokenHash() != null));
        verify(auditService).record(userId, "EMAIL_CHANGE_STARTED", "User", userId);
        verify(emailProvider).sendEmailChangeVerificationEmail(eq("jane.new@example.com"), contains("https://app.finora.test"));
    }

    @Test
    void start_whenNoEmailProviderIsConfigured_returnsTheLinkDirectlyForDevTesting() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser()));
        when(passwordEncoder.matches("CorrectPassword", "hashed-old-password")).thenReturn(true);
        when(userRepository.existsByEmailIgnoreCaseAndAccountScope(eq("jane.new@example.com"), any())).thenReturn(false);
        when(emailProvider.isConfigured()).thenReturn(false);

        var response = service.start(userId, new StartRequest("CorrectPassword", null, null, "jane.new@example.com"));

        assertThat(response.devVerifyLink()).isNotBlank().contains("token=");
    }

    @Test
    void start_withWrongCurrentPassword_rejectsAndRecordsAnAuditEvent() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser()));
        when(passwordEncoder.matches("WrongPassword", "hashed-old-password")).thenReturn(false);

        assertThatThrownBy(() -> service.start(userId, new StartRequest("WrongPassword", null, null, "jane.new@example.com")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Current password is incorrect");

        verify(auditService).record(userId, "INVALID_CURRENT_PASSWORD", "User", userId);
        verify(sessionRepository, never()).save(any());
        verify(emailProvider, never()).sendEmailChangeVerificationEmail(any(), any());
    }

    @Test
    void start_onAGoogleAccount_verifiesAFreshGoogleTokenInsteadOfAPassword() {
        User user = existingUser();
        user.setSignInMethod(User.SIGN_IN_METHOD_GOOGLE);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(googleIdTokenVerifierService.verify("fresh-google-token"))
                .thenReturn(new GoogleIdentity(user.getEmail(), "Jane"));
        when(userRepository.existsByEmailIgnoreCaseAndAccountScope(eq("jane.new@example.com"), any())).thenReturn(false);

        var response = service.start(userId, new StartRequest(null, "fresh-google-token", null, "jane.new@example.com"));

        assertThat(response.sessionId()).isNotBlank();
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void start_withEmailAlreadyOnTheAccount_isRejectedAsANoOp() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser()));
        when(passwordEncoder.matches("CorrectPassword", "hashed-old-password")).thenReturn(true);

        assertThatThrownBy(() -> service.start(userId, new StartRequest("CorrectPassword", null, null, "jane@example.com")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already the email on your account");

        verify(sessionRepository, never()).save(any());
    }

    /** Bug fix (self-review): AuthService.register() normalizes a new email to lowercase before
     *  storing it (request.email().trim().toLowerCase()) -- this flow only trimmed, so two users
     *  could otherwise end up differing only by the stored case of their email even though the
     *  uq_users_email_scope index and every existsByEmailIgnoreCase* lookup already treat the
     *  account as case-insensitive; a stored "Jane.New@Example.COM" would compare correctly
     *  everywhere but look like a different address to anything comparing exact strings. */
    @Test
    void start_normalizesTheNewEmailToLowercaseBeforeStoring() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser()));
        when(passwordEncoder.matches("CorrectPassword", "hashed-old-password")).thenReturn(true);
        when(userRepository.existsByEmailIgnoreCaseAndAccountScope(eq("jane.new@example.com"), any())).thenReturn(false);

        service.start(userId, new StartRequest("CorrectPassword", null, null, "Jane.New@Example.COM"));

        verify(sessionRepository).save(argThat(s -> "jane.new@example.com".equals(s.getRequestedEmail())));
    }

    @Test
    void start_caseInsensitively_rejectsAnEmailThatOnlyDiffersByCaseFromTheCurrentOne() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser()));
        when(passwordEncoder.matches("CorrectPassword", "hashed-old-password")).thenReturn(true);

        assertThatThrownBy(() -> service.start(userId, new StartRequest("CorrectPassword", null, null, "JANE@EXAMPLE.COM")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already the email on your account");
    }

    @Test
    void start_withAnEmailAlreadyClaimedByAnotherAccount_rejectsWithConflictAndAudits() {
        User user = existingUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("CorrectPassword", "hashed-old-password")).thenReturn(true);
        when(userRepository.existsByEmailIgnoreCaseAndAccountScope("jane.new@example.com", user.getAccountScope())).thenReturn(true);

        assertThatThrownBy(() -> service.start(userId, new StartRequest("CorrectPassword", null, null, "jane.new@example.com")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already exists");

        verify(auditService).record(userId, "EMAIL_CHANGE_REJECTED_DUPLICATE", "User", userId);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void start_onASuspendedAccount_isRejectedBeforeVerifyingIdentity() {
        User user = existingUser();
        user.setStatus(User.STATUS_SUSPENDED);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.start(userId, new StartRequest("CorrectPassword", null, null, "jane.new@example.com")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("suspended");

        verify(passwordEncoder, never()).matches(any(), any());
    }

    // --- verify() ---

    @Test
    void verify_withTheCorrectToken_advancesTheSessionToEmailVerified() {
        EmailChangeSession session = sessionWith(EmailChangeSession.Status.STARTED, Instant.now().plusSeconds(600), "correct-token");
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser()));

        var response = service.verify(userId, new VerifyRequest(session.getId().toString(), "correct-token"));

        assertThat(response.message()).isNotBlank();
        assertThat(session.getStatus()).isEqualTo(EmailChangeSession.Status.EMAIL_VERIFIED);
        assertThat(session.getEmailVerifiedAt()).isNotNull();
        verify(auditService).record(userId, "EMAIL_CHANGE_TOKEN_VERIFIED", "User", userId);
    }

    @Test
    void verify_withTheWrongToken_isRejectedAndAudited() {
        EmailChangeSession session = sessionWith(EmailChangeSession.Status.STARTED, Instant.now().plusSeconds(600), "correct-token");
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser()));

        assertThatThrownBy(() -> service.verify(userId, new VerifyRequest(session.getId().toString(), "wrong-token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("verification link is invalid");

        assertThat(session.getStatus()).isEqualTo(EmailChangeSession.Status.STARTED);
        verify(auditService).record(userId, "INVALID_EMAIL_CHANGE_TOKEN", "User", userId);
    }

    @Test
    void verify_onASessionThatAlreadyCompletedThisStep_rejectsWithoutCheckingTheToken() {
        EmailChangeSession session = sessionWith(EmailChangeSession.Status.EMAIL_VERIFIED, Instant.now().plusSeconds(600), "correct-token");
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.verify(userId, new VerifyRequest(session.getId().toString(), "correct-token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been completed");
    }

    @Test
    void verify_onAnExpiredSession_rejectsAndMarksTheSessionExpired() {
        EmailChangeSession session = sessionWith(EmailChangeSession.Status.STARTED, Instant.now().minusSeconds(60), "correct-token");
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.verify(userId, new VerifyRequest(session.getId().toString(), "correct-token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");

        assertThat(session.getStatus()).isEqualTo(EmailChangeSession.Status.EXPIRED);
        verify(auditService).record(userId, "SESSION_EXPIRED", "User", userId);
    }

    @Test
    void verify_onAnUnknownOrForeignSessionId_rejectsCleanly() {
        when(sessionRepository.findByIdAndUserId(any(), eq(userId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(userId, new VerifyRequest(UUID.randomUUID().toString(), "some-token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid email change session");
    }

    @Test
    void verify_withAMalformedSessionId_rejectsCleanlyInsteadOfThrowingAnUnhandledException() {
        assertThatThrownBy(() -> service.verify(userId, new VerifyRequest("not-a-uuid", "some-token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid email change session");
    }

    // --- complete() ---

    @Test
    void complete_afterEmailVerified_writesTheNewEmailAndMarksEmailVerified() {
        EmailChangeSession session = sessionWith(EmailChangeSession.Status.EMAIL_VERIFIED, Instant.now().plusSeconds(600), "correct-token");
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        User user = existingUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        var response = service.complete(userId, new CompleteRequest(session.getId().toString()), thisDevicesSession);

        assertThat(user.getEmail()).isEqualTo("jane.new@example.com");
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(session.getStatus()).isEqualTo(EmailChangeSession.Status.COMPLETED);
        assertThat(response.email()).isEqualTo("jane.new@example.com");
        verify(auditService).record(eq(userId), eq("EMAIL_CHANGED"), eq("User"), eq(userId), any());
    }

    /** Phase 3.5 lesson applied from day one here, unlike PhoneChangeService which needed a
     *  follow-up fix: unconditional, current device spared. See EmailChangeService.complete's
     *  own doc comment for why unconditional is the right default absent a frontend toggle. */
    @Test
    void complete_revokesEveryOtherSession_sparingOnlyThisDevice() {
        EmailChangeSession session = sessionWith(EmailChangeSession.Status.EMAIL_VERIFIED, Instant.now().plusSeconds(600), "correct-token");
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser()));

        service.complete(userId, new CompleteRequest(session.getId().toString()), thisDevicesSession);

        verify(refreshTokenService).revokeAllOtherSessionsForUser(userId, thisDevicesSession);
    }

    @Test
    void complete_beforeEmailHasBeenVerified_rejects() {
        EmailChangeSession session = sessionWith(EmailChangeSession.Status.STARTED, Instant.now().plusSeconds(600), "correct-token");
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.complete(userId, new CompleteRequest(session.getId().toString()), thisDevicesSession))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Confirm the link");

        verify(userRepository, never()).save(any());
    }

    /** Idempotency, not replay rejection: a session already COMPLETED (the frontend retried after
     *  a timeout/network hiccup without ever seeing the first response) must return the same
     *  outcome again -- not throw, and not re-run the email write, audit entry, or session
     *  revocation a second time. */
    @Test
    void complete_onASessionAlreadyCompleted_returnsTheOriginalOutcomeInsteadOfRepeatingTheSideEffects() {
        EmailChangeSession session = sessionWith(EmailChangeSession.Status.COMPLETED, Instant.now().plusSeconds(600), "correct-token");
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));

        var response = service.complete(userId, new CompleteRequest(session.getId().toString()), thisDevicesSession);

        assertThat(response.email()).isEqualTo("jane.new@example.com");
        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
        verify(refreshTokenService, never()).revokeAllOtherSessionsForUser(any(), any());
        verify(auditService, never()).record(any(), eq("EMAIL_CHANGED"), any(), any(), any());
    }

    @Test
    void complete_onASuspendedAccount_isRejectedWithoutWritingTheNewEmail() {
        EmailChangeSession session = sessionWith(EmailChangeSession.Status.EMAIL_VERIFIED, Instant.now().plusSeconds(600), "correct-token");
        when(sessionRepository.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        User user = existingUser();
        user.setStatus(User.STATUS_SUSPENDED);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.complete(userId, new CompleteRequest(session.getId().toString()), thisDevicesSession))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("suspended");

        assertThat(user.getEmail()).isEqualTo("jane@example.com");
        verify(userRepository, never()).save(any());
    }
}
