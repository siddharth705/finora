package com.finora.imports;

import com.finora.accounts.AccountService;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.UnparseableRow;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.exception.ApiException;
import com.finora.repository.*;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ADR-0002 -- specifically covers the two things confirmSession() adds on top of the
 * already-tested core confirm() logic (see ImportServiceAskOnceTest): resolving the file from a
 * persisted session instead of a re-upload, and rejecting a confirm whose row count doesn't
 * match what was actually staged.
 */
class ImportServiceSessionTest {

    private ImportSessionService importSessionService;
    private ImportService importService;
    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private AccountRepository accountRepository;
    private com.finora.imports.pdf.PdfPreviewGenerator pdfPreviewGenerator;

    private com.finora.service.MerchantLearningEventPublisher learningEventPublisher;

    @BeforeEach
    void setUp() {
        learningEventPublisher = mock(com.finora.service.MerchantLearningEventPublisher.class);
        accountRepository = mock(AccountRepository.class);
        AccountService accountService = mock(AccountService.class);
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        StatementImportRepository statementImportRepository = mock(StatementImportRepository.class);
        CategorizationService categorizationService = mock(CategorizationService.class);
        ReconciliationService reconciliationService = mock(ReconciliationService.class);
        RecurringService recurringService = mock(RecurringService.class);
        importSessionService = mock(ImportSessionService.class);

        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        // Mockito's default answer for an unstubbed saveAll() returning a List is an EMPTY list,
        // not null -- confirm() does `int imported = saved.size()` off this return value, so
        // without this stub every confirm() silently reports 0 imported regardless of how many
        // rows were actually built (see ImportServiceAskOnceTest's identical stub).
        when(transactionRepository.saveAll(any())).thenAnswer(inv -> {
            List<com.finora.entity.Transaction> txns = inv.getArgument(0);
            txns.forEach(t -> ReflectionTestUtils.setField(t, "id", UUID.randomUUID()));
            return txns;
        });
        CsvParser csvParser = new CsvParser();
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector, com.finora.imports.TestRuleEngines.empty());
        StatementValidator statementValidator = new StatementValidator(com.finora.imports.product.ProductDiscovery.standard());
        PreviewGenerator previewGenerator = new PreviewGenerator(csvParser, transactionNormalizer, statementValidator, new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(), new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(), new com.finora.imports.ColumnAmbiguityValidator()), com.finora.imports.TestRuleEngines.empty());
        ImportRuleLearningService ruleLearningService = new ImportRuleLearningService(categorizationService);

        pdfPreviewGenerator = mock(com.finora.imports.pdf.PdfPreviewGenerator.class);
        var productIdentityResolver = new com.finora.imports.product.ProductIdentityResolver(accountRepository);
        importService = new ImportService(accountRepository, accountService, transactionRepository,
                merchantRepository, statementImportRepository, categorizationService, reconciliationService,
                recurringService, previewGenerator, duplicateDetector, ruleLearningService, importSessionService,
                pdfPreviewGenerator, productIdentityResolver, new com.finora.imports.storage.StatementContentService(java.util.Optional.empty(), "", ""),
                mock(com.finora.imports.analysis.StatementAnalysisRecorder.class),
                mock(com.finora.imports.analysis.ImportVerificationRecorder.class),
                learningEventPublisher, mock(LayoutRegistryService.class));

        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", accountId);
        account.setUserId(userId);
        // confirm() re-fetches this account and maps it through AccountDto.from() for the
        // response's account snapshot, which calls .name() on the account type -- an unset
        // accountType NPEs there (see ImportServiceAskOnceTest's identical setup).
        account.setAccountType(Account.Type.SAVINGS);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of());
        when(categorizationService.resolveOrCreateCategory(any(), any())).thenAnswer(inv -> {
            var cat = new com.finora.entity.Category();
            ReflectionTestUtils.setField(cat, "id", UUID.randomUUID());
            cat.setName(inv.getArgument(1));
            return cat;
        });
        // Mockito returns null for an unstubbed save() by default -- confirm() reads the
        // generated id back off this return value to stamp onto every transaction in the batch
        // (see ImportServiceAskOnceTest's identical stub), so without this, confirm() NPEs on
        // savedImport.getId() before ever reaching the assertions below.
        when(statementImportRepository.save(any(com.finora.entity.StatementImport.class))).thenAnswer(inv -> {
            com.finora.entity.StatementImport si = inv.getArgument(0);
            ReflectionTestUtils.setField(si, "id", UUID.randomUUID());
            return si;
        });
    }

    private ImportSession sessionWith(UUID id, byte[] fileContent, String status) {
        ImportSession session = new ImportSession();
        ReflectionTestUtils.setField(session, "id", id);
        session.setUserId(userId);
        session.setFileName("statement.csv");
        session.setFileContent(fileContent);
        session.setExpiresAt(Instant.now().plusSeconds(600));
        session.setStatus(status);
        return session;
    }

    private ConfirmedRow confirmedRow() {
        return new ConfirmedRow(LocalDate.of(2026, 7, 1), "Coffee Shop", new BigDecimal("150.00"),
                "EXPENSE", "Food & Dining", true, "rule", null, false, null, null);
    }

    private StagedRow stagedRow() {
        return new StagedRow(LocalDate.of(2026, 7, 1), "Coffee Shop", new BigDecimal("150.00"),
                "EXPENSE", "Food & Dining", "rule", null, false, null, null);
    }

    /**
     * Reported against a real HDFC statement holding 100+ transactions: the pipeline read none of
     * them, the upload returned 200 anyway, and the review screen showed an empty table with a live
     * Confirm button -- a total extraction failure was indistinguishable from a quiet month. These
     * two tests hold the line that an import producing no transactions is never staged, so every
     * session that does exist is guaranteed to have something in it.
     */
    @Test
    void aFileWithNoRecognizableTransactionTable_isRejectedRatherThanStagedAsAnEmptySession() {
        MockMultipartFile file = new MockMultipartFile("file", "statement.csv", "text/csv",
                ("Dear Customer\nYour e-statement is attached.\nThis is a computer generated document.\n")
                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> importService.parseAndStageWithSession(userId, file))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("could not find a transaction table")
                // "Never lose information": the text we DID recover is reported alongside the
                // failure, so this is diagnosable without the original file.
                .hasMessageContaining("3 line(s) of text were recovered");

        verifyNoInteractions(importSessionService);
    }

    @Test
    void aFileWhoseTableWasFoundButYieldedNoRows_isRejectedWithADifferentCodeThanAMissingTable() {
        // The table IS located here -- the header is recognized -- there is simply nothing under
        // it. That is a different failure from "this layout defeated table detection", needs
        // different follow-up, and so must not collapse into the same error.
        MockMultipartFile file = new MockMultipartFile("file", "statement.csv", "text/csv",
                "Date,Description,Amount\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> importService.parseAndStageWithSession(userId, file))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("could not read any transactions from it");

        verifyNoInteractions(importSessionService);
    }

    @Test
    void tablesInACombinedStatementThatHoldNoTransactions_areNotOfferedAsAccounts() throws Exception {
        // A real HDFC combined statement: the savings account, plus a term-deposit summary and a
        // recurring-deposit installment schedule. All three are genuine tables, and all three were
        // presented as ACCOUNTS -- so the user was shown two empty accounts to confirm. Until the
        // product-classification stage exists to say what those two actually ARE, they must not be
        // asserted to be accounts on evidence that only shows they are tables.
        var savings = new StagedAccountSection(null, List.of(stagedRow()), 1, 0, List.of());
        var termDeposit = new StagedAccountSection(null, List.of(), 0, 0,
                List.of(new UnparseableRow(
                        java.util.Map.of("Maturity Date", "01/06/2027"), "no date column")));
        var recurringDeposit = new StagedAccountSection(null, List.of(), 0, 0, List.of());
        when(importSessionService.createSession(any(), any(), any(), any(), any(), any()))
                .thenReturn(sessionWith(UUID.randomUUID(), new byte[]{1}, ImportSession.STATUS_STAGED));
        when(pdfPreviewGenerator.generateSectionsWithContext(any(), any(), any(), any())).thenReturn(
                new com.finora.imports.pdf.PdfPreviewGenerator.PdfGenerationResult(
                        List.<StagedAccountSection>of(savings, termDeposit, recurringDeposit),
                        new DocumentContext("PDF", "test")));

        var response = importService.parseAndStagePdfWithSession(userId,
                new MockMultipartFile("file", "combined.pdf", "application/pdf", new byte[]{1}), null);

        assertThat(response.multiAccount()).as("one account, not three").isFalse();
        // "Never lose information": the deposit table's contents survive as unparseable rows on the
        // surviving section rather than vanishing with the section that held them.
        assertThat(response.staging().unparseableRows()).hasSize(1);
    }

    @Test
    void confirmSession_withoutSessionId_isRejected() {
        var request = new ConfirmRequest(null, List.of(confirmedRow()), accountId, null, null, null);

        assertThatThrownBy(() -> importService.confirmSession(userId, request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("sessionId is required");

        verifyNoInteractions(importSessionService);
    }

    @Test
    void confirmSession_whenConfirmedRowCountDoesNotMatchWhatWasStaged_isRejected() {
        UUID sessionId = UUID.randomUUID();
        ImportSession session = sessionWith(sessionId, new byte[]{1, 2, 3}, ImportSession.STATUS_STAGED);
        when(importSessionService.claimForConfirmation(userId, sessionId)).thenReturn(session);
        // Two rows were staged, but only one is being confirmed -- a mismatch this check exists
        // to catch, not something a normal "uncheck a row to skip it" edit would trigger (that
        // still sends the row, just with include=false, so the count stays the same).
        when(importSessionService.readStagedRows(session)).thenReturn(List.of(stagedRow(), stagedRow()));

        var request = new ConfirmRequest(sessionId, List.of(confirmedRow()), accountId, null, null, null);

        assertThatThrownBy(() -> importService.confirmSession(userId, request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("don't match what was staged");

        verify(importSessionService).claimForConfirmation(userId, sessionId);
        // Row-count mismatch is caught AFTER the claim succeeds -- the claim (and everything
        // else in this @Transactional method) rolls back together on any downstream exception,
        // same as any other failure past that point, so a rejected confirm here doesn't leave
        // the session stuck CONFIRMED with nothing actually imported.
    }

    @Test
    void confirmSession_withMatchingRowCount_usesTheSessionsPersistedFile_andMarksItConfirmed() throws Exception {
        UUID sessionId = UUID.randomUUID();
        byte[] fileBytes = "date,description,amount\n".getBytes();
        ImportSession session = sessionWith(sessionId, fileBytes, ImportSession.STATUS_STAGED);
        when(importSessionService.claimForConfirmation(userId, sessionId)).thenReturn(session);
        when(importSessionService.readStagedRows(session)).thenReturn(List.of(stagedRow()));

        var request = new ConfirmRequest(sessionId, List.of(confirmedRow()), accountId, null, null, null);
        var response = importService.confirmSession(userId, request);

        assertThat(response.imported()).isEqualTo(1);
        // The whole point: no file was re-uploaded here (confirmSession takes no MultipartFile
        // parameter at all) -- the bytes it used to build the StatementImport row came from the
        // session, which is exactly what a resumed-on-another-device or dropped-and-reopened
        // session needs to work. claimForConfirmation() (not a separate markConfirmed() call) is
        // what actually flips the session to CONFIRMED, atomically, as the first thing this
        // method does -- see ImportSessionRepository.claimForConfirmation's own doc comment.
        verify(importSessionService).claimForConfirmation(userId, sessionId);
    }

    @Test
    void confirmSession_copiesTheSessionsLayoutMetadataFingerprintAndCapabilities_ontoTheStatementImport() throws Exception {
        // Phase 1 "capture facts" (docs/engineering/financial-document-intelligence-principles.md):
        // confirm() has no access to the original DocumentContext (only the reviewed ConfirmedRow
        // list) -- these three fields must be copied verbatim from the session, not recomputed.
        UUID sessionId = UUID.randomUUID();
        ImportSession session = sessionWith(sessionId, "date,description,amount\n".getBytes(), ImportSession.STATUS_STAGED);
        session.setLayoutMetadataJson("{\"sourceFormat\":\"CSV\"}");
        session.setLayoutFingerprint("FP-ABCD1234");
        session.setActivatedCapabilitiesJson("[{\"capability\":\"DR_CR_SUFFIX\",\"status\":\"SUCCESS\"}]");
        when(importSessionService.claimForConfirmation(userId, sessionId)).thenReturn(session);
        when(importSessionService.readStagedRows(session)).thenReturn(List.of(stagedRow()));

        var request = new ConfirmRequest(sessionId, List.of(confirmedRow()), accountId, null, null, null);
        importService.confirmSession(userId, request);

        var captor = org.mockito.ArgumentCaptor.forClass(com.finora.entity.StatementImport.class);
        verify(getStatementImportRepository()).save(captor.capture());
        assertThat(captor.getValue().getLayoutMetadataJson()).isEqualTo("{\"sourceFormat\":\"CSV\"}");
        assertThat(captor.getValue().getLayoutFingerprint()).isEqualTo("FP-ABCD1234");
        assertThat(captor.getValue().getActivatedCapabilitiesJson())
                .isEqualTo("[{\"capability\":\"DR_CR_SUFFIX\",\"status\":\"SUCCESS\"}]");
    }

    @Test
    void confirm_withNoSession_leavesLayoutFieldsNull() {
        // The byte-array confirm() overload (used by StatementImportService.confirmReimport(),
        // which replays already-stored bytes rather than a fresh staged session) has no
        // DocumentContext to copy from -- best-effort, same as every other nullable field here.
        var request = new ConfirmRequest(null, List.of(confirmedRow()), accountId, null, null, null);
        importService.confirm(userId, "statement.csv", "date,description,amount\n".getBytes(), request);

        var captor = org.mockito.ArgumentCaptor.forClass(com.finora.entity.StatementImport.class);
        verify(getStatementImportRepository()).save(captor.capture());
        assertThat(captor.getValue().getLayoutMetadataJson()).isNull();
        assertThat(captor.getValue().getLayoutFingerprint()).isNull();
        assertThat(captor.getValue().getActivatedCapabilitiesJson()).isNull();
    }

    private StatementImportRepository getStatementImportRepository() {
        return (StatementImportRepository) ReflectionTestUtils.getField(importService, "statementImportRepository");
    }
}
