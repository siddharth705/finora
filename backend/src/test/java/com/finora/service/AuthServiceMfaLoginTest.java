package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.dto.AuthDtos.AuthResponse;
import com.finora.dto.AuthDtos.LoginRequest;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.CategoryRepository;
import com.finora.repository.PasswordResetTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SEC-03 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). login()'s MFA gate
 * and its completion via {@code completeMfaLogin} -- see {@code AdminMfaServiceTest} for the
 * challenge/TOTP verification logic itself, which is mocked here so this file stays about the
 * gate's own placement and behavior (which scope it applies to, when it fires relative to the
 * password check, and that a real session only begins once it's satisfied).
 */
class AuthServiceMfaLoginTest {

    private UserRepository userRepository;
    private AuthenticationManager authenticationManager;
    private RefreshTokenService refreshTokenService;
    private PlatformSettingsService platformSettingsService;
    private AuditService auditService;
    private com.finora.config.RequestMetadata requestMetadata;
    private AdminMfaService adminMfaService;
    private AuthService authService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        authenticationManager = mock(AuthenticationManager.class);
        when(authenticationManager.authenticate(any())).thenReturn(mock(Authentication.class));
        refreshTokenService = mock(RefreshTokenService.class);
        when(refreshTokenService.issue(any())).thenReturn(
                new RefreshTokenService.IssuedToken("test-refresh-token", Instant.now().plusSeconds(3600), UUID.randomUUID()));
        platformSettingsService = mock(PlatformSettingsService.class);
        when(platformSettingsService.getEntity()).thenReturn(new com.finora.entity.PlatformSettings());
        auditService = mock(AuditService.class);
        adminMfaService = mock(AdminMfaService.class);
        requestMetadata = mock(com.finora.config.RequestMetadata.class);
        when(requestMetadata.addTo(any())).thenAnswer(inv -> inv.getArgument(0));
        // app.admin-mfa.enabled -- on here so every test below exercises the gate's own placement
        // and behavior exactly as it worked before that flag existed. See the "feature flag"
        // section for flag-off behavior, which overrides this to false per test.
        when(adminMfaService.isFeatureEnabled()).thenReturn(true);

