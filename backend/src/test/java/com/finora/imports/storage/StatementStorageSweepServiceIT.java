package com.finora.imports.storage;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.ImportJob;
import com.finora.entity.ImportSession;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.exception.ErrorCode;
import com.finora.repository.AccountRepository;
import com.finora.repository.ImportJobRepository;
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
    @Autowired private ImportJobRepository importJobRepository;
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
        service = new StatementStorageSweepService(Optional.of(storage), statementImportRepository,
                importSessionRepository, importJobRepository);
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
        return saveStatementImport(address, userId, accountId);
    }

    private StatementImport saveStatementImport(ContentAddress address, UUID forUserId, UUID forAccountId) {
        StatementImport si = new StatementImport();
        si.setUserId(forUserId);
        si.setAccountId(forAccountId);
        si.setFileName("statement.pdf");
        si.setSourceFormat("PDF");
        si.setFileContent(new byte[]{1});
        si.setContentHash(address.hash());
        si.setObjectKey(address.key());
        return statementImportRepository.save(si);
    }

    private record OtherTenant(UUID userId, UUID accountId) {}

    /** A second, unrelated user+account -- for BH-039's cross-tenant case specifically, where the
     *  fixture's single {@link #userId} would not exercise the property being tested. */
    private OtherTenant otherTenant() {
        User other = new User();
        other.setEmail("sweep-other-" + UUID.randomUUID() + "@example.com");
        other.setPasswordHash("irrelevant-for-this-test");
        other.setFullName("Sweep Test Other User");
        UUID otherUserId = userRepository.save(other).getId();

        Account otherAccount = new Account();
        otherAccount.setUserId(otherUserId);
        otherAccount.setName("Other User's Savings");
        otherAccount.setAccountType(Account.Type.SAVINGS);
        otherAccount.setBalance(BigDecimal.valueOf(500));
        UUID otherAccountId = accountRepository.save(otherAccount).getId();
        return new OtherTenant(otherUserId, otherAccountId);
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
        return saveImportSession(address, status, userId);
    }

    private ImportSession saveImportSession(ContentAddress address, String status, UUID forUserId) {
        ImportSession session = new ImportSession();
        session.setUserId(forUserId);
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

    private ImportJob saveFailedImportJob(ContentAddress address) {
        ImportJob job = new ImportJob(userId, "statement.pdf", address.hash(), address.key(), "PDF");
        job.recordFailure("boom", null, ErrorCode.RetryPolicy.FAIL_FAST, Instant.now());
        return importJobRepository.save(job);
    }

    private ImportJob saveCompletedImportJob(ContentAddress address) {
        ImportJob job = new ImportJob(userId, "statement.pdf", address.hash(), address.key(), "PDF");
        job.complete(UUID.randomUUID(), Instant.now());
        return importJobRepository.save(job);
    }

    /**
     * The gap this change closes, against a real Postgres. A FAILED import_jobs row has no
     * counterpart in either statement_imports or import_sessions -- work that fails before
     * producing a confirmable row leaves no trace in either table -- so before
     * ImportJobRepository.existsByObjectKeyAndStatusNot joined this check, this object would have
     * been reclaimed the moment it looked old enough, destroying the one thing a future "retry
     * without re-upload" would need.
     */
    @Test
    @Transactional
    void sweep_doesNotReclaimAnObjectStillReferencedByALiveFailedImportJob() {
        ContentAddress address = storeBytes("failed-async-import");
        StatementImport si = saveStatementImport(address);
        softDeleteAndBackdate(si, Instant.now().minus(91, ChronoUnit.DAYS));
        saveFailedImportJob(address);

        StatementStorageSweepService.Result result = service.sweep();

        assertThat(result.swept()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(storage.exists(address)).isTrue();
    }

    /**
     * The other half: a COMPLETED import_jobs row must not protect its object on its own. Unlike
     * statement_imports and import_sessions, import_jobs rows never expire (only cascading away
     * with the owning user) -- if COMPLETED counted here, this object would become permanently
     * unsweepable the moment the job completed, even long after the statement it produced was
     * deleted and its originating session expired.
     */
    @Test
    @Transactional
    void sweep_reclaimsAnObjectWhoseOnlyImportJobReferenceIsCompleted() {
        ContentAddress address = storeBytes("completed-async-import");
        StatementImport si = saveStatementImport(address);
        softDeleteAndBackdate(si, Instant.now().minus(91, ChronoUnit.DAYS));
        saveCompletedImportJob(address);

        StatementStorageSweepService.Result result = service.sweep();

        assertThat(result.swept()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        assertThat(storage.exists(address)).isFalse();
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

    /**
     * BH-039. Content addressing is global -- {@code SHA-256(bytes)}, no tenant prefix (see
     * {@link ContentAddress}'s class doc) -- so two DIFFERENT users who happen to upload byte-
     * identical documents share one object, by design. The finding's own words: this "becomes a
     * cross-tenant defect the moment the future sweep is built and reference counting is not
     * per-object-global." The sweep has since been built (BH-017); this proves the reference
     * counting it actually shipped with is global, not per-tenant -- {@code existsByObjectKey} on
     * both {@code StatementImportRepository} and {@code ImportSessionRepository} takes no
     * {@code userId} at all, so it structurally cannot be scoped to one tenant.
     *
     * <p>{@link #sweep_doesNotReclaimAnObjectStillReferencedByAnotherLiveStatementImportRow} proves
     * shared-object survival too, but both its rows belong to the SAME user (the fixture's single
     * {@link #userId}) -- it cannot tell "correctly checks every reference" apart from "correctly
     * checks every reference this one tenant has," which is exactly the distinction BH-039 is
     * about. This is the test that would catch a future refactor which "helpfully" adds user
     * scoping to either {@code existsByObjectKey} query, thinking it looks under-scoped --
     * silently reintroducing the cross-tenant data loss BH-039 named: deleting a DIFFERENT user's
     * only copy of a statement because this user's copy was deleted.
     */
    @Test
    @Transactional
    void sweep_doesNotReclaimAnObjectStillReferencedByAnotherTenantsLiveRow() {
        ContentAddress address = storeBytes("byte-identical-across-two-tenants");
        StatementImport mine = saveStatementImport(address);
        OtherTenant other = otherTenant();
        StatementImport theirs = saveStatementImport(address, other.userId(), other.accountId());
        softDeleteAndBackdate(mine, Instant.now().minus(91, ChronoUnit.DAYS));
        // theirs is deliberately left alone -- still live, and belongs to a different tenant.
        assertThat(statementImportRepository.findById(theirs.getId())).isPresent();

        StatementStorageSweepService.Result result = service.sweep();

        assertThat(result.swept())
                .as("another tenant's only copy of this document must not be destroyed because "
                        + "MY reference to the same bytes was deleted")
                .isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(storage.exists(address)).isTrue();
    }

    /**
     * BH-039, the {@code ImportSessionRepository} half. {@link
     * #sweep_doesNotReclaimAnObjectStillReferencedByAnotherTenantsLiveRow} proves the cross-tenant
     * property, but its surviving reference is a {@code StatementImport} row -- the sweep's guard is
     * {@code statementImportRepository.existsByObjectKey(...) ||
     * importSessionRepository.existsByObjectKey(...)}, so that test's first clause alone keeps the
     * object alive and the {@code ImportSessionRepository} half of the OR is never actually
     * exercised. A future mistake scoped to only {@code ImportSessionRepository.existsByObjectKey}
     * (the same well-meaning "add userId, it looks under-scoped" refactor BH-039 warns against)
     * would pass every existing test and still silently destroy another tenant's only copy of a
     * still-staged document. This is the test that would catch that: the surviving live reference
     * here is an {@code ImportSession}, not a {@code StatementImport}, and it belongs to a different
     * tenant.
     */
    @Test
    @Transactional
    void sweep_doesNotReclaimAnObjectStillReferencedByAnotherTenantsLiveImportSession() {
        ContentAddress address = storeBytes("staged-by-one-tenant-imported-by-another");
        StatementImport mine = saveStatementImport(address);
        OtherTenant other = otherTenant();
        ImportSession theirs = saveImportSession(address, ImportSession.STATUS_STAGED, other.userId());
        softDeleteAndBackdate(mine, Instant.now().minus(91, ChronoUnit.DAYS));
        // theirs is deliberately left alone -- still live, not expired, and belongs to a different tenant.
        assertThat(importSessionRepository.findById(theirs.getId())).isPresent();

        StatementStorageSweepService.Result result = service.sweep();

        assertThat(result.swept())
                .as("another tenant's still-staged session on the same bytes must not be destroyed "
                        + "because MY reference to the same bytes was deleted")
                .isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(storage.exists(address)).isTrue();
    }
}
