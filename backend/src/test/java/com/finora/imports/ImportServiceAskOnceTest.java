package com.finora.imports;

import com.finora.accounts.AccountDto;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.NewAccountRequest;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Merchant;
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
 * Covers ImportService's confirm()/parseAndStage() behavior: the "Ask Once, Learn Forever"
 * category-learning rule, the one-account-per-import model (existing account vs. auto-created
 * new account — see ImportDto.ConfirmRequest), the Debit/Credit misclassification regression,
 * and that the Import Summary (ConfirmResponse) reports real counts rather than placeholders.
 *
 * Exercises the real CsvParser / TransactionNormalizer / StatementValidator / PreviewGenerator /
 * DuplicateDetector / ImportRuleLearningService wiring end-to-end (only repositories and
 * CategorizationService are mocked), rather than mocking every collaborator, so this test still
 * proves the pipeline behaves correctly as a whole after the v56 modularization split.
 */
class ImportServiceAskOnceTest {

    private AccountRepository accountRepository;
    private AccountService accountService;
    private TransactionRepository transactionRepository;
    private MerchantRepository merchantRepository;
    private StatementImportRepository statementImportRepository;
    private CategorizationService categorizationService;
    private ReconciliationService reconciliationService;
    private RecurringService recurringService;
    private ImportService importService;
    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    private com.finora.service.MerchantLearningEventPublisher learningEventPublisher;

