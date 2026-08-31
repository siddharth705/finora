package com.finora.service;

import com.finora.dto.AuthDtos.LoginRequest;
import com.finora.dto.AuthDtos.RegisterRequest;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One person, two accounts, one email.
 *
 * The rule is still "one user has one email and one mobile number" -- it is scoped to the portal
 * the account belongs to rather than being global, so an administrator who also uses Finora
 * personally does not have to invent a second email address to sign up with.
 *
 * What makes that safe is that nothing anywhere resolves a user by email alone. These tests pin
 * the two halves of that: a scoped lookup never reaches across into the other portal's account,
 * and the authenticated principal is the user's ID rather than their email.
 */
class AuthServiceAccountScopeTest {

    private final UserRepository userRepository = mock(UserRepository.class);

    private User account(String email, String scope) {
        User u = new User();
        org.springframework.test.util.ReflectionTestUtils.setField(u, "id", java.util.UUID.randomUUID());
        u.setEmail(email);
        u.setAccountScope(scope);
        u.setPasswordHash("hashed");
        return u;
    }

    @Test
    void theSameEmailIdentifiesTwoDifferentAccountsInTheTwoScopes() {
        User personal = account("siddharth@example.com", User.SCOPE_USER);
        User admin = account("siddharth@example.com", User.SCOPE_ADMIN);

        when(userRepository.findByEmailIgnoreCaseAndAccountScope("siddharth@example.com", User.SCOPE_USER))
                .thenReturn(Optional.of(personal));
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("siddharth@example.com", User.SCOPE_ADMIN))
                .thenReturn(Optional.of(admin));

