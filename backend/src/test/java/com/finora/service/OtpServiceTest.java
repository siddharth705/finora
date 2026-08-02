package com.finora.service;

import com.finora.entity.PhoneOtp;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.PhoneOtpRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OtpServiceTest {

    private PhoneOtpRepository otpRepository;
    private UserRepository userRepository;
    private SmsService smsService;
    private OtpService otpService;

    private final UUID userId = UUID.randomUUID();
    private List<PhoneOtp> otpHistory;

    @BeforeEach
    void setUp() {
        otpRepository = mock(PhoneOtpRepository.class);
        userRepository = mock(UserRepository.class);
        smsService = mock(SmsService.class);
        otpService = new OtpService(otpRepository, userRepository, smsService, mock(AuditService.class));

        otpHistory = new ArrayList<>();
        when(otpRepository.findByUserIdAndPurposeOrderByCreatedAtDesc(eq(userId), any())).thenAnswer(inv -> {
            PhoneOtp.Purpose purpose = inv.getArgument(1);
            List<PhoneOtp> reversed = new ArrayList<>(otpHistory.stream().filter(o -> o.getPurpose() == purpose).toList());
            java.util.Collections.reverse(reversed);
            return reversed;
        });
        when(otpRepository.save(any(PhoneOtp.class))).thenAnswer(inv -> {
            PhoneOtp saved = inv.getArgument(0);
            if (!otpHistory.contains(saved)) otpHistory.add(saved);
            return saved;
        });

        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setEmail("test@example.com");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void issueOtp_generatesSixDigitCode_andSendsViaSms() {
        when(smsService.isConfigured()).thenReturn(true);

        var result = otpService.issueOtp(userId, "+919876543210", PhoneOtp.Purpose.REGISTER_PHONE);

        assertThat(result.otp()).hasSize(6);
        assertThat(result.otp()).matches("\\d{6}");
        assertThat(result.delivered()).isTrue();
        verify(smsService).sendOtp(eq("+919876543210"), eq(result.otp()));
    }

    @Test
    void issueOtp_whenSmsNotConfigured_reportsNotDelivered() {
        when(smsService.isConfigured()).thenReturn(false);

        var result = otpService.issueOtp(userId, "+919876543210", PhoneOtp.Purpose.REGISTER_PHONE);

        assertThat(result.delivered()).isFalse();
    }

    @Test
    void verifyOtp_withCorrectCode_succeedsAndMarksVerified() {
        when(smsService.isConfigured()).thenReturn(false);
        var issued = otpService.issueOtp(userId, "+919876543210", PhoneOtp.Purpose.REGISTER_PHONE);

        boolean result = otpService.verifyOtp(userId, issued.otp(), PhoneOtp.Purpose.REGISTER_PHONE);

        assertThat(result).isTrue();
        assertThat(otpHistory.get(0).getVerifiedAt()).isNotNull();
    }

    @Test
    void verifyOtp_withWrongCode_failsAndIncrementsAttempts() {
        when(smsService.isConfigured()).thenReturn(false);
        otpService.issueOtp(userId, "+919876543210", PhoneOtp.Purpose.REGISTER_PHONE);

        boolean result = otpService.verifyOtp(userId, "000000", PhoneOtp.Purpose.REGISTER_PHONE);

        assertThat(result).isFalse();
        assertThat(otpHistory.get(0).getAttempts()).isEqualTo(1);
        assertThat(otpHistory.get(0).getVerifiedAt()).isNull();
    }

    @Test
    void verifyOtp_afterFiveWrongAttempts_rejectsFurtherAttemptsEvenIfCorrect() {
        when(smsService.isConfigured()).thenReturn(false);
        var issued = otpService.issueOtp(userId, "+919876543210", PhoneOtp.Purpose.REGISTER_PHONE);

        for (int i = 0; i < 5; i++) {
            otpService.verifyOtp(userId, "000000", PhoneOtp.Purpose.REGISTER_PHONE);
        }

        assertThatThrownBy(() -> otpService.verifyOtp(userId, issued.otp(), PhoneOtp.Purpose.REGISTER_PHONE))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Too many incorrect attempts");
    }

    @Test
    void verifyOtp_afterExpiry_throwsExpiredError() {
        when(smsService.isConfigured()).thenReturn(false);
        var issued = otpService.issueOtp(userId, "+919876543210", PhoneOtp.Purpose.REGISTER_PHONE);
        otpHistory.get(0).setExpiresAt(Instant.now().minusSeconds(60));

        assertThatThrownBy(() -> otpService.verifyOtp(userId, issued.otp(), PhoneOtp.Purpose.REGISTER_PHONE))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void verifyOtp_alreadyUsed_throwsAlreadyUsedError() {
        when(smsService.isConfigured()).thenReturn(false);
        var issued = otpService.issueOtp(userId, "+919876543210", PhoneOtp.Purpose.REGISTER_PHONE);
        otpService.verifyOtp(userId, issued.otp(), PhoneOtp.Purpose.REGISTER_PHONE);

        assertThatThrownBy(() -> otpService.verifyOtp(userId, issued.otp(), PhoneOtp.Purpose.REGISTER_PHONE))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been used");
    }

    @Test
    void verifyOtp_withNoOtpEverIssued_throwsClearError() {
        assertThatThrownBy(() -> otpService.verifyOtp(userId, "123456", PhoneOtp.Purpose.REGISTER_PHONE))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No OTP has been requested");
    }
}
