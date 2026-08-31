package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.dto.AuthDtos.ResetPasswordRequest;
import com.finora.dto.AuthDtos.VerifyResetPasswordPhoneRequest;
import com.finora.entity.PasswordResetToken;
import com.finora.entity.User;
import com.finora.exception.ApiException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * resetPassword() requires a Firebase ID token as a second factor on top of the reset token
 * itself -- a reset link alone (proof of email access) is no longer sufficient to change a
 * password, matching the same two-factor principle VerifyPhone already applies elsewhere. Covers
 * the original token-validation cases (valid/used/expired/unknown) plus the Firebase requirement
 * (matching phone succeeds, mismatched phone is rejected) and verifyResetPasswordPhone() (BH-015
 * fix: confirms a user-typed phone number against the account server-side, before the client is
 * allowed to hand that same number to Firebase -- never reveals the real number itself).
 */
class AuthServiceResetPasswordTest {

    private UserRepository userRepository;
    private PasswordResetTokenRepository resetTokenRepository;
    private PasswordEncoder passwordEncoder;
    private PhoneVerificationProvider phoneVerificationProvider;
    private EmailProvider emailProvider;
    private AuditService auditService;
    private AuthService authService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        resetTokenRepository = mock(PasswordResetTokenRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        phoneVerificationProvider = mock(PhoneVerificationProvider.class);
        // resetPassword()'s success path calls emailProvider.sendPasswordChangedEmail(...) and now
        // immediately dereferences the EmailResult it returns -- an unstubbed mock returns null,
        // which would NPE the one test below that actually reaches this line.
        emailProvider = mock(EmailProvider.class);
        when(emailProvider.sendPasswordChangedEmail(any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "test-message-id"));
        auditService = mock(AuditService.class);

        authService = new AuthService(
                userRepository, mock(CategoryRepository.class), resetTokenRepository,
                mock(com.finora.repository.AccountReactivationTokenRepository.class),
                mock(com.finora.repository.EmailVerificationTokenRepository.class),
                passwordEncoder, mock(JwtService.class), mock(AuthenticationManager.class),
                auditService, mock(RefreshTokenService.class), emailProvider,
                new EmailProperties(), phoneVerificationProvider, mock(PlatformSettingsService.class),
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

    private PasswordResetToken tokenRecord(String rawToken, Instant expiresAt, Instant usedAt) {
        PasswordResetToken prt = new PasswordResetToken();
        prt.setUserId(userId);
        prt.setTokenHash(TokenHasher.sha256(rawToken));
        prt.setExpiresAt(expiresAt);
        prt.setUsedAt(usedAt);
        return prt;
    }

    private User existingUser() {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setPasswordHash("old-hash");
        user.setPhoneNumber("+919999999999");
        return user;
    }

    @Test
    void resetPassword_withValidTokenAndMatchingFirebasePhone_updatesPasswordHashAndMarksTokenUsed() {
        String rawToken = "valid-raw-token";
        PasswordResetToken prt = tokenRecord(rawToken, Instant.now().plusSeconds(900), null);
        when(resetTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))).thenReturn(Optional.of(prt));

        User user = existingUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("NewSecurePass123")).thenReturn("new-encoded-hash");
        when(phoneVerificationProvider.verifyAndGetPhoneNumber("valid-firebase-token"))
                .thenReturn("+919999999999");

        var response = authService.resetPassword(new ResetPasswordRequest(rawToken, "valid-firebase-token", "NewSecurePass123"));

