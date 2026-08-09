package com.finora.imports.storage;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BH-017 against a real Postgres. {@link StatementStorageSweepServiceTest} proves the decision
 * logic works given whatever a mock hands it; this proves the actual query -- native, deliberately
 * bypassing {@code StatementImport}'s {@code @SQLRestriction} to read {@code deleted_at} -- finds
 * the right candidates against real soft-delete and foreign-key semantics, and that the
 * reference-count re-check genuinely stops a shared object from being deleted.
 *
 * <p>Uses a real {@link FilesystemStatementStorage} against a temp directory rather than a mock, so
 * "the object is gone" / "the object is still there" are real filesystem assertions, not a stubbed
 * verify().
 */
class StatementStorageSweepServiceIT extends AbstractIntegrationTest {

    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private ImportSessionRepository importSessionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    @TempDir
    Path storageRoot;

    private FilesystemStatementStorage storage;
    private StatementStorageSweepService service;
    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        storage = new FilesystemStatementStorage(storageRoot.toString());
        service = new StatementStorageSweepService(Optional.of(storage), statementImportRepository, importSessionRepository);
        ReflectionTestUtils.setField(service, "retentionDays", 90);
        ReflectionTestUtils.setField(service, "batchSize", 200);
        ReflectionTestUtils.setField(service, "sweepEnabled", true);

        User user = new User();
        user.setEmail("sweep-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Sweep Test User");
        userId = userRepository.save(user).getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(1000));
        accountId = accountRepository.save(account).getId();
    }

    private ContentAddress storeBytes(String marker) {
        return storage.store(("%PDF-1.6\n" + marker + "-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
    }

    private StatementImport saveStatementImport(ContentAddress address) {
        StatementImport si = new StatementImport();
        si.setUserId(userId);
        si.setAccountId(accountId);
        si.setFileName("statement.pdf");
        si.setSourceFormat("PDF");
        si.setFileContent(new byte[]{1});
        si.setContentHash(address.hash());
        si.setObjectKey(address.key());
        return statementImportRepository.save(si);
    }

    private void softDeleteAndBackdate(StatementImport si, Instant deletedAt) {
        statementImportRepository.delete(si);
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE statement_imports SET deleted_at = :deletedAt WHERE id = :id")
                .setParameter("deletedAt", Timestamp.from(deletedAt))
                .setParameter("id", si.getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }

    private ImportSession saveImportSession(ContentAddress address, String status) {
        ImportSession session = new ImportSession();
        session.setUserId(userId);
        session.setFileName("statement.pdf");
        session.setFileContent(new byte[]{1});
        session.setStagedRowsJson("[]");
        session.setDetectedAccountJson("{}");
        session.setContentHash(address.hash());
        session.setObjectKey(address.key());
        session.setExpiresAt(Instant.now().plusSeconds(3600));
        session.setStatus(status);
        return importSessionRepository.save(session);
    }

    @Test
    @Transactional
    void sweep_reclaimsAnObjectWhoseLastReferenceWasRemovedMoreThanTheRetentionWindowAgo() {
        ContentAddress address = storeBytes("old-and-orphaned");
        StatementImport si = saveStatementImport(address);
        softDeleteAndBackdate(si, Instant.now().minus(91, ChronoUnit.DAYS));

        StatementStorageSweepService.Result result = service.sweep();

        assertThat(result.swept()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        assertThat(storage.exists(address)).isFalse();
    }

    /**
     * The BH-017 regression case. Two StatementImport rows reference the identical object because
     * content-addressing deduplicates by bytes -- the exact scenario StatementContentService's class
     * doc describes (one row per account section of a composite statement, or one per re-import).
     * Row A is deleted and old enough to be a discovery candidate on its own; row B is a live,
     * undeleted reference to the same object.
     *
     * <p>A naive implementation that deletes the object the moment the row referencing it is
     * deleted/expires -- exactly what BH-017 reports as broken and exactly what Sid decided against
     * building -- would have destroyed row B's object out from under it. This test fails under that
     * implementation and passes only because the sweep re-checks the live reference count, across
     * both tables, immediately before acting.
     */
    @Test
    @Transactional
    void sweep_doesNotReclaimAnObjectStillReferencedByAnotherLiveStatementImportRow() {
        ContentAddress address = storeBytes("shared-across-two-rows");
        StatementImport rowA = saveStatementImport(address);
        StatementImport rowB = saveStatementImport(address); // same bytes -> same address, by construction
        softDeleteAndBackdate(rowA, Instant.now().minus(91, ChronoUnit.DAYS));
        // rowB is deliberately left alone -- still live.
        assertThat(statementImportRepository.findById(rowB.getId())).isPresent();

        StatementStorageSweepService.Result result = service.sweep();

        assertThat(result.swept()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(storage.exists(address)).isTrue();
    }

    @Test
    @Transactional
    void sweep_doesNotReclaimAnObjectStillReferencedByALiveImportSession() {
        ContentAddress address = storeBytes("staged-and-confirmed");
        StatementImport si = saveStatementImport(address);
        softDeleteAndBackdate(si, Instant.now().minus(91, ChronoUnit.DAYS));
        // The session this was originally staged from -- same bytes, same address -- hasn't hit its
        // own 48h TTL yet.
        saveImportSession(address, ImportSession.STATUS_CONFIRMED);

        StatementStorageSweepService.Result result = service.sweep();

        assertThat(result.swept()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(storage.exists(address)).isTrue();
    }

    @Test
    @Transactional
    void sweep_doesNotReclaimAnObjectDeletedLessThanTheRetentionWindowAgo() {
        ContentAddress address = storeBytes("recently-deleted");
        StatementImport si = saveStatementImport(address);
        softDeleteAndBackdate(si, Instant.now().minus(10, ChronoUnit.DAYS));

        StatementStorageSweepService.Result result = service.sweep();

        // Not even a discovery candidate -- the object never shows up as swept or skipped.
        assertThat(result.swept()).isZero();
        assertThat(result.skipped()).isZero();
        assertThat(storage.exists(address)).isTrue();
    }

    @Test
    @Transactional
    void sweep_neverConsidersAnActivelyReferencedRow_regardlessOfHowOldItIs() {
        ContentAddress address = storeBytes("old-but-never-deleted");
        StatementImport si = saveStatementImport(address);
        // Never soft-deleted -- backdate created_at instead, to prove age alone never makes a live
        // row a candidate. findObjectsUnreferencedSince only ever looks at deleted_at.
        entityManager.createNativeQuery("UPDATE statement_imports SET created_at = :createdAt WHERE id = :id")
                .setParameter("createdAt", Timestamp.from(Instant.now().minus(400, ChronoUnit.DAYS)))
                .setParameter("id", si.getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        StatementStorageSweepService.Result result = service.sweep();

        assertThat(result.swept()).isZero();
        assertThat(result.skipped()).isZero();
        assertThat(storage.exists(address)).isTrue();
    }
}
