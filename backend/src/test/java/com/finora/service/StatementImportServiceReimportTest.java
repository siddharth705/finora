package com.finora.service;

import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.imports.ImportService;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Bug fix: reimport() used to unconditionally call ImportService's CSV-only byte-stream
 * parseAndStage() overload, regardless of what format the original statement was actually
 * uploaded in. Once PDF support existed (Milestone 1), that meant re-importing a PDF-sourced
 * statement would feed its raw PDF bytes through the CSV reader -- garbage in, at best a thrown
 * exception, at worst nonsense staged rows. These tests prove reimport() now routes by the
 * explicit sourceFormat recorded on the row at confirm() time (StatementImport.sourceFormat,
 * V36 migration) -- not the filename's extension, which was this fix's own first version and a
 * real, if narrower, fragility in its own right (nothing stops a re-upload with a missing or
 * mismatched extension).
 */
class StatementImportServiceReimportTest {

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
                importService, mock(AuditService.class), mock(BankManagementService.class));

        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", accountId);
        account.setName("Test Savings");
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    }

    private StatementImport statementWithFile(String fileName, String sourceFormat) {
        StatementImport si = new StatementImport();
        ReflectionTestUtils.setField(si, "id", statementImportId);
        si.setUserId(userId);
        si.setAccountId(accountId);
        si.setFileName(fileName);
        si.setSourceFormat(sourceFormat);
        si.setFileContent(new byte[]{1, 2, 3});
        return si;
    }

    @Test
    void reimport_ofAPdfSourcedStatement_routesThroughThePdfPath_notTheCsvOne() throws Exception {
        when(statementImportRepository.findById(statementImportId))
                .thenReturn(Optional.of(statementWithFile("sbi_statement.pdf", "PDF")));
        when(importService.parseAndStageAnyFormat(eq(userId), eq("PDF"), eq("sbi_statement.pdf"), any(), isNull()))
                .thenReturn(new StagingResponse(List.of(), 0, 0,
                        new DetectedAccountInfo(null, null, null, null, null, null, null, null, null, null, null, null, null)));

        service.reimport(userId, statementImportId);

        verify(importService).parseAndStageAnyFormat(eq(userId), eq("PDF"), eq("sbi_statement.pdf"), any(), isNull());
        // The old (buggy) call path must never fire for a PDF-sourced statement.
        verify(importService, never()).parseAndStage(any(), any(), any(java.io.InputStream.class));
    }

    @Test
    void reimport_ofACsvSourcedStatement_stillWorksTheSameWayAsBefore() throws Exception {
        when(statementImportRepository.findById(statementImportId))
                .thenReturn(Optional.of(statementWithFile("hdfc_statement.csv", "CSV")));
        when(importService.parseAndStageAnyFormat(eq(userId), eq("CSV"), eq("hdfc_statement.csv"), any(), isNull()))
                .thenReturn(new StagingResponse(List.of(), 0, 0,
                        new DetectedAccountInfo(null, null, null, null, null, null, null, null, null, null, null, null, null)));

        var result = service.reimport(userId, statementImportId);

        assertThat(result.accountId()).isEqualTo(accountId);
        verify(importService).parseAndStageAnyFormat(eq(userId), eq("CSV"), eq("hdfc_statement.csv"), any(), isNull());
    }

    @Test
    void reimport_routesByTheExplicitSourceFormatField_evenIfTheFilenameExtensionWouldSuggestOtherwise() throws Exception {
        // The whole point of recording sourceFormat explicitly rather than inferring it from the
        // filename: a mismatched/missing extension shouldn't be able to send a PDF-sourced
        // statement through the CSV path (or vice versa). ".dat" here proves routing follows the
        // stored field, not a guess from the name.
        when(statementImportRepository.findById(statementImportId))
                .thenReturn(Optional.of(statementWithFile("export.dat", "PDF")));
        when(importService.parseAndStageAnyFormat(eq(userId), eq("PDF"), eq("export.dat"), any(), isNull()))
                .thenReturn(new StagingResponse(List.of(), 0, 0,
                        new DetectedAccountInfo(null, null, null, null, null, null, null, null, null, null, null, null, null)));

        service.reimport(userId, statementImportId);

        verify(importService).parseAndStageAnyFormat(eq(userId), eq("PDF"), eq("export.dat"), any(), isNull());
    }
}
