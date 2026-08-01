package com.finora.imports;

import com.finora.accounts.AccountService;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.exception.ApiException;
import com.finora.repository.*;
import com.finora.service.CategorizationService;
import com.finora.service.ReconciliationService;
import com.finora.service.RecurringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
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

    @BeforeEach
    void setUp() {
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
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector);
        StatementValidator statementValidator = new StatementValidator();
        PreviewGenerator previewGenerator = new PreviewGenerator(csvParser, transactionNormalizer, statementValidator);
        ImportRuleLearningService ruleLearningService = new ImportRuleLearningService(categorizationService);

        importService = new ImportService(accountRepository, accountService, transactionRepository,
                merchantRepository, statementImportRepository, categorizationService, reconciliationService,
                recurringService, previewGenerator, duplicateDetector, ruleLearningService, importSessionService,
                mock(com.finora.imports.pdf.PdfPreviewGenerator.class));

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
