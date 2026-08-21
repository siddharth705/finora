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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * verifyPhoneWithFirebase() marks the current user's phone verified once Firebase attests it --
 * replaces the old OTP-based sendPhoneOtp()/verifyPhoneOtp() pair now that Firebase Phone
 * Authentication owns sending and confirming the code client-side (see
 * PhoneVerificationProvider's own doc comment). The backend's own job shrinks to one
 * check: does the phone number Firebase attests to actually match this account's own.
 */
class AuthServiceVerifyPhoneTest {

    private UserRepository userRepository;
    private PhoneVerificationProvider phoneVerificationProvider;
    private AuthService authService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        phoneVerificationProvider = mock(PhoneVerificationProvider.class);

        authService = new AuthService(
                userRepository, mock(CategoryRepository.class), mock(PasswordResetTokenRepository.class),
                mock(com.finora.repository.AccountReactivationTokenRepository.class),
                mock(com.finora.repository.EmailVerificationTokenRepository.class),
                mock(PasswordEncoder.class), mock(JwtService.class), mock(AuthenticationManager.class),
                mock(AuditService.class), mock(RefreshTokenService.class), mock(EmailProvider.class),
                new EmailProperties(), phoneVerificationProvider, mock(PlatformSettingsService.class),
                mock(PasswordHistoryService.class), new IdentityLookup(userRepository),
                mock(com.finora.config.RequestMetadata.class),
                mock(com.finora.service.SubscriptionService.class),
                // SEC-07: same-thread executor -- runs the dispatched email/audit work
                // synchronously so assertions against it don't race a real background thread.
                Runnable::run,
                // SEC-03: no MFA gate interference for tests unrelated to it -- an
                // unstubbed mock's isEnabled() returns false by default.
                mock(AdminMfaService.class)
        );
    }

    private User userWith(String phoneNumber) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setPhoneNumber(phoneNumber);
        return user;
    }

    @Test
    void verifyPhoneWithFirebase_withAMatchingPhoneNumber_marksThePhoneVerified() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWith("+919876500001")));
        when(phoneVerificationProvider.verifyAndGetPhoneNumber("valid-firebase-token")).thenReturn("+919876500001");

        var response = authService.verifyPhoneWithFirebase(userId, "valid-firebase-token");

        assertThat(response.message()).containsIgnoringCase("verified");
    }

    @Test
    void verifyPhoneWithFirebase_toleratesTheStoredNumberMissingItsLeadingPlus() {
        // User.phoneNumber may or may not carry the leading "+" depending on how it was typed at
        // registration (RegisterRequest's own pattern accepts either); Firebase's claim is always
        // E.164 with one. A difference of just that character must not read as a mismatch.
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWith("919876500001")));
        when(phoneVerificationProvider.verifyAndGetPhoneNumber("valid-firebase-token")).thenReturn("+919876500001");

        var response = authService.verifyPhoneWithFirebase(userId, "valid-firebase-token");

        assertThat(response.message()).containsIgnoringCase("verified");
    }

    @Test
    void verifyPhoneWithFirebase_withATokenForADifferentPhoneNumber_throws() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWith("+919876500001")));
        when(phoneVerificationProvider.verifyAndGetPhoneNumber("someone-elses-token")).thenReturn("+911111111111");

        assertThatThrownBy(() -> authService.verifyPhoneWithFirebase(userId, "someone-elses-token"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("doesn't match");
    }
}
