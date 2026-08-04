package com.finora.imports.storage;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backfill end to end: real Postgres, real storage, real rows.
 *
 * The unit tests prove the orchestration with a mocked worker. This proves the claim the whole
 * migration rests on -- that many rows holding the same statement collapse onto ONE stored object
 * -- which cannot be demonstrated without actually writing bytes and counting what landed.
 *
 * Filesystem storage rather than R2 for the same reason the local provider exists at all: no
 * credentials, no network, deterministic, and it exercises the same StatementStorage contract R2
 * will have to satisfy.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-backfill-it"
})
class StatementBackfillIT extends AbstractIntegrationTest {

    @Autowired StatementImportRepository statementImportRepository;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired StatementBackfillService backfillService;
    @Autowired StatementStorage storage;

    private static final byte[] STATEMENT = "%PDF-1.6 the same statement".getBytes(StandardCharsets.UTF_8);

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void createOwner() {
        // Both user_id and account_id are real foreign keys -- random UUIDs are rejected.
        User user = new User();
        user.setEmail("backfill-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Backfill Test User");
        userId = userRepository.save(user).getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Backfill Test Account");
        account.setAccountType(Account.Type.SAVINGS);
        accountId = accountRepository.save(account).getId();
    }

    private UUID persistUnaddressedImport(byte[] content) {
        StatementImport si = new StatementImport();
        si.setUserId(userId);
        si.setAccountId(accountId);
        si.setFileName("statement.pdf");
        si.setSourceFormat("PDF");
        si.setFileContent(content);
        si.setStatementPeriodStart(LocalDate.of(2026, 7, 1));
        si.setStatementPeriodEnd(LocalDate.of(2026, 7, 31));
        si.setTransactionsImported(6);
        si.setTransactionsSkipped(0);
        // Deliberately no content hash or object key: this is what every row predating Phase 2
        // looks like, and what the backfill exists to fix.
        return statementImportRepository.save(si).getId();
    }

    @Test
    void collapsesManyRowsHoldingTheSameStatementOntoOneStoredObject() {
        // Exactly the shape §2.1 describes: three sections of one composite statement, or a
        // statement re-imported twice. Same bytes, three rows.
        List<UUID> ids = List.of(
                persistUnaddressedImport(STATEMENT),
                persistUnaddressedImport(STATEMENT),
                persistUnaddressedImport(STATEMENT));

        var result = backfillService.runBatch(50);

        assertThat(result.processed()).isGreaterThanOrEqualTo(3);
        assertThat(result.failed()).isZero();

        // Every row now carries the SAME address, and the bytes come back intact through it.
        List<StatementImport> after = ids.stream()
                .map(id -> statementImportRepository.findByIdIncludingDeleted(id).orElseThrow())
                .toList();
        assertThat(after).allSatisfy(si -> {
            assertThat(si.getContentHash()).isEqualTo(ContentAddress.hashOf(STATEMENT));
            assertThat(si.getObjectKey()).isNotBlank();
            assertThat(storage.retrieve(new ContentAddress(si.getContentHash(), si.getObjectKey())))
                    .isEqualTo(STATEMENT);
        });
        assertThat(after).extracting(StatementImport::getObjectKey).containsOnly(after.get(0).getObjectKey());

        // The payoff, stated as a file count: three rows, one object on disk.
        assertThat(objectsFor(after.get(0).getObjectKey())).isEqualTo(1);
    }

    @Test
    void isResumableAndDoesNotReprocessRowsItHasAlreadyAddressed() {
        UUID id = persistUnaddressedImport("%PDF-1.6 resumable".getBytes(StandardCharsets.UTF_8));
        backfillService.runBatch(50);
        String addressAfterFirstRun = statementImportRepository.findByIdIncludingDeleted(id).orElseThrow().getObjectKey();

        // A second run must be a no-op for this row rather than rewriting it -- that is what makes
        // "run it again until remaining is zero" a safe instruction.
        var second = backfillService.runBatch(50);

        assertThat(second.failures()).isEmpty();
        assertThat(statementImportRepository.findByIdIncludingDeleted(id).orElseThrow().getObjectKey())
                .isEqualTo(addressAfterFirstRun);
    }

    @Test
    void leavesTheDatabaseCopyInPlace_soPhase3IsStillReversible() {
        UUID id = persistUnaddressedImport("%PDF-1.6 keep the column".getBytes(StandardCharsets.UTF_8));

        backfillService.runBatch(50);

        // Phase 4 drops file_content; Phase 3 must not. Until then, unsetting the provider has to
        // remain a complete rollback.
        assertThat(statementImportRepository.findByIdIncludingDeleted(id).orElseThrow().getFileContent())
                .isNotEmpty();
    }

    /** How many files exist at the directory this key lives in — the dedup measurement. */
    private long objectsFor(String key) {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "finora-backfill-it").resolve(key).getParent();
        try (var files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
