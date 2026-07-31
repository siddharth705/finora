package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.dto.AuthDtos.RequestPasswordResetOtpRequest;
import com.finora.dto.AuthDtos.ResetPasswordRequest;
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
 * resetPassword() now requires a phone OTP as a second factor on top of the reset token itself
 * -- a reset link alone (proof of email access) is no longer sufficient to change a password,
 * matching the same two-factor principle VerifyPhone already applies elsewhere. Covers the
 * original token-validation cases (valid/used/expired/unknown) plus the new OTP requirement
 * (correct code succeeds, wrong code is rejected, requestPasswordResetOtp() itself).
 */
class AuthServiceResetPasswordTest {

    private UserRepository userRepository;
    private PasswordResetTokenRepository resetTokenRepository;
    private PasswordEncoder passwordEncoder;
    private OtpService otpService;
    private AuthService authService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        resetTokenRepository = mock(PasswordResetTokenRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        otpService = mock(OtpService.class);

        authService = new AuthService(
                userRepository, mock(CategoryRepository.class), resetTokenRepository,
                passwordEncoder, mock(JwtService.class), mock(AuthenticationManager.class),
                mock(AuditService.class), mock(RefreshTokenService.class), mock(EmailService.class),
                new EmailProperties(), otpService, mock(PlatformSettingsService.class)
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
    void resetPassword_withValidTokenAndCorrectOtp_updatesPasswordHashAndMarksTokenUsed() {
        String rawToken = "valid-raw-token";
        PasswordResetToken prt = tokenRecord(rawToken, Instant.now().plusSeconds(900), null);
        when(resetTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))).thenReturn(Optional.of(prt));

        User user = existingUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("NewSecurePass123")).thenReturn("new-encoded-hash");
        when(otpService.verifyOtp(userId, "123456")).thenReturn(true);

        var response = authService.resetPassword(new ResetPasswordRequest(rawToken, "123456", "NewSecurePass123"));

        assertThat(response.message()).containsIgnoringCase("updated");
        // The rewritten hash is exactly what makes signing in again with the new password work --
        // CurrentUserDetailsService/Spring Security compares against whatever's stored here.
        assertThat(user.getPasswordHash()).isEqualTo("new-encoded-hash");
        assertThat(prt.getUsedAt()).isNotNull();
        verify(resetTokenRepository).save(prt);
        verify(userRepository).save(user);
    }

    @Test
    void resetPassword_withValidTokenButWrongOtp_throwsAndDoesNotTouchThePasswordOrConsumeTheToken() {
        String rawToken = "valid-raw-token";
        PasswordResetToken prt = tokenRecord(rawToken, Instant.now().plusSeconds(900), null);
        when(resetTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))).thenReturn(Optional.of(prt));
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser()));
        when(otpService.verifyOtp(userId, "000000")).thenReturn(false);

        assertThatThrownBy(() -> authService.resetPassword(new ResetPasswordRequest(rawToken, "000000", "NewSecurePass123")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Incorrect verification code");

        // Neither the password nor the reset token itself should be touched -- a wrong OTP
        // shouldn't burn the user's one reset link, since they may just have mistyped the code.
        assertThat(prt.getUsedAt()).isNull();
        verify(userRepository, never()).save(any());
        verify(resetTokenRepository, never()).save(any());
    }

    @Test
    void resetPassword_withAlreadyUsedToken_throwsBeforeEvenCheckingTheOtp() {
        String rawToken = "already-used-token";
        PasswordResetToken prt = tokenRecord(rawToken, Instant.now().plusSeconds(900), Instant.now().minusSeconds(60));
        when(resetTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))).thenReturn(Optional.of(prt));

        assertThatThrownBy(() -> authService.resetPassword(new ResetPasswordRequest(rawToken, "123456", "NewSecurePass123")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been used");

        verify(userRepository, never()).save(any());
        verify(otpService, never()).verifyOtp(any(), any());
    }

    @Test
    void resetPassword_withExpiredToken_throwsAndDoesNotTouchThePassword() {
        String rawToken = "expired-token";
        PasswordResetToken prt = tokenRecord(rawToken, Instant.now().minusSeconds(60), null);
        when(resetTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))).thenReturn(Optional.of(prt));

        assertThatThrownBy(() -> authService.resetPassword(new ResetPasswordRequest(rawToken, "123456", "NewSecurePass123")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");

        verify(userRepository, never()).save(any());
    }

    @Test
    void resetPassword_withUnknownToken_throwsInvalidLinkError() {
        when(resetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.resetPassword(new ResetPasswordRequest("never-issued", "123456", "NewSecurePass123")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("invalid");

        verify(userRepository, never()).save(any());
    }

    @Test
    void requestPasswordResetOtp_withValidToken_issuesOtpToTheAccountsPhoneNumber() {
        String rawToken = "valid-raw-token";
        PasswordResetToken prt = tokenRecord(rawToken, Instant.now().plusSeconds(900), null);
        when(resetTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))).thenReturn(Optional.of(prt));
        User user = existingUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(otpService.issueOtp(userId, "+919999999999")).thenReturn(new OtpService.OtpIssueResult("654321", false));

        var response = authService.requestPasswordResetOtp(new RequestPasswordResetOtpRequest(rawToken));

        assertThat(response.devOtp()).isEqualTo("654321");
        verify(otpService).issueOtp(userId, "+919999999999");
        // Requesting the OTP must NOT consume the reset token itself -- only a fully completed
        // reset (resetPassword()) does that, so a user who requests a code but never finishes
        // can still use the same link again within its normal expiry.
        assertThat(prt.getUsedAt()).isNull();
    }

    @Test
    void requestPasswordResetOtp_withExpiredToken_throwsBeforeIssuingAnyOtp() {
        String rawToken = "expired-token";
        PasswordResetToken prt = tokenRecord(rawToken, Instant.now().minusSeconds(60), null);
        when(resetTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))).thenReturn(Optional.of(prt));

        assertThatThrownBy(() -> authService.requestPasswordResetOtp(new RequestPasswordResetOtpRequest(rawToken)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");

        verify(otpService, never()).issueOtp(any(), any());
    }

    @Test
    void requestPasswordResetOtp_whenAccountHasNoPhoneNumberOnFile_throwsClearError() {
        // phoneNumber has no NOT NULL constraint at the DB level (V8) -- a real, if unlikely,
        // state worth guarding rather than letting a null flow into SmsService.sendOtp().
        String rawToken = "valid-raw-token";
        PasswordResetToken prt = tokenRecord(rawToken, Instant.now().plusSeconds(900), null);
        when(resetTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))).thenReturn(Optional.of(prt));
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.requestPasswordResetOtp(new RequestPasswordResetOtpRequest(rawToken)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no phone number on file");

        verify(otpService, never()).issueOtp(any(), any());
    }
}
