package com.finora.service;

import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmResponse;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.exception.ApiException;
import com.finora.imports.ImportService;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * BH-006, the reimport half. {@code ImportService.confirmSession} has run every confirmed row
 * through {@link com.finora.imports.ConfirmedRowIntegrity} since BH-023 -- but {@code
 * confirmReimport} had no persisted {@code ImportSession} to check against (reimport() stages and
 * forgets), so it ran nothing at all. {@code request.rows()} went straight into {@code
 * importService.confirm}, exactly as trusted as the document it claims to have come from.
 *
 * <p>Reproduced against the real stack before this test existed: a row dated 2099-01-01, for
 * ₹999,999, present in no statement this account has ever had, POSTed to {@code
 * /statement-imports/{id}/reimport/confirm} and came back {@code 200 Import complete} — and stayed
 * in the ledger. {@link #confirmReimport_rejectsARowThatWasNeverInTheDocument} is that same
 * exploit, unit-scoped: the fabricated row here is exactly as absent from the mocked fresh parse
 * as ₹999,999 was absent from the real statement.
 */
class StatementImportServiceReimportIntegrityTest {

    private StatementImportRepository statementImportRepository;
    private AccountRepository accountRepository;
    private ImportService importService;
    private StatementImportService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID statementImportId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        statementImportRepository = mock(StatementImportRepository.class);
        accountRepository = mock(AccountRepository.class);
        importService = mock(ImportService.class);

        service = new StatementImportService(statementImportRepository, accountRepository,
                mock(CategoryRepository.class), mock(TransactionRepository.class),
                mock(ReconciliationService.class), mock(RecurringService.class),
                importService, mock(AuditService.class), mock(BankManagementService.class),
                new com.finora.imports.storage.StatementContentService(Optional.empty(), "", ""));

        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", accountId);
        account.setName("Test Savings");
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        StatementImport si = new StatementImport();
        ReflectionTestUtils.setField(si, "id", statementImportId);
        si.setUserId(userId);
        si.setAccountId(accountId);
        si.setFileName("hdfc_statement.csv");
        si.setSourceFormat("CSV");
        si.setFileContent(new byte[]{1, 2, 3});
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.of(si));
    }

    /** What the document actually says, as far as the (mocked) fresh parse is concerned. */
    private static final StagedRow GENUINE_ROW = new StagedRow(
            LocalDate.of(2026, 8, 1), "COFFEE SHOP", new BigDecimal("150.00"), "EXPENSE",
            "Dining", "default", null, false, null, null, null);

    private static ConfirmedRow confirming(StagedRow r) {
        return new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(), r.suggestedCategory(),
                true, r.categorySource(), r.ruleId(), r.likelyDuplicate(), r.referenceNumber(), r.balanceAfter());
    }

    private void mockFreshParseReturns(StagedRow... rows) throws Exception {
        when(importService.parseAndStageAnyFormat(eq(userId), eq("CSV"), eq("hdfc_statement.csv"), any(), eq((Integer) null)))
                .thenReturn(new StagingResponse(List.of(rows), rows.length, 0,
                        new DetectedAccountInfo(null, null, null, null, null, null, null, null, null, null, null,
                                null, null, null, 0.0, true, List.of(), null, null, null, null, null, null, null, null),
                        List.of()));
    }

    @Test
    void confirmReimport_rejectsARowThatWasNeverInTheDocument() throws Exception {
        mockFreshParseReturns(GENUINE_ROW);

        // Never staged, never in the document the fresh parse just re-read. The exact shape of the
        // live exploit: a self-consistent fabrication with nothing tying it to this statement.
        ConfirmedRow forged = new ConfirmedRow(
                LocalDate.of(2099, 1, 1), "FORGED ROW", new BigDecimal("999999.00"), "EXPENSE",
                "Uncategorized", true, "default", null, false, null, null);

        ConfirmRequest request = new ConfirmRequest(null, List.of(forged), null, null, null, null);

        assertThatThrownBy(() -> service.confirmReimport(userId, statementImportId, request))
                .isInstanceOf(ApiException.class);

        // The strongest assertion available: not merely that an exception was thrown, but that the
        // write path was never reached. A guard that throws AFTER persistSection has already run
        // would still fail this test's first assertion and still leave a partial write behind.
        verify(importService, never()).confirm(any(), anyString(), any(byte[].class), any(ConfirmRequest.class));
    }

    @Test
    void confirmReimport_acceptsRowsThatMatchTheFreshParseExactly() throws Exception {
        mockFreshParseReturns(GENUINE_ROW);
        ConfirmedRow echoed = confirming(GENUINE_ROW);
        ConfirmRequest request = new ConfirmRequest(null, List.of(echoed), null, null, null, null);

        ConfirmResponse expected = new ConfirmResponse(1, 0, 0, 0, 0, List.of(), java.util.Map.of(),
                java.util.Map.of(), List.of(), null, BigDecimal.ZERO, new BigDecimal("150.00"), null, null,
                null, null, 0L, "CSV");
        when(importService.confirm(eq(userId), eq("hdfc_statement.csv"), any(byte[].class), any(ConfirmRequest.class)))
                .thenReturn(expected);

        ConfirmResponse result = service.confirmReimport(userId, statementImportId, request);

        assertThat(result).isSameAs(expected);
        verify(importService).confirm(eq(userId), eq("hdfc_statement.csv"), any(byte[].class), argThat(scoped ->
                scoped.rows().equals(List.of(echoed)) && scoped.existingAccountId().equals(accountId)));
    }

    @Test
    void confirmReimport_rejectsAGenuineRowWithATamperedAmount() throws Exception {
        // Not a wholesale fabrication -- the same row the document contains, with one field bent.
        // Distinguishes "checks the whole row" from a check that only looked at, say, the date.
        mockFreshParseReturns(GENUINE_ROW);
        ConfirmedRow tampered = new ConfirmedRow(GENUINE_ROW.date(), GENUINE_ROW.description(),
                new BigDecimal("15000.00"), GENUINE_ROW.type(), GENUINE_ROW.suggestedCategory(), true,
                GENUINE_ROW.categorySource(), GENUINE_ROW.ruleId(), GENUINE_ROW.likelyDuplicate(),
                GENUINE_ROW.referenceNumber(), GENUINE_ROW.balanceAfter());

        ConfirmRequest request = new ConfirmRequest(null, List.of(tampered), null, null, null, null);

        assertThatThrownBy(() -> service.confirmReimport(userId, statementImportId, request))
                .isInstanceOf(ApiException.class);
        verify(importService, never()).confirm(any(), anyString(), any(byte[].class), any(ConfirmRequest.class));
    }
}
