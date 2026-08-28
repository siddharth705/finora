package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.entity.EmailVerificationToken;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.AccountReactivationTokenRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.EmailVerificationTokenRepository;
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

/**
 * D-23. {@code AuthService.verifyEmail} -- confirms a {@code /verify-email?token=...} link.
 * Mirrors {@code AuthServiceReactivateTest}'s own setup and coverage shape exactly (same class of
 * token: hashed at rest, single-use, short-TTL), since this is the same pattern applied to a new
 * token type.
 */
class AuthServiceVerifyEmailTest {

    private UserRepository userRepository;
    private EmailVerificationTokenRepository emailVerificationTokenRepository;
    private AuditService auditService;
    private AuthService authService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        emailVerificationTokenRepository = mock(EmailVerificationTokenRepository.class);
        auditService = mock(AuditService.class);

        authService = new AuthService(
                userRepository, mock(CategoryRepository.class), mock(PasswordResetTokenRepository.class),
                mock(AccountReactivationTokenRepository.class),
                emailVerificationTokenRepository,
                mock(PasswordEncoder.class), mock(JwtService.class), mock(AuthenticationManager.class),
                auditService, mock(RefreshTokenService.class), mock(EmailProvider.class),
                new EmailProperties(), mock(PhoneVerificationProvider.class), mock(PlatformSettingsService.class),
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

    private User unverifiedUser() {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", userId);
        u.setEmail("jane@example.com");
        u.setEmailVerified(false);
        return u;
    }

    private EmailVerificationToken token(Instant expiresAt, Instant usedAt) {
        EmailVerificationToken t = new EmailVerificationToken();
        t.setUserId(userId);
        t.setTokenHash(TokenHasher.sha256("raw-token"));
        t.setExpiresAt(expiresAt);
        t.setUsedAt(usedAt);
        return t;
    }

    @Test
    void verifyEmail_withAValidToken_marksTheAccountVerified() {
        when(emailVerificationTokenRepository.findByTokenHash(TokenHasher.sha256("raw-token")))
                .thenReturn(Optional.of(token(Instant.now().plusSeconds(600), null)));
        User u = unverifiedUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        var response = authService.verifyEmail("raw-token");

        assertThat(u.isEmailVerified()).isTrue();
        assertThat(response.message()).isNotBlank();
        verify(auditService).record(userId, "EMAIL_VERIFIED", "User", userId);
    }

    @Test
    void verifyEmail_marksTheTokenUsed_soItCannotBeReplayed() {
        EmailVerificationToken t = token(Instant.now().plusSeconds(600), null);
        when(emailVerificationTokenRepository.findByTokenHash(TokenHasher.sha256("raw-token")))
                .thenReturn(Optional.of(t));
        when(userRepository.findById(userId)).thenReturn(Optional.of(unverifiedUser()));

        authService.verifyEmail("raw-token");

        assertThat(t.getUsedAt()).isNotNull();
        verify(emailVerificationTokenRepository).save(t);
    }

    @Test
    void verifyEmail_withAnExpiredToken_isRejectedAndDoesNotVerify() {
        when(emailVerificationTokenRepository.findByTokenHash(TokenHasher.sha256("raw-token")))
                .thenReturn(Optional.of(token(Instant.now().minusSeconds(60), null)));
        User u = unverifiedUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        try {
            authService.verifyEmail("raw-token");
        } catch (ApiException e) {
            assertThat(e.getMessage()).contains("expired");
            assertThat(u.isEmailVerified()).isFalse();
            verify(userRepository, never()).save(any());
            return;
        }
        throw new AssertionError("Expected verifyEmail() to throw for an expired token");
    }

    @Test
    void verifyEmail_withAnAlreadyUsedToken_isRejected() {
        when(emailVerificationTokenRepository.findByTokenHash(TokenHasher.sha256("raw-token")))
                .thenReturn(Optional.of(token(Instant.now().plusSeconds(600), Instant.now().minusSeconds(60))));

        try {
            authService.verifyEmail("raw-token");
        } catch (ApiException e) {
            assertThat(e.getMessage()).contains("already been used");
            verify(userRepository, never()).findById(any());
            return;
        }
        throw new AssertionError("Expected verifyEmail() to throw for an already-used token");
    }

    @Test
    void verifyEmail_withAnUnknownToken_isRejected() {
        when(emailVerificationTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        try {
            authService.verifyEmail("bogus-token");
        } catch (ApiException e) {
            assertThat(e.getMessage()).contains("invalid");
            return;
        }
        throw new AssertionError("Expected verifyEmail() to throw for an unknown token");
    }
}
