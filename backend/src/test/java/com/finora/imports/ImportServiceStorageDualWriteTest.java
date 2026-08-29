package com.finora.imports;

import com.finora.accounts.AccountService;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.MultiAccountConfirmRequest;
import com.finora.dto.ImportDto.SectionConfirm;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.entity.StatementImport;
import com.finora.imports.storage.ContentAddress;
import com.finora.imports.storage.StatementContentService;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import com.finora.service.ReconciliationService;
import com.finora.service.RecurringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BH-025 / BH-046: {@code ImportService.persistSection} used to call
 * {@code statementImport.setFileContent(fileContent)} unconditionally, right after
 * {@code statementContentService.store(fileContent).ifPresent(...)}, regardless of whether that
 * store actually recorded an object-storage address. {@code confirmMultiSection()} calls
 * {@code persistSection()} once per detected account section with the SAME full file, so a
 * 3-section 9 MB composite statement wrote 27 MB of {@code BYTEA} on top of the one
 * already-deduplicated content-addressed object (BH-025) -- with no phase left to ever stop it,
 * since Phase 3's backfill was deleted and Phase 4 never got a trigger (BH-046).
 *
 * <p>The fix: write {@code file_content} only when {@code store()} came back empty (no provider
 * configured -- the row stays legacy, exactly as before). When a provider IS configured,
 * {@code file_content} is left null and the row carries {@code object_key}/{@code content_hash}
 * instead.
 *
 * <p>Wired the same manual way as {@code ImportServiceAskOnceTest}/{@code ImportServiceSessionTest}
 * -- only {@code statementContentService} varies between tests, so both branches of the new
 * if/else are exercised without a live filesystem or R2 provider.
 */
