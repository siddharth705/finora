package com.finora.imports;

import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.accounts.AccountService;
import com.finora.service.CategorizationService;
import com.finora.service.ReconciliationService;
import com.finora.service.RecurringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 2 (docs/proposals/statement-continuity-and-coverage-integrity-proposal.md §11) wired
 * through the real {@link ImportService#confirm} entry point -- confirms the glue between {@code
 * coverageWarningsFor} and {@link CoverageWarnings} actually fires and reaches
 * {@code response.warnings()}. The warning-generation logic itself is covered exhaustively,
 * without any mocking, in {@link CoverageWarningsTest}; this class only proves the wiring.
 *
 * <p>Wired the same way {@link ImportServiceOpeningBalanceCarryForwardTest} wires the real
 * pipeline -- only repositories and {@code CategorizationService} are mocked. {@code
 * findMetadataWithPeriodByUserIdAndAccountId} is stubbed dynamically off every statement this test
 * has actually saved so far (via {@link #savedStatements}), since the just-persisted statement's
 * real id is only known once {@code save} has run -- there is no way to pre-stub it before the
 * call that needs it.
 */
class ImportServiceCoverageWarningsTest {

    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private MerchantRepository merchantRepository;
    private StatementImportRepository statementImportRepository;
    private CategorizationService categorizationService;
    private ImportService importService;
    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final List<StatementImport> savedStatements = new ArrayList<>();

    @BeforeEach
    void setUp() {
        var learningEventPublisher = mock(com.finora.service.MerchantLearningEventPublisher.class);
        accountRepository = mock(AccountRepository.class);
        AccountService accountService = mock(AccountService.class);
        transactionRepository = mock(TransactionRepository.class);
        merchantRepository = mock(MerchantRepository.class);
        statementImportRepository = mock(StatementImportRepository.class);
        categorizationService = mock(CategorizationService.class);
        when(categorizationService.needsCategoryReview(any(), anyBoolean(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
        ReconciliationService reconciliationService = mock(ReconciliationService.class);
        RecurringService recurringService = mock(RecurringService.class);
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository, accountRepository);
        CsvParser csvParser = new CsvParser();
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector, com.finora.imports.TestRuleEngines.empty());
        StatementValidator statementValidator = new StatementValidator(com.finora.imports.product.ProductDiscovery.standard());
        PreviewGenerator previewGenerator = new PreviewGenerator(csvParser, transactionNormalizer, statementValidator, new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(), new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(), new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(), new com.finora.imports.CreditCardStatementTotalsValidator(), new com.finora.imports.CreditCardFlowReconciliationValidator()), com.finora.imports.TestRuleEngines.empty());
        ImportRuleLearningService ruleLearningService = new ImportRuleLearningService(categorizationService);

        importService = new ImportService(accountRepository, accountService, transactionRepository,
                merchantRepository, statementImportRepository, categorizationService, reconciliationService,
                recurringService, previewGenerator, duplicateDetector, ruleLearningService,
                mock(ImportSessionService.class), mock(com.finora.imports.pdf.PdfPreviewGenerator.class),
                new com.finora.imports.product.ProductIdentityResolver(accountRepository), new com.finora.imports.storage.StatementContentService(java.util.Optional.empty(), mock(com.finora.security.crypto.EncryptionService.class), "", ""),
                mock(com.finora.imports.analysis.StatementAnalysisRecorder.class),
                mock(com.finora.imports.analysis.ImportVerificationRecorder.class),
                learningEventPublisher, mock(LayoutRegistryService.class),
                mock(com.finora.imports.evidence.ClosingBalanceEvidenceShadowObserver.class));

        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", accountId);
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        when(accountRepository.findById(accountId)).thenReturn(java.util.Optional.of(account));

        Category otherCategory = new Category();
        otherCategory.setUserId(userId);
        otherCategory.setName("Other");
        when(categorizationService.resolveMerchantId(any(), any())).thenReturn(UUID.randomUUID());
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Other"))).thenReturn(otherCategory);

        when(transactionRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<Transaction> txns = inv.getArgument(0);
            txns.forEach(t -> ReflectionTestUtils.setField(t, "id", UUID.randomUUID()));
            return txns;
        });
        when(merchantRepository.findByUserId(any())).thenReturn(List.of());

        when(statementImportRepository.save(any(StatementImport.class))).thenAnswer(inv -> {
            StatementImport si = inv.getArgument(0);
            ReflectionTestUtils.setField(si, "id", UUID.randomUUID());
            savedStatements.add(si);
            return si;
        });
        when(statementImportRepository.findPriorStatementClosingBalanceForAccount(any(), any(), any(), any()))
                .thenReturn(List.of());
        // Phase 1's query, reused here (ImportService.coverageWarningsFor calls it too) -- built
        // dynamically off whatever this test has saved so far, since the just-persisted statement's
        // real id is unknown until save() returns it.
        when(statementImportRepository.findMetadataWithPeriodByUserIdAndAccountId(eq(userId), eq(accountId)))
                .thenAnswer(inv -> savedStatements.stream()
                        .filter(si -> si.getStatementPeriodStart() != null)
                        .map(ImportServiceCoverageWarningsTest::metadataFor)
                        .toList());
    }

    private static StatementImportRepository.StatementMetadata metadataFor(StatementImport si) {
        StatementImportRepository.StatementMetadata m = mock(StatementImportRepository.StatementMetadata.class);
        when(m.getId()).thenReturn(si.getId());
        when(m.getStatementPeriodStart()).thenReturn(si.getStatementPeriodStart());
        when(m.getStatementPeriodEnd()).thenReturn(si.getStatementPeriodEnd());
        when(m.getOpeningBalance()).thenReturn(si.getOpeningBalance());
        when(m.getClosingBalance()).thenReturn(si.getClosingBalance());
        when(m.getImportedAt()).thenReturn(Instant.now());
        return m;
    }

    private ConfirmedRow row(LocalDate date, String description, BigDecimal amount, String type) {
        return new ConfirmedRow(date, description, amount, type, "Other", true, "default", null, false, null, null);
    }

    private MockMultipartFile dummyFile() {
        return new MockMultipartFile("file", "statement.csv", "text/csv", "irrelevant".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void importingJuly_afterMay_warnsAboutTheMissingJune() throws Exception {
        var mayRow = row(LocalDate.of(2026, 5, 15), "SALARY", new BigDecimal("1000.00"), "INCOME");
        var mayRequest = new ConfirmRequest(null, List.of(mayRow), accountId, null,
                BigDecimal.ZERO, new BigDecimal("1000.00"), null,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), null, null);
        importService.confirm(userId, dummyFile(), mayRequest);

        var julyRow = row(LocalDate.of(2026, 7, 15), "SALARY", new BigDecimal("1000.00"), "INCOME");
        var julyRequest = new ConfirmRequest(null, List.of(julyRow), accountId, null,
                new BigDecimal("1000.00"), new BigDecimal("2000.00"), null,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), null, null);
        var response = importService.confirm(userId, dummyFile(), julyRequest);

        assertThat(response.warnings())
                .as("July directly borders the missing June -- must be warned at import time")
                .anyMatch(w -> w.contains("Missing statement detected") && w.contains("2026-06-01") && w.contains("2026-06-30"));
    }

    @Test
    void importingJune_immediatelyAfterMay_noCoverageWarning() throws Exception {
        var mayRow = row(LocalDate.of(2026, 5, 15), "SALARY", new BigDecimal("1000.00"), "INCOME");
        var mayRequest = new ConfirmRequest(null, List.of(mayRow), accountId, null,
                BigDecimal.ZERO, new BigDecimal("1000.00"), null,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), null, null);
        importService.confirm(userId, dummyFile(), mayRequest);

        var juneRow = row(LocalDate.of(2026, 6, 15), "SALARY", new BigDecimal("1000.00"), "INCOME");
        var juneRequest = new ConfirmRequest(null, List.of(juneRow), accountId, null,
                new BigDecimal("1000.00"), new BigDecimal("2000.00"), null,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), null, null);
        var response = importService.confirm(userId, dummyFile(), juneRequest);

        assertThat(response.warnings()).noneMatch(w -> w.contains("Missing statement detected"));
    }

    @Test
    void importingTheSamePeriodTwice_warnsAboutTheDuplicate() throws Exception {
        var juneRow = row(LocalDate.of(2026, 6, 15), "SALARY", new BigDecimal("1000.00"), "INCOME");
        var juneRequest = new ConfirmRequest(null, List.of(juneRow), accountId, null,
                BigDecimal.ZERO, new BigDecimal("1000.00"), null,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), null, null);
        importService.confirm(userId, dummyFile(), juneRequest);

        // Re-imported: same account, same exact printed period.
        var againRow = row(LocalDate.of(2026, 6, 16), "SALARY", new BigDecimal("1000.00"), "INCOME");
        var againRequest = new ConfirmRequest(null, List.of(againRow), accountId, null,
                BigDecimal.ZERO, new BigDecimal("1000.00"), null,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), null, null);
        var response = importService.confirm(userId, dummyFile(), againRequest);

        assertThat(response.warnings())
                .anyMatch(w -> w.contains("You already have a statement for this period")
                        && w.contains("Replacing an existing statement isn't supported yet"));
    }

    @Test
    void csvImport_withNoStatementPeriod_producesNoCoverageWarning_andDoesNotThrow() throws Exception {
        var row = row(LocalDate.of(2026, 6, 15), "SALARY", new BigDecimal("1000.00"), "INCOME");
        var request = new ConfirmRequest(null, List.of(row), accountId, null,
                BigDecimal.ZERO, new BigDecimal("1000.00"), null,
                null, null, null, null);

        var response = importService.confirm(userId, dummyFile(), request);

        assertThat(response.warnings()).noneMatch(w -> w.contains("Missing statement detected")
                || w.contains("You already have a statement for this period"));
    }
}
