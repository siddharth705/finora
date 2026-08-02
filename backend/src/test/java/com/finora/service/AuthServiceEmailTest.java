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
 * Locks in forgotPassword()'s email-vs-devlink branching: with a configured EmailService, the
 * real email gets sent and the link is NOT exposed in the API response (it would be a needless
 * leak once real delivery exists); with no provider configured, the dev-convenience fallback
 * (returning the link directly) still works exactly as before.
 */
class AuthServiceEmailTest {

    private UserRepository userRepository;
    private PasswordResetTokenRepository resetTokenRepository;
    private EmailService emailService;
    private EmailProperties emailProperties;
    private AuthService authService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        resetTokenRepository = mock(PasswordResetTokenRepository.class);
        emailService = mock(EmailService.class);
        emailProperties = new EmailProperties();
        emailProperties.setAppBaseUrl("http://localhost:5173");

        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setEmail("test@example.com");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        authService = new AuthService(
                userRepository, mock(CategoryRepository.class), resetTokenRepository,
                mock(PasswordEncoder.class), mock(JwtService.class), mock(AuthenticationManager.class),
                mock(AuditService.class), mock(RefreshTokenService.class), emailService, emailProperties,
                mock(PhoneVerificationProvider.class), mock(PlatformSettingsService.class)
        );
    }

    @Test
    void forgotPassword_sendsRealEmail_andOmitsLinkFromResponse_whenEmailServiceConfigured() {
        when(emailService.isConfigured()).thenReturn(true);

        var response = authService.forgotPassword(new ForgotPasswordRequest("test@example.com"), null);

        verify(emailService).sendPasswordResetEmail(eq("test@example.com"), contains("/reset-password?token="));
        assertThat(response.devResetLink()).isNull();
    }

    @Test
    void forgotPassword_returnsLinkDirectly_andDoesNotSendEmail_whenNoProviderConfigured() {
        when(emailService.isConfigured()).thenReturn(false);

        var response = authService.forgotPassword(new ForgotPasswordRequest("test@example.com"), null);

        verify(emailService, never()).sendPasswordResetEmail(any(), any());
        assertThat(response.devResetLink()).contains("/reset-password?token=");
    }

    @Test
    void forgotPassword_doesNotSendEmailOrLeakWhetherAccountExists_forUnknownEmail() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        when(emailService.isConfigured()).thenReturn(true);

        var response = authService.forgotPassword(new ForgotPasswordRequest("nobody@example.com"), null);

        verify(emailService, never()).sendPasswordResetEmail(any(), any());
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
        when(emailService.isConfigured()).thenReturn(false);

        var response = authService.forgotPassword(new ForgotPasswordRequest("test@example.com"), "http://localhost:5174");

        assertThat(response.devResetLink()).startsWith("http://localhost:5174/reset-password?token=");
    }

    @Test
    void forgotPassword_stillLinksToTheUserFrontend_whenTheRequestOriginatedFromIt() {
        emailProperties.setAdminAppBaseUrl("http://localhost:5174");
        when(emailService.isConfigured()).thenReturn(false);

        var response = authService.forgotPassword(new ForgotPasswordRequest("test@example.com"), "http://localhost:5173");

        assertThat(response.devResetLink()).startsWith("http://localhost:5173/reset-password?token=");
    }

    @Test
    void forgotPassword_fallsBackToTheUserFrontend_whenAdminAppBaseUrlWasNeverConfigured() {
        // ADMIN_APP_BASE_URL is optional -- an unconfigured deployment must keep behaving exactly
        // as it did before this fix, not start failing or linking somewhere blank.
        when(emailService.isConfigured()).thenReturn(false);

        var response = authService.forgotPassword(new ForgotPasswordRequest("test@example.com"), "http://localhost:5174");

        assertThat(response.devResetLink()).startsWith("http://localhost:5173/reset-password?token=");
    }

    @Test
    void forgotPassword_fallsBackToTheUserFrontend_whenTheOriginHeaderIsMissing() {
        // A same-origin request (or any client that doesn't send Origin) has no way to signal
        // which app it is -- default to the user frontend rather than erroring.
        emailProperties.setAdminAppBaseUrl("http://localhost:5174");
        when(emailService.isConfigured()).thenReturn(false);

        var response = authService.forgotPassword(new ForgotPasswordRequest("test@example.com"), null);

        assertThat(response.devResetLink()).startsWith("http://localhost:5173/reset-password?token=");
    }
}
