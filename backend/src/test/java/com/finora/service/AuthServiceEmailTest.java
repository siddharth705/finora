package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.dto.AuthDtos.ForgotPasswordRequest;
import com.finora.entity.User;
import com.finora.repository.CategoryRepository;
import com.finora.repository.PasswordResetTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Locks in forgotPassword()'s email-vs-devlink branching: with a configured EmailProvider, the
 * real email gets sent and the link is NOT exposed in the API response (it would be a needless
 * leak once real delivery exists); with no provider configured, the dev-convenience fallback
 * (returning the link directly) still works exactly as before.
 */
class AuthServiceEmailTest {

    private UserRepository userRepository;
    private PasswordResetTokenRepository resetTokenRepository;
    private EmailProvider emailProvider;
    private EmailProperties emailProperties;
    private AuditService auditService;
    private AuthService authService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        resetTokenRepository = mock(PasswordResetTokenRepository.class);
        emailProvider = mock(EmailProvider.class);
        emailProperties = new EmailProperties();
        emailProperties.setAppBaseUrl("http://localhost:5173");
        auditService = mock(AuditService.class);

        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setEmail("test@example.com");
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("test@example.com", "USER")).thenReturn(Optional.of(user));

        authService = new AuthService(
                userRepository, mock(CategoryRepository.class), resetTokenRepository,
                mock(com.finora.repository.AccountReactivationTokenRepository.class),
                mock(com.finora.repository.EmailVerificationTokenRepository.class),
                mock(PasswordEncoder.class), mock(JwtService.class), mock(AuthenticationManager.class),
                auditService, mock(RefreshTokenService.class), emailProvider, emailProperties,
                mock(PhoneVerificationProvider.class), mock(PlatformSettingsService.class),
                mock(PasswordHistoryService.class), new IdentityLookup(userRepository),
                mock(com.finora.config.RequestMetadata.class),
                mock(com.finora.service.SubscriptionService.class),
                mock(com.finora.service.ReferralService.class),
                mock(com.finora.service.MerchantSeedService.class),
                // SEC-07: same-thread executor -- runs the dispatched email/audit work
                // synchronously so assertions against it don't race a real background thread.
                Runnable::run,
                // SEC-03: no MFA gate interference for tests unrelated to it -- an
                // unstubbed mock's isEnabled() returns false by default.
                mock(AdminMfaService.class)
        );
    }

    @Test
    void forgotPassword_sendsRealEmail_andOmitsLinkFromResponse_whenEmailProviderConfigured() {
        when(emailProvider.isConfigured()).thenReturn(true);
        when(emailProvider.sendPasswordResetEmail(any(), any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "test-message-id"));

        var response = authService.forgotPassword(new ForgotPasswordRequest("test@example.com", "USER"), null);

        verify(emailProvider).sendPasswordResetEmail(eq("test@example.com"), contains("/reset-password?token="));
        assertThat(response.devResetLink()).isNull();
        verify(auditService).recordEvenOnRollback(eq(userId), eq("EMAIL_SENT"), eq("User"), eq(userId),
                argThat(metadata -> "password_reset".equals(metadata.get("type")) && Boolean.TRUE.equals(metadata.get("success"))));
    }

    /**
     * SEC-07 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). The other tests
     * in this file use a same-thread executor for determinism, which -- correctly -- makes them
     * unable to tell a dispatched call from an inline one. This test uses a capturing executor
     * instead, specifically to prove the property those others can't: forgotPassword() returns
     * without the send having happened yet, and the send only happens once the captured work is
     * actually run. Before this fix, the send was called inline, synchronously, before the method
     * returned -- indistinguishable from the "no matching account" branch only in payload, not in
     * how long either one took.
     */
    @Test
    void forgotPassword_dispatchesTheEmailSendRatherThanBlockingOnItInline() {
        java.util.concurrent.atomic.AtomicReference<Runnable> captured = new java.util.concurrent.atomic.AtomicReference<>();
        AuthService dispatchingAuthService = new AuthService(
                userRepository, mock(CategoryRepository.class), resetTokenRepository,
                mock(com.finora.repository.AccountReactivationTokenRepository.class),
                mock(com.finora.repository.EmailVerificationTokenRepository.class),
                mock(PasswordEncoder.class), mock(JwtService.class), mock(AuthenticationManager.class),
                auditService, mock(RefreshTokenService.class), emailProvider, emailProperties,
                mock(PhoneVerificationProvider.class), mock(PlatformSettingsService.class),
                mock(PasswordHistoryService.class), new IdentityLookup(userRepository),
                mock(com.finora.config.RequestMetadata.class),
                mock(com.finora.service.SubscriptionService.class),
                mock(com.finora.service.ReferralService.class),
                mock(com.finora.service.MerchantSeedService.class),
                captured::set, // records the work instead of running it
                mock(AdminMfaService.class)
        );
        when(emailProvider.isConfigured()).thenReturn(true);
        when(emailProvider.sendPasswordResetEmail(any(), any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "test-message-id"));

        dispatchingAuthService.forgotPassword(new ForgotPasswordRequest("test@example.com", "USER"), null);

        // The method has already returned, and the send has NOT happened yet.
        verify(emailProvider, never()).sendPasswordResetEmail(any(), any());
        assertThat(captured.get()).as("the send must have been submitted to the executor").isNotNull();

        captured.get().run();

        verify(emailProvider).sendPasswordResetEmail(eq("test@example.com"), contains("/reset-password?token="));
    }

    @Test
    void forgotPassword_returnsLinkDirectly_andDoesNotSendEmail_whenNoProviderConfigured() {
        when(emailProvider.isConfigured()).thenReturn(false);

        var response = authService.forgotPassword(new ForgotPasswordRequest("test@example.com", "USER"), null);

        verify(emailProvider, never()).sendPasswordResetEmail(any(), any());
        assertThat(response.devResetLink()).contains("/reset-password?token=");
    }

    /**
     * Bug fix: case-insensitive email uniqueness was never enforced before this session, so two
     * pre-existing accounts could differ only by case. findByEmailIgnoreCaseAndAccountScope(, "USER") throws
     * IncorrectResultSizeDataAccessException if it matches more than one row -- forgotPassword()
     * must fail closed to the same generic "if an account exists..." response every unresolvable
     * email gets, not bubble up as an opaque 500.
     */
    @Test
    void forgotPassword_whenEmailIgnoreCaseLookupIsAmbiguous_failsClosedInsteadOf500ing() {
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("jane@example.com", "USER"))
                .thenThrow(new org.springframework.dao.IncorrectResultSizeDataAccessException(1));

        var response = authService.forgotPassword(new ForgotPasswordRequest("jane@example.com", "USER"), null);

        verify(emailProvider, never()).sendPasswordResetEmail(any(), any());
        assertThat(response.devResetLink()).isNull();
    }

    @Test
    void forgotPassword_doesNotSendEmailOrLeakWhetherAccountExists_forUnknownEmail() {
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("nobody@example.com", "USER")).thenReturn(Optional.empty());
        when(emailProvider.isConfigured()).thenReturn(true);

        var response = authService.forgotPassword(new ForgotPasswordRequest("nobody@example.com", "USER"), null);

        verify(emailProvider, never()).sendPasswordResetEmail(any(), any());
        assertThat(response.devResetLink()).isNull();
        assertThat(response.message()).isEqualTo("If an account exists for that email, we've sent a password reset link.");
    }

    /**
     * Bug fix: the user frontend and admin portal are two separate deployed apps at two
     * different origins, each with its own /reset-password page -- but there's no separate admin
     * auth service, so an admin's "Forgot Password" went through this exact same method. It used
     * to build every reset link from the single user-frontend base URL unconditionally, so an
     * admin got an email linking to the wrong app's reset-password page entirely.
     */
    @Test
    void forgotPassword_linksToTheAdminPortal_whenTheRequestOriginatedFromIt() {
        emailProperties.setAdminAppBaseUrl("http://localhost:5174");
        when(emailProvider.isConfigured()).thenReturn(false);

        var response = authService.forgotPassword(new ForgotPasswordRequest("test@example.com", "USER"), "http://localhost:5174");

        assertThat(response.devResetLink()).startsWith("http://localhost:5174/reset-password?token=");
    }

    @Test
    void forgotPassword_stillLinksToTheUserFrontend_whenTheRequestOriginatedFromIt() {
        emailProperties.setAdminAppBaseUrl("http://localhost:5174");
        when(emailProvider.isConfigured()).thenReturn(false);

        var response = authService.forgotPassword(new ForgotPasswordRequest("test@example.com", "USER"), "http://localhost:5173");

        assertThat(response.devResetLink()).startsWith("http://localhost:5173/reset-password?token=");
    }

    @Test
    void forgotPassword_fallsBackToTheUserFrontend_whenAdminAppBaseUrlWasNeverConfigured() {
        // ADMIN_APP_BASE_URL is optional -- an unconfigured deployment must keep behaving exactly
        // as it did before this fix, not start failing or linking somewhere blank.
        when(emailProvider.isConfigured()).thenReturn(false);

        var response = authService.forgotPassword(new ForgotPasswordRequest("test@example.com", "USER"), "http://localhost:5174");

        assertThat(response.devResetLink()).startsWith("http://localhost:5173/reset-password?token=");
    }

    @Test
    void forgotPassword_fallsBackToTheUserFrontend_whenTheOriginHeaderIsMissing() {
        // A same-origin request (or any client that doesn't send Origin) has no way to signal
        // which app it is -- default to the user frontend rather than erroring.
        emailProperties.setAdminAppBaseUrl("http://localhost:5174");
        when(emailProvider.isConfigured()).thenReturn(false);

        var response = authService.forgotPassword(new ForgotPasswordRequest("test@example.com", "USER"), null);

        assertThat(response.devResetLink()).startsWith("http://localhost:5173/reset-password?token=");
    }
}
