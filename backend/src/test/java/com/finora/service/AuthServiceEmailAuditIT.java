package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.AuthDtos.ReactivateRequest;
import com.finora.dto.AuthDtos.RegisterRequest;
import com.finora.entity.AccountReactivationToken;
import com.finora.entity.AuditLog;
import com.finora.entity.User;
import com.finora.repository.AccountReactivationTokenRepository;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.UserRepository;
import com.finora.util.TokenHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code EMAIL_SENT} audit row {@link AuthService} writes is recorded from inside an
 * {@code AfterCommit.run(...)} callback -- see {@code MerchantLearningEventWorker}'s class doc for
 * the mechanism, and {@code AuditService#recordEvenOnRollback}'s doc comment for why a plain {@code
 * record} loses the write there. {@code AuthServiceRegisterTest}, {@code AuthServiceEmailTest} and
 * friends mock both {@code AuditService} and (in the password-reset case) {@code authEmailExecutor}
 * with a same-thread stub, so none of them can see this: a mock records that the call happened, not
 * whether a real transaction would have kept it. Only a real Postgres commit can.
 *
 * <p>Two of the three flows below (welcome + email verification) come from the same
 * {@link AuthService#register} call, so one call proves both. Account reactivation gets its own
 * setup since {@code AuthServiceReactivateTest} never asserted on its {@code EMAIL_SENT} row at
 * all -- a real gap uncovered while investigating this bug, not merely an existing assertion this
 * class had to re-home.
 */
class AuthServiceEmailAuditIT extends AbstractIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountReactivationTokenRepository reactivationTokenRepository;

    @Test
    void registerRecordsBothTheWelcomeAndVerificationEmailAudits() {
        String email = "register-audit-it-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(
                email, "Password123", "Audit IT User", "+919876500011", null)); // synthetic-ok

        UUID userId = userRepository.findByEmailIgnoreCaseAndAccountScope(email, User.SCOPE_USER)
                .orElseThrow().getId();
        List<AuditLog> rows = auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId);

        assertThat(rows)
                .as("welcome email audit -- must survive AfterCommit.run, not silently vanish")
                .anySatisfy(a -> assertThat(a.getAction()).isEqualTo("EMAIL_SENT"))
                .filteredOn(a -> "EMAIL_SENT".equals(a.getAction()) && "welcome".equals(a.getMetadata().get("type")))
                .hasSize(1);
        assertThat(rows)
                .as("email verification audit -- must survive AfterCommit.run, not silently vanish")
                .filteredOn(a -> "EMAIL_SENT".equals(a.getAction())
                        && "email_verification".equals(a.getMetadata().get("type")))
                .hasSize(1);
    }

    @Test
    void reactivateRecordsTheAccountReactivatedEmailAudit() {
        User user = new User();
        user.setEmail("reactivate-audit-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Reactivate Audit IT User");
        user.setPhoneVerified(true);
        user.setStatus(User.STATUS_DEACTIVATED);
        User savedUser = userRepository.save(user);

        String rawToken = "reactivate-audit-it-token-" + UUID.randomUUID();
        AccountReactivationToken token = new AccountReactivationToken();
        token.setUserId(savedUser.getId());
        token.setTokenHash(TokenHasher.sha256(rawToken));
        token.setExpiresAt(Instant.now().plusSeconds(600));
        reactivationTokenRepository.save(token);

        authService.reactivate(new ReactivateRequest(rawToken));

        List<AuditLog> rows = auditLogRepository.findByUserIdOrderByCreatedAtDesc(savedUser.getId());
        assertThat(rows)
                .as("account-reactivated email audit -- must survive AfterCommit.run, not silently "
                        + "vanish (AuthServiceReactivateTest's mocked AuditService cannot see this)")
                .filteredOn(a -> "EMAIL_SENT".equals(a.getAction())
                        && "account_reactivated".equals(a.getMetadata().get("type")))
                .hasSize(1);
    }
}
