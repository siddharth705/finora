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
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BUG 1 repro: PNB's July statement prints "For Period: 30-06-2026 to 31-07-2026" --
 * sharing its start boundary with June's own end boundary -- and re-lists June's last day's
 * transactions inside the July PDF. {@code BalanceSequenceResolver} (staging time, not exercised
 * by this test directly) derives July's opening balance from ITS OWN earliest printed row, which
 * is one of those re-listed 30/06 rows, landing on the balance BEFORE it (32,013.97) rather than
 * after it (35,354.97 -- June's own correct closing balance). This proves the fix at the point
 * where it actually lands: {@code ImportService.persistSection} wiring {@code
 * OpeningBalanceCarryForward} against the account's own prior statement.
 *
 * <p>Wired the same way {@link ImportServiceAskOnceTest} wires the real pipeline -- only
 * repositories and {@code CategorizationService} are mocked.
 */
class ImportServiceOpeningBalanceCarryForwardTest {

    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private MerchantRepository merchantRepository;
    private StatementImportRepository statementImportRepository;
    private CategorizationService categorizationService;
    private ImportService importService;
    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

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
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
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
        account.setName("PNB Savings");
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
            return si;
        });
        // No prior statement by default -- overridden per-test where one is needed. Matches a
        // real (unmocked) repository's behavior for an account with no import history yet.
        when(statementImportRepository.findPriorStatementClosingBalanceForAccount(any(), any(), any(), any()))
                .thenReturn(List.of());
    }

    private ConfirmedRow row(LocalDate date, String description, BigDecimal amount, String type) {
        return new ConfirmedRow(date, description, amount, type, "Other", true, "default", null, false, null, null);
    }

    private MockMultipartFile dummyFile() {
        return new MockMultipartFile("file", "statement.csv", "text/csv", "irrelevant".getBytes(StandardCharsets.UTF_8));
    }

    private StatementImport secondSavedStatementImport() {
        ArgumentCaptor<StatementImport> captor = ArgumentCaptor.forClass(StatementImport.class);
        verify(statementImportRepository, times(2)).save(captor.capture());
        return captor.getAllValues().get(1);
    }

    @Test
    void secondImport_carriesForwardOpeningBalance_fromFirstImportsClosingBalance_whenTheyDisagree() throws Exception {
        // June: 31-05-2026 to 30-06-2026, closes at 35,354.97 -- as printed on the statement and
        // corroborated by its own rows.
        var juneRow = row(LocalDate.of(2026, 6, 30), "SALARY", new BigDecimal("35354.97"), "INCOME");
        var juneRequest = new ConfirmRequest(null, List.of(juneRow), accountId, null,
                BigDecimal.ZERO, new BigDecimal("35354.97"), null,
                LocalDate.of(2026, 5, 31), LocalDate.of(2026, 6, 30), null, null);
        importService.confirm(userId, dummyFile(), juneRequest);

        // July: "For Period: 30-06-2026 to 31-07-2026" -- shares its start with June's own end.
        // Its own derived opening balance (32,013.97) is what BalanceSequenceResolver would
        // compute from the re-listed 30/06 row in the real PDF -- wrong by the overlapping
        // day's net effect. The prior statement's closing balance (looked up here, since the
        // account now has one on file) must win instead.
        when(statementImportRepository.findPriorStatementClosingBalanceForAccount(
                eq(userId), eq(accountId), eq(LocalDate.of(2026, 6, 30)), any()))
                .thenReturn(List.of(new BigDecimal("35354.97")));

        var julyRow = row(LocalDate.of(2026, 7, 28), "ACH/INDIAN CLEARING CORP/30473",
                new BigDecimal("500.00"), "EXPENSE");
        var julyRequest = new ConfirmRequest(null, List.of(julyRow), accountId, null,
                new BigDecimal("32013.97"), new BigDecimal("7025.86"), null,
                LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 31), null, null);

        var response = importService.confirm(userId, dummyFile(), julyRequest);

        assertThat(secondSavedStatementImport().getOpeningBalance())
                .as("July's stored opening balance should be June's closing balance, not July's own wrong derivation")
                .isEqualByComparingTo(new BigDecimal("35354.97"));
        assertThat(response.warnings())
                .as("the user should be told the opening balance was carried forward")
                .anyMatch(w -> w.contains("carried forward"));
    }

    @Test
    void secondImport_leavesOpeningBalanceUnchanged_whenItAlreadyAgreesWithThePriorClose() throws Exception {
        var juneRow = row(LocalDate.of(2026, 6, 30), "SALARY", new BigDecimal("35354.97"), "INCOME");
        var juneRequest = new ConfirmRequest(null, List.of(juneRow), accountId, null,
                BigDecimal.ZERO, new BigDecimal("35354.97"), null,
                LocalDate.of(2026, 5, 31), LocalDate.of(2026, 6, 30), null, null);
        importService.confirm(userId, dummyFile(), juneRequest);

        when(statementImportRepository.findPriorStatementClosingBalanceForAccount(
                eq(userId), eq(accountId), eq(LocalDate.of(2026, 6, 30)), any()))
                .thenReturn(List.of(new BigDecimal("35354.97")));

        var julyRow = row(LocalDate.of(2026, 7, 1), "SALARY", new BigDecimal("100.00"), "INCOME");
        var julyRequest = new ConfirmRequest(null, List.of(julyRow), accountId, null,
                new BigDecimal("35354.97"), new BigDecimal("35454.97"), null,
                LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 31), null, null);

        var response = importService.confirm(userId, dummyFile(), julyRequest);

        assertThat(secondSavedStatementImport().getOpeningBalance())
                .isEqualByComparingTo(new BigDecimal("35354.97"));
        assertThat(response.warnings()).noneMatch(w -> w.contains("carried forward"));
    }

    @Test
    void secondImport_keepsItsOwnOpeningBalance_whenItReconcilesAgainstItsOwnTotals_evenIfItDisagreesWithThePriorClose() throws Exception {
        // A statement's own opening balance disagreeing with the prior statement's close is not
        // always a defect to correct -- it can mean the user genuinely never imported an
        // intermediate statement, and the bank's own printed figure already reflects everything
        // that happened in between (a deposit here, say). That figure is still correct FOR THIS
        // STATEMENT: it reconciles cleanly against this statement's own totals and claimed
        // closing balance, which is what distinguishes it from PNB's case (wrong by construction,
        // and provably so against ITS OWN totals). Carry-forward must never override a statement
        // whose own arithmetic already checks out, no matter what Finora's own (necessarily
        // incomplete) history says.
        var juneRow = row(LocalDate.of(2026, 6, 30), "SALARY", new BigDecimal("35354.97"), "INCOME");
        var juneRequest = new ConfirmRequest(null, List.of(juneRow), accountId, null,
                BigDecimal.ZERO, new BigDecimal("35354.97"), null,
                LocalDate.of(2026, 5, 31), LocalDate.of(2026, 6, 30), null, null);
        importService.confirm(userId, dummyFile(), juneRequest);

        when(statementImportRepository.findPriorStatementClosingBalanceForAccount(
                eq(userId), eq(accountId), eq(LocalDate.of(2026, 6, 30)), any()))
                .thenReturn(List.of(new BigDecimal("35354.97")));

        // A cash deposit of 4,645.03 happened in a period the user never imported, so the July
        // statement's own opening balance (40,000.00) is genuinely higher than June's close --
        // and correctly reconciles against July's own single 5,000.00 expense to its own claimed
        // closing balance of 35,000.00.
        var julyRow = row(LocalDate.of(2026, 7, 5), "RENT", new BigDecimal("5000.00"), "EXPENSE");
        var julyRequest = new ConfirmRequest(null, List.of(julyRow), accountId, null,
                new BigDecimal("40000.00"), new BigDecimal("35000.00"), null,
                LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 31), null, null);

        var response = importService.confirm(userId, dummyFile(), julyRequest);

        assertThat(secondSavedStatementImport().getOpeningBalance())
                .as("July's own opening balance reconciles against its own totals -- must not be overwritten")
                .isEqualByComparingTo(new BigDecimal("40000.00"));
        assertThat(response.warnings()).noneMatch(w -> w.contains("carried forward"));
    }
}
