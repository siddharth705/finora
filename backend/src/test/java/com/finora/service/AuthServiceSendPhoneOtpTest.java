package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.entity.User;
import com.finora.exception.ApiException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * sendPhoneOtp() always returns a masked phone number now (see PhoneMasking, AuthDtos.
 * SendOtpResponse) -- the actual incident this was built to prevent a repeat of: a number stored
 * without its country code silently failed to deliver via Twilio, with no way for the person on
 * VerifyPhone.tsx to notice anything was wrong until they dug through server logs.
 */
class AuthServiceSendPhoneOtpTest {

    private UserRepository userRepository;
    private OtpService otpService;
    private AuthService authService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        otpService = mock(OtpService.class);

        authService = new AuthService(
                userRepository, mock(CategoryRepository.class), mock(PasswordResetTokenRepository.class),
                mock(PasswordEncoder.class), mock(JwtService.class), mock(AuthenticationManager.class),
                mock(AuditService.class), mock(RefreshTokenService.class), mock(EmailService.class),
                new EmailProperties(), otpService, mock(PlatformSettingsService.class)
        );
    }

    private User userWith(String phoneNumber) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setPhoneNumber(phoneNumber);
        return user;
    }

    @Test
    void sendPhoneOtp_returnsTheMaskedPhoneNumber_whenDeliveredViaARealProvider() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWith("+919876500001")));
        when(otpService.issueOtp(eq(userId), eq("+919876500001")))
                .thenReturn(new OtpService.OtpIssueResult("123456", true));

        var response = authService.sendPhoneOtp(userId);

        assertThat(response.maskedPhone()).isEqualTo("+•••••••••001");
        assertThat(response.devOtp()).isNull();
    }

    @Test
    void sendPhoneOtp_stillReturnsTheMaskedPhoneNumber_whenNoRealProviderIsConfigured() {
        // devOtp's fallback-visibility branch and maskedPhone are independent -- masking the
        // phone doesn't depend on whether a real SMS provider is wired up.
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWith("+919876500001")));
        when(otpService.issueOtp(eq(userId), eq("+919876500001")))
                .thenReturn(new OtpService.OtpIssueResult("123456", false));

        var response = authService.sendPhoneOtp(userId);

        assertThat(response.maskedPhone()).isEqualTo("+•••••••••001");
        assertThat(response.devOtp()).isEqualTo("123456");
    }

    @Test
    void sendPhoneOtp_throws_whenNoPhoneNumberIsOnFile() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWith(null)));

        assertThatThrownBy(() -> authService.sendPhoneOtp(userId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No phone number on file");
    }
}
