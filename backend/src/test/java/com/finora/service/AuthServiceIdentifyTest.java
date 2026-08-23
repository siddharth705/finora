package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.dto.AuthDtos.IdentifyRequest;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AuthService.identify() -- the identifier-first entry step (Finora auth/security review §2.2,
 * docs/proposals/authentication-account-security-review.md): given an email or phone, tells the
 * client what to show next (EXISTS / CONTINUE) without exposing a raw account-existence boolean.
 * Reuses resolveEmailForLogin's email-or-phone resolution so behavior stays consistent with
 * login() itself; oracle-safety of that resolution (case handling, phone variants) is already
 * covered by AuthServiceLoginTest and LoginExistenceOracleIT and is not re-tested here.
 *
 * Phase 7 hardening (resolved 2026-08-23): nextAction used to mirror the account's
 * signInMethod exactly (PASSWORD/GOOGLE/APPLE), letting a caller learn which sign-in method an
 * existing account uses before ever attempting to sign in. Collapsed to a single EXISTS value
 * regardless of method -- see IdentifyResponse's own doc comment for the full reasoning.
 */
class AuthServiceIdentifyTest {

    private UserRepository userRepository;
    private AuthService authService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        var platformSettingsService = mock(PlatformSettingsService.class);
        when(platformSettingsService.getEntity()).thenReturn(new com.finora.entity.PlatformSettings());

        authService = new AuthService(
                userRepository, mock(CategoryRepository.class), mock(PasswordResetTokenRepository.class),
                mock(com.finora.repository.AccountReactivationTokenRepository.class),
                mock(com.finora.repository.EmailVerificationTokenRepository.class),
                mock(PasswordEncoder.class), mock(JwtService.class), mock(AuthenticationManager.class),
                mock(AuditService.class), mock(RefreshTokenService.class), mock(EmailProvider.class),
                new EmailProperties(), mock(PhoneVerificationProvider.class), platformSettingsService,
                mock(PasswordHistoryService.class), new IdentityLookup(userRepository),
                mock(com.finora.config.RequestMetadata.class),
                mock(com.finora.service.SubscriptionService.class),
                mock(com.finora.service.ReferralService.class),
                Runnable::run,
                mock(AdminMfaService.class)
        );
    }

    private User user(String email, String signInMethod) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", userId);
        u.setEmail(email);
        u.setSignInMethod(signInMethod);
        return u;
    }

    @Test
    void identify_withEmailOfPasswordAccount_returnsExists() {
        User u = user("jane@example.com", User.SIGN_IN_METHOD_PASSWORD);
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("jane@example.com", User.SCOPE_USER))
                .thenReturn(Optional.of(u));

        var response = authService.identify(new IdentifyRequest("jane@example.com"));

        assertThat(response.nextAction()).isEqualTo("EXISTS");
    }

    @Test
    void identify_withEmailOfGoogleAccount_returnsExists_notWhichMethod() {
        User u = user("jane@example.com", User.SIGN_IN_METHOD_GOOGLE);
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("jane@example.com", User.SCOPE_USER))
                .thenReturn(Optional.of(u));

        var response = authService.identify(new IdentifyRequest("jane@example.com"));

        // Phase 7's whole point: a Google account and a password account must be indistinguishable
        // from this response alone.
        assertThat(response.nextAction()).isEqualTo("EXISTS");
    }

    @Test
    void identify_withEmailOfAppleAccount_returnsExists_notWhichMethod() {
        User u = user("jane@example.com", User.SIGN_IN_METHOD_APPLE);
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("jane@example.com", User.SCOPE_USER))
                .thenReturn(Optional.of(u));

        var response = authService.identify(new IdentifyRequest("jane@example.com"));

        assertThat(response.nextAction()).isEqualTo("EXISTS");
    }

    @Test
    void identify_withUnknownIdentifier_returnsContinue() {
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("nobody@example.com", User.SCOPE_USER))
                .thenReturn(Optional.empty());

        var response = authService.identify(new IdentifyRequest("nobody@example.com"));

        assertThat(response.nextAction()).isEqualTo("CONTINUE");
    }

    @Test
    void identify_withPhoneNumberIdentifier_resolvesViaPhoneLookupLikeLoginDoes() {
        User u = user("jane@example.com", User.SIGN_IN_METHOD_PASSWORD);
        when(userRepository.findByPhoneNumberAndAccountScope("+919876500001", User.SCOPE_USER))
                .thenReturn(Optional.of(u));
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("jane@example.com", User.SCOPE_USER))
                .thenReturn(Optional.of(u));

        var response = authService.identify(new IdentifyRequest("+919876500001"));

        assertThat(response.nextAction()).isEqualTo("EXISTS");
    }

    @Test
    void identify_alwaysResolvesWithinUserScope_regardlessOfAnAdminAccountWithSameEmail() {
        // identify() is the consumer entry-flow step (§2.2) -- it never accepts a scope
        // parameter, unlike login(), since the admin portal has its own separate flow. An email
        // shared between a USER row and an ADMIN row (V52 allows this) must resolve only the
        // USER row here.
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("shared@example.com", User.SCOPE_USER))
                .thenReturn(Optional.empty());

        var response = authService.identify(new IdentifyRequest("shared@example.com"));

        assertThat(response.nextAction()).isEqualTo("CONTINUE");
    }
}
