package com.finora.service;

import com.finora.config.RequestMetadata;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.integrations.google.login.GoogleIdTokenVerifierService;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

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
    private GoogleIdTokenVerifierService googleIdTokenVerifierService;
    private RefreshTokenService refreshTokenService;
    private AuditService auditService;
    private EmailProvider emailProvider;
    private RequestMetadata requestMetadata;
    private PasswordChangeService passwordChangeService;
    private AccountPurgeSweepService accountPurgeSweepService;
    private TransactionTemplate transactionTemplate;
    private UserAccountLifecycleService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        googleIdTokenVerifierService = mock(GoogleIdTokenVerifierService.class);
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
        when(emailProvider.sendAccountDeletedEmail(any(), any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "test-message-id"));
        passwordChangeService = mock(PasswordChangeService.class);
        accountPurgeSweepService = mock(AccountPurgeSweepService.class);
        transactionTemplate = mock(TransactionTemplate.class);
        // Runs the real lambda passed to executeWithoutResult -- without this, requestDeletion()'s
        // phase-one work (validation, consuming the OTP session, setting PENDING_DELETION,
        // revoking sessions) would never actually happen. Same pattern
        // AccountPurgeSweepServiceTest already establishes for its own bulk-delete phase.
        doAnswer(inv -> {
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        service = new UserAccountLifecycleService(userRepository,
                new GoogleReauthVerifier(passwordEncoder, googleIdTokenVerifierService), refreshTokenService,
                auditService, emailProvider, requestMetadata, passwordChangeService,
                accountPurgeSweepService, transactionTemplate);
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

        service.deactivate(userId, "correct", null, "TAKING_A_BREAK", "Back in a bit");

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

        service.deactivate(userId, "correct", null, "OTHER", "   ");

        assertThat(u.getDeactivationNote()).isNull();
    }

    @Test
    void deactivate_withAnUnrecognizedReason_isRejectedAndChangesNothing() {
        User u = user(User.SCOPE_USER);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        try {
            service.deactivate(userId, "correct", null, "NOT_A_REAL_REASON", null);
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
    void deactivate_onAGoogleAccount_verifiesAFreshGoogleTokenInsteadOfAPassword() {
        User u = user(User.SCOPE_USER);
        u.setSignInMethod(User.SIGN_IN_METHOD_GOOGLE);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));
        when(googleIdTokenVerifierService.verify("fresh-google-token"))
                .thenReturn(new com.finora.integrations.google.login.GoogleIdentity(u.getEmail(), "Jane"));

        service.deactivate(userId, null, "fresh-google-token", "TAKING_A_BREAK", null);

        assertThat(u.getStatus()).isEqualTo(User.STATUS_DEACTIVATED);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void deactivate_withTheWrongPassword_rejectsAndChangesNothing() {
        User u = user(User.SCOPE_USER);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        try {
            service.deactivate(userId, "wrong", null, "OTHER", null);
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
            service.deactivate(userId, "whatever", null, "OTHER", null);
        } catch (ApiException e) {
            assertThat(u.getStatus()).isEqualTo(User.STATUS_ACTIVE);
            // Rejected on scope alone, before ever checking the password.
            verify(passwordEncoder, never()).matches(any(), any());
            verify(refreshTokenService, never()).revokeAllForUser(any());
            return;
        }
        throw new AssertionError("Expected deactivate() to throw for an admin-scope account");
    }

    /** Regression test: without this, requestDeletion()'s "no cancel link" product decision was
     *  trivially reversible -- an access token already issued keeps working for up to 15 minutes
     *  past the status change (requestDeletion only revokes refresh tokens), and the account's
     *  real password is unchanged until AccountPurgeSweepService's purge runs 48h later, so
     *  deactivate() would have happily flipped a PENDING_DELETION account back to DEACTIVATED with
     *  nothing more than the same still-known current password. */
    @Test
    void deactivate_onAPendingDeletionAccount_isRejectedBeforeCheckingThePassword() {
        User u = user(User.SCOPE_USER);
        u.setStatus(User.STATUS_PENDING_DELETION);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        try {
            service.deactivate(userId, "correct", null, "OTHER", null);
        } catch (ApiException e) {
            assertThat(e.getMessage()).contains("scheduled for deletion");
            assertThat(u.getStatus()).isEqualTo(User.STATUS_PENDING_DELETION);
            verify(passwordEncoder, never()).matches(any(), any());
            verify(refreshTokenService, never()).revokeAllForUser(any());
            return;
        }
        throw new AssertionError("Expected deactivate() to throw for a pending-deletion account");
    }

    @Test
    void deactivate_onAnAlreadyDeletedAccount_isRejected() {
        User u = user(User.SCOPE_USER);
        u.setStatus(User.STATUS_DELETED);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        try {
            service.deactivate(userId, "correct", null, "OTHER", null);
        } catch (ApiException e) {
            assertThat(e.getMessage()).contains("scheduled for deletion");
            verify(passwordEncoder, never()).matches(any(), any());
            return;
        }
        throw new AssertionError("Expected deactivate() to throw for an already-deleted account");
    }

    // --- requestDeletion() ---

    private static final String SESSION_ID = UUID.randomUUID().toString();

    @Test
    void requestDeletion_withAConsumedSession_setsStatusAndTimestampAndRevokesEverySessionAndPurgesImmediately() {
        User u = user(User.SCOPE_USER);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        service.requestDeletion(userId, SESSION_ID);

        verify(passwordChangeService).consumeForAccountDeletion(userId, SESSION_ID);
        assertThat(u.getStatus()).isEqualTo(User.STATUS_PENDING_DELETION);
        assertThat(u.getDeletionRequestedAt()).isNotNull();
        verify(refreshTokenService).revokeAllForUser(userId);
        verify(auditService).record(eq(userId), eq("ACCOUNT_DELETION_REQUESTED"), eq("User"), eq(userId), any());
        // The actual purge (anonymize, hard-delete every owned table, finally set status=DELETED)
        // is AccountPurgeSweepServiceTest/IT's own concern -- this test only proves it's
        // triggered, synchronously, as part of the same requestDeletion() call, not deferred to a
        // sweep (product decision: instant deletion, not a 48h delayed purge).
        verify(accountPurgeSweepService).purgeOne(userId);
        verify(emailProvider).sendAccountDeletedEmail(eq("jane@example.com"), any(Instant.class));
    }

    /** Regression test for the crash-recovery property AccountPurgeSweepService's own doc comment
     *  promises: a purge failure (a crash, a transient Gmail API outage) must leave the account
     *  exactly where the scheduled sweep already knows how to find and safely retry it -- not
     *  silently reported as a success, and not sent a "deleted" confirmation for a purge that
     *  never actually finished. */
    @Test
    void requestDeletion_whenPurgeThrows_propagatesAndNeverSendsTheDeletedEmail() {
        User u = user(User.SCOPE_USER);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));
        doThrow(new RuntimeException("Gmail revocation timed out"))
                .when(accountPurgeSweepService).purgeOne(userId);

        try {
            service.requestDeletion(userId, SESSION_ID);
        } catch (RuntimeException e) {
            // Phase one already committed (PENDING_DELETION, tokens revoked) before the purge
            // ran -- exactly the state the scheduled sweep's own retry-from-scratch logic expects.
            assertThat(u.getStatus()).isEqualTo(User.STATUS_PENDING_DELETION);
            verify(refreshTokenService).revokeAllForUser(userId);
            verify(emailProvider, never()).sendAccountDeletedEmail(any(), any());
            return;
        }
        throw new AssertionError("Expected requestDeletion() to propagate a purge failure");
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
            verify(accountPurgeSweepService, never()).purgeOne(any());
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
