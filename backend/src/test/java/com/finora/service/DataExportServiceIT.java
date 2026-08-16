package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.AuditLog;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DataExportServiceTest} proves the export's decision logic against mocks; this proves,
 * against a real Postgres, the one thing only a real transaction manager can: {@link
 * DataExportService}'s own class doc explains that {@code writeZip} must resolve each statement's
 * bytes AFTER {@code buildBundle}'s transaction has already closed -- exactly the situation
 * {@code StreamingResponseBody} creates in production, where the write callback runs on a separate
 * thread once the controller method has already returned. {@code StatementImport.fileContent} is
 * {@code @Basic(fetch = FetchType.LAZY)}, so this test deliberately does NOT wrap itself in
 * {@code @Transactional} -- doing so would keep one Hibernate session open across both calls and
 * let a design that reads the lazy field at the wrong time pass anyway.
 */
class DataExportServiceIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private DataExportService service;

    private UUID userId;
    private static final String PASSWORD = "correct horse battery staple";

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("export-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setFullName("Export Test User");
        userId = userRepository.save(user).getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Export Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(1000));
        accountRepository.save(account);
    }

    /**
     * The regression this class exists to catch: reading {@code fileContent} from inside {@code
     * writeZip} using a {@link StatementImport} entity captured back in {@code buildBundle} (rather
     * than re-fetching it fresh via {@code StatementImportService.getFile}) would throw {@code
     * LazyInitializationException} here -- caught internally by {@code writeZip}'s own per-statement
     * try/catch, which would silently turn this test's real statement entry into a {@code
     * .MISSING.txt} placeholder instead of surfacing a loud test failure. So this test asserts the
     * REAL entry is present with the exact original bytes, not merely that nothing threw.
     */
    @Test
    void writeZip_readsLegacyFileContentBytes_afterBuildBundlesTransactionHasAlreadyClosed() throws Exception {
        byte[] originalBytes = "the quick brown fox jumps over the lazy dog".getBytes();
        Account account = accountRepository.findByUserId(userId).get(0);
        StatementImport statement = new StatementImport();
        statement.setUserId(userId);
        statement.setAccountId(account.getId());
        statement.setFileName("legacy-statement.csv");
        statement.setSourceFormat("CSV");
        statement.setFileContent(originalBytes);
        statement.setContentHash("export-it-hash-" + UUID.randomUUID());
        UUID statementId = statementImportRepository.save(statement).getId();

        // buildBundle runs its own @Transactional(readOnly = true) and returns -- its Hibernate
        // session is closed by the time this line completes, exactly like the controller's
        // transaction is closed by the time StreamingResponseBody's callback thread runs writeZip.
        DataExportService.ExportBundle bundle = service.buildBundle(userId, PASSWORD);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeZip(userId, bundle, out);

        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
            }
        }

        String expectedEntry = "statements/" + statementId + "-legacy-statement.csv";
        assertThat(entries).containsKey(expectedEntry);
        assertThat(entries.get(expectedEntry)).isEqualTo(originalBytes);
        assertThat(entries.keySet()).noneMatch(name -> name.contains(".MISSING.txt"));
    }

    /**
     * Regression test for a real bug found via manual verification, not written speculatively:
     * {@code buildBundle} is {@code @Transactional(readOnly = true)}; a plain {@code
     * auditService.record(...)} call immediately followed by throwing {@code ApiException} (a
     * {@code RuntimeException}) was silently rolled back along with the rest of that transaction --
     * the row was never visible in {@code audit_logs} despite {@code record()} having been called,
     * and only a mocked {@code AuditServiceTest} would ever have missed this, since a mock has no
     * transaction to roll back. See {@code AuditService.recordEvenOnRollback}'s own doc comment.
     */
    @Test
    void buildBundle_wrongPassword_stillPersistsTheAuditRow_despiteTheTransactionRollingBack() {
        assertThrows(ApiException.class, () -> service.buildBundle(userId, "definitely-the-wrong-password"));

        List<AuditLog> logs = auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId);
        assertThat(logs).anySatisfy(log -> assertThat(log.getAction()).isEqualTo("INVALID_CURRENT_PASSWORD"));
    }
}
