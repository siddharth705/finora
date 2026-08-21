package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.config.RequestMetadata;
import com.finora.dto.AuthDtos.ReactivateRequest;
import com.finora.entity.AccountReactivationToken;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.AccountReactivationTokenRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.PasswordResetTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.util.TokenHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** AuthService.reactivate() -- completes the "Welcome back" confirmation login()'s deactivated
 *  branch starts (see AuthServiceLoginTest's deactivated-login tests for the other half). */
class AuthServiceReactivateTest {

    private UserRepository userRepository;
    private AccountReactivationTokenRepository reactivationTokenRepository;
    private RefreshTokenService refreshTokenService;
    private AuditService auditService;
    private EmailProvider emailProvider;
    private RequestMetadata requestMetadata;
    private AuthService authService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        reactivationTokenRepository = mock(AccountReactivationTokenRepository.class);
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
        when(refreshTokenService.issue(any())).thenReturn(
                new RefreshTokenService.IssuedToken("new-refresh-token", Instant.now().plusSeconds(3600), UUID.randomUUID()));
        when(emailProvider.sendAccountReactivatedEmail(any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "test-message-id"));

        authService = new AuthService(
                userRepository, mock(CategoryRepository.class), mock(PasswordResetTokenRepository.class),
                reactivationTokenRepository,
                mock(com.finora.repository.EmailVerificationTokenRepository.class),
                mock(PasswordEncoder.class), mock(JwtService.class), mock(AuthenticationManager.class),
                auditService, refreshTokenService, emailProvider,
                new EmailProperties(), mock(PhoneVerificationProvider.class), mock(PlatformSettingsService.class),
                mock(PasswordHistoryService.class), new IdentityLookup(userRepository), requestMetadata,
                mock(com.finora.service.SubscriptionService.class),
                // SEC-07: same-thread executor -- runs the dispatched email/audit work
                // synchronously so assertions against it don't race a real background thread.
                Runnable::run,
                // SEC-03: no MFA gate interference for tests unrelated to it -- an
                // unstubbed mock's isEnabled() returns false by default.
                mock(AdminMfaService.class)
        );
    }

    private User deactivatedUser() {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", userId);
        u.setEmail("jane@example.com");
        u.setStatus(User.STATUS_DEACTIVATED);
        return u;
    }

    private AccountReactivationToken token(Instant expiresAt, Instant usedAt) {
        AccountReactivationToken t = new AccountReactivationToken();
        t.setUserId(userId);
        t.setTokenHash(TokenHasher.sha256("raw-token"));
        t.setExpiresAt(expiresAt);
        t.setUsedAt(usedAt);
        return t;
    }

    @Test
    void reactivate_withAValidToken_reactivatesAndIssuesRealTokens() {
        when(reactivationTokenRepository.findByTokenHash(TokenHasher.sha256("raw-token")))
                .thenReturn(Optional.of(token(Instant.now().plusSeconds(600), null)));
        User u = deactivatedUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        var response = authService.reactivate(new ReactivateRequest("raw-token"));

        assertThat(u.getStatus()).isEqualTo(User.STATUS_ACTIVE);
        assertThat(response.refreshToken()).isEqualTo("new-refresh-token");
        verify(auditService).record(eq(userId), eq("ACCOUNT_REACTIVATED"), eq("User"), eq(userId), any());
        verify(auditService).record(userId, "USER_LOGIN", "User", userId);
    }

    @Test
    void reactivate_recordsTheRequestsIpAndDeviceOnTheAuditEntry() {
        when(reactivationTokenRepository.findByTokenHash(TokenHasher.sha256("raw-token")))
                .thenReturn(Optional.of(token(Instant.now().plusSeconds(600), null)));
        User u = deactivatedUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        authService.reactivate(new ReactivateRequest("raw-token"));

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditService).record(eq(userId), eq("ACCOUNT_REACTIVATED"), eq("User"), eq(userId), captor.capture());
        assertThat(captor.getValue()).containsEntry("ip", "203.0.113.7").containsEntry("device", "Chrome on macOS");
    }

    @Test
    void reactivate_withAnExpiredToken_isRejectedAndDoesNotReactivate() {
        when(reactivationTokenRepository.findByTokenHash(TokenHasher.sha256("raw-token")))
                .thenReturn(Optional.of(token(Instant.now().minusSeconds(60), null)));
        User u = deactivatedUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        try {
            authService.reactivate(new ReactivateRequest("raw-token"));
        } catch (ApiException e) {
            assertThat(e.getMessage()).contains("expired");
            assertThat(u.getStatus()).isEqualTo(User.STATUS_DEACTIVATED);
            verify(refreshTokenService, never()).issue(any());
            return;
        }
        throw new AssertionError("Expected reactivate() to throw for an expired token");
    }

    @Test
    void reactivate_withAnAlreadyUsedToken_isRejected() {
        when(reactivationTokenRepository.findByTokenHash(TokenHasher.sha256("raw-token")))
                .thenReturn(Optional.of(token(Instant.now().plusSeconds(600), Instant.now().minusSeconds(60))));

        try {
            authService.reactivate(new ReactivateRequest("raw-token"));
        } catch (ApiException e) {
            assertThat(e.getMessage()).contains("already been used");
            verify(userRepository, never()).findById(any());
            return;
        }
        throw new AssertionError("Expected reactivate() to throw for an already-used token");
    }

    @Test
    void reactivate_withAnUnknownToken_isRejected() {
        when(reactivationTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        try {
            authService.reactivate(new ReactivateRequest("bogus-token"));
        } catch (ApiException e) {
            assertThat(e.getMessage()).contains("invalid");
            return;
        }
        throw new AssertionError("Expected reactivate() to throw for an unknown token");
    }

    /** Race guard: the token is valid, but the account isn't DEACTIVATED anymore -- e.g. an admin
     *  already reactivated it through the admin portal, or two tabs both tried this flow. */
    @Test
    void reactivate_whenTheAccountIsNoLongerDeactivated_isRejected() {
        when(reactivationTokenRepository.findByTokenHash(TokenHasher.sha256("raw-token")))
                .thenReturn(Optional.of(token(Instant.now().plusSeconds(600), null)));
        User u = deactivatedUser();
        u.setStatus(User.STATUS_ACTIVE);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        try {
            authService.reactivate(new ReactivateRequest("raw-token"));
        } catch (ApiException e) {
            assertThat(e.getMessage()).contains("no longer deactivated");
            verify(refreshTokenService, never()).issue(any());
            return;
        }
        throw new AssertionError("Expected reactivate() to throw when the account is no longer deactivated");
    }
}
