package com.finora.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.accounts.AccountService;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmResponse;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.imports.evidence.ClosingBalanceEvidenceShadowObserver;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import com.finora.service.ReconciliationService;
import com.finora.service.RecurringService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * C-9 shadow mode -- the isolation proof: <b>whatever the shadow observation does, the confirm it
 * runs inside behaves identically.</b>
 *
 * <p>This is the test that would fail if shadow mode ever became control. Its method is comparison,
 * not assertion of a hardcoded expectation: one confirm is run with a no-op observer to establish
 * the baseline response, and the same confirm is then re-run with an observer that throws a
 * {@link RuntimeException}, one that throws an {@link Error}, and none at all -- every response
 * must equal the baseline, field for field, and the same StatementImport must be written each time.
 * A future change that let a shadow failure alter, skip or fail a confirm shows up here as a
 * difference rather than as a subtly wrong production import.
 *
 * <p>{@code Error} is included on purpose: re-deriving evidence re-parses a PDF, and PDF parsing is
 * the one step here that can raise a {@link StackOverflowError} on a hostile document. See
 * {@link ClosingBalanceEvidenceShadowObserver}'s class doc for why that is caught rather than
 * allowed to escape.
 *
 * <p>The ordering assertion is separate and equally load-bearing: the observation has to happen
 * <em>before</em> {@code claimForConfirmation}, because claiming sets the session to CONFIRMED and
 * the re-derivation's own ownership check rejects a confirmed session. Placed after the claim, the
 * feature would compile, pass its happy-path unit tests against a stubbed service, and record
 * nothing but failures in production.
 */
class ImportServiceShadowEvidenceIsolationTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    /** Everything a confirm touches, rebuilt per harness so two runs cannot share state. */
    private record Harness(ImportService importService, ImportSessionService importSessionService,
                           StatementImportRepository statementImportRepository) {}

    private Harness harness(ClosingBalanceEvidenceShadowObserver observer) {
        AccountRepository accountRepository = mock(AccountRepository.class);
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        StatementImportRepository statementImportRepository = mock(StatementImportRepository.class);
        CategorizationService categorizationService = mock(CategorizationService.class);
        ImportSessionService importSessionService = mock(ImportSessionService.class);

        when(transactionRepository.saveAll(any())).thenAnswer(inv -> {
            List<com.finora.entity.Transaction> txns = inv.getArgument(0);
            txns.forEach(t -> ReflectionTestUtils.setField(t, "id", UUID.randomUUID()));
            return txns;
        });
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", accountId);
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of());
        when(categorizationService.resolveOrCreateCategory(any(), any())).thenAnswer(inv -> {
            var cat = new com.finora.entity.Category();
            ReflectionTestUtils.setField(cat, "id", UUID.randomUUID());
            cat.setName(inv.getArgument(1));
            return cat;
        });
        when(statementImportRepository.save(any(com.finora.entity.StatementImport.class))).thenAnswer(inv -> {
            com.finora.entity.StatementImport si = inv.getArgument(0);
            ReflectionTestUtils.setField(si, "id", UUID.randomUUID());
            return si;
        });

        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        TransactionNormalizer normalizer = new TransactionNormalizer(categorizationService, duplicateDetector,
                TestRuleEngines.empty());
        PreviewGenerator previewGenerator = new PreviewGenerator(new CsvParser(), normalizer,
                new StatementValidator(com.finora.imports.product.ProductDiscovery.standard()),
                new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                        new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new RowAccountingValidator(), new com.finora.imports.CreditCardStatementTotalsValidator()),
                TestRuleEngines.empty());

        ImportSession session = new ImportSession();
        ReflectionTestUtils.setField(session, "id", sessionId);
        session.setUserId(userId);
        session.setFileName("statement.csv");
        session.setFileContent("date,description,amount\n".getBytes());
        session.setExpiresAt(Instant.now().plusSeconds(600));
        session.setStatus(ImportSession.STATUS_STAGED);
        when(importSessionService.claimForConfirmation(userId, sessionId)).thenReturn(session);
        when(importSessionService.readStagedRows(session)).thenReturn(List.of(stagedRow()));

        ImportService importService = new ImportService(accountRepository, mock(AccountService.class),
                transactionRepository, merchantRepository, statementImportRepository, categorizationService,
                mock(ReconciliationService.class), mock(RecurringService.class), previewGenerator,
                duplicateDetector, new ImportRuleLearningService(categorizationService), importSessionService,
                mock(com.finora.imports.pdf.PdfPreviewGenerator.class),
                new com.finora.imports.product.ProductIdentityResolver(accountRepository),
                new com.finora.imports.storage.StatementContentService(Optional.empty(), "", ""),
                mock(com.finora.imports.analysis.StatementAnalysisRecorder.class),
                mock(com.finora.imports.analysis.ImportVerificationRecorder.class),
                mock(com.finora.service.MerchantLearningEventPublisher.class),
                mock(LayoutRegistryService.class),
                observer);
        return new Harness(importService, importSessionService, statementImportRepository);
    }

    private StagedRow stagedRow() {
        return new StagedRow(LocalDate.of(2026, 7, 1), "Coffee Shop", new BigDecimal("150.00"),
                "EXPENSE", "Food & Dining", "rule", null, false, null, null);
    }

    private ConfirmedRow confirmedRow() {
        return new ConfirmedRow(LocalDate.of(2026, 7, 1), "Coffee Shop", new BigDecimal("150.00"),
                "EXPENSE", "Food & Dining", true, "rule", null, false, null, null);
    }

    private ConfirmRequest request() {
        return new ConfirmRequest(sessionId, List.of(confirmedRow()), accountId, null,
                new BigDecimal("1000.00"), new BigDecimal("850.00"), null);
    }

    /** The response, reduced to the fields a caller can observe, so two runs can be compared
     *  without depending on the ids Mockito generates fresh each time. */
    private record Observable(int imported, int skipped, String accountName, BigDecimal closingBalance) {}

    private Observable confirmWith(ClosingBalanceEvidenceShadowObserver observer) {
        Harness harness = harness(observer);
        ConfirmResponse response = harness.importService().confirmSession(userId, request());
        var captor = org.mockito.ArgumentCaptor.forClass(com.finora.entity.StatementImport.class);
        verify(harness.statementImportRepository()).save(captor.capture());
        return new Observable(response.imported(), response.skipped(),
                captor.getValue().getFileName(), captor.getValue().getClosingBalance());
    }

    @Test
    void aShadowObserverThatThrows_changesNothingAboutTheConfirm() {
        Observable baseline = confirmWith(mock(ClosingBalanceEvidenceShadowObserver.class));

        ClosingBalanceEvidenceShadowObserver throwsRuntime = mock(ClosingBalanceEvidenceShadowObserver.class);
        doAnswer(inv -> { throw new IllegalStateException("shadow exploded"); })
                .when(throwsRuntime).observe(any(), any(), any(), any());

        ClosingBalanceEvidenceShadowObserver throwsError = mock(ClosingBalanceEvidenceShadowObserver.class);
        doAnswer(inv -> { throw new StackOverflowError("hostile pdf"); })
                .when(throwsError).observe(any(), any(), any(), any());

        assertThat(confirmWith(throwsRuntime)).isEqualTo(baseline);
        assertThat(confirmWith(throwsError)).isEqualTo(baseline);
        // No observer at all -- the shape the hand-constructed unit tests elsewhere use.
        assertThat(confirmWith(null)).isEqualTo(baseline);

        assertThat(baseline.imported()).as("the baseline actually did something to be unchanged from")
                .isEqualTo(1);
    }

    @Test
    void theObservationRunsBeforeTheSessionIsClaimed_andIsHandedTheConfirmedClosingBalance() {
        ClosingBalanceEvidenceShadowObserver observer = mock(ClosingBalanceEvidenceShadowObserver.class);
        Harness harness = harness(observer);

        harness.importService().confirmSession(userId, request());

        // Order, not merely occurrence: after the claim the session is CONFIRMED and the
        // re-derivation's own getOwnedSession would reject every observation.
        var order = inOrder(observer, harness.importSessionService());
        order.verify(observer).observe(eq(userId), eq(sessionId), isNull(), eq(new BigDecimal("850.00")));
        order.verify(harness.importSessionService()).claimForConfirmation(userId, sessionId);
    }
}
