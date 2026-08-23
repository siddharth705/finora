package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.integrations.apple.login.AppleIdentity;
import com.finora.repository.CategoryRepository;
import com.finora.repository.PasswordResetTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * D-23 Phase 2. {@code AuthService.loginWithApple} shares its whole body
 * ({@code loginWithOAuthIdentity}) with {@code loginWithGoogle} — see
 * {@code AuthServiceGoogleLoginTest} for exhaustive coverage of the account-status gate
 * (suspended/deactivated/pending-deletion), not repeated here since it's the identical code path.
 * This class covers what's actually different about Apple: the client-supplied
 * {@code fullName} parameter (Apple's own identity token never carries a name claim — see
 * {@link AppleIdentity}'s own doc comment), and that the audit actions/user-facing copy say
 * "Apple", not "Google" — the one thing a bug in {@code OAuthProvider} threading would silently
 * get wrong without a test that actually asserts on it.
 */
class AuthServiceAppleLoginTest {

    private UserRepository userRepository;
    private CategoryRepository categoryRepository;
    private PasswordEncoder passwordEncoder;
    private RefreshTokenService refreshTokenService;
    private PasswordHistoryService passwordHistoryService;
    private com.finora.repository.AccountReactivationTokenRepository reactivationTokenRepository;
    private com.finora.repository.EmailVerificationTokenRepository emailVerificationTokenRepository;
    private EmailProvider emailProvider;
    private AuditService auditService;
    private PlatformSettingsService platformSettingsService;
    private AuthService authService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        refreshTokenService = mock(RefreshTokenService.class);
        when(refreshTokenService.issue(any())).thenReturn(
                new RefreshTokenService.IssuedToken("test-refresh-token", Instant.now().plusSeconds(3600), UUID.randomUUID()));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) {
                ReflectionTestUtils.setField(u, "id", userId);
            }
            return u;
        });
        when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash-of-a-random-value");

        passwordHistoryService = mock(PasswordHistoryService.class);
        reactivationTokenRepository = mock(com.finora.repository.AccountReactivationTokenRepository.class);
        emailVerificationTokenRepository = mock(com.finora.repository.EmailVerificationTokenRepository.class);
        auditService = mock(AuditService.class);
        emailProvider = mock(EmailProvider.class);
        when(emailProvider.sendEmailVerificationEmail(any(), any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "test-message-id"));
        platformSettingsService = mock(PlatformSettingsService.class);
        when(platformSettingsService.getEntity()).thenReturn(new com.finora.entity.PlatformSettings());

        authService = new AuthService(
                userRepository, categoryRepository, mock(PasswordResetTokenRepository.class),
                reactivationTokenRepository,
                emailVerificationTokenRepository,
                passwordEncoder, mock(JwtService.class), mock(AuthenticationManager.class),
                auditService, refreshTokenService, emailProvider,
                new EmailProperties(), mock(PhoneVerificationProvider.class), platformSettingsService,
                passwordHistoryService, new IdentityLookup(userRepository),
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
        clearInvocations(passwordEncoder);
    }

    private User existingUser(String email, String status) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", userId);
        u.setEmail(email);
        u.setStatus(status);
        u.setPhoneNumber("+919876500001");
        u.setEmailVerified(true);
        return u;
    }

    @Test
    @DisplayName("no existing account, name captured on first authorization -- creates one with no phone number, seeded with default categories")
    void newAccount_withClientProvidedFullName_isCreated() {
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("amy@example.test", "USER"))
                .thenReturn(Optional.empty());

        var response = authService.loginWithApple(
                new AppleIdentity("amy@example.test", "001234.abcd5678.1234"), "Amy Santiago");

        assertThat(response.email()).isEqualTo("amy@example.test");
        assertThat(response.fullName()).isEqualTo("Amy Santiago");
        assertThat(response.phoneVerified()).isFalse();
        assertThat(response.maskedPhone()).isNull();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPhoneNumber()).isNull();
        assertThat(captor.getValue().getAccountScope()).isEqualTo(User.SCOPE_USER);
        assertThat(captor.getValue().isEmailVerified()).isTrue();

        verify(categoryRepository).saveAll(any());
        verify(auditService).record(eq(userId), eq("USER_REGISTERED_APPLE"), eq("User"), eq(userId), any());
    }

    @Test
    @DisplayName("no client-supplied name (every sign-in after the first) -- falls back to the email, not a blank name")
    void newAccount_withNoClientProvidedFullName_fallsBackToEmail() {
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("amy@example.test", "USER"))
                .thenReturn(Optional.empty());

        var response = authService.loginWithApple(new AppleIdentity("amy@example.test", "sub-123"), null);

        assertThat(response.fullName()).isEqualTo("amy@example.test");
    }

    @Test
    @DisplayName("a client-supplied name that fails this app's own full-name rules falls back to the email, not stored unchecked")
    void newAccount_withInvalidClientProvidedFullName_fallsBackToEmail() {
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("amy@example.test", "USER"))
                .thenReturn(Optional.empty());

        var response = authService.loginWithApple(
                new AppleIdentity("amy@example.test", "sub-123"), "<script>Amy</script> 123");

        assertThat(response.fullName()).isEqualTo("amy@example.test");
    }

    @Test
    @DisplayName("self-review fix (Google) applies to Apple too: a NEW account is refused when an admin has disabled public registrations")
    void newAccount_whenRegistrationsAreDisabled_isRefused() {
        var settings = new com.finora.entity.PlatformSettings();
        settings.setRegistrationsEnabled(false);
        when(platformSettingsService.getEntity()).thenReturn(settings);
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("amy@example.test", "USER"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.loginWithApple(
                new AppleIdentity("amy@example.test", "sub-123"), "Amy"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("disabled");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("an existing active account with the same verified email signs in -- auto-link, no duplicate created")
    void existingActiveAccount_autoLinksInsteadOfCreatingADuplicate() {
        User existing = existingUser("jane@example.com", User.STATUS_ACTIVE);
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("jane@example.com", "USER"))
                .thenReturn(Optional.of(existing));

        var response = authService.loginWithApple(
                new AppleIdentity("jane@example.com", "sub-456"), null);

        assertThat(response.email()).isEqualTo("jane@example.com");
        assertThat(response.maskedPhone()).isEqualTo("+•••••••••001");
        verify(userRepository, never()).save(any(User.class));
        verify(auditService).record(eq(userId), eq("USER_LOGIN_APPLE"), eq("User"), eq(userId), any());
    }

    @Test
    @DisplayName("pre-hijacking protection applies to Apple too: refuses to auto-link into an existing account whose email isn't verified yet -- sends a fresh verification link, message says Apple")
    void existingAccount_withUnverifiedEmail_isRefusedAndSentAFreshVerificationLink() {
        User existing = existingUser("jane@example.com", User.STATUS_ACTIVE);
        existing.setEmailVerified(false);
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("jane@example.com", "USER"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> authService.loginWithApple(
                new AppleIdentity("jane@example.com", "sub-456"), "Jane"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("verified")
                .hasMessageContaining("Apple")
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN));

        verify(emailVerificationTokenRepository).save(any());
        verify(emailProvider).sendEmailVerificationEmail(eq("jane@example.com"), anyString());
        verify(auditService).record(eq(userId), eq("EMAIL_SENT"), eq("User"), eq(userId), any());
        verify(refreshTokenService, never()).issue(any());
        verify(auditService, never()).record(any(), eq("USER_LOGIN_APPLE"), any(), any(), any());
    }

    @Test
    @DisplayName("looking up by email is scoped to USER, never ADMIN -- Apple sign-in never touches an admin account")
    void lookupIsScopedToUserAccounts() {
        when(userRepository.findByEmailIgnoreCaseAndAccountScope(anyString(), anyString()))
                .thenReturn(Optional.empty());

        authService.loginWithApple(new AppleIdentity("admin@example.test", "sub-789"), "Admin");

        verify(userRepository).findByEmailIgnoreCaseAndAccountScope("admin@example.test", User.SCOPE_USER);
        verify(userRepository, never()).findByEmailIgnoreCaseAndAccountScope(anyString(), eq(User.SCOPE_ADMIN));
    }
}