    @BeforeEach
    void setUp() {
        learningEventPublisher = mock(com.finora.service.MerchantLearningEventPublisher.class);
        accountRepository = mock(AccountRepository.class);
        accountService = mock(AccountService.class);
        transactionRepository = mock(TransactionRepository.class);
        merchantRepository = mock(MerchantRepository.class);
        statementImportRepository = mock(StatementImportRepository.class);
        categorizationService = mock(CategorizationService.class);
        reconciliationService = mock(ReconciliationService.class);
        recurringService = mock(RecurringService.class);
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        CsvParser csvParser = new CsvParser();
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector, com.finora.imports.TestRuleEngines.empty());
        StatementValidator statementValidator = new StatementValidator(com.finora.imports.product.ProductDiscovery.standard());
        PreviewGenerator previewGenerator = new PreviewGenerator(csvParser, transactionNormalizer, statementValidator, new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(), new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(), new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(), new com.finora.imports.CreditCardStatementTotalsValidator(), new com.finora.imports.CreditCardFlowReconciliationValidator()), com.finora.imports.TestRuleEngines.empty());
        ImportRuleLearningService ruleLearningService = new ImportRuleLearningService(categorizationService);

        // Wired the same way Spring would assemble it — see the v56 modularization pass, which
        // split the old monolithic CsvImportService into these focused collaborators. Only the
        // repository/service boundary below this line is mocked; everything above is real.
        // ImportSessionService is mocked but never actually exercised by these tests -- they all
        // go through the MultipartFile confirm() overload with sessionId left null throughout,
        // not confirmSession() (see ImportServiceSessionTest for session-specific coverage).
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
        when(accountRepository.findById(accountId)).thenReturn(java.util.Optional.of(account));

        Category otherCategory = new Category();
        otherCategory.setUserId(userId);
        otherCategory.setName("Other");
        // resolveMerchantId is a mock here, and an unstubbed mock returns null. ImportService
        // skips queueing learning when it cannot resolve a merchant -- the event has a NOT NULL
        // FK to merchants, so queueing without one would fail at insert. Stubbed so the
        // Ask-Once-Learn-Forever tests below exercise the queueing path rather than that guard.
        when(categorizationService.resolveMerchantId(any(), any())).thenReturn(UUID.randomUUID());
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Other"))).thenReturn(otherCategory);

        Category diningCategory = new Category();
        diningCategory.setUserId(userId);
        diningCategory.setName("Dining");
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Dining"))).thenReturn(diningCategory);

        // saveAll needs to hand back entities with IDs set, same as a real JPA save would —
        // confirm() relies on this to re-fetch the batch by ID for the summary counts.
        when(transactionRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<Transaction> txns = inv.getArgument(0);
            txns.forEach(t -> ReflectionTestUtils.setField(t, "id", UUID.randomUUID()));
            return txns;
        });
        when(merchantRepository.findByUserId(any())).thenReturn(List.of());

        // Likewise for the StatementImport row itself — confirm() reads its generated id back
        // to stamp onto every transaction in the batch before saving them.
        when(statementImportRepository.save(any(StatementImport.class))).thenAnswer(inv -> {
            StatementImport si = inv.getArgument(0);
            ReflectionTestUtils.setField(si, "id", UUID.randomUUID());
            return si;
        });
    }

    private ConfirmRequest requestWith(ConfirmedRow row) {
        return new ConfirmRequest(null, List.of(row), accountId, null, null, null,
                null);
    }

    private MockMultipartFile dummyFile() {
        return new MockMultipartFile("file", "statement.csv", "text/csv", "irrelevant".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void confirm_doesNotLearn_whenLowConfidenceGuessLeftUnresolvedAsOther() throws Exception {
        var row = new ConfirmedRow(LocalDate.of(2026, 7, 10), "UNKNOWN MERCHANT XYZ",
                BigDecimal.valueOf(500), "EXPENSE", "Other", true, "default", null, false, null, null);

        importService.confirm(userId, dummyFile(), requestWith(row));

        // An unresolved guess still teaches nothing -- now asserted as "nothing was queued",
        // which is where the decision lands after WI1.
        verify(learningEventPublisher, never()).enqueue(any(), any(), any(), any(), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Transaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).isNeedsCategoryReview()).isTrue();
    }

    @Test
    void confirm_learns_whenRuleEngineWasConfident() throws Exception {
        var row = new ConfirmedRow(LocalDate.of(2026, 7, 10), "SWIGGY*ORDR9182 BLR",
                BigDecimal.valueOf(486), "EXPENSE", "Dining", true, "rule", null, false, null, null);

        importService.confirm(userId, dummyFile(), requestWith(row));

        // WI1: the rule is unchanged -- a confident match still teaches the merchant map -- but the
        // learning is QUEUED rather than applied inside this transaction. Asserting on the
        // publisher rather than on learn() is the whole point: learn() being called here again
        // would be Bug 02 restored, since it applies the confirmation inline and a lost race
        // against UNIQUE(user_id, merchant_id, category_id) would roll the import back.
        verify(learningEventPublisher).enqueue(eq(userId), any(), any(), any(), any());
        verify(categorizationService, never()).learn(any(), any(), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Transaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).isNeedsCategoryReview()).isFalse();
    }

    @Test
    void confirm_learns_whenOtherWasAConfidentRuleMatch_notAnUnresolvedDefault() throws Exception {
        var row = new ConfirmedRow(LocalDate.of(2026, 7, 10), "ATM WITHDRAWAL FEE",
                BigDecimal.valueOf(20), "EXPENSE", "Other", true, "rule", null, false, null, null);

        importService.confirm(userId, dummyFile(), requestWith(row));

        // "Other" from a CONFIDENT rule match is a real decision and still teaches -- what changed
        // is only that it teaches via the queue. See the sibling test above.
        verify(learningEventPublisher).enqueue(any(), any(), any(), any(), any());
    }

    @Test
    void confirm_assignCategoryRuleMatch_recordsTheMatch_usingTheRuleIdCarriedFromStaging() throws Exception {
        // Financial Intelligence Workspace, Rule Management module -- confirm() is the actual
        // write (parseRow()'s suggest() call at staging time is only a preview, possibly never
        // confirmed), so the match is recorded here using row.ruleId() rather than by
        // re-evaluating rules against the confirmed row. See RuleEngineService.recordMatch's own
        // doc comment.
        UUID ruleId = UUID.randomUUID();
        var row = new ConfirmedRow(LocalDate.of(2026, 7, 10), "SWIGGY*ORDR9182 BLR",
                BigDecimal.valueOf(486), "EXPENSE", "Dining", true, "user_rule", ruleId, false, null, null);

        importService.confirm(userId, dummyFile(), requestWith(row));

        verify(categorizationService).recordRuleMatch(ruleId);
    }

    @Test
    void confirm_noRuleBehindTheSuggestion_recordsNothing() throws Exception {
        var row = new ConfirmedRow(LocalDate.of(2026, 7, 10), "SWIGGY*ORDR9182 BLR",
                BigDecimal.valueOf(486), "EXPENSE", "Dining", true, "learned", null, false, null, null);

        importService.confirm(userId, dummyFile(), requestWith(row));

        verify(categorizationService).recordRuleMatch(null);
    }

    @Test
    void confirm_resolvesAndSetsMerchantId_onEveryImportedTransaction() throws Exception {
        UUID resolvedMerchantId = UUID.randomUUID();
        when(categorizationService.resolveMerchantId(eq(userId), eq("SWIGGY*ORDR9182 BLR"))).thenReturn(resolvedMerchantId);

        var row = new ConfirmedRow(LocalDate.of(2026, 7, 10), "SWIGGY*ORDR9182 BLR",
                BigDecimal.valueOf(486), "EXPENSE", "Dining", true, "rule", null, false, null, null);

        importService.confirm(userId, dummyFile(), requestWith(row));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Transaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getMerchantId()).isEqualTo(resolvedMerchantId);
    }

    /**
     * BH-041 changed WHICH entry point the import path reconciles through, not whether it does.
     *
     * <p>{@code reconcileForImport} narrows the candidate set to the imported date range ±the widest
     * matching window; {@code reconcileForUser} keeps its unbounded behaviour for its seven other
     * callers, one of which depends on the full re-scan. Asserting the specific method matters here
     * rather than being over-specification: "reconciliation happened" would pass if the import path
     * silently reverted to the unbounded pass, which is the regression this names.
     *
     * <p>The date arguments are asserted too — a window built from the wrong dates would still
     * satisfy a bare {@code any(), any()} while quietly failing to load the rows it exists to find.
     */
    @Test
    void confirm_runsReconciliation_afterImportingAtLeastOneTransaction() throws Exception {
        var row = new ConfirmedRow(LocalDate.of(2026, 7, 10), "SWIGGY*ORDR9182 BLR",
                BigDecimal.valueOf(486), "EXPENSE", "Dining", true, "rule", null, false, null, null);

        importService.confirm(userId, dummyFile(), requestWith(row));

        verify(reconciliationService).reconcileForImport(
                userId, LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 10));
        verify(reconciliationService, never()).reconcileForUser(any());
    }

    @Test
    void confirm_skipsReconciliation_whenNothingWasActuallyImported() throws Exception {
        var row = new ConfirmedRow(LocalDate.of(2026, 7, 10), "SWIGGY*ORDR9182 BLR",
                BigDecimal.valueOf(486), "EXPENSE", "Dining", false, "rule", null, false, null, null);

        var response = importService.confirm(userId, dummyFile(), requestWith(row));

        verify(reconciliationService, never()).reconcileForUser(any());
        assertThat(response.skipped()).isEqualTo(1);
        assertThat(response.imported()).isEqualTo(0);
    }

    @Test
    void confirm_runsRecurringDetection_alongsideReconciliation_afterImportingAtLeastOneTransaction() throws Exception {
        // See docs/team-message-financial-intelligence-v1-closeout.md -- a MARK_SUBSCRIPTION
        // rule match (or a completed pattern spanning this import) must take effect immediately,
        // not only once the user happens to open the Recurring page.
        var row = new ConfirmedRow(LocalDate.of(2026, 7, 10), "NETFLIX.COM",
                BigDecimal.valueOf(649), "EXPENSE", "Dining", true, "rule", null, false, null, null); // category is irrelevant to this test -- reusing the pre-stubbed one from setUp()

        importService.confirm(userId, dummyFile(), requestWith(row));

        verify(recurringService).detectForUser(userId);
    }

    @Test
    void confirm_skipsRecurringDetection_whenNothingWasActuallyImported() throws Exception {
        var row = new ConfirmedRow(LocalDate.of(2026, 7, 10), "SWIGGY*ORDR9182 BLR",
                BigDecimal.valueOf(486), "EXPENSE", "Dining", false, "rule", null, false, null, null);

        importService.confirm(userId, dummyFile(), requestWith(row));

        verify(recurringService, never()).detectForUser(any());
    }

    @Test
    void confirm_createsTheAccount_whenNewAccountDetailsAreProvidedInsteadOfAnExistingId() throws Exception {
        UUID newAccountId = UUID.randomUUID();
        when(accountService.create(eq(userId), any(), any())).thenReturn(
                new AccountDto(newAccountId, "HDFC Savings", "SAVINGS", BigDecimal.valueOf(15000), null, null, null, null, null,
                        null, null,
                        AccountDto.BankDto.from(com.finora.util.BankRegistry.get("OTHER")), null, null, null,
                        0, 0L, "ACTIVE",
                        null, null, null, null, null, null, null));

        var row = new ConfirmedRow(LocalDate.of(2026, 7, 10), "SWIGGY*ORDR9182 BLR",
                BigDecimal.valueOf(486), "EXPENSE", "Dining", true, "rule", null, false, null, null);
        var newAccount = new NewAccountRequest("HDFC Savings", "SAVINGS", BigDecimal.valueOf(15000), null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
        var request = new ConfirmRequest(null, List.of(row), null, newAccount, null, null,
                null);

        var response = importService.confirm(userId, dummyFile(), request);

        verify(accountService).create(eq(userId), any(), any());
        assertThat(response.accountsCreated()).containsExactly("HDFC Savings");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Transaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getAccountId()).isEqualTo(newAccountId);
    }

    @Test
    void confirm_routesATermDepositToInvestments_notToAnEmptySavingsAccount() throws Exception {
        // Phase 3 step 8. A deposit section used to become a savings account with no transactions
        // in it, because the review form only ever offered account types. FinancialProductType
        // carries its own routing, so the product decides where it lands: INVESTMENT with an
        // investmentKind of "FD", alongside mutual funds and PPF in the Investments module.
        UUID newAccountId = UUID.randomUUID();
        when(accountService.create(eq(userId), any(), any())).thenReturn(
                new AccountDto(newAccountId, "HDFC Term Deposit", "INVESTMENT", BigDecimal.valueOf(100000),
                        null, null, null, null, null, null, null,
                        AccountDto.BankDto.from(com.finora.util.BankRegistry.get("OTHER")), null, null, null,
                        0, 0L, "ACTIVE",
                        null, null, null, null, null, null, null));

        var row = new ConfirmedRow(LocalDate.of(2026, 7, 10), "Deposit",
                BigDecimal.valueOf(100000), "INCOME", "Dining", true, "rule", null, false, null, null);
        // The review screen echoes the classification back; accountType stays what the form had.
        var newAccount = new NewAccountRequest("HDFC Term Deposit", "SAVINGS", BigDecimal.valueOf(100000),
                null, null, null, null, null, null, null, "FIXED_DEPOSIT", null,
                null, null, null, null, null, null, null);
        var request = new ConfirmRequest(null, List.of(row), null, newAccount, null, null,
                null);

        var response = importService.confirm(userId, dummyFile(), request);

        ArgumentCaptor<AccountDto.CreateRequest> captor =
                ArgumentCaptor.forClass(AccountDto.CreateRequest.class);
        verify(accountService).create(eq(userId), captor.capture(), any());
        assertThat(captor.getValue().accountType())
                .as("the product's own routing wins over the form's default")
                .isEqualTo("INVESTMENT");
        assertThat(captor.getValue().investmentKind()).isEqualTo("FD");
        assertThat(response.productsCreated())
                .as("the summary names products, not a count of accounts")
                .containsEntry("FIXED_DEPOSIT", 1);
    }

    @Test
    void confirm_leavesAnUnknownProductToWhateverTypeTheUserPicked() throws Exception {
        // The correction loop's backstop: an unclassifiable product has nothing to route by, so the
        // user's one-time answer on the review screen is what decides -- never a guess.
        UUID newAccountId = UUID.randomUUID();
        when(accountService.create(eq(userId), any(), any())).thenReturn(
                new AccountDto(newAccountId, "Mystery", "WALLET", BigDecimal.ZERO, null, null, null, null,
                        null, null, null,
                        AccountDto.BankDto.from(com.finora.util.BankRegistry.get("OTHER")), null, null, null,
                        0, 0L, "ACTIVE",
                        null, null, null, null, null, null, null));

        var row = new ConfirmedRow(LocalDate.of(2026, 7, 10), "Something",
                BigDecimal.valueOf(10), "EXPENSE", "Other", true, "rule", null, false, null, null);
        var newAccount = new NewAccountRequest("Mystery", "WALLET", BigDecimal.ZERO, null, null,
                null, null, null, null, null, "UNKNOWN", null,
                null, null, null, null, null, null, null);
        var request = new ConfirmRequest(null, List.of(row), null, newAccount, null, null,
                null);

        importService.confirm(userId, dummyFile(), request);

        ArgumentCaptor<AccountDto.CreateRequest> captor =
                ArgumentCaptor.forClass(AccountDto.CreateRequest.class);
        verify(accountService).create(eq(userId), captor.capture(), any());
        assertThat(captor.getValue().accountType()).isEqualTo("WALLET");
        assertThat(captor.getValue().investmentKind()).isNull();
    }

    @Test
    void confirm_honoursAnInvestmentChoice_evenWhenTheClientSendsNoDetectedProduct() throws Exception {
        // Bug: the no-detected-product fallback mapped anything that wasn't CREDIT_CARD or WALLET to
        // SAVINGS, and SAVINGS's own routing then overrode the form -- so a user who explicitly
        // picked Investment on the review screen got a Savings account. Only reachable from a client
        // that doesn't echo detectedProduct back (an older build), which is exactly the case that
        // should degrade to "do what the user said", not "invent a product they didn't choose".
        UUID newAccountId = UUID.randomUUID();
        when(accountService.create(eq(userId), any(), any())).thenReturn(
                new AccountDto(newAccountId, "Gold Fund", "INVESTMENT", BigDecimal.ZERO, null, null, null, null,
                        null, null, null,
                        AccountDto.BankDto.from(com.finora.util.BankRegistry.get("OTHER")), null, null, null,
                        0, 0L, "ACTIVE",
                        null, null, null, null, null, null, null));

        var row = new ConfirmedRow(LocalDate.of(2026, 7, 10), "Something",
                BigDecimal.valueOf(10), "EXPENSE", "Other", true, "rule", null, false, null, null);
        var newAccount = new NewAccountRequest("Gold Fund", "INVESTMENT", BigDecimal.ZERO, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);

        importService.confirm(userId, dummyFile(), new ConfirmRequest(null, List.of(row), null, newAccount, null, null,
                null));

        ArgumentCaptor<AccountDto.CreateRequest> captor =
                ArgumentCaptor.forClass(AccountDto.CreateRequest.class);
        verify(accountService).create(eq(userId), captor.capture(), any());
        assertThat(captor.getValue().accountType())
                .as("the user's own choice must survive when nothing was detected")
                .isEqualTo("INVESTMENT");
    }

    @Test
    void confirm_throws_whenNeitherExistingAccountNorNewAccountIsProvided() {
        var row = new ConfirmedRow(LocalDate.of(2026, 7, 10), "SWIGGY*ORDR9182 BLR",
                BigDecimal.valueOf(486), "EXPENSE", "Dining", true, "rule", null, false, null, null);
        var request = new ConfirmRequest(null, List.of(row), null, null, null, null,
                null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> importService.confirm(userId, dummyFile(), request))
                .isInstanceOf(com.finora.exception.ApiException.class);
    }

    @Test
    void confirm_reportsNewMerchantsLearned_asTheNetIncreaseInMerchantCountForThisUser() throws Exception {
        // countByUserId, not findByUserId().size(): ImportService only ever needed the number, and
        // was loading and hydrating the user's whole merchant table twice per import to get it.
        // Equivalent by construction -- Merchant carries no soft-delete filter, so the count and
        // the list size are the same value.
        when(merchantRepository.countByUserId(userId))
                .thenReturn(0L)  // "before" snapshot
                .thenReturn(2L); // "after" snapshot: 2 new merchants

        var row = new ConfirmedRow(LocalDate.of(2026, 7, 10), "SWIGGY*ORDR9182 BLR",
                BigDecimal.valueOf(486), "EXPENSE", "Dining", true, "rule", null, false, null, null);

        var response = importService.confirm(userId, dummyFile(), requestWith(row));

        assertThat(response.newMerchantsLearned()).isEqualTo(2);
    }

    @Test
    void confirm_tallysCategoriesAssigned_acrossTheImportedBatch() throws Exception {
        var row1 = new ConfirmedRow(LocalDate.of(2026, 7, 10), "SWIGGY*ORDR9182 BLR",
                BigDecimal.valueOf(486), "EXPENSE", "Dining", true, "rule", null, false, null, null);
        var row2 = new ConfirmedRow(LocalDate.of(2026, 7, 11), "ZOMATO ORDER",
                BigDecimal.valueOf(300), "EXPENSE", "Dining", true, "rule", null, false, null, null);
        var request = new ConfirmRequest(null, List.of(row1, row2), accountId, null, null, null,
                null);

        var response = importService.confirm(userId, dummyFile(), request);

        assertThat(response.categoriesAssigned()).containsEntry("Dining", 2);
    }

    @Test
    void confirm_prefersRequestSuppliedStatementPeriod_overTheConfirmedRowsDateRange() throws Exception {
        // A printed "Statement Period" can be wider than the rows a user actually has
        // transactions for (e.g. no activity in the first few days of the cycle) --
        // PdfPreviewGenerator/StatementValidator already compute and surface this correctly at
        // staging time (see their own buildDetectedAccountInfo), but until now ConfirmRequest had
        // nowhere to carry it back, so persistSection silently re-derived the period from
        // minDate/maxDate of the confirmed rows alone, discarding the printed period the user was
        // shown on the review screen.
        var row1 = new ConfirmedRow(LocalDate.of(2026, 7, 10), "SWIGGY*ORDR9182 BLR",
                BigDecimal.valueOf(486), "EXPENSE", "Dining", true, "rule", null, false, null, null);
        var row2 = new ConfirmedRow(LocalDate.of(2026, 7, 12), "ZOMATO ORDER",
                BigDecimal.valueOf(300), "EXPENSE", "Dining", true, "rule", null, false, null, null);
        var request = new ConfirmRequest(null, List.of(row1, row2), accountId, null, null, null, null,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        importService.confirm(userId, dummyFile(), request);

        ArgumentCaptor<StatementImport> captor = ArgumentCaptor.forClass(StatementImport.class);
        verify(statementImportRepository).save(captor.capture());
        assertThat(captor.getValue().getStatementPeriodStart()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(captor.getValue().getStatementPeriodEnd()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    void confirm_reportsTheRequestSuppliedStatementPeriod_onTheImmediateSummaryResponse() throws Exception {
        // Same fix as above, but for the response the user actually sees first -- the "Statement
        // period: ..." line on the post-confirm summary screen (Import.tsx) reads
        // ConfirmResponse.statementPeriodStart/End directly, not the persisted StatementImport row.
        var row1 = new ConfirmedRow(LocalDate.of(2026, 7, 10), "SWIGGY*ORDR9182 BLR",
                BigDecimal.valueOf(486), "EXPENSE", "Dining", true, "rule", null, false, null, null);
        var row2 = new ConfirmedRow(LocalDate.of(2026, 7, 12), "ZOMATO ORDER",
                BigDecimal.valueOf(300), "EXPENSE", "Dining", true, "rule", null, false, null, null);
        var request = new ConfirmRequest(null, List.of(row1, row2), accountId, null, null, null, null,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        var response = importService.confirm(userId, dummyFile(), request);

        assertThat(response.statementPeriodStart()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(response.statementPeriodEnd()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    void confirm_leavesTheStatementPeriodNull_whenTheRequestCarriesNoStatementPeriod() throws Exception {
        // Bug fix: this used to fall back to the confirmed rows' own min/max date -- which is only
        // ever a LOWER bound on the statement's true period whenever a cycle has no activity near
        // its own printed boundary dates. Confirmed wrong against a real Kotak Mahindra Bank
        // credit-card statement, whose own earliest/latest transactions fall inside its printed
        // period rather than at its edges (see PdfPreviewGenerator.buildDetectedAccountInfo's own
        // comment, which had and removed the identical fallback). No genuine period was ever printed
        // here (the request carries none), so this stays null rather than guessing one from the rows.
        var row1 = new ConfirmedRow(LocalDate.of(2026, 7, 10), "SWIGGY*ORDR9182 BLR",
                BigDecimal.valueOf(486), "EXPENSE", "Dining", true, "rule", null, false, null, null);
        var row2 = new ConfirmedRow(LocalDate.of(2026, 7, 12), "ZOMATO ORDER",
                BigDecimal.valueOf(300), "EXPENSE", "Dining", true, "rule", null, false, null, null);
        var request = new ConfirmRequest(null, List.of(row1, row2), accountId, null, null, null, null);

        importService.confirm(userId, dummyFile(), request);

        ArgumentCaptor<StatementImport> captor = ArgumentCaptor.forClass(StatementImport.class);
        verify(statementImportRepository).save(captor.capture());
        assertThat(captor.getValue().getStatementPeriodStart()).isNull();
        assertThat(captor.getValue().getStatementPeriodEnd()).isNull();
    }

    @Test
    void confirm_appliesSideEffectRules_andReflectsTheOverriddenCategoryInTheTallyAndTheSavedTransaction() throws Exception {
        // A matching MARK_INVESTMENT rule overrides whatever category was staged for this row --
        // CategorizationService.applySideEffectRules returns the new Category, and confirm() must
        // reflect it in both the persisted transaction and the categoriesAssigned tally, not the
        // pre-side-effect staged category.
        Category shopping = new Category();
        ReflectionTestUtils.setField(shopping, "id", UUID.randomUUID());
        shopping.setUserId(userId);
        shopping.setName("Shopping");
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Shopping"))).thenReturn(shopping);

        Category investments = new Category();
        ReflectionTestUtils.setField(investments, "id", UUID.randomUUID());
        investments.setUserId(userId);
        investments.setName("Investments");
        when(categorizationService.applySideEffectRules(eq(userId), any(Transaction.class), any()))
                .thenReturn(investments);

        var row = new ConfirmedRow(LocalDate.of(2026, 7, 10), "SIP MUTUAL FUND DEDUCTION",
                BigDecimal.valueOf(5000), "EXPENSE", "Shopping", true, "rule", null, false, null, null);
        var request = new ConfirmRequest(null, List.of(row), accountId, null, null, null,
                null);

        var response = importService.confirm(userId, dummyFile(), request);

        assertThat(response.categoriesAssigned()).containsEntry("Investments", 1);
        assertThat(response.categoriesAssigned()).doesNotContainKey("Shopping");

        ArgumentCaptor<List<Transaction>> savedCaptor = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(savedCaptor.capture());
        assertThat(savedCaptor.getValue().get(0).getCategoryId()).isEqualTo(investments.getId());
    }

    /**
     * Regression test for the Debit/Credit misclassification bug: CSVReaderHeaderAware.readMap()
     * returns every header as a key on every row (even when blank), so a naive
     * `row.containsKey("Credit")` check was true for every row in a Debit/Credit-style statement —
     * including expense rows where "Credit" was simply empty. Only a non-blank Credit value
     * should mark a row as income.
     */
    @Test
    void parseAndStage_classifiesDebitCreditRowAsExpense_whenCreditColumnIsBlank() throws Exception {
        when(categorizationService.suggestReadOnly(anyList(), eq(userId), anyString(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Dining", "rule", UUID.randomUUID(), Transaction.DecisionSource.KEYWORD_MATCH, null));
        when(categorizationService.suggestReadOnly(anyList(), eq(userId), anyString(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Dining", "rule", UUID.randomUUID(), Transaction.DecisionSource.KEYWORD_MATCH, null));

        String csv = "Date,Description,Debit,Credit\n2026-07-10,SWIGGY ORDER,486.00,\n";
        MockMultipartFile file = new MockMultipartFile("file", "statement.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        StagingResponse response = importService.parseAndStage(userId, file.getOriginalFilename(), file.getInputStream());

        assertThat(response.rows()).hasSize(1);
        assertThat(response.rows().get(0).type()).isEqualTo("EXPENSE");
    }

    @Test
    void parseAndStage_classifiesDebitCreditRowAsIncome_whenCreditColumnIsPopulated() throws Exception {
        // Bug fix: missing from this test (unlike its sibling immediately above), so
        // categorizationService.suggestReadOnly() returned null (a plain record, not a collection --
        // Mockito's smart-null defaults don't cover it) and TransactionNormalizer.normalize's
        // suggestion.category() call NPE'd before this test's actual assertion (row type) was
        // ever reached.
        when(categorizationService.suggestReadOnly(anyList(), eq(userId), anyString(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Salary", "rule", UUID.randomUUID(), Transaction.DecisionSource.KEYWORD_MATCH, null));
        when(categorizationService.suggestReadOnly(anyList(), eq(userId), anyString(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Salary", "rule", UUID.randomUUID(), Transaction.DecisionSource.KEYWORD_MATCH, null));

        String csv = "Date,Description,Debit,Credit\n2026-07-10,SALARY,,50000.00\n";
        MockMultipartFile file = new MockMultipartFile("file", "statement.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        StagingResponse response = importService.parseAndStage(userId, file.getOriginalFilename(), file.getInputStream());

        assertThat(response.rows()).hasSize(1);
        assertThat(response.rows().get(0).type()).isEqualTo("INCOME");
    }

    /**
     * Statement Import v2: opening/closing balance should be derivable whenever the file has a
     * running-balance column, without needing the user to type anything.
     *
     * <p>Bug fix: this test used to also assert the statement PERIOD was derived from the same two
     * transaction dates -- exactly the "transaction range as a guessed period" fallback removed from
     * StatementValidator.buildDetectedAccountInfo (see that method's own comment). This CSV prints no
     * period anywhere, so the correct, honest answer is null, not the two transaction dates.
     */
    @Test
    void parseAndStage_derivesOpeningAndClosingBalance_fromARunningBalanceColumn() throws Exception {
        when(categorizationService.suggestReadOnly(anyList(), eq(userId), anyString(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Dining", "rule", UUID.randomUUID(), Transaction.DecisionSource.KEYWORD_MATCH, null));
        when(categorizationService.suggestReadOnly(anyList(), eq(userId), anyString(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Dining", "rule", UUID.randomUUID(), Transaction.DecisionSource.KEYWORD_MATCH, null));

        // Opening balance 10000 -> -486 (debit) -> 9514 -> +2000 (credit) -> 11514
        String csv = "Date,Description,Debit,Credit,Balance\n"
                + "2026-07-10,SWIGGY ORDER,486.00,,9514.00\n"
                + "2026-07-12,SALARY,,2000.00,11514.00\n";
        MockMultipartFile file = new MockMultipartFile("file", "statement.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        StagingResponse response = importService.parseAndStage(userId, file.getOriginalFilename(), file.getInputStream());

        assertThat(response.detectedAccount().openingBalance()).isEqualByComparingTo("10000.00");
        assertThat(response.detectedAccount().closingBalance()).isEqualByComparingTo("11514.00");
        assertThat(response.detectedAccount().statementPeriodStart()).isNull();
        assertThat(response.detectedAccount().statementPeriodEnd()).isNull();
    }

    @Test
    void parseAndStage_suggestsAccountNameFromFilename() throws Exception {
        // Bug fix: same missing stub as parseAndStage_classifiesDebitCreditRowAsIncome above --
        // this test's assertion is only about the detected account name, but parseRow() still
        // calls categorizationService.suggestReadOnly() for every row on the way there.
        when(categorizationService.suggestReadOnly(anyList(), eq(userId), anyString(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Salary", "rule", UUID.randomUUID(), Transaction.DecisionSource.KEYWORD_MATCH, null));
        when(categorizationService.suggestReadOnly(anyList(), eq(userId), anyString(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Salary", "rule", UUID.randomUUID(), Transaction.DecisionSource.KEYWORD_MATCH, null));

        String csv = "Date,Description,Amount\n2026-07-10,SALARY,50000.00\n";
        MockMultipartFile file = new MockMultipartFile("file", "hdfc_savings_statement.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        StagingResponse response = importService.parseAndStage(userId, file.getOriginalFilename(), file.getInputStream());

        // Bug fix: this test's expectation predated StatementValidator's own suggestedAccountName
        // documented bug fix ("Pnbone Stmt Xx4802 23072026.csv" showing up as the account name
        // verbatim -- see that method's comment) -- it no longer title-cases the raw filename at
        // all. "hdfc" in the filename is a real BankRegistry signal, and a recognized bank's
        // officialName ("HDFC Bank") is used in place of any filename-derived guess, which is
        // exactly what BankRegistry.java registers HDFC's name as. This test was never updated
        // after that fix landed, so it was asserting on behavior the code deliberately no longer
        // has.
        assertThat(response.detectedAccount().suggestedName()).isEqualTo("HDFC Bank");
    }

    /**
     * Regression test for a real PNB ONE export: ~18 lines of branch/customer metadata before
     * the actual header row, "Txn Date"/"Dr Amount"/"Cr Amount" column names instead of the
     * generic aliases, every transaction line ending in a trailing comma (one more field than
     * there are header columns — CSVReaderHeaderAware used to throw on this), and a Balance
     * column formatted like "10728.27 Cr.". None of this should crash, and it should still stage
     * every transaction row and correctly classify each as income/expense.
     */
    @Test
    void parseAndStage_handlesRealBankExport_withMetadataPreambleRaggedRowsAndCrSuffixedBalance() throws Exception {
        when(categorizationService.suggestReadOnly(anyList(), eq(userId), anyString(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, Transaction.DecisionSource.MERCHANT_DEFAULT, null));
        when(categorizationService.suggestReadOnly(anyList(), eq(userId), anyString(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, Transaction.DecisionSource.MERCHANT_DEFAULT, null));

        String csv = String.join("\n",
                "Account Statement for Account Number 2222000011119999",
                "",
                "Branch Details,",
                "Branch Name:,SAMPLETOWN,MAIN BAZAR",
                "IFSC:,PUNB0999999",
                "",
                "Statement Period:     23-06-2026    to     23-07-2026",
                "",
                "Txn No.,Txn Date,Description,Branch Name,Cheque No.,Dr Amount,Cr Amount,Balance",
                "T20721400,22/07/2026,UPI/DR/900077778888/MERCHANT/YESB/sample11111@ybl/S,-,,420.0,,10728.27 Cr.,",
                "U55126421,20/07/2026,UPI/CR/900099990000/PAYCO CO/UTIB/samplemerch.pay/,-,,,1.0,11148.27 Cr.,",
                "",
                "***Generated through PNB ONE ***",
                "\"1.  Unless constituent notifies the bank immediately, it will be taken that the account is correct.\""
        ) + "\n";
        MockMultipartFile file = new MockMultipartFile("file", "PNBONE_STMT_XX9999.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        StagingResponse response = importService.parseAndStage(userId, file.getOriginalFilename(), file.getInputStream());

        assertThat(response.rows()).hasSize(2);
        assertThat(response.rows().get(0).type()).isEqualTo("EXPENSE");
        assertThat(response.rows().get(0).amount()).isEqualByComparingTo("420.0");
        assertThat(response.rows().get(1).type()).isEqualTo("INCOME");
        assertThat(response.rows().get(1).amount()).isEqualByComparingTo("1.0");
        // Balance parsed despite the "Cr." suffix, with no crash from the trailing comma or the
        // unrelated metadata/footer lines.
        assertThat(response.detectedAccount().closingBalance()).isEqualByComparingTo("10728.27");
    }

    /**
     * The account-holder-name detection this test covers mirrors accountNumberMasked's existing
     * pattern: best-effort, from an "Account Holder"-style column, null when the file doesn't
     * carry one. Real SBI/PNB exports do carry it (see the dummy statement files), and it used
     * to be dropped on the floor entirely — DetectedAccountInfo never had a field for it.
     */
    @Test
    void parseAndStage_detectsAccountHolderName_fromAccountHolderColumn() throws Exception {
        when(categorizationService.suggestReadOnly(anyList(), eq(userId), anyString(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, Transaction.DecisionSource.MERCHANT_DEFAULT, null));
        when(categorizationService.suggestReadOnly(anyList(), eq(userId), anyString(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, Transaction.DecisionSource.MERCHANT_DEFAULT, null));

        String csv = String.join("\n",
                "Bank,Account Holder,Account Number,Date,Description,Debit (INR),Credit (INR)",
                "State Bank of India,Sample Customer,XXXXXX4587,2026-07-01,Salary Credit,,85000"
        ) + "\n";
        MockMultipartFile file = new MockMultipartFile("file", "sbi.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        StagingResponse response = importService.parseAndStage(userId, file.getOriginalFilename(), file.getInputStream());

        assertThat(response.detectedAccount().accountHolderName()).isEqualTo("Sample Customer");
        assertThat(response.detectedAccount().accountNumberMasked()).isEqualTo("4587");
    }

    /**
     * Detection alone isn't enough — DetectedAccountInfo's accountNumberMasked was already being
     * computed before this fix but silently dropped by Import.tsx's confirm payload and never
     * reached AccountService.create(). This covers the backend half: whatever the caller sends
     * in NewAccountRequest actually lands on the persisted Account.
     */
    @Test
    void confirm_passesAccountHolderNameAndNumberMasked_toNewAccountCreation() throws Exception {
        UUID newAccountId = UUID.randomUUID();
        when(accountService.create(eq(userId), any(), any())).thenReturn(
                new AccountDto(newAccountId, "SBI Savings", "SAVINGS", BigDecimal.valueOf(25000), null, null, null,
                        "Sample Customer", "4587", null, null,
                        AccountDto.BankDto.from(com.finora.util.BankRegistry.get("SBI")), null, null, null,
                        0, 0L, "ACTIVE",
                        null, null, null, null, null, null, null));

        var row = new ConfirmedRow(LocalDate.of(2026, 7, 10), "SWIGGY*ORDR9182 BLR",
                BigDecimal.valueOf(486), "EXPENSE", "Dining", true, "rule", null, false, null, null);
        var newAccount = new com.finora.dto.ImportDto.NewAccountRequest(
                "SBI Savings", "SAVINGS", BigDecimal.valueOf(25000), null, null,
                "Sample Customer", "4587", "SBI", null, null, null, null,
                null, null, null, null, null, null, null);
        var request = new ConfirmRequest(null, List.of(row), null, newAccount, null, null,
                null);

        importService.confirm(userId, dummyFile(), request);

        ArgumentCaptor<AccountDto.CreateRequest> captor = ArgumentCaptor.forClass(AccountDto.CreateRequest.class);
        verify(accountService).create(eq(userId), captor.capture(), any());
        assertThat(captor.getValue().accountHolderName()).isEqualTo("Sample Customer");
        assertThat(captor.getValue().accountNumberMasked()).isEqualTo("4587");
    }

    /**
     * Regression test for the "every credit row is Salary" bug found from real screenshots of
     * friend UPI repayments (e.g. "UPI/CR/900022223333/SAMPLEP/ICIC/samplepayer98-/U") landing
     * under Salary with full confidence. parseRow() used to special-case isIncome straight to
     * "Salary"/"default" without ever calling the suggestion engine — this verifies income rows
     * now go through categorizationService.suggestReadOnly() exactly like expense rows do, so a
     * non-salary credit gets a real (possibly low-confidence, review-flagged) suggestion instead
     * of a wrong one asserted with full confidence.
     */
    @Test
    void parseAndStage_asksTheSuggestionEngine_forIncomeRowsToo_insteadOfHardcodingSalary() throws Exception {
        when(categorizationService.suggestReadOnly(anyList(), eq(userId), anyString(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, Transaction.DecisionSource.MERCHANT_DEFAULT, null));
        when(categorizationService.suggestReadOnly(anyList(), eq(userId), anyString(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, Transaction.DecisionSource.MERCHANT_DEFAULT, null));

        String description = "UPI/CR/900022223333/SAMPLEP/ICIC/samplepayer98-/U";
        String csv = "Date,Description,Debit,Credit\n2026-07-13," + description + ",,38.00\n";
        MockMultipartFile file = new MockMultipartFile("file", "statement.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        StagingResponse response = importService.parseAndStage(userId, file.getOriginalFilename(), file.getInputStream());

        assertThat(response.rows()).hasSize(1);
        assertThat(response.rows().get(0).type()).isEqualTo("INCOME");
        assertThat(response.rows().get(0).suggestedCategory()).isEqualTo("Other");
        assertThat(response.rows().get(0).categorySource()).isEqualTo("default");
        // suggestReadOnly, and the rules-and-merchant-index-carrying overload: staging asks the
        // engine for income rows exactly like expense rows, and does so WITHOUT writing (WI3)
        // against a rule set the preview generator fetched once (Bug 35's sibling fix).
        verify(categorizationService).suggestReadOnly(anyList(), eq(userId), eq(description), any(), any(), any());
    }

    /**
     * Regression test for the currency-suffixed-header bug found via the dummy SBI/PNB statement
     * files: real exports often tack a currency unit onto amount/balance headers — "Debit (INR)",
     * "Credit (INR)", "Running Balance (INR)" — and DATE_HEADER_HINTS/AMOUNT_HEADER_HINTS used to
     * be matched by exact string equality, so findHeaderRowIndex never recognized "debit (inr)"
     * as "debit" and the header row was never found at all. That made every row in the file
     * silently skip (0 rows staged), even the blank-Date OPENING/CLOSING BALANCE rows a real
     * Indian bank statement wraps around the transaction table.
     */
    @Test
    void parseAndStage_recognizesCurrencySuffixedHeaders_andSkipsOpeningClosingBalanceRows() throws Exception {
        when(categorizationService.suggestReadOnly(anyList(), eq(userId), anyString(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, Transaction.DecisionSource.MERCHANT_DEFAULT, null));
        when(categorizationService.suggestReadOnly(anyList(), eq(userId), anyString(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, Transaction.DecisionSource.MERCHANT_DEFAULT, null));

        String csv = String.join("\n",
                "Bank,Account Holder,Account Number,Statement Period,Date,Description,Reference No,Debit (INR),Credit (INR),Running Balance (INR)",
                "State Bank of India,Sample Customer,XXXXXX4587,01-Jul-2026 to 31-Jul-2026,,OPENING BALANCE,,,,25000.0",
                "State Bank of India,Sample Customer,XXXXXX4587,01-Jul-2026 to 31-Jul-2026,2026-07-01,Salary Credit - ABC Pvt Ltd,SBI1001,,85000,110000.0",
                "State Bank of India,Sample Customer,XXXXXX4587,01-Jul-2026 to 31-Jul-2026,2026-07-02,UPI Rent Payment,SBI1002,18000,,92000.0",
                "State Bank of India,Sample Customer,XXXXXX4587,01-Jul-2026 to 31-Jul-2026,,CLOSING BALANCE,,,,80885.75"
        ) + "\n";
        MockMultipartFile file = new MockMultipartFile("file", "SBI_Dummy_Statement_July_2026.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        StagingResponse response = importService.parseAndStage(userId, file.getOriginalFilename(), file.getInputStream());

        // Only the 2 real transaction rows stage; the blank-Date OPENING/CLOSING BALANCE rows
        // are correctly skipped (parseRow returns null when date can't be parsed).
        assertThat(response.rows()).hasSize(2);
        assertThat(response.rows().get(0).type()).isEqualTo("INCOME");
        assertThat(response.rows().get(0).amount()).isEqualByComparingTo("85000");
        assertThat(response.rows().get(1).type()).isEqualTo("EXPENSE");
        assertThat(response.rows().get(1).amount()).isEqualByComparingTo("18000");
    }

    /**
     * The real production bug the test above did NOT cover, per
     * docs/architecture/system-design/marker-row-pollution-scope-investigation.md: that test's
     * OPENING/CLOSING BALANCE rows only got excluded because their Date cell happened to be blank
     * -- a property of that one fixture, not a real guard. A real bank statement whose marker rows
     * DO carry a date (as the project's own PDF golden fixture's do) used to sail straight through
     * staging, verification, and confirm as ordinary transactions.
     *
     * <p>This proves the full path end-to-end with a DATED marker row: parseAndStage() must not
     * offer it as a staged row at all, and confirm() -- fed every staged row with include=true,
     * exactly the frontend's default for a non-duplicate row
     * ({@code frontend/src/lib/importReview.ts}'s {@code beginReview}) -- must persist only the
     * two real transactions, never a fake 50000.00/28885.75 EXPENSE for the balance labels.
     */
    @Test
    void confirm_neverPersistsADatedBalanceMarkerRow_evenWhenTheClientIncludesEveryStagedRow() throws Exception {
        when(categorizationService.suggestReadOnly(anyList(), eq(userId), anyString(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, Transaction.DecisionSource.MERCHANT_DEFAULT, null));
        when(categorizationService.suggestReadOnly(anyList(), eq(userId), anyString(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, Transaction.DecisionSource.MERCHANT_DEFAULT, null));

        String csv = String.join("\n",
                "Date,Description,Debit,Credit,Balance",
                "2026-07-01,OPENING BALANCE,,,50000.00",
                "2026-07-01,Salary Credit - ABC Pvt Ltd,,85000,135000.00",
                "2026-07-02,UPI Rent Payment,18000,,117000.00",
                "2026-07-31,CLOSING BALANCE,,,117000.00"
        ) + "\n";
        MockMultipartFile file = new MockMultipartFile("file", "dated_balance_markers.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        StagingResponse response = importService.parseAndStage(userId, file.getOriginalFilename(), file.getInputStream());

        // Staging itself must already exclude the two marker rows, dated or not -- checked by the
        // marker rows' own exact amounts (50000.00/117000.00), not a size threshold: a real
        // transaction is allowed to be large too, which is exactly why this fix cannot be "drop
        // big amounts".
        assertThat(response.rows()).hasSize(2);
        assertThat(response.rows()).extracting(com.finora.dto.ImportDto.StagedRow::amount)
                .doesNotContain(new BigDecimal("50000.00"), new BigDecimal("117000.00"));

        // Simulate the frontend's beginReview() default: every staged row (there is no duplicate
        // here) arrives back as a ConfirmedRow with include=true -- confirm() must still not
        // persist anything the balance markers would have produced, because they were never
        // offered as staged rows to begin with.
        List<ConfirmedRow> confirmedRows = response.rows().stream()
                .map(r -> new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(),
                        r.suggestedCategory(), true, r.categorySource(), r.ruleId(),
                        r.likelyDuplicate(), r.referenceNumber(), r.balanceAfter()))
                .toList();
        ConfirmRequest request = new ConfirmRequest(null, confirmedRows, accountId, null, null, null, null);

        importService.confirm(userId, dummyFile(), request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Transaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(captor.capture());
        List<Transaction> persisted = captor.getValue();

        assertThat(persisted).hasSize(2);
        assertThat(persisted).extracting(Transaction::getAmount)
                .doesNotContain(new BigDecimal("50000.00"), new BigDecimal("117000.00"));
        assertThat(persisted).noneMatch(t -> t.getDescription() != null
                && t.getDescription().toUpperCase(java.util.Locale.ROOT).contains("BALANCE"));
        assertThat(persisted).extracting(Transaction::getAmount)
                .containsExactlyInAnyOrder(new BigDecimal("85000"), new BigDecimal("18000"));
    }

    /**
     * Second fix pass, Item 1: the zero-padded variant of the test above. A separate-columns
     * layout that prints "0.00" on BOTH the Debit and Credit side of a balance-marker row instead
     * of leaving them blank (see TransactionNormalizer.firstNonZeroAmount's own doc comment, and
     * RowKind classification's zero-padding tiebreaker) used to still classify TRANSACTION and
     * persist as a fake 50000.00/117000.00 EXPENSE. Proves the fix end-to-end through
     * parseAndStage() and confirm(), exactly like the dated-marker test above.
     */
    @Test
    void confirm_neverPersistsAZeroPaddedBalanceMarkerRow_evenWhenTheClientIncludesEveryStagedRow() throws Exception {
        when(categorizationService.suggestReadOnly(anyList(), eq(userId), anyString(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, Transaction.DecisionSource.MERCHANT_DEFAULT, null));
        when(categorizationService.suggestReadOnly(anyList(), eq(userId), anyString(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, Transaction.DecisionSource.MERCHANT_DEFAULT, null));

        String csv = String.join("\n",
                "Date,Description,Debit,Credit,Balance",
                "2026-07-01,OPENING BALANCE,0.00,0.00,50000.00",
                "2026-07-01,Salary Credit - ABC Pvt Ltd,0.00,85000,135000.00",
                "2026-07-02,UPI Rent Payment,18000,0.00,117000.00",
                "2026-07-31,CLOSING BALANCE,0.00,0.00,117000.00"
        ) + "\n";
        MockMultipartFile file = new MockMultipartFile("file", "zero_padded_balance_markers.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        StagingResponse response = importService.parseAndStage(userId, file.getOriginalFilename(), file.getInputStream());

        assertThat(response.rows()).hasSize(2);
        assertThat(response.rows()).extracting(com.finora.dto.ImportDto.StagedRow::amount)
                .doesNotContain(new BigDecimal("50000.00"), new BigDecimal("117000.00"));

        List<ConfirmedRow> confirmedRows = response.rows().stream()
                .map(r -> new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(),
                        r.suggestedCategory(), true, r.categorySource(), r.ruleId(),
                        r.likelyDuplicate(), r.referenceNumber(), r.balanceAfter()))
                .toList();
        ConfirmRequest request = new ConfirmRequest(null, confirmedRows, accountId, null, null, null, null);

        importService.confirm(userId, dummyFile(), request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Transaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(captor.capture());
        List<Transaction> persisted = captor.getValue();

        assertThat(persisted).hasSize(2);
        assertThat(persisted).extracting(Transaction::getAmount)
                .doesNotContain(new BigDecimal("50000.00"), new BigDecimal("117000.00"));
        assertThat(persisted).extracting(Transaction::getAmount)
                .containsExactlyInAnyOrder(new BigDecimal("85000"), new BigDecimal("18000"));
    }

    /**
     * Second fix pass, Item 3: a row whose transactional-amount column uses a header name
     * TransactionNormalizer's hint lists don't recognize ("Txn Amount") but which also has a
     * recognized Balance column. Before this fix, such a row classified BALANCE_MARKER (no
     * TRANSACTION_AMOUNT_HINTS column matched at all) and was silently excluded from `staged`
     * with nowhere else to go -- worse than the pre-existing behavior of staging it with the
     * wrong amount (the balance figure), because the row disappeared entirely instead of being
     * visibly wrong. It must now surface via the unparseable-row diagnostic instead of vanishing.
     */
    @Test
    void parseAndStage_routesUnrecognizedColumnRowToUnparseable_insteadOfSilentlyDroppingIt() throws Exception {
        when(categorizationService.suggestReadOnly(anyList(), eq(userId), anyString(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, Transaction.DecisionSource.MERCHANT_DEFAULT, null));
        when(categorizationService.suggestReadOnly(anyList(), eq(userId), anyString(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, Transaction.DecisionSource.MERCHANT_DEFAULT, null));

        // Withdrawal/Deposit are included, blank, purely so CsvParser.findHeaderRowIndex (a
        // separate, pre-existing header-detection pass with its own narrower AMOUNT_HEADER_HINTS
        // list that does not include "balance" at all) recognizes this as a transaction table to
        // begin with -- unrelated to the bug this test targets, which is entirely about
        // TransactionNormalizer's per-row column recognition once the header IS found.
        String csv = String.join("\n",
                "Date,Description,Withdrawal,Deposit,Txn Amount,Balance",
                "2026-07-05,Some Real Transaction,,,2000.00,52000.00"
        ) + "\n";
        MockMultipartFile file = new MockMultipartFile("file", "unrecognized_amount_column.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        StagingResponse response = importService.parseAndStage(userId, file.getOriginalFilename(), file.getInputStream());

        // Not staged as a (wrongly-amounted) transaction, and not silently gone either.
        assertThat(response.rows()).isEmpty();
        assertThat(response.unparseableRows()).hasSize(1);
        assertThat(response.unparseableRows().get(0).raw()).containsEntry("Txn Amount", "2000.00");
    }

    /**
     * Bug 04 (docs/quality/bug-reports/BUG_REVIEW_REPORT.md): "A null/blank row.category() hits
     * the same path against NOT NULL" as an oversized category name. Merge-conflict resolution
     * note: this used to be asserted inside CategorizationServiceTest against
     * resolveOrCreateCategory directly, back when that method itself defaulted null/blank to
     * "Other". It no longer does -- resolveOrCreateCategory now throws on null/blank for every
     * OTHER caller (Bug 16's self-review fix, see its own doc comment: a blank name reaching most
     * callers is a genuinely malformed request, not a parser artifact). The "Other" degradation
     * this bug actually needs moved to exactly the one call site the report identifies as
     * reachable with unbounded, possibly-blank raw parser output -- here, in confirm() itself --
     * so this is where the regression coverage belongs now too.
     */
    @Test
    void confirm_fallsBackToOther_whenTheStatementsCategoryCellWasNullOrBlank() throws Exception {
        var nullCategoryRow = new ConfirmedRow(LocalDate.of(2026, 7, 10), "UNKNOWN MERCHANT XYZ",
                BigDecimal.valueOf(500), "EXPENSE", null, true, "default", null, false, null, null);

        importService.confirm(userId, dummyFile(), requestWith(nullCategoryRow));

        verify(categorizationService).resolveOrCreateCategory(eq(userId), eq("Other"));
        verify(categorizationService, never()).resolveOrCreateCategory(eq(userId), isNull());
    }

    /** The blank-string half of the same fallback -- see the null-category test above. */
    @Test
    void confirm_fallsBackToOther_whenTheStatementsCategoryCellWasBlank() throws Exception {
        var blankCategoryRow = new ConfirmedRow(LocalDate.of(2026, 7, 10), "UNKNOWN MERCHANT XYZ",
                BigDecimal.valueOf(500), "EXPENSE", "   ", true, "default", null, false, null, null);

        importService.confirm(userId, dummyFile(), requestWith(blankCategoryRow));

        verify(categorizationService).resolveOrCreateCategory(eq(userId), eq("Other"));
    }
}