        authService = new AuthService(
                userRepository, mock(CategoryRepository.class), mock(PasswordResetTokenRepository.class),
                mock(com.finora.repository.AccountReactivationTokenRepository.class),
                mock(com.finora.repository.EmailVerificationTokenRepository.class),
                mock(PasswordEncoder.class), mock(JwtService.class), authenticationManager,
                auditService, refreshTokenService, mock(EmailProvider.class),
                new EmailProperties(), mock(PhoneVerificationProvider.class), platformSettingsService,
                mock(PasswordHistoryService.class), new IdentityLookup(userRepository),
                requestMetadata,
                mock(com.finora.service.SubscriptionService.class),
                mock(com.finora.service.ReferralService.class),
                Runnable::run,
                adminMfaService
        );
    }

    private User adminUser() {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", userId);
        u.setEmail("admin@example.com");
        u.setAccountScope(User.SCOPE_ADMIN);
        return u;
    }

    private User consumerUser() {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", userId);
        u.setEmail("jane@example.com");
        return u; // accountScope defaults to SCOPE_USER
    }

    @Test
    void login_forAnAdminWithMfaEnabled_throwsMfaRequired_withAChallengeTokenInDetails_insteadOfIssuingTokens() {
        User admin = adminUser();
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("admin@example.com", "ADMIN")).thenReturn(Optional.of(admin));
        when(adminMfaService.isEnabled(userId)).thenReturn(true);
        when(adminMfaService.issueChallenge(userId)).thenReturn("raw-challenge-token");

        assertThatThrownBy(() -> authService.login(new LoginRequest("admin@example.com", "the-right-password", "ADMIN")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.AUTH_MFA_REQUIRED);
                    assertThat(e.getDetails()).containsEntry("mfaChallengeToken", "raw-challenge-token");
                });

        // No session actually began -- this is only the first factor.
        verify(refreshTokenService, never()).issue(any());
        verify(auditService, never()).record(any(), eq("USER_LOGIN"), any(), any(), any());
    }

    @Test
    void login_forAnAdminWithMfaNotEnabled_signsInNormally() {
        User admin = adminUser();
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("admin@example.com", "ADMIN")).thenReturn(Optional.of(admin));
        when(adminMfaService.isEnabled(userId)).thenReturn(false);

        AuthResponse response = authService.login(new LoginRequest("admin@example.com", "the-right-password", "ADMIN"));

        assertThat(response.refreshToken()).isEqualTo("test-refresh-token");
        verify(adminMfaService, never()).issueChallenge(any());
        verify(auditService).record(eq(userId), eq("USER_LOGIN"), eq("User"), eq(userId), any());
    }

    @Test
    void login_forAConsumerAccount_neverChecksMfaAtAll_evenIfSomehowEnabled() {
        User consumer = consumerUser();
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("jane@example.com", "USER")).thenReturn(Optional.of(consumer));
        // Deliberately stubbed true, to prove login() doesn't even ask for a SCOPE_USER account --
        // the check is gated on accountScope before it ever calls isEnabled().
        when(adminMfaService.isEnabled(userId)).thenReturn(true);

        AuthResponse response = authService.login(new LoginRequest("jane@example.com", "the-right-password", "USER"));

        assertThat(response.refreshToken()).isEqualTo("test-refresh-token");
        verify(adminMfaService, never()).isEnabled(any());
    }

    @Test
    void completeMfaLogin_onASuccessfulChallenge_issuesRealTokensAndRecordsUserLoginWithMfaFlag() {
        User admin = adminUser();
        when(adminMfaService.verifyChallenge("raw-challenge-token", "123456")).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(admin));

        AuthResponse response = authService.completeMfaLogin("raw-challenge-token", "123456");

        assertThat(response.refreshToken()).isEqualTo("test-refresh-token");
        assertThat(response.email()).isEqualTo("admin@example.com");
        verify(refreshTokenService).issue(userId);
        verify(auditService).record(eq(userId), eq("USER_LOGIN"), eq("User"), eq(userId),
                argThat(metadata -> Boolean.TRUE.equals(metadata.get("mfa"))));
    }

    @Test
    void completeMfaLogin_propagatesAdminMfaServicesRejection_withoutIssuingAnyTokens() {
        when(adminMfaService.verifyChallenge("raw-challenge-token", "000000"))
                .thenThrow(new ApiException(ErrorCode.AUTH_MFA_INVALID_CODE));

        assertThatThrownBy(() -> authService.completeMfaLogin("raw-challenge-token", "000000"))
                .isInstanceOf(ApiException.class);
        verify(refreshTokenService, never()).issue(any());
        verify(auditService, never()).record(any(), eq("USER_LOGIN"), any(), any(), any());
    }

    // --- feature flag (app.admin-mfa.enabled) ---
    //
    // Sid's decision: keep this off until the admin portal has an MFA UI. login() must not get
    // stuck requiring an MFA step that has no UI -- even for an admin whose credential row
    // somehow exists (enrolled before this flag existed, or written directly to the database) --
    // so with the flag off, login() must skip the gate entirely rather than ask AdminMfaService
    // whether that account has MFA enabled.

    @Test
    void login_forAnAdminWithMfaEnabled_butFeatureFlagOff_signsInNormally_neverConsultingMfaState() {
        User admin = adminUser();
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("admin@example.com", "ADMIN")).thenReturn(Optional.of(admin));
        when(adminMfaService.isFeatureEnabled()).thenReturn(false);
        // Deliberately stubbed true, to prove login() doesn't even ask -- the flag check short-
        // circuits isEnabled() the same way SCOPE_ADMIN itself does for a consumer account.
        when(adminMfaService.isEnabled(userId)).thenReturn(true);

        AuthResponse response = authService.login(new LoginRequest("admin@example.com", "the-right-password", "ADMIN"));

        assertThat(response.refreshToken()).isEqualTo("test-refresh-token");
        verify(adminMfaService, never()).isEnabled(any());
        verify(adminMfaService, never()).issueChallenge(any());
        verify(auditService).record(eq(userId), eq("USER_LOGIN"), eq("User"), eq(userId), any());
    }

    @Test
    void completeMfaLogin_whenFeatureFlagOff_throwsNotAvailable_withoutConsultingAnyChallenge() {
        when(adminMfaService.isFeatureEnabled()).thenReturn(false);

        assertThatThrownBy(() -> authService.completeMfaLogin("raw-challenge-token", "123456"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.AUTH_MFA_NOT_AVAILABLE));

        verify(adminMfaService, never()).verifyChallenge(any(), any());
        verify(refreshTokenService, never()).issue(any());
        verify(auditService, never()).record(any(), eq("USER_LOGIN"), any(), any(), any());
    }
}