        assertThat(response.message()).containsIgnoringCase("updated");
        // The rewritten hash is exactly what makes signing in again with the new password work --
        // CurrentUserDetailsService/Spring Security compares against whatever's stored here.
        assertThat(user.getPasswordHash()).isEqualTo("new-encoded-hash");
        assertThat(prt.getUsedAt()).isNotNull();
        verify(resetTokenRepository).save(prt);
        verify(userRepository).save(user);
        verify(auditService).record(eq(userId), eq("EMAIL_SENT"), eq("User"), eq(userId),
                argThat(metadata -> "password_changed".equals(metadata.get("type")) && Boolean.TRUE.equals(metadata.get("success"))));
    }

    /**
     * Bug fix: this used to rely entirely on PasswordHistoryService catching a same-as-current
     * password indirectly (record() runs on every password write, so the current hash is always
     * the newest history row) -- which silently didn't hold for an account with zero history rows
     * (any account that existed before password history started being recorded and hasn't changed
     * its password since). Without a direct check here, resubmitting the unchanged password
     * returned a false "Password updated" success. Mirrors the same direct check
     * PasswordChangeService.complete() already has.
     */
    @Test
    void resetPassword_withNewPasswordSameAsCurrent_throwsRegardlessOfPasswordHistory() {
        String rawToken = "valid-raw-token";
        PasswordResetToken prt = tokenRecord(rawToken, Instant.now().plusSeconds(900), null);
        when(resetTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))).thenReturn(Optional.of(prt));
        User user = existingUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(phoneVerificationProvider.verifyAndGetPhoneNumber("valid-firebase-token")).thenReturn("+919999999999");
        when(passwordEncoder.matches("SamePassword123", "old-hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.resetPassword(new ResetPasswordRequest(rawToken, "valid-firebase-token", "SamePassword123")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("different from your current password");

        assertThat(user.getPasswordHash()).isEqualTo("old-hash");
        assertThat(prt.getUsedAt()).isNull();
        verify(userRepository, never()).save(any());
    }

    @Test
    void resetPassword_withValidTokenButMismatchedFirebasePhone_throwsAndDoesNotTouchThePasswordOrConsumeTheToken() {
        String rawToken = "valid-raw-token";
        PasswordResetToken prt = tokenRecord(rawToken, Instant.now().plusSeconds(900), null);
        when(resetTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))).thenReturn(Optional.of(prt));
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser()));
        when(phoneVerificationProvider.verifyAndGetPhoneNumber("someone-elses-token"))
                .thenReturn("+911111111111");

        assertThatThrownBy(() -> authService.resetPassword(new ResetPasswordRequest(rawToken, "someone-elses-token", "NewSecurePass123")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("doesn't match");

        // Neither the password nor the reset token itself should be touched -- a mismatched
        // phone shouldn't burn the user's one reset link.
        assertThat(prt.getUsedAt()).isNull();
        verify(userRepository, never()).save(any());
        verify(resetTokenRepository, never()).save(any());
    }

    @Test
    void resetPassword_withAlreadyUsedToken_throwsBeforeEvenCheckingFirebase() {
        String rawToken = "already-used-token";
        PasswordResetToken prt = tokenRecord(rawToken, Instant.now().plusSeconds(900), Instant.now().minusSeconds(60));
        when(resetTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))).thenReturn(Optional.of(prt));

        assertThatThrownBy(() -> authService.resetPassword(new ResetPasswordRequest(rawToken, "some-token", "NewSecurePass123")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been used");

        verify(userRepository, never()).save(any());
        verify(phoneVerificationProvider, never()).verifyAndGetPhoneNumber(any());
    }

    @Test
    void resetPassword_withExpiredToken_throwsAndDoesNotTouchThePassword() {
        String rawToken = "expired-token";
        PasswordResetToken prt = tokenRecord(rawToken, Instant.now().minusSeconds(60), null);
        when(resetTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))).thenReturn(Optional.of(prt));

        assertThatThrownBy(() -> authService.resetPassword(new ResetPasswordRequest(rawToken, "some-token", "NewSecurePass123")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");

        verify(userRepository, never()).save(any());
    }

    @Test
    void resetPassword_withUnknownToken_throwsInvalidLinkError() {
        when(resetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.resetPassword(new ResetPasswordRequest("never-issued", "some-token", "NewSecurePass123")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("invalid");

        verify(userRepository, never()).save(any());
    }

    /**
     * BH-015 fix: the endpoint no longer reveals the account's real phone number. The user types
     * their own number; this only confirms whether it matches (so the client may proceed to call
     * Firebase with the SAME number the user just typed, never a value the backend handed back).
     */
    @Test
    void verifyResetPasswordPhone_withValidTokenAndMatchingPhone_succeedsAndDoesNotConsumeToken() {
        String rawToken = "valid-raw-token";
        PasswordResetToken prt = tokenRecord(rawToken, Instant.now().plusSeconds(900), null);
        when(resetTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))).thenReturn(Optional.of(prt));
        User user = existingUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        var response = authService.verifyResetPasswordPhone(new VerifyResetPasswordPhoneRequest(rawToken, "+919999999999"));

        assertThat(response.message()).isNotBlank();
        // Verifying the phone number must NOT consume the reset token itself -- only a fully
        // completed reset (resetPassword()) does that, so a user who verifies but never finishes
        // can still use the same link again within its normal expiry.
        assertThat(prt.getUsedAt()).isNull();
    }

    @Test
    void verifyResetPasswordPhone_toleratesAMissingCountryCodePrefix_sameAsResetPasswordsOwnFirebaseCheck() {
        // Reuses phoneNumbersMatch() -- the exact digit-only comparison resetPassword() already
        // applies to the Firebase-verified number -- so this should tolerate the same "+91" vs
        // bare-digits difference a typed number could plausibly have.
        String rawToken = "valid-raw-token";
        PasswordResetToken prt = tokenRecord(rawToken, Instant.now().plusSeconds(900), null);
        when(resetTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))).thenReturn(Optional.of(prt));
        User user = existingUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        var response = authService.verifyResetPasswordPhone(new VerifyResetPasswordPhoneRequest(rawToken, "919999999999"));

        assertThat(response.message()).isNotBlank();
    }

    @Test
    void verifyResetPasswordPhone_withValidTokenButMismatchedPhone_throwsAGenericErrorAndDoesNotConsumeTheToken() {
        String rawToken = "valid-raw-token";
        PasswordResetToken prt = tokenRecord(rawToken, Instant.now().plusSeconds(900), null);
        when(resetTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))).thenReturn(Optional.of(prt));
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser()));

        assertThatThrownBy(() -> authService.verifyResetPasswordPhone(new VerifyResetPasswordPhoneRequest(rawToken, "+911111111111")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("doesn't match");

        assertThat(prt.getUsedAt()).isNull();
    }

    @Test
    void verifyResetPasswordPhone_withExpiredToken_throwsBeforeCheckingThePhoneNumber() {
        String rawToken = "expired-token";
        PasswordResetToken prt = tokenRecord(rawToken, Instant.now().minusSeconds(60), null);
        when(resetTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))).thenReturn(Optional.of(prt));

        assertThatThrownBy(() -> authService.verifyResetPasswordPhone(new VerifyResetPasswordPhoneRequest(rawToken, "+919999999999")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");

        verify(userRepository, never()).findById(any());
    }

    @Test
    void verifyResetPasswordPhone_onAGoogleAccountWithNoPhoneNumberOnFile_pointsToGoogleSignInInsteadOfAnAdministrator() {
        // A null phoneNumber (no NOT NULL constraint at the DB level, V8) means a Google Sign-In
        // account today -- AuthService.createGoogleUserRecord is the only writer that leaves it
        // unset. Such an account also has no password of its own to reset, so the message should
        // point at the login method that actually works, not at a nonexistent administrator fix.
        String rawToken = "valid-raw-token";
        PasswordResetToken prt = tokenRecord(rawToken, Instant.now().plusSeconds(900), null);
        when(resetTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))).thenReturn(Optional.of(prt));
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setSignInMethod(User.SIGN_IN_METHOD_GOOGLE);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.verifyResetPasswordPhone(new VerifyResetPasswordPhoneRequest(rawToken, "+919999999999")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Sign in with Google");
    }

    @Test
    void verifyResetPasswordPhone_onAPasswordAccountWithNoPhoneNumberOnFile_stillPointsAtAnAdministratorNotGoogle() {
        // The "real, if unlikely" case this guard was originally written for: a PASSWORD-method
        // account somehow missing its phone number (no DB-level NOT NULL). It has a real
        // password and no Google identity to fall back to, so it must NOT get the Google-Sign-In
        // message -- that would send a real password user chasing a login method that doesn't
        // exist for their account.
        String rawToken = "valid-raw-token";
        PasswordResetToken prt = tokenRecord(rawToken, Instant.now().plusSeconds(900), null);
        when(resetTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))).thenReturn(Optional.of(prt));
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setSignInMethod(User.SIGN_IN_METHOD_PASSWORD);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.verifyResetPasswordPhone(new VerifyResetPasswordPhoneRequest(rawToken, "+919999999999")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Contact an administrator")
                .hasMessageNotContaining("Sign in with Google");
    }
}