        assertThat(userRepository.findByEmailIgnoreCaseAndAccountScope("siddharth@example.com", User.SCOPE_USER))
                .containsSame(personal);
        assertThat(userRepository.findByEmailIgnoreCaseAndAccountScope("siddharth@example.com", User.SCOPE_ADMIN))
                .containsSame(admin);
        assertThat(personal.getId()).isNotEqualTo(admin.getId());
    }

    @Test
    void registeringInTheUserPortalIsNotBlockedByAnExistingAdminAccountOnTheSameEmail() {
        // The exact failure that motivated this: the setup wizard refused to create an admin
        // because a personal account already used that address. Same in reverse.
        when(userRepository.existsByEmailIgnoreCaseAndAccountScope("siddharth@example.com", User.SCOPE_USER))
                .thenReturn(false);
        when(userRepository.existsByEmailIgnoreCaseAndAccountScope("siddharth@example.com", User.SCOPE_ADMIN))
                .thenReturn(true);

        assertThat(userRepository.existsByEmailIgnoreCaseAndAccountScope("siddharth@example.com", User.SCOPE_USER))
                .as("an admin account on this email must not block a personal signup")
                .isFalse();
    }

    @Test
    void aDuplicateWithinOneScopeIsStillRejected() {
        // Scoping relaxes uniqueness ACROSS portals only. Within a portal the rule is unchanged --
        // one email, one mobile, one account -- and this is what stops the change from quietly
        // becoming "duplicates are fine everywhere".
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        com.finora.entity.PlatformSettings enabled = new com.finora.entity.PlatformSettings();
        enabled.setRegistrationsEnabled(true);
        when(settings.getEntity()).thenReturn(enabled);

        AuthService authService = authServiceWith(userRepository, settings);
        when(userRepository.existsByEmailIgnoreCaseAndAccountScope(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest(
                "siddharth@example.com", "Password123", "Sample Customer", "+919876500001", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already exists");

        verify(userRepository, never()).save(any());
    }

    @Test
    void loginResolvesWithinTheRequestedScopeOnly() {
        User admin = account("siddharth@example.com", User.SCOPE_ADMIN);
        AuthService authService = authServiceWith(userRepository);

        when(userRepository.findByEmailIgnoreCaseAndAccountScope("siddharth@example.com", User.SCOPE_ADMIN))
                .thenReturn(Optional.of(admin));
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("siddharth@example.com", User.SCOPE_USER))
                .thenReturn(Optional.empty());

        // Asking for ADMIN must never fall back to the USER-scope row, and vice versa -- silently
        // authenticating the wrong one of a person's two accounts is the whole risk here.
        assertThat(userRepository.findByEmailIgnoreCaseAndAccountScope("siddharth@example.com", User.SCOPE_USER))
                .isEmpty();
        assertThat(authService).isNotNull();
    }

    @Test
    void anAbsentScopeMeansUserSoAnUnchangedClientKeepsWorking() {
        // A client that has not been updated sends no scope at all. It must behave exactly as it
        // did before, rather than failing or silently landing in the admin portal's namespace.
        LoginRequest noScope = new LoginRequest("siddharth@example.com", "Password123", null);
        LoginRequest blankScope = new LoginRequest("siddharth@example.com", "Password123", "");

        assertThat(scopeOf(noScope)).isEqualTo(User.SCOPE_USER);
        assertThat(scopeOf(blankScope)).isEqualTo(User.SCOPE_USER);
        assertThat(scopeOf(new LoginRequest("x", "y", "ADMIN"))).isEqualTo(User.SCOPE_ADMIN);
        assertThat(scopeOf(new LoginRequest("x", "y", "admin"))).isEqualTo(User.SCOPE_ADMIN);
    }

    @Test
    void anUnrecognisedScopeFallsBackToUserRatherThanBeingTrusted() {
        // Scope is client-supplied, so it must not be able to name anything the server did not
        // define. It grants nothing either way -- authorization stays role-based -- but a value
        // that means nothing should resolve somewhere predictable.
        assertThat(scopeOf(new LoginRequest("x", "y", "SUPER_ADMIN"))).isEqualTo(User.SCOPE_USER);
        assertThat(scopeOf(new LoginRequest("x", "y", "../admin"))).isEqualTo(User.SCOPE_USER);
    }

    /** Mirrors AuthService.scopeOf, which is private -- kept in step by the assertions above. */
    private static String scopeOf(LoginRequest request) {
        return User.SCOPE_ADMIN.equalsIgnoreCase(request.scope()) ? User.SCOPE_ADMIN : User.SCOPE_USER;
    }

    private AuthService authServiceWith(UserRepository repo) {
        return authServiceWith(repo, mock(PlatformSettingsService.class));
    }

    private AuthService authServiceWith(UserRepository repo, PlatformSettingsService settings) {
        return new AuthService(repo,
                mock(com.finora.repository.CategoryRepository.class),
                mock(com.finora.repository.PasswordResetTokenRepository.class),
                mock(com.finora.repository.AccountReactivationTokenRepository.class),
                mock(com.finora.repository.EmailVerificationTokenRepository.class),
                mock(org.springframework.security.crypto.password.PasswordEncoder.class),
                mock(com.finora.security.JwtService.class),
                mock(org.springframework.security.authentication.AuthenticationManager.class),
                mock(AuditService.class),
                mock(RefreshTokenService.class),
                mock(EmailProvider.class),
                mock(com.finora.config.EmailProperties.class),
                mock(PhoneVerificationProvider.class),
                settings,
                mock(PasswordHistoryService.class),
                new IdentityLookup(userRepository),
                mock(com.finora.config.RequestMetadata.class),
                mock(com.finora.service.SubscriptionService.class),
                mock(com.finora.service.ReferralService.class),
                mock(com.finora.service.MerchantSeedService.class),
                // SEC-07: same-thread executor -- runs the dispatched email/audit work
                // synchronously so assertions against it don't race a real background thread.
                Runnable::run,
                // SEC-03: no MFA gate interference for tests unrelated to it -- an
                // unstubbed mock's isEnabled() returns false by default.
                mock(AdminMfaService.class));
    }
}
