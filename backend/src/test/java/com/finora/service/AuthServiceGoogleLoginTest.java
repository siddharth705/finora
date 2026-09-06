package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.integrations.google.login.GoogleIdentity;
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
 * D-23. {@code AuthService.loginWithGoogle} — a verified {@link GoogleIdentity} either signs into
 * an existing account (auto-link, per D-23's own recorded decision) or creates a new one; either
 * way it must reach the SAME account-status gate {@code login()} already enforces
 * (suspended/deactivated/pending-deletion/deleted), never a shortcut around it. Matches
 * {@code AuthServiceLoginTest}'s own setup pattern exactly, for the same class under test.
 */
class AuthServiceGoogleLoginTest {

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
        // Every save() this class does just needs to hand back the same entity it was given,
        // including a real generated id -- the entity itself is what later assertions inspect.
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
                mock(com.finora.service.MerchantSeedService.class),
                // SEC-07: same-thread executor -- runs the dispatched email/audit work
                // synchronously so assertions against it don't race a real background thread.
                Runnable::run,
                // SEC-03: no MFA gate interference for tests unrelated to it -- an
                // unstubbed mock's isEnabled() returns false by default.
                mock(AdminMfaService.class)
        );
        // AuthService's own constructor calls passwordEncoder.encode() once, to build the BH-014
        // timing-parity hash (see that field's own doc comment) -- clearing invocations here so
        // tests asserting on encode() calls only see the ones their own action triggers.
        clearInvocations(passwordEncoder);
    }

    // emailVerified=true by default -- every test using this helper is about the account-STATUS
    // gate (suspended/deactivated/pending-deletion/active), not the email-verification gate that
    // runs before it; a dedicated test below covers the unverified case on its own so it isn't
    // silently masked by whichever status check happens to run second.
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
    @DisplayName("no existing account -- creates one with no phone number, unverified, seeded with default categories")
    void newAccount_isCreatedWithNoPhoneNumberAndPhoneUnverified() {
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("amy@example.test", "USER"))
                .thenReturn(Optional.empty());

        var response = authService.loginWithGoogle(new GoogleIdentity("amy@example.test", "Amy Santiago"));

        assertThat(response.email()).isEqualTo("amy@example.test");
        assertThat(response.fullName()).isEqualTo("Amy Santiago");
        assertThat(response.phoneVerified()).isFalse();
        assertThat(response.maskedPhone()).isNull();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPhoneNumber()).isNull();
        assertThat(captor.getValue().getAccountScope()).isEqualTo(User.SCOPE_USER);
        // Google's own verified-email claim is itself sufficient proof for a BRAND NEW account --
        // there's no separate password anyone else could hold, so nothing to gate.
        assertThat(captor.getValue().isEmailVerified()).isTrue();

        verify(categoryRepository).saveAll(any());
        verify(auditService).record(eq(userId), eq("USER_REGISTERED_GOOGLE"), eq("User"), eq(userId), any());
    }

    @Test
    @DisplayName("no display name from Google -- falls back to the email rather than a blank name")
    void newAccount_withNoDisplayName_fallsBackToEmail() {
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("amy@example.test", "USER"))
                .thenReturn(Optional.empty());

        var response = authService.loginWithGoogle(new GoogleIdentity("amy@example.test", null));

        assertThat(response.fullName()).isEqualTo("amy@example.test");
    }

    @Test
    @DisplayName("a display name that fails this app's own full-name rules falls back to the email, not stored unchecked")
    void newAccount_withInvalidDisplayName_fallsBackToEmail() {
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("amy@example.test", "USER"))
                .thenReturn(Optional.empty());

        // Google's own `name` claim is self-reported to Google, not constrained the way this
        // app's own @Pattern-validated registration field is -- digits/symbols here stand in for
        // anything that wouldn't pass RegisterRequest.fullName's own validation.
        var response = authService.loginWithGoogle(new GoogleIdentity("amy@example.test", "<script>Amy</script> 123"));

        assertThat(response.fullName()).isEqualTo("amy@example.test");
    }

    @Test
    @DisplayName("each new Google account gets its own random, unguessable password hash -- never a shared or predictable value")
    void newAccount_getsARandomPasswordHash_neverAKnownValue() {
        when(userRepository.findByEmailIgnoreCaseAndAccountScope(anyString(), eq("USER")))
                .thenReturn(Optional.empty());

        authService.loginWithGoogle(new GoogleIdentity("amy@example.test", "Amy"));

        ArgumentCaptor<String> rawPasswordCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(rawPasswordCaptor.capture());
        // Long enough that it isn't a short, guessable placeholder -- 32 random bytes, base64
        // encoded, is 44 characters.
        assertThat(rawPasswordCaptor.getValue()).hasSize(44);
    }

    @Test
    @DisplayName("self-review fix: a NEW account via Google is refused, same as register(), when an admin has disabled public registrations")
    void newAccount_whenRegistrationsAreDisabled_isRefused() {
        var settings = new com.finora.entity.PlatformSettings();
        settings.setRegistrationsEnabled(false);
        when(platformSettingsService.getEntity()).thenReturn(settings);
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("amy@example.test", "USER"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.loginWithGoogle(new GoogleIdentity("amy@example.test", "Amy")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("disabled");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("the registrations-disabled gate does NOT block signing into an EXISTING account -- that isn't a new registration")
    void existingAccount_signsInEvenWhenRegistrationsAreDisabled() {
        var settings = new com.finora.entity.PlatformSettings();
        settings.setRegistrationsEnabled(false);
        when(platformSettingsService.getEntity()).thenReturn(settings);
        User existing = existingUser("jane@example.com", User.STATUS_ACTIVE);
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("jane@example.com", "USER"))
                .thenReturn(Optional.of(existing));

        var response = authService.loginWithGoogle(new GoogleIdentity("jane@example.com", "Jane Doe"));

        assertThat(response.email()).isEqualTo("jane@example.com");
    }

    @Test
    @DisplayName("an existing active account with the same verified email signs in -- auto-link, no duplicate created")
    void existingActiveAccount_autoLinksInsteadOfCreatingADuplicate() {
        User existing = existingUser("jane@example.com", User.STATUS_ACTIVE);
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("jane@example.com", "USER"))
                .thenReturn(Optional.of(existing));

        var response = authService.loginWithGoogle(new GoogleIdentity("jane@example.com", "Jane Doe"));

        assertThat(response.email()).isEqualTo("jane@example.com");
        // The EXISTING account's own phone/verification state comes through -- proves this really
        // signed into the pre-existing row rather than fabricating a fresh response.
        assertThat(response.maskedPhone()).isEqualTo("+•••••••••001");
        verify(userRepository, never()).save(any(User.class));
        verify(auditService).record(eq(userId), eq("USER_LOGIN_GOOGLE"), eq("User"), eq(userId), any());
    }

    @Test
    @DisplayName("self-review fix: Google sign-in refuses to auto-link into an existing account whose email isn't verified yet -- and sends a fresh verification link instead")
    void existingAccount_withUnverifiedEmail_isRefusedAndSentAFreshVerificationLink() {
        User existing = existingUser("jane@example.com", User.STATUS_ACTIVE);
        existing.setEmailVerified(false);
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("jane@example.com", "USER"))
                .thenReturn(Optional.of(existing));

        // Without this gate, an attacker who pre-registered jane@example.com with a password of
        // their own choosing would have this call sign the real Jane straight into the attacker's
        // account -- see loginWithGoogle's own doc comment on the self-review finding this closes.
        assertThatThrownBy(() -> authService.loginWithGoogle(new GoogleIdentity("jane@example.com", "Jane")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("verified")
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN));

        // A genuine path forward for the real owner: a fresh token minted and a fresh email sent,
        // not a permanent dead end.
        verify(emailVerificationTokenRepository).save(any());
        verify(emailProvider).sendEmailVerificationEmail(eq("jane@example.com"), anyString());
        verify(auditService).recordEvenOnRollback(eq(userId), eq("EMAIL_SENT"), eq("User"), eq(userId), any());
        // Never actually signed in -- no session issued, no login recorded.
        verify(refreshTokenService, never()).issue(any());
        verify(auditService, never()).record(any(), eq("USER_LOGIN_GOOGLE"), any(), any(), any());
    }

    @Test
    @DisplayName("a suspended account cannot sign in via Google either -- the same gate login() enforces")
    void suspendedAccount_isRefusedViaGoogleToo() {
        User existing = existingUser("jane@example.com", User.STATUS_SUSPENDED);
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("jane@example.com", "USER"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> authService.loginWithGoogle(new GoogleIdentity("jane@example.com", "Jane")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("suspended");
        verify(auditService, never()).record(any(), eq("USER_LOGIN_GOOGLE"), any(), any(), any());
    }

    @Test
    @DisplayName("a deactivated account gets the same reactivation-token response Google-signing-in as password login does")
    void deactivatedAccount_getsTheReactivationFlowViaGoogleToo() {
        User existing = existingUser("jane@example.com", User.STATUS_DEACTIVATED);
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("jane@example.com", "USER"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> authService.loginWithGoogle(new GoogleIdentity("jane@example.com", "Jane")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getDetails()).containsKey("reactivationToken"));
    }

    @Test
    @DisplayName("a pending-deletion account is a dead end via Google too, same as password login")
    void pendingDeletionAccount_isRefusedViaGoogleToo() {
        User existing = existingUser("jane@example.com", User.STATUS_PENDING_DELETION);
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("jane@example.com", "USER"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> authService.loginWithGoogle(new GoogleIdentity("jane@example.com", "Jane")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("scheduled for deletion");
    }

    @Test
    @DisplayName("looking up by email is scoped to USER, never ADMIN -- Google sign-in never touches an admin account")
    void lookupIsScopedToUserAccounts() {
        when(userRepository.findByEmailIgnoreCaseAndAccountScope(anyString(), anyString()))
                .thenReturn(Optional.empty());

        authService.loginWithGoogle(new GoogleIdentity("admin@example.test", "Admin"));

        verify(userRepository).findByEmailIgnoreCaseAndAccountScope("admin@example.test", User.SCOPE_USER);
        verify(userRepository, never()).findByEmailIgnoreCaseAndAccountScope(anyString(), eq(User.SCOPE_ADMIN));
    }
}
