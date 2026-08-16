package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.PasswordChangeDtos.CompleteRequest;
import com.finora.dto.PasswordChangeDtos.StartRequest;
import com.finora.dto.PasswordChangeDtos.StartResponse;
import com.finora.dto.PasswordChangeDtos.VerifyOtpRequest;
import com.finora.entity.AuditLog;
import com.finora.entity.PasswordChangeSession;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.PasswordChangeSessionRepository;
import com.finora.repository.UserRepository;
import com.finora.testsupport.FakePhoneVerificationProvider;
import com.finora.testsupport.TestPhoneVerificationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PasswordChangeServiceTest} proves this service's decision logic against mocks; this
 * proves, against a real Postgres, that its three "record a failure, then throw" call sites
 * (start()'s INVALID_CURRENT_PASSWORD, verifyOtp()'s INVALID_OTP, resolveSession()'s
 * SESSION_EXPIRED) do NOT lose their audit row the way {@code UserAccountLifecycleService
 * .deactivate()}'s identically-shaped INVALID_CURRENT_PASSWORD call site did (see {@code
 * UserAccountLifecycleServiceIT}).
 *
 * <p>The difference: every public method here is {@code @Transactional(noRollbackFor =
 * ApiException.class)}, not plain {@code @Transactional}. Spring's rollback rule explicitly
 * excludes {@code ApiException} on these methods, so a plain {@code auditService.record(...)}
 * immediately before throwing {@code ApiException} already commits fine -- switching these call
 * sites to {@code recordEvenOnRollback} would add an unnecessary {@code REQUIRES_NEW} transaction
 * for no behavioral change. These three tests exist to prove that claim against a real
 * transaction manager rather than leave it as unverified reasoning, and to catch a future
 * regression if {@code noRollbackFor} is ever dropped from one of these methods.
 */
@Import(TestPhoneVerificationConfig.class)
class PasswordChangeServiceIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private PasswordChangeSessionRepository sessionRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PasswordChangeService service;

    private UUID userId;
    private String phoneNumber;
    private static final String PASSWORD = "correct horse battery staple";

    @BeforeEach
    void setUp() {
        phoneNumber = "+9198765" + (100000 + new java.util.Random().nextInt(900000));
        User user = new User();
        user.setEmail("pwchange-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setFullName("Password Change Test User");
        user.setPhoneNumber(phoneNumber);
        userId = userRepository.save(user).getId();
    }

    @Test
    void start_wrongPassword_stillPersistsTheAuditRow_becauseNoRollbackForApiExceptionAlreadyCoversIt() {
        assertThrows(ApiException.class, () -> service.start(userId, new StartRequest("definitely-the-wrong-password")));

        assertThat(actionsFor(userId)).contains("INVALID_CURRENT_PASSWORD");
    }

    @Test
    void verifyOtp_phoneMismatch_stillPersistsTheAuditRow_becauseNoRollbackForApiExceptionAlreadyCoversIt() {
        StartResponse start = service.start(userId, new StartRequest(PASSWORD));
        // A validly-signed fake token, just for the WRONG phone number -- reaches the
        // phoneNumbersMatch() check (unlike an unrecognized token, which 401s earlier in
        // PhoneVerificationProvider itself) and fails it, which is what reaches INVALID_OTP.
        String wrongPhoneToken = FakePhoneVerificationProvider.tokenFor("+919999999999");

        assertThrows(ApiException.class,
                () -> service.verifyOtp(userId, new VerifyOtpRequest(start.sessionId(), wrongPhoneToken)));

        assertThat(actionsFor(userId)).contains("INVALID_OTP");
    }

    @Test
    void complete_expiredSession_stillPersistsTheAuditRow_becauseNoRollbackForApiExceptionAlreadyCoversIt() {
        PasswordChangeSession session = new PasswordChangeSession();
        session.setUserId(userId);
        session.setStatus(PasswordChangeSession.Status.STARTED);
        session.setCurrentPasswordVerifiedAt(Instant.now().minusSeconds(3600));
        session.setExpiresAt(Instant.now().minusSeconds(60));
        UUID sessionId = sessionRepository.save(session).getId();

        assertThrows(ApiException.class, () -> service.complete(
                userId, new CompleteRequest(sessionId.toString(), "SomeNewPassword123!", false, null), UUID.randomUUID()));

        assertThat(actionsFor(userId)).contains("SESSION_EXPIRED");
    }

    private List<String> actionsFor(UUID userId) {
        return auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(AuditLog::getAction).toList();
    }
}
