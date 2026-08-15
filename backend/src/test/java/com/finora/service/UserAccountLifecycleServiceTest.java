package com.finora.service;

import com.finora.config.RequestMetadata;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit-level coverage for UserAccountLifecycleService.deactivate() -- no Spring context, so
 * AfterCommit.run's post-commit email send executes synchronously (see AfterCommit's own doc
 * comment on why that's the correct, intended fallback for exactly this kind of test).
 */
class UserAccountLifecycleServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private RefreshTokenService refreshTokenService;
    private AuditService auditService;
    private EmailProvider emailProvider;
    private RequestMetadata requestMetadata;
    private PasswordChangeService passwordChangeService;
    private UserAccountLifecycleService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        refreshTokenService = mock(RefreshTokenService.class);
        auditService = mock(AuditService.class);
        emailProvider = mock(EmailProvider.class);
        // CALLS_REAL_METHODS, not a plain mock: addTo() is a real composed method that calls
        // ip()/device() internally, and a plain mock() never runs a method's real body -- even one
        // this class defines itself -- for anything left unstubbed. Individually stubbing ip()/
        // device() below still overrides those two specific calls; only addTo() falls through.
        requestMetadata = mock(RequestMetadata.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        when(requestMetadata.ip()).thenReturn("203.0.113.7");
        when(requestMetadata.device()).thenReturn("Chrome on macOS");
        when(emailProvider.sendAccountDeactivatedEmail(any(), any(), any(), any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "test-message-id"));
        when(emailProvider.sendAccountDeletionRequestedEmail(any(), any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "test-message-id"));
        passwordChangeService = mock(PasswordChangeService.class);
        service = new UserAccountLifecycleService(userRepository, passwordEncoder, refreshTokenService,
                auditService, emailProvider, requestMetadata, passwordChangeService);
    }

    private User user(String accountScope) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", userId);
        u.setEmail("jane@example.com");
        u.setAccountScope(accountScope);
        u.setPasswordHash("hashed");
        u.setStatus(User.STATUS_ACTIVE);
        return u;
    }

    @Test
    void deactivate_withTheCorrectPassword_setsStatusReasonAndRevokesEverySession() {
        User u = user(User.SCOPE_USER);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("correct", "hashed")).thenReturn(true);

        service.deactivate(userId, "correct", "TAKING_A_BREAK", "Back in a bit");

        assertThat(u.getStatus()).isEqualTo(User.STATUS_DEACTIVATED);
        assertThat(u.getDeactivationReason()).isEqualTo("TAKING_A_BREAK");
        assertThat(u.getDeactivationNote()).isEqualTo("Back in a bit");
        assertThat(u.getDeactivatedAt()).isNotNull();
        verify(refreshTokenService).revokeAllForUser(userId);
        verify(auditService).record(eq(userId), eq("ACCOUNT_DEACTIVATED"), eq("User"), eq(userId), any());
        verify(emailProvider).sendAccountDeactivatedEmail(eq("jane@example.com"), any(Instant.class),
                eq("Chrome on macOS"), eq("203.0.113.7"));
    }

    @Test
    void deactivate_withABlankNote_storesNullNotEmptyString() {
        User u = user(User.SCOPE_USER);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("correct", "hashed")).thenReturn(true);

        service.deactivate(userId, "correct", "OTHER", "   ");

        assertThat(u.getDeactivationNote()).isNull();
    }

    @Test
    void deactivate_withAnUnrecognizedReason_isRejectedAndChangesNothing() {
        User u = user(User.SCOPE_USER);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        try {
            service.deactivate(userId, "correct", "NOT_A_REAL_REASON", null);
        } catch (ApiException e) {
            assertThat(u.getStatus()).isEqualTo(User.STATUS_ACTIVE);
            // Rejected on the reason alone, before ever checking the password.
            verify(passwordEncoder, never()).matches(any(), any());
            verify(refreshTokenService, never()).revokeAllForUser(any());
            return;
        }
        throw new AssertionError("Expected deactivate() to throw for an unrecognized reason");
    }

    @Test
    void deactivate_withTheWrongPassword_rejectsAndChangesNothing() {
        User u = user(User.SCOPE_USER);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        try {
            service.deactivate(userId, "wrong", "OTHER", null);
        } catch (ApiException e) {
            assertThat(e.getMessage()).isEqualTo("Current password is incorrect.");
            assertThat(u.getStatus()).isEqualTo(User.STATUS_ACTIVE);
            verify(refreshTokenService, never()).revokeAllForUser(any());
            verify(emailProvider, never()).sendAccountDeactivatedEmail(any(), any(), any(), any());
            return;
        }
        throw new AssertionError("Expected deactivate() to throw for the wrong password");
    }

    /** Admin-portal account lifecycle stays an admin-portal/support operation -- see
     *  UserAccountLifecycleService's own doc comment on the trust boundary. */
    @Test
    void deactivate_onAnAdminScopeAccount_isRejected() {
        User u = user(User.SCOPE_ADMIN);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        try {
            service.deactivate(userId, "whatever", "OTHER", null);
        } catch (ApiException e) {
            assertThat(u.getStatus()).isEqualTo(User.STATUS_ACTIVE);
            // Rejected on scope alone, before ever checking the password.
            verify(passwordEncoder, never()).matches(any(), any());
            verify(refreshTokenService, never()).revokeAllForUser(any());
            return;
        }
        throw new AssertionError("Expected deactivate() to throw for an admin-scope account");
    }

    // --- requestDeletion() ---

    private static final String SESSION_ID = UUID.randomUUID().toString();

    @Test
    void requestDeletion_withAConsumedSession_setsStatusAndTimestampAndRevokesEverySession() {
        User u = user(User.SCOPE_USER);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        service.requestDeletion(userId, SESSION_ID);

        verify(passwordChangeService).consumeForAccountDeletion(userId, SESSION_ID);
        assertThat(u.getStatus()).isEqualTo(User.STATUS_PENDING_DELETION);
        assertThat(u.getDeletionRequestedAt()).isNotNull();
        verify(refreshTokenService).revokeAllForUser(userId);
        verify(auditService).record(eq(userId), eq("ACCOUNT_DELETION_REQUESTED"), eq("User"), eq(userId), any());
        verify(emailProvider).sendAccountDeletionRequestedEmail(eq("jane@example.com"), any(Instant.class));
    }

    @Test
    void requestDeletion_whenSessionConsumptionThrows_changesNothing() {
        User u = user(User.SCOPE_USER);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));
        doThrow(new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid password change session."))
                .when(passwordChangeService).consumeForAccountDeletion(userId, SESSION_ID);

        try {
            service.requestDeletion(userId, SESSION_ID);
        } catch (ApiException e) {
            assertThat(u.getStatus()).isEqualTo(User.STATUS_ACTIVE);
            verify(userRepository, never()).save(any());
            verify(refreshTokenService, never()).revokeAllForUser(any());
            verify(auditService, never()).record(any(), eq("ACCOUNT_DELETION_REQUESTED"), any(), any(), any());
            return;
        }
        throw new AssertionError("Expected requestDeletion() to propagate the session-consumption failure");
    }

    @Test
    void requestDeletion_onASuspendedAccount_isRejectedBeforeConsumingTheSession() {
        User u = user(User.SCOPE_USER);
        u.setStatus(User.STATUS_SUSPENDED);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        try {
            service.requestDeletion(userId, SESSION_ID);
        } catch (ApiException e) {
            assertThat(e.getMessage()).contains("suspended");
            assertThat(u.getStatus()).isEqualTo(User.STATUS_SUSPENDED);
            // Blocked before ever burning the OTP session.
            verify(passwordChangeService, never()).consumeForAccountDeletion(any(), any());
            verify(refreshTokenService, never()).revokeAllForUser(any());
            return;
        }
        throw new AssertionError("Expected requestDeletion() to throw for a suspended account");
    }

    @Test
    void requestDeletion_onAnAlreadyPendingDeletionAccount_isRejected() {
        User u = user(User.SCOPE_USER);
        u.setStatus(User.STATUS_PENDING_DELETION);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        try {
            service.requestDeletion(userId, SESSION_ID);
        } catch (ApiException e) {
            assertThat(e.getMessage()).contains("already scheduled for deletion");
            verify(passwordChangeService, never()).consumeForAccountDeletion(any(), any());
            return;
        }
        throw new AssertionError("Expected requestDeletion() to throw for an already-pending-deletion account");
    }

    @Test
    void requestDeletion_onAnAlreadyDeletedAccount_isRejectedAsNotFound() {
        User u = user(User.SCOPE_USER);
        u.setStatus(User.STATUS_DELETED);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        try {
            service.requestDeletion(userId, SESSION_ID);
        } catch (ApiException e) {
            assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
            verify(passwordChangeService, never()).consumeForAccountDeletion(any(), any());
            return;
        }
        throw new AssertionError("Expected requestDeletion() to throw for an already-deleted account");
    }

    @Test
    void requestDeletion_onAnAdminScopeAccount_isRejected() {
        User u = user(User.SCOPE_ADMIN);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        try {
            service.requestDeletion(userId, SESSION_ID);
        } catch (ApiException e) {
            assertThat(u.getStatus()).isEqualTo(User.STATUS_ACTIVE);
            verify(passwordChangeService, never()).consumeForAccountDeletion(any(), any());
            verify(refreshTokenService, never()).revokeAllForUser(any());
            return;
        }
        throw new AssertionError("Expected requestDeletion() to throw for an admin-scope account");
    }
}
