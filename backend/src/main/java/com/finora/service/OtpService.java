package com.finora.service;

import com.finora.entity.PhoneOtp;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.PhoneOtpRepository;
import com.finora.repository.UserRepository;
import com.finora.util.TokenHasher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phone verification via a 6-digit OTP. Unlike the 32-byte random tokens used for password
 * reset / refresh tokens, a 6-digit code only has 1,000,000 possible values - expiry alone
 * isn't enough protection, so verification is also attempt-limited (MAX_ATTEMPTS per code,
 * not per time window) to make brute-forcing impractical within the code's lifetime.
 */
@Service
public class OtpService {

    private static final int OTP_LENGTH = 6;
    private static final long OTP_TTL_MINUTES = 10;
    private static final int MAX_ATTEMPTS = 5;

    private final PhoneOtpRepository otpRepository;
    private final UserRepository userRepository;
    private final SmsService smsService;
    private final AuditService auditService;
    private final SecureRandom secureRandom = new SecureRandom();

    public OtpService(PhoneOtpRepository otpRepository, UserRepository userRepository,
                       SmsService smsService, AuditService auditService) {
        this.otpRepository = otpRepository;
        this.userRepository = userRepository;
        this.smsService = smsService;
        this.auditService = auditService;
    }

    public record OtpIssueResult(String otp, boolean delivered) {}

    /** Generates and sends a fresh OTP, replacing any still-active one for this user. Returns
     *  the plaintext code plus whether it was actually delivered via SMS — the caller uses
     *  `delivered` to decide whether it's safe to also expose the code in an API response
     *  (only when it wasn't really sent anywhere), mirroring forgotPassword()'s devResetLink. */
    @Transactional
    public OtpIssueResult issueOtp(UUID userId, String phoneNumber) {
        String otp = generateOtp();

        PhoneOtp record = new PhoneOtp();
        record.setUserId(userId);
        record.setPhoneNumber(phoneNumber);
        record.setOtpHash(TokenHasher.sha256(otp));
        record.setExpiresAt(Instant.now().plusSeconds(OTP_TTL_MINUTES * 60));
        otpRepository.save(record);

        smsService.sendOtp(phoneNumber, otp);
        auditService.record(userId, "PHONE_OTP_ISSUED", "User", userId);
        return new OtpIssueResult(otp, smsService.isConfigured());
    }

    /**
     * Verifies the OTP against the most recent one issued for this user. On success, marks the
     * user's phone as verified. On failure, increments the attempt counter on that same OTP
     * record (not a new one) - repeated wrong guesses against one code exhaust its own attempt
     * budget rather than resetting with each new lookup.
     */
    @Transactional
    public boolean verifyOtp(UUID userId, String submittedOtp) {
        List<PhoneOtp> history = otpRepository.findByUserIdOrderByCreatedAtDesc(userId);
        PhoneOtp latest = history.stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "No OTP has been requested for this account."));

        if (latest.getVerifiedAt() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This OTP has already been used.");
        }
        if (latest.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This OTP has expired - request a new one.");
        }
        if (latest.getAttempts() >= MAX_ATTEMPTS) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many incorrect attempts - request a new OTP.");
        }

        boolean matches = TokenHasher.sha256(submittedOtp).equals(latest.getOtpHash());
        if (!matches) {
            latest.setAttempts(latest.getAttempts() + 1);
            otpRepository.save(latest);
            return false;
        }

        latest.setVerifiedAt(Instant.now());
        otpRepository.save(latest);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        user.setPhoneVerified(true);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        auditService.record(userId, "PHONE_VERIFIED", "User", userId);
        return true;
    }

    private String generateOtp() {
        int max = (int) Math.pow(10, OTP_LENGTH);
        int code = secureRandom.nextInt(max);
        return String.format("%0" + OTP_LENGTH + "d", code);
    }
}