class ImportServiceStorageDualWriteTest {

    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private MerchantRepository merchantRepository;
    private StatementImportRepository statementImportRepository;
    private CategorizationService categorizationService;
    private ImportSessionService importSessionService;
    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        merchantRepository = mock(MerchantRepository.class);
        statementImportRepository = mock(StatementImportRepository.class);
        categorizationService = mock(CategorizationService.class);
        importSessionService = mock(ImportSessionService.class);

        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", accountId);
        account.setUserId(userId);
        account.setAccountType(Account.Type.SAVINGS);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(merchantRepository.findByUserId(any())).thenReturn(List.of());
        when(categorizationService.resolveOrCreateCategory(any(), any())).thenAnswer(inv -> {
            var cat = new com.finora.entity.Category();
            ReflectionTestUtils.setField(cat, "id", UUID.randomUUID());
            cat.setName(inv.getArgument(1));
            return cat;
        });
        // Same reasoning as ImportServiceAskOnceTest/ImportServiceSessionTest: an unstubbed
        // saveAll() returns an empty list, which confirm() would read as "0 imported".
        when(transactionRepository.saveAll(any())).thenAnswer(inv -> {
            List<com.finora.entity.Transaction> txns = inv.getArgument(0);
            txns.forEach(t -> ReflectionTestUtils.setField(t, "id", UUID.randomUUID()));
            return txns;
        });
        when(statementImportRepository.save(any(StatementImport.class))).thenAnswer(inv -> {
            StatementImport si = inv.getArgument(0);
            ReflectionTestUtils.setField(si, "id", UUID.randomUUID());
            return si;
        });
    }

    /** Builds a fresh ImportService with every collaborator the same except statementContentService,
     *  which each test swaps to exercise one branch of the store()-present/absent split. */
    private ImportService importServiceWith(StatementContentService statementContentService) {
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive());
        CsvParser csvParser = new CsvParser();
        TransactionNormalizer transactionNormalizer =
                new TransactionNormalizer(categorizationService, duplicateDetector, TestRuleEngines.empty());
        StatementValidator statementValidator =
                new StatementValidator(com.finora.imports.product.ProductDiscovery.standard());
        PreviewGenerator previewGenerator = new PreviewGenerator(csvParser, transactionNormalizer, statementValidator,
                new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                        new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new RowAccountingValidator(), new com.finora.imports.CreditCardStatementTotalsValidator(), new com.finora.imports.CreditCardFlowReconciliationValidator()),
                TestRuleEngines.empty());
        ImportRuleLearningService ruleLearningService = new ImportRuleLearningService(categorizationService);
        var productIdentityResolver = new com.finora.imports.product.ProductIdentityResolver(accountRepository);

        return new ImportService(accountRepository, mock(AccountService.class), transactionRepository,
                merchantRepository, statementImportRepository, categorizationService, mock(ReconciliationService.class),
                mock(RecurringService.class), previewGenerator, duplicateDetector, ruleLearningService,
                importSessionService, mock(com.finora.imports.pdf.PdfPreviewGenerator.class),
                productIdentityResolver, statementContentService,
                mock(com.finora.imports.analysis.StatementAnalysisRecorder.class),
                mock(com.finora.imports.analysis.ImportVerificationRecorder.class),
                mock(com.finora.service.MerchantLearningEventPublisher.class), mock(LayoutRegistryService.class),
                mock(com.finora.imports.evidence.ClosingBalanceEvidenceShadowObserver.class));
    }

    private ConfirmedRow confirmedRow() {
        return new ConfirmedRow(LocalDate.of(2026, 7, 1), "Coffee Shop", new BigDecimal("150.00"),
                "EXPENSE", "Food & Dining", true, "rule", null, false, null, null);
    }

    private StagedRow stagedRow() {
        return new StagedRow(LocalDate.of(2026, 7, 1), "Coffee Shop", new BigDecimal("150.00"),
                "EXPENSE", "Food & Dining", "rule", null, false, null, null);
    }

    @Test
    void persistSection_whenNoStorageProviderConfigured_stillFillsFileContent_unchangedFromBeforeTheFix() {
        // Optional.empty() storage -- store() returns Optional.empty(), same as production with
        // app.statement-storage.provider unset. This branch MUST NOT change: it is every existing
        // deployment today, since no provider is configured in production yet.
        ImportService importService = importServiceWith(
                new StatementContentService(Optional.empty(), mock(com.finora.security.crypto.EncryptionService.class), "", ""));
        var request = new ConfirmRequest(null, List.of(confirmedRow()), accountId, null, null, null,
                null);
        byte[] fileBytes = "irrelevant".getBytes();

        importService.confirm(userId, "statement.csv", fileBytes, request);

        ArgumentCaptor<StatementImport> captor = ArgumentCaptor.forClass(StatementImport.class);
        verify(statementImportRepository).save(captor.capture());
        assertThat(captor.getValue().getFileContent()).isEqualTo(fileBytes);
        assertThat(captor.getValue().getObjectKey()).isNull();
        assertThat(captor.getValue().getContentHash()).isNull();
    }

    @Test
    void persistSection_whenObjectStorageConfigured_recordsTheAddress_andLeavesFileContentNull() {
        // Reproduces BH-025's actual defect: before this fix, setFileContent(fileContent) ran
        // unconditionally right after store().ifPresent(...) recorded the address below, so
        // getFileContent() here would have come back with the full byte array instead of null --
        // this assertion is the one that would have failed pre-fix.
        StatementContentService storageBacked = mock(StatementContentService.class);
        ContentAddress address = new ContentAddress("a".repeat(64), "statements/aa/aa/" + "a".repeat(64) + ".bin");
        when(storageBacked.store(any(), any())).thenReturn(Optional.of(new StatementContentService.StoredContent(
                address, 100, 50, com.finora.imports.storage.CompressionType.GZIP, "text/csv", "v1")));
        ImportService importService = importServiceWith(storageBacked);
        var request = new ConfirmRequest(null, List.of(confirmedRow()), accountId, null, null, null,
                null);

        importService.confirm(userId, "statement.csv", "irrelevant".getBytes(), request);

        ArgumentCaptor<StatementImport> captor = ArgumentCaptor.forClass(StatementImport.class);
        verify(statementImportRepository).save(captor.capture());
        assertThat(captor.getValue().getFileContent()).isNull();
        assertThat(captor.getValue().getObjectKey()).isEqualTo(address.key());
        assertThat(captor.getValue().getContentHash()).isEqualTo(address.hash());
    }

    @Test
    void confirmMultiSection_withStorageConfigured_doesNotMultiplyFileBytesAcrossSections() {
        // The BH-025 scenario itself: a composite statement (e.g. HSBC savings + credit card)
        // staged as two account sections, both persistSection() calls sharing the same uploaded
        // bytes. Pre-fix, both rows would have carried an independent full copy of fileContent --
        // exactly the "N sections, N copies in Postgres" defect BH-025 reported, on top of the
        // one deduplicated object address both rows already share below.
        StatementContentService storageBacked = mock(StatementContentService.class);
        ContentAddress address = new ContentAddress("b".repeat(64), "statements/bb/bb/" + "b".repeat(64) + ".bin");
        when(storageBacked.store(any(), any())).thenReturn(Optional.of(new StatementContentService.StoredContent(
                address, 100, 50, com.finora.imports.storage.CompressionType.GZIP, "application/pdf", "v1")));
        byte[] fileBytes = "the-whole-composite-pdf".getBytes();
        when(storageBacked.read(any())).thenReturn(fileBytes);
        ImportService importService = importServiceWith(storageBacked);

        UUID sessionId = UUID.randomUUID();
        ImportSession session = new ImportSession();
        ReflectionTestUtils.setField(session, "id", sessionId);
        session.setUserId(userId);
        session.setFileName("composite.pdf");
        session.setExpiresAt(Instant.now().plusSeconds(600));
        when(importSessionService.claimForConfirmation(userId, sessionId)).thenReturn(session);

        var section1 = new StagedAccountSection(null, List.of(stagedRow()), 1, 0, List.of());
        var section2 = new StagedAccountSection(null, List.of(stagedRow()), 1, 0, List.of());
        when(importSessionService.readSections(session)).thenReturn(List.of(section1, section2));

        var sectionConfirm1 = new SectionConfirm(List.of(confirmedRow()), accountId, null, null, null);
        var sectionConfirm2 = new SectionConfirm(List.of(confirmedRow()), accountId, null, null, null);
        var request = new MultiAccountConfirmRequest(sessionId, List.of(sectionConfirm1, sectionConfirm2));

        importService.confirmMultiSection(userId, request);

        ArgumentCaptor<StatementImport> captor = ArgumentCaptor.forClass(StatementImport.class);
        verify(statementImportRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .as("neither section's row should carry its own copy of the shared file bytes")
                .allSatisfy(saved -> {
                    assertThat(saved.getFileContent()).isNull();
                    assertThat(saved.getObjectKey()).isEqualTo(address.key());
                });
    }
}
