package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.EmailChangeDtos.CompleteRequest;
import com.finora.dto.EmailChangeDtos.StartRequest;
import com.finora.dto.EmailChangeDtos.StartResponse;
import com.finora.dto.EmailChangeDtos.VerifyRequest;
import com.finora.entity.AuditLog;
import com.finora.entity.EmailChangeSession;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.EmailChangeSessionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link EmailChangeServiceTest} proves this service's decision logic against mocks; this proves,
 * against a real Postgres, two things a mock cannot: (1) the same "record a failure, then throw"
 * audit durability property {@code PasswordChangeServiceIT}/{@code PhoneChangeServiceIT} already
 * prove for their own services -- every public method here is
 * {@code @Transactional(noRollbackFor = ApiException.class)} for the identical reason -- and (2)
 * that the DB's own {@code uq_users_email_scope} unique index is a real, working backstop against
 * the TOCTOU race {@link EmailChangeService#start} can only check-then-act against, turned into a
 * clean 409 by {@code GlobalExceptionHandler.handleDataIntegrityViolation}.
 */
class EmailChangeServiceIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private EmailChangeSessionRepository sessionRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EmailChangeService service;

    private UUID userId;
    private static final String PASSWORD = "correct horse battery staple";

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("emailchange-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setFullName("Email Change Test User");
        userId = userRepository.save(user).getId();
    }

    private static String randomEmail() {
        return "target-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void start_wrongPassword_stillPersistsTheAuditRow_becauseNoRollbackForApiExceptionAlreadyCoversIt() {
        assertThrows(ApiException.class,
                () -> service.start(userId, new StartRequest("definitely-the-wrong-password", null, null, randomEmail())));

        assertThat(actionsFor(userId)).contains("INVALID_CURRENT_PASSWORD");
    }

    @Test
    void verify_wrongToken_stillPersistsTheAuditRow_becauseNoRollbackForApiExceptionAlreadyCoversIt() {
        StartResponse start = service.start(userId, new StartRequest(PASSWORD, null, null, randomEmail()));

        assertThrows(ApiException.class,
                () -> service.verify(userId, new VerifyRequest(start.sessionId(), "definitely-the-wrong-token")));

        assertThat(actionsFor(userId)).contains("INVALID_EMAIL_CHANGE_TOKEN");
    }

    @Test
    void complete_expiredSession_stillPersistsTheAuditRow_becauseNoRollbackForApiExceptionAlreadyCoversIt() {
        EmailChangeSession session = new EmailChangeSession();
        session.setUserId(userId);
        session.setStatus(EmailChangeSession.Status.STARTED);
        session.setCurrentEmail("old@example.com");
        session.setRequestedEmail(randomEmail());
        session.setVerificationTokenHash("irrelevant-hash");
        session.setExpiresAt(Instant.now().minusSeconds(60));
        UUID sessionId = sessionRepository.save(session).getId();

        assertThrows(ApiException.class,
                () -> service.complete(userId, new CompleteRequest(sessionId.toString()), UUID.randomUUID()));

        assertThat(actionsFor(userId)).contains("SESSION_EXPIRED");
    }

    /**
     * The DB-level backstop {@link EmailChangeService#start}'s own doc comment relies on: start()
     * itself only check-then-acts against {@code existsByEmailIgnoreCaseAndAccountScope}, but the
     * authoritative guarantee is {@code uq_users_email_scope} at complete()'s user save -- proven
     * here by racing it directly rather than trusting that the index exists and behaves as
     * documented. Mirrors PhoneChangeServiceIT's identically-shaped test for phone numbers.
     */
    @Test
    void complete_whenAnotherAccountClaimedTheEmailAfterStart_failsCleanlyRatherThanCreatingADuplicate() {
        String requestedEmail = randomEmail();
        StartResponse start = service.start(userId, new StartRequest(PASSWORD, null, null, requestedEmail));
        EmailChangeSession session = sessionRepository.findById(UUID.fromString(start.sessionId())).orElseThrow();
        // Consuming the session directly rather than going through verify() with a real token --
        // this test only cares about the DB race at complete(), not the token-verification step.
        session.setStatus(EmailChangeSession.Status.EMAIL_VERIFIED);
        session.setEmailVerifiedAt(Instant.now());
        sessionRepository.save(session);

        // A second account claims the exact address this session is about to commit to,
        // simulating the race the DB constraint (not the app-level check start() already ran)
        // has to catch.
        User anotherUser = new User();
        anotherUser.setEmail(requestedEmail);
        anotherUser.setPasswordHash("irrelevant-hash");
        anotherUser.setFullName("Another User");
        userRepository.save(anotherUser);

        assertThatThrownBy(() -> service.complete(userId, new CompleteRequest(start.sessionId()), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private List<String> actionsFor(UUID userId) {
        return auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(AuditLog::getAction).toList();
    }
}
