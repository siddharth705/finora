package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.dto.AuthDtos.LoginRequest;
import com.finora.entity.User;
import com.finora.repository.CategoryRepository;
import com.finora.repository.PasswordResetTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Locks in email-or-phone login (AuthService.resolveEmailForLogin / login()): a single
 * identifier field should authenticate a user regardless of whether they typed their email or
 * their registered mobile number, without ever revealing which accounts exist to a caller who
 * guesses wrong.
 */
class AuthServiceLoginTest {

    private UserRepository userRepository;
    private AuthenticationManager authenticationManager;
    private RefreshTokenService refreshTokenService;
    private PlatformSettingsService platformSettingsService;
    private com.finora.repository.AccountReactivationTokenRepository reactivationTokenRepository;
    private AuditService auditService;
    private AuthService authService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        authenticationManager = mock(AuthenticationManager.class);
        refreshTokenService = mock(RefreshTokenService.class);
        // Bug fix: unstubbed, this mock's issue() returns null (RefreshTokenService.IssuedToken
        // is a plain record, not a List/Optional, so Mockito's smart-null defaults don't apply)
        // -- any test whose login() call reaches the token-issuing line NPEs on the immediate
        // .rawToken() call. login_withUnknownIdentifier never reaches it (throws first) and the
        // resolveEmailForLogin-only tests never call login() at all, which is why this had never
        // been caught until this suite actually got to run.
        when(refreshTokenService.issue(any())).thenReturn(
                new RefreshTokenService.IssuedToken("test-refresh-token", java.time.Instant.now().plusSeconds(3600), java.util.UUID.randomUUID()));

        // registerFailedLogin() (invoked from login()'s catch block whenever the user is known)
        // reads live lockout policy off this on every failed attempt -- a real entity with the
        // same 5/15 defaults the old hardcoded constants had, so existing lockout-related
        // expectations don't shift just from this mock existing.
        platformSettingsService = mock(PlatformSettingsService.class);
        when(platformSettingsService.getEntity()).thenReturn(new com.finora.entity.PlatformSettings());

