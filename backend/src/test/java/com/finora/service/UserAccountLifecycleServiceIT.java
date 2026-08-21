package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.AuditLog;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link UserAccountLifecycleServiceTest} proves deactivate()'s decision logic against mocks;
 * this proves, against a real Postgres, the one thing only a real transaction manager can --
 * same shape as {@link DataExportServiceIT}'s own rollback regression test.
 *
 * <p>Regression test for a real bug found via manual verification of Phase C, not written
 * speculatively: {@code deactivate()} is plain {@code @Transactional} (no {@code noRollbackFor}),
 * so its wrong-password branch's {@code auditService.record(...)} call, immediately followed by
 * throwing {@code ApiException} (a {@code RuntimeException}), was silently rolled back along with
 * the rest of that transaction under Spring's default rollback-on-RuntimeException rule -- the
 * row was never visible in {@code audit_logs} despite {@code record()} having been called. Only a
 * mocked {@code UserAccountLifecycleServiceTest} would ever have missed this, since a mock has no
 * transaction to roll back. See {@code AuditService.recordEvenOnRollback}'s own doc comment.
 */
class UserAccountLifecycleServiceIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserAccountLifecycleService service;

    private UUID userId;
    private static final String PASSWORD = "correct horse battery staple";

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("lifecycle-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setFullName("Lifecycle Test User");
        userId = userRepository.save(user).getId();
    }

    @Test
    void deactivate_wrongPassword_stillPersistsTheAuditRow_despiteTheTransactionRollingBack() {
        assertThrows(ApiException.class,
                () -> service.deactivate(userId, "definitely-the-wrong-password", null, "TAKING_A_BREAK", null));

        List<AuditLog> logs = auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId);
        assertThat(logs).anySatisfy(log -> assertThat(log.getAction()).isEqualTo("INVALID_CURRENT_PASSWORD"));
    }
}
