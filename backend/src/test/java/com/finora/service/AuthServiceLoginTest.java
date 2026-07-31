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
                new RefreshTokenService.IssuedToken("test-refresh-token", java.time.Instant.now().plusSeconds(3600)));

        // registerFailedLogin() (invoked from login()'s catch block whenever the user is known)
        // reads live lockout policy off this on every failed attempt -- a real entity with the
        // same 5/15 defaults the old hardcoded constants had, so existing lockout-related
        // expectations don't shift just from this mock existing.
        platformSettingsService = mock(PlatformSettingsService.class);
        when(platformSettingsService.getEntity()).thenReturn(new com.finora.entity.PlatformSettings());

        authService = new AuthService(
                userRepository, mock(CategoryRepository.class), mock(PasswordResetTokenRepository.class),
                mock(PasswordEncoder.class), mock(JwtService.class), authenticationManager,
                mock(AuditService.class), refreshTokenService, mock(EmailService.class),
                new EmailProperties(), mock(OtpService.class), platformSettingsService
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
        when(userRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(u));
        stubSuccessfulAuthentication();

        authService.login(new LoginRequest("jane@example.com", "Password123"));

        verify(authenticationManager).authenticate(argThat(token ->
                "jane@example.com".equals(token.getPrincipal())));
        verify(userRepository, never()).findByPhoneNumber(any());
    }

    @Test
    void login_withExactPhoneNumberMatch_resolvesToTheAccountsEmail() {
        User u = user("jane@example.com", "+919876500001");
        when(userRepository.findByPhoneNumber("+919876500001")).thenReturn(Optional.of(u));
        when(userRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(u));
        stubSuccessfulAuthentication();

        authService.login(new LoginRequest("+919876500001", "Password123"));

        verify(authenticationManager).authenticate(argThat(token ->
                "jane@example.com".equals(token.getPrincipal())));
    }

    @Test
    void resolveEmailForLogin_findsAccountWhenTypedNumberIsMissingOnlyTheLeadingPlus() {
        // Stored exactly as the registration form's own placeholder shows it ("+91XXXXXXXXXX"),
        // but the user drops the "+" when typing it back in at login.
        User u = user("raj@example.com", "+919876500002");
        when(userRepository.findByPhoneNumber("919876500002")).thenReturn(Optional.empty());
        when(userRepository.findByPhoneNumber("+919876500002")).thenReturn(Optional.of(u));

        Object resolved = ReflectionTestUtils.invokeMethod(authService, "resolveEmailForLogin", "919876500002");

        assertThat(resolved).isEqualTo("raj@example.com");
    }

    @Test
    void login_withUnknownIdentifier_doesNotLeakWhetherAccountExists() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        try {
            authService.login(new LoginRequest("nobody@example.com", "whatever"));
        } catch (Exception e) {
            assertThat(e.getMessage()).isEqualTo("Invalid credentials");
            return;
        }
        throw new AssertionError("Expected login() to throw for an unknown identifier");
    }

    /**
     * Locks in that a suspended account (User.status, see V23__user_account_status.sql and
     * AdminUserService.suspend) is rejected before ever reaching Spring Security's
     * authenticationManager -- a suspended user shouldn't get a "your password was correct"
     * signal, mirroring how the existing lockout check above it in login() behaves.
     */
    @Test
    void login_withSuspendedAccount_isRejectedBeforeAuthenticating() {
        User u = user("suspended@example.com", "+919876500099");
        u.setStatus("SUSPENDED");
        when(userRepository.findByEmail("suspended@example.com")).thenReturn(Optional.of(u));

        try {
            authService.login(new LoginRequest("suspended@example.com", "whatever"));
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("suspended");
            verify(authenticationManager, never()).authenticate(any());
            return;
        }
        throw new AssertionError("Expected login() to throw for a suspended account");
    }

    @Test
    void resolveEmailForLogin_returnsOriginalIdentifierUnchanged_whenNoPhoneNumberMatchesAnyVariant() {
        when(userRepository.findByPhoneNumber(anyString())).thenReturn(Optional.empty());

        Object resolved = ReflectionTestUtils.invokeMethod(authService, "resolveEmailForLogin", "0000000000");

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
    @Test
    void login_locksAccountAfterConfiguredMaxAttempts_notTheOldHardcodedDefault() {
        var settings = new com.finora.entity.PlatformSettings();
        settings.setMaxFailedLoginAttempts(2);
        settings.setLockoutDurationMinutes(30);
        when(platformSettingsService.getEntity()).thenReturn(settings);

        User u = user("locksout@example.com", "+919876500077");
        when(userRepository.findByEmail("locksout@example.com")).thenReturn(Optional.of(u));
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        // First failure: below the configured threshold, no lockout yet.
        try {
            authService.login(new LoginRequest("locksout@example.com", "wrong"));
        } catch (Exception ignored) { /* expected */ }
        assertThat(u.getLockedUntil()).isNull();

        // Second failure: hits the configured max of 2 (not the old hardcoded 5) -- locked now.
        try {
            authService.login(new LoginRequest("locksout@example.com", "wrong"));
        } catch (Exception ignored) { /* expected */ }
        assertThat(u.getLockedUntil()).isNotNull();
        assertThat(u.getLockedUntil()).isAfter(java.time.Instant.now().plusSeconds(29 * 60));
    }
}