        reactivationTokenRepository = mock(com.finora.repository.AccountReactivationTokenRepository.class);
        auditService = mock(AuditService.class);
        authService = new AuthService(
                userRepository, mock(CategoryRepository.class), mock(PasswordResetTokenRepository.class),
                reactivationTokenRepository,
                mock(com.finora.repository.EmailVerificationTokenRepository.class),
                mock(PasswordEncoder.class), mock(JwtService.class), authenticationManager,
                auditService, refreshTokenService, mock(EmailProvider.class),
                new EmailProperties(), mock(PhoneVerificationProvider.class), platformSettingsService,
                mock(PasswordHistoryService.class), new IdentityLookup(userRepository),
                mock(com.finora.config.RequestMetadata.class),
                mock(com.finora.service.SubscriptionService.class),
                mock(com.finora.service.ReferralService.class),
                // SEC-07: same-thread executor -- runs the dispatched email/audit work
                // synchronously so assertions against it don't race a real background thread.
                Runnable::run,
                // SEC-03: no MFA gate interference for tests unrelated to it -- an
                // unstubbed mock's isEnabled() returns false by default.
                mock(AdminMfaService.class)
        );
    }

    private User user(String email, String phoneNumber) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", userId);
        u.setEmail(email);
        u.setPhoneNumber(phoneNumber);
        return u;
    }

    /** authenticate() succeeding is all login() needs to proceed past the try block -- its
     *  return value is never inspected, so any non-throwing mock answer works. */
    private void stubSuccessfulAuthentication() {
        when(authenticationManager.authenticate(any())).thenReturn(mock(Authentication.class));
    }

    @Test
    void login_withEmailIdentifier_authenticatesDirectlyWithoutPhoneLookup() {
        User u = user("jane@example.com", "+919876500001");
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("jane@example.com", "USER")).thenReturn(Optional.of(u));
        stubSuccessfulAuthentication();

        authService.login(new LoginRequest("jane@example.com", "Password123", "USER"));

        // Authenticated by ID, not email: the Spring Security principal is the user id, because
        // an email identifies an account only within a portal scope since V52.
        verify(authenticationManager).authenticate(argThat(token ->
                u.getId().toString().equals(token.getPrincipal())));
        verify(userRepository, never()).findByPhoneNumberAndAccountScope(anyString(), anyString());
    }

    /**
     * The masked phone is populated here regardless of verification state, since VerifyPhone.tsx
     * needs it to display which number a code will go to once it calls Firebase Phone
     * Authentication directly right after.
     */
    @Test
    void login_returnsTheMaskedPhoneNumber_evenThoughItNeverTriggersPhoneVerificationItself() {
        User u = user("jane@example.com", "+919876500001");
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("jane@example.com", "USER")).thenReturn(Optional.of(u));
        stubSuccessfulAuthentication();

        var response = authService.login(new LoginRequest("jane@example.com", "Password123", "USER"));

        assertThat(response.maskedPhone()).isEqualTo("+•••••••••001");
    }

    @Test
    void login_withExactPhoneNumberMatch_resolvesToTheAccountsEmail() {
        User u = user("jane@example.com", "+919876500001");
        when(userRepository.findByPhoneNumberAndAccountScope("+919876500001", "USER")).thenReturn(Optional.of(u));
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("jane@example.com", "USER")).thenReturn(Optional.of(u));
        stubSuccessfulAuthentication();

        authService.login(new LoginRequest("+919876500001", "Password123", "USER"));

        verify(authenticationManager).authenticate(argThat(token ->
                u.getId().toString().equals(token.getPrincipal())));
    }

    @Test
    void resolveEmailForLogin_findsAccountWhenTypedNumberIsMissingOnlyTheLeadingPlus() {
        // Stored exactly as the registration form's own placeholder shows it ("+91XXXXXXXXXX"),
        // but the user drops the "+" when typing it back in at login.
        User u = user("raj@example.com", "+919876500002");
        when(userRepository.findByPhoneNumberAndAccountScope("919876500002", "USER")).thenReturn(Optional.empty());
        when(userRepository.findByPhoneNumberAndAccountScope("+919876500002", "USER")).thenReturn(Optional.of(u));

        Object resolved = ReflectionTestUtils.invokeMethod(authService, "resolveEmailForLogin", "919876500002", "USER");

        assertThat(resolved).isEqualTo("raj@example.com");
    }

    /**
     * Regression test: registration now normalizes a bare 10-digit number to E.164 by prepending
     * "+91" (see AuthService.normalizePhoneNumber()) -- a user who registered with "9876500003"
     * (stored as "+919876500003") and later types that exact same bare number to log in must still
     * resolve to their account. Before this fix, resolveEmailForLogin only ever tried the raw
     * identifier, "+" + digits, and digits alone -- none of which reconstruct a "+91"-prefixed
     * stored number from a bare 10-digit identifier, so this login would silently fail with a
     * generic "Invalid credentials" for every newly-registered user who logged in this way.
     */
    @Test
    void resolveEmailForLogin_findsAccountRegisteredWithABareTenDigitNumber_nowStoredWithTheLeadingCountryCode() {
        User u = user("priya@example.com", "+919876500003");
        when(userRepository.findByPhoneNumberAndAccountScope("9876500003", "USER")).thenReturn(Optional.empty());
        when(userRepository.findByPhoneNumberAndAccountScope("+9876500003", "USER")).thenReturn(Optional.empty());
        when(userRepository.findByPhoneNumberAndAccountScope("+919876500003", "USER")).thenReturn(Optional.of(u));

        Object resolved = ReflectionTestUtils.invokeMethod(authService, "resolveEmailForLogin", "9876500003", "USER");

        assertThat(resolved).isEqualTo("priya@example.com");
    }

    @Test
    void login_withUnknownIdentifier_doesNotLeakWhetherAccountExists() {
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("nobody@example.com", "USER")).thenReturn(Optional.empty());
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        try {
            authService.login(new LoginRequest("nobody@example.com", "whatever", "USER"));
        } catch (Exception e) {
            assertThat(e.getMessage()).isEqualTo("Invalid credentials");
            return;
        }
        throw new AssertionError("Expected login() to throw for an unknown identifier");
    }

    /**
     * Bug fix: case-insensitive email uniqueness was never enforced before this session, so two
     * pre-existing accounts could differ only by case. findByEmailIgnoreCaseAndAccountScope(, "USER") throws
     * IncorrectResultSizeDataAccessException if it matches more than one row -- login() must fail
     * closed to the same generic "Invalid credentials" every other unresolvable identifier gets,
     * not bubble up as an opaque 500.
     */
    @Test
    void login_whenEmailIgnoreCaseLookupIsAmbiguous_failsClosedInsteadOf500ing() {
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("jane@example.com", "USER"))
                .thenThrow(new org.springframework.dao.IncorrectResultSizeDataAccessException(1));
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        try {
            authService.login(new LoginRequest("jane@example.com", "whatever", "USER"));
        } catch (Exception e) {
            assertThat(e).isInstanceOf(com.finora.exception.ApiException.class);
            assertThat(e.getMessage()).isEqualTo("Invalid credentials");
            return;
        }
        throw new AssertionError("Expected login() to throw, not propagate the ambiguous-lookup exception");
    }

    /**
     * A suspended account is rejected, and only ever tells the CORRECT password that it is
     * suspended.
     *
     * <p><b>These two tests replace one that asserted the opposite</b>
     * ({@code login_withSuspendedAccount_isRejectedBeforeAuthenticating}, which verified
     * {@code authenticationManager, never()).authenticate(any())}). That ordering was the bug: it
     * meant an unauthenticated caller could post any email with any password and read "This account
     * has been suspended" where an unregistered address returned "Invalid credentials" — an
     * account-existence oracle on a public endpoint. The old test was not wrong about what the code
     * did; it pinned the wrong behaviour, which is why it is deleted rather than adjusted.
     */
    @Test
    void login_withSuspendedAccount_andTheRightPassword_saysItIsSuspended() {
        // Same 98765-000NN fixture block the rest of this suite uses; unchanged from the
        // test these two replace, and flagged only because the lines themselves are new.
        User u = user("suspended@example.com", "+919876500099"); // synthetic-ok
        u.setStatus("SUSPENDED");
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("suspended@example.com", "USER")).thenReturn(Optional.of(u));
        stubSuccessfulAuthentication();

        try {
            authService.login(new LoginRequest("suspended@example.com", "the-right-password", "USER"));
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("suspended");
            // No session for an account that cannot sign in, whatever the password was.
            verify(refreshTokenService, never()).issue(any());
            return;
        }
        throw new AssertionError("Expected login() to throw for a suspended account");
    }

    /**
     * The enumeration fix itself. A wrong password against a suspended account has to be
     * indistinguishable from a wrong password against an account that does not exist — which is
     * what {@code login_withUnknownIdentifier} asserts returns "Invalid credentials".
     */
    @Test
    void login_withSuspendedAccount_andAWrongPassword_revealsNothingAboutTheAccount() {
        // Same 98765-000NN fixture block the rest of this suite uses; unchanged from the
        // test these two replace, and flagged only because the lines themselves are new.
        User u = user("suspended@example.com", "+919876500099"); // synthetic-ok
        u.setStatus("SUSPENDED");
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("suspended@example.com", "USER")).thenReturn(Optional.of(u));
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("nope"));

        try {
            authService.login(new LoginRequest("suspended@example.com", "wrong", "USER"));
        } catch (Exception e) {
            assertThat(e.getMessage())
                    .as("a caller who has not proved the password must not learn that this address "
                            + "belongs to a real, suspended account")
                    .isEqualTo("Invalid credentials");
            assertThat(e.getMessage()).doesNotContain("suspended");
            return;
        }
        throw new AssertionError("Expected login() to throw for a wrong password");
    }

    /**
     * A deactivated account is a different branch from suspended (User.isDeactivated(), not
     * isSuspended()) -- same positioning discipline (checked after a proven-correct password, so
     * this never becomes a second account-existence oracle), but instead of a dead-end rejection
     * it mints a reactivation token and carries it in the exception's details map, matching what
     * ReactivateAccountPrompt.tsx expects to read from AUTH_ACCOUNT_DEACTIVATED's response.
     */
    @Test
    void login_withDeactivatedAccount_andTheRightPassword_mintsAReactivationTokenAndThrows() {
        User u = user("deactivated@example.com", "+919876500098"); // synthetic-ok
        u.setStatus(User.STATUS_DEACTIVATED);
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("deactivated@example.com", "USER")).thenReturn(Optional.of(u));
        stubSuccessfulAuthentication();

        try {
            authService.login(new LoginRequest("deactivated@example.com", "the-right-password", "USER"));
        } catch (com.finora.exception.ApiException e) {
            assertThat(e.getCode()).isEqualTo(com.finora.exception.ErrorCode.AUTH_ACCOUNT_DEACTIVATED);
            assertThat(e.getDetails()).containsKey("reactivationToken");
            assertThat((String) e.getDetails().get("reactivationToken")).isNotBlank();
            verify(reactivationTokenRepository).save(any());
            // No session, and no login recorded -- no login actually happened.
            verify(refreshTokenService, never()).issue(any());
            verify(auditService, never()).record(any(), eq("USER_LOGIN"), any(), any());
            return;
        }
        throw new AssertionError("Expected login() to throw for a deactivated account");
    }

    /** app.account-lifecycle.reactivation-window-enabled/-days -- disabled by default (see the
     *  test above, which mints a token with the window fields left at their Java defaults), but
     *  once enabled and the configured number of days has elapsed since deactivatedAt, login()
     *  must fall through to a plain rejection instead of a token-bearing reactivation prompt. */
    @Test
    void login_withDeactivatedAccount_pastAConfiguredReactivationWindow_rejectsWithoutMintingAToken() {
        ReflectionTestUtils.setField(authService, "reactivationWindowEnabled", true);
        ReflectionTestUtils.setField(authService, "reactivationWindowDays", 3);

        User u = user("longgone@example.com", "+919876500097"); // synthetic-ok
        u.setStatus(User.STATUS_DEACTIVATED);
        u.setDeactivatedAt(java.time.Instant.now().minus(java.time.Duration.ofDays(4)));
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("longgone@example.com", "USER")).thenReturn(Optional.of(u));
        stubSuccessfulAuthentication();

        try {
            authService.login(new LoginRequest("longgone@example.com", "the-right-password", "USER"));
        } catch (com.finora.exception.ApiException e) {
            assertThat(e.getCode()).isNull();
            assertThat(e.getMessage()).contains("window has closed");
            verify(reactivationTokenRepository, never()).save(any());
            verify(refreshTokenService, never()).issue(any());
            return;
        }
        throw new AssertionError("Expected login() to throw for a deactivated account past its reactivation window");
    }

    /** Same window, but still within it -- the existing token-minting path must still apply. */
    @Test
    void login_withDeactivatedAccount_withinAConfiguredReactivationWindow_stillMintsAToken() {
        ReflectionTestUtils.setField(authService, "reactivationWindowEnabled", true);
        ReflectionTestUtils.setField(authService, "reactivationWindowDays", 3);

        User u = user("stillintime@example.com", "+919876500096"); // synthetic-ok
        u.setStatus(User.STATUS_DEACTIVATED);
        u.setDeactivatedAt(java.time.Instant.now().minus(java.time.Duration.ofDays(1)));
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("stillintime@example.com", "USER")).thenReturn(Optional.of(u));
        stubSuccessfulAuthentication();

        try {
            authService.login(new LoginRequest("stillintime@example.com", "the-right-password", "USER"));
        } catch (com.finora.exception.ApiException e) {
            assertThat(e.getCode()).isEqualTo(com.finora.exception.ErrorCode.AUTH_ACCOUNT_DEACTIVATED);
            verify(reactivationTokenRepository).save(any());
            return;
        }
        throw new AssertionError("Expected login() to throw for a deactivated account");
    }

    /** The same enumeration-safety guarantee login_withSuspendedAccount_andAWrongPassword_...
     *  pins for suspended accounts, mirrored for deactivated. */
    @Test
    void login_withDeactivatedAccount_andAWrongPassword_revealsNothingAboutTheAccount() {
        User u = user("deactivated@example.com", "+919876500098"); // synthetic-ok
        u.setStatus(User.STATUS_DEACTIVATED);
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("deactivated@example.com", "USER")).thenReturn(Optional.of(u));
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("nope"));

        try {
            authService.login(new LoginRequest("deactivated@example.com", "wrong", "USER"));
        } catch (Exception e) {
            assertThat(e.getMessage()).isEqualTo("Invalid credentials");
            verify(reactivationTokenRepository, never()).save(any());
            return;
        }
        throw new AssertionError("Expected login() to throw for a wrong password");
    }

    @Test
    void resolveEmailForLogin_returnsOriginalIdentifierUnchanged_whenNoPhoneNumberMatchesAnyVariant() {
        when(userRepository.findByPhoneNumberAndAccountScope(anyString(), anyString())).thenReturn(Optional.empty());

        Object resolved = ReflectionTestUtils.invokeMethod(authService, "resolveEmailForLogin", "0000000000", "USER");

        // Falls through to the original identifier so the existing authenticate()-then-catch
        // path in login() still fails with the same generic "Invalid credentials" -- exactly
        // the same observable behavior as an unrecognized email today.
        assertThat(resolved).isEqualTo("0000000000");
    }

    /**
     * Locks in PlatformSettingsService wiring (V27__platform_settings.sql /
     * PlatformSettingsController) -- an admin lowering maxFailedLoginAttempts must actually
     * change how many bad passwords it takes to lock an account, not just update a number nobody
     * reads. Two failures is enough to lock when the configured max is 2, where the old hardcoded
     * default of 5 would have allowed three more attempts first.
     */
    /** See UserAccountLifecycleService.requestDeletion's "no cancel link" product decision -- a
     *  login() that let a PENDING_DELETION account back in would trivially undo it, since the real
     *  passwordHash is still on the row until AccountPurgeSweepService's last purge step. Unlike
     *  DEACTIVATED, there is no reactivation path: this is intentionally a dead end. */
    @Test
    void login_withPendingDeletionAccount_andTheRightPassword_rejectsWithNoReactivationPath() {
        User u = user("pendingdeletion@example.com", "+919876500095"); // synthetic-ok
        u.setStatus(User.STATUS_PENDING_DELETION);
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("pendingdeletion@example.com", "USER")).thenReturn(Optional.of(u));
        stubSuccessfulAuthentication();

        try {
            authService.login(new LoginRequest("pendingdeletion@example.com", "the-right-password", "USER"));
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("scheduled for deletion");
            verify(reactivationTokenRepository, never()).save(any());
            verify(refreshTokenService, never()).issue(any());
            return;
        }
        throw new AssertionError("Expected login() to throw for a pending-deletion account");
    }

    @Test
    void login_withPendingDeletionAccount_andAWrongPassword_revealsNothingAboutTheAccount() {
        User u = user("pendingdeletion@example.com", "+919876500095"); // synthetic-ok
        u.setStatus(User.STATUS_PENDING_DELETION);
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("pendingdeletion@example.com", "USER")).thenReturn(Optional.of(u));
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("nope"));

        try {
            authService.login(new LoginRequest("pendingdeletion@example.com", "wrong", "USER"));
        } catch (Exception e) {
            assertThat(e.getMessage()).isEqualTo("Invalid credentials");
            return;
        }
        throw new AssertionError("Expected login() to throw for a wrong password");
    }

    /** Realistically unreachable via login() in production (DELETED's passwordHash is a random
     *  unusable value the purge itself writes), but this test drives the branch directly by
     *  stubbing authentication to succeed anyway -- see AuthService.login()'s own doc comment on
     *  why the check exists as an explicit branch regardless. */
    @Test
    void login_withDeletedAccount_isRejected() {
        User u = user("deleted@example.com", "+919876500094"); // synthetic-ok
        u.setStatus(User.STATUS_DELETED);
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("deleted@example.com", "USER")).thenReturn(Optional.of(u));
        stubSuccessfulAuthentication();

        try {
            authService.login(new LoginRequest("deleted@example.com", "whatever", "USER"));
        } catch (Exception e) {
            assertThat(e.getMessage()).isEqualTo("This account no longer exists.");
            verify(refreshTokenService, never()).issue(any());
            return;
        }
        throw new AssertionError("Expected login() to throw for a deleted account");
    }

    @Test
    void login_locksAccountAfterConfiguredMaxAttempts_notTheOldHardcodedDefault() {
        var settings = new com.finora.entity.PlatformSettings();
        settings.setMaxFailedLoginAttempts(2);
        settings.setLockoutDurationMinutes(30);
        when(platformSettingsService.getEntity()).thenReturn(settings);

        User u = user("locksout@example.com", "+919876500077");
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("locksout@example.com", "USER")).thenReturn(Optional.of(u));
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        // First failure: below the configured threshold, no lockout yet.
        try {
            authService.login(new LoginRequest("locksout@example.com", "wrong", "USER"));
        } catch (Exception ignored) { /* expected */ }
        assertThat(u.getLockedUntil()).isNull();

        // Second failure: hits the configured max of 2 (not the old hardcoded 5) -- locked now.
        try {
            authService.login(new LoginRequest("locksout@example.com", "wrong", "USER"));
        } catch (Exception ignored) { /* expected */ }
        assertThat(u.getLockedUntil()).isNotNull();
        assertThat(u.getLockedUntil()).isAfter(java.time.Instant.now().plusSeconds(29 * 60));
    }

    /**
     * Serving a lockout has to actually clear it.
     *
     * <p>This was latent until per-account lockout started working, and making it work is what
     * exposed it. registerFailedLogin never resets failedLoginAttempts -- only a SUCCESSFUL login
     * did -- but while the counter was being discarded by rollback it never reached the threshold,
     * so a second lock could not happen either. Once the counter persisted, an account that had
     * served its 15 minutes came back with the count still at the maximum, and ONE wrong password
     * re-locked it for the full duration. Indefinitely, with no way out except getting the password
     * right first time.
     */
    @Test
    void login_afterAnExpiredLockout_startsCountingAgainRatherThanRelockingImmediately() {
        var settings = new com.finora.entity.PlatformSettings();
        settings.setMaxFailedLoginAttempts(2);
        settings.setLockoutDurationMinutes(30);
        when(platformSettingsService.getEntity()).thenReturn(settings);

        User u = user("servedtime@example.com", "+919876500000");
        // The state an account is in the moment its lockout expires: penalty served, counter still
        // at the maximum that produced it.
        u.setFailedLoginAttempts(2);
        u.setLockedUntil(java.time.Instant.now().minusSeconds(60));
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("servedtime@example.com", "USER"))
                .thenReturn(Optional.of(u));
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        try {
            authService.login(new LoginRequest("servedtime@example.com", "wrong", "USER"));
        } catch (Exception ignored) { /* expected */ }

        // One wrong password after serving a lockout is one strike, not an instant re-lock.
        assertThat(u.getFailedLoginAttempts()).isEqualTo(1);
        assertThat(u.getLockedUntil()).isNull();
    }
}
