package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.dto.AuthDtos.ResetPasswordRequest;
import com.finora.dto.AuthDtos.ResolveResetPasswordPhoneRequest;
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
 * (matching phone succeeds, mismatched phone is rejected) and resolveResetPasswordPhone() (the
 * endpoint that reveals the real phone number the frontend hands to Firebase directly).
 */
class AuthServiceResetPasswordTest {

    private UserRepository userRepository;
    private PasswordResetTokenRepository resetTokenRepository;
    private PasswordEncoder passwordEncoder;
    private PhoneVerificationProvider phoneVerificationProvider;
    private AuthService authService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        resetTokenRepository = mock(PasswordResetTokenRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        phoneVerificationProvider = mock(PhoneVerificationProvider.class);

        authService = new AuthService(
                userRepository, mock(CategoryRepository.class), resetTokenRepository,
                passwordEncoder, mock(JwtService.class), mock(AuthenticationManager.class),
                mock(AuditService.class), mock(RefreshTokenService.class), mock(EmailService.class),
                new EmailProperties(), phoneVerificationProvider, mock(PlatformSettingsService.class)
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

    @Test
    void resolveResetPasswordPhone_withValidToken_returnsTheAccountsPhoneNumber() {
        String rawToken = "valid-raw-token";
        PasswordResetToken prt = tokenRecord(rawToken, Instant.now().plusSeconds(900), null);
        when(resetTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))).thenReturn(Optional.of(prt));
        User user = existingUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        var response = authService.resolveResetPasswordPhone(new ResolveResetPasswordPhoneRequest(rawToken));

        assertThat(response.phoneNumber()).isEqualTo("+919999999999");
        // Requesting the phone number must NOT consume the reset token itself -- only a fully
        // completed reset (resetPassword()) does that, so a user who looks it up but never
        // finishes can still use the same link again within its normal expiry.
        assertThat(prt.getUsedAt()).isNull();
    }

    @Test
    void resolveResetPasswordPhone_withExpiredToken_throwsBeforeRevealingAnything() {
        String rawToken = "expired-token";
        PasswordResetToken prt = tokenRecord(rawToken, Instant.now().minusSeconds(60), null);
        when(resetTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))).thenReturn(Optional.of(prt));

        assertThatThrownBy(() -> authService.resolveResetPasswordPhone(new ResolveResetPasswordPhoneRequest(rawToken)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void resolveResetPasswordPhone_whenAccountHasNoPhoneNumberOnFile_throwsClearError() {
        // phoneNumber has no NOT NULL constraint at the DB level (V8) -- a real, if unlikely,
        // state worth guarding rather than returning null as if it were a real number.
        String rawToken = "valid-raw-token";
        PasswordResetToken prt = tokenRecord(rawToken, Instant.now().plusSeconds(900), null);
        when(resetTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))).thenReturn(Optional.of(prt));
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.resolveResetPasswordPhone(new ResolveResetPasswordPhoneRequest(rawToken)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no phone number on file");
    }
}
