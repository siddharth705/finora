package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.PhoneChangeDtos.CompleteRequest;
import com.finora.dto.PhoneChangeDtos.StartRequest;
import com.finora.dto.PhoneChangeDtos.StartResponse;
import com.finora.dto.PhoneChangeDtos.VerifyOtpRequest;
import com.finora.entity.AuditLog;
import com.finora.entity.PhoneChangeSession;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.PhoneChangeSessionRepository;
import com.finora.repository.UserRepository;
import com.finora.testsupport.FakePhoneVerificationProvider;
import com.finora.testsupport.TestPhoneVerificationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PhoneChangeServiceTest} proves this service's decision logic against mocks; this proves,
 * against a real Postgres, two things a mock cannot: (1) the same "record a failure, then throw"
 * audit durability property {@code PasswordChangeServiceIT} already proves for its own service --
 * every public method here is {@code @Transactional(noRollbackFor = ApiException.class)} for the
 * identical reason -- and (2) that the DB's own {@code uq_users_phone_scope} unique index is a
 * real, working backstop against the TOCTOU race {@link PhoneChangeService#start} can only
 * check-then-act against, turned into a clean 409 by
 * {@code GlobalExceptionHandler.handleDataIntegrityViolation}.
 */
@Import(TestPhoneVerificationConfig.class)
class PhoneChangeServiceIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private PhoneChangeSessionRepository sessionRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PhoneChangeService service;

    private UUID userId;
    private String phoneNumber;

    @BeforeEach
    void setUp() {
        phoneNumber = randomPhoneNumber();
        User user = new User();
        user.setEmail("phonechange-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-hash");
        user.setFullName("Phone Change Test User");
        user.setPhoneNumber(phoneNumber);
        userId = userRepository.save(user).getId();
    }

    // Every test in this class runs against the same real, never-rolled-back-between-tests
    // Postgres (see AbstractIntegrationTest) and this service enforces phone uniqueness -- a
    // shared literal like "+919999999999" across multiple test methods would make later tests
    // fail against rows an earlier test in the same run already committed. Each call here gets
    // its own number instead, the same way setUp()'s own phoneNumber already does.
    private static String randomPhoneNumber() {
        return "+9198" + ThreadLocalRandom.current().nextLong(100_000_0000L, 999_999_9999L);
    }

    @Test
    void verifyOtp_wrongNumber_stillPersistsTheAuditRow_becauseNoRollbackForApiExceptionAlreadyCoversIt() {
        String requestedNumber = randomPhoneNumber();
        StartResponse start = service.start(userId, new StartRequest(requestedNumber));
        // A validly-signed fake token, just for a DIFFERENT number than the one this session was
        // opened for -- reaches the PhoneNumbers.sameNumber() check (unlike an unrecognized token,
        // which 401s earlier in PhoneVerificationProvider itself) and fails it.
        String wrongNumberToken = FakePhoneVerificationProvider.tokenFor(randomPhoneNumber());

        assertThrows(ApiException.class,
                () -> service.verifyOtp(userId, new VerifyOtpRequest(start.sessionId(), wrongNumberToken)));

        assertThat(actionsFor(userId)).contains("INVALID_OTP");
    }

    @Test
    void complete_expiredSession_stillPersistsTheAuditRow_becauseNoRollbackForApiExceptionAlreadyCoversIt() {
        PhoneChangeSession session = new PhoneChangeSession();
        session.setUserId(userId);
        session.setStatus(PhoneChangeSession.Status.STARTED);
        session.setCurrentPhoneNumber(phoneNumber);
        session.setRequestedPhoneNumber(randomPhoneNumber());
        session.setExpiresAt(Instant.now().minusSeconds(60));
        UUID sessionId = sessionRepository.save(session).getId();

        assertThrows(ApiException.class,
                () -> service.complete(userId, new CompleteRequest(sessionId.toString())));

        assertThat(actionsFor(userId)).contains("SESSION_EXPIRED");
    }

    /**
     * The DB-level backstop {@link PhoneChangeService#start}'s own doc comment relies on: start()
     * itself only check-then-acts against {@code existsByPhoneNumberAndAccountScope}, but the
     * authoritative guarantee is {@code uq_users_phone_scope} at complete()'s user save -- proven
     * here by racing it directly rather than trusting that the index exists and behaves as
     * documented.
     */
    @Test
    void complete_whenAnotherAccountClaimedTheNumberAfterStart_failsCleanlyRatherThanCreatingADuplicate() {
        String requestedNumber = randomPhoneNumber();
        StartResponse start = service.start(userId, new StartRequest(requestedNumber));
        service.verifyOtp(userId, new VerifyOtpRequest(start.sessionId(), FakePhoneVerificationProvider.tokenFor(requestedNumber)));

        // A second account claims the exact number this session is about to commit to, simulating
        // the race the DB constraint (not the app-level check start() already ran) has to catch.
        User anotherUser = new User();
        anotherUser.setEmail("other-" + UUID.randomUUID() + "@example.com");
        anotherUser.setPasswordHash("irrelevant-hash");
        anotherUser.setFullName("Another User");
        anotherUser.setPhoneNumber(requestedNumber);
        userRepository.save(anotherUser);

        assertThatThrownBy(() -> service.complete(userId, new CompleteRequest(start.sessionId())))
                .isInstanceOf(DataIntegrityViolationException.class);

        User unchangedUser = userRepository.findById(userId).orElseThrow();
        assertThat(unchangedUser.getPhoneNumber()).isEqualTo(phoneNumber);
    }

    private List<String> actionsFor(UUID userId) {
        return auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(AuditLog::getAction).toList();
    }
}
