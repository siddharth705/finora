package com.finora.imports;

import com.finora.accounts.AccountService;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.VerificationFinding;
import com.finora.dto.ImportDto.VerificationReport;
import com.finora.entity.ImportSession;
import com.finora.imports.analysis.ImportVerificationRecorder;
import com.finora.imports.analysis.StatementAnalysisRecorder;
import com.finora.repository.*;
import com.finora.service.CategorizationService;
import com.finora.service.ReconciliationService;
import com.finora.service.RecurringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The {@code StagedAccountSection -> StagingResponse} conversion inside {@link ImportService} keeps
 * the verification report, on every path that performs it.
 *
 * <p>See docs/architecture/system-design/pdfpreviewgenerator-verification-loss-investigation.md.
 * {@code toStagingResponse} built its result with the five-argument {@code StagingResponse}
 * overload, which defaults verification to {@code null}. That method is what the live single-account
 * PDF upload endpoint returns ({@code POST /import/pdf/stage}), and what the section-indexed
 * re-import returns, so every ordinary one-account bank statement reached the review screen with no
 * verification at all — and, because {@code ImportVerificationRecorder} skips a null report, wrote
 * zero rows to {@code import_verification_findings}.
 *
 * <p>The multi-section PDF path and the CSV path never performed that conversion and were never
 * affected; both are asserted here so the fix can be shown not to have changed them.
 *
 * <p>The generator is mocked on purpose: what is under test is the conversion, and a stubbed
 * section lets a KNOWN report be handed in so "the response carries this exact report" is a real
 * assertion rather than a re-derivation. {@code SingleAccountPdfKeepsItsVerificationTest} covers
 * the same property against real extraction of a real fixture.
 */
class VerificationSurvivesStagingConversionTest {

    private ImportService importService;
    private ImportSessionService importSessionService;
    private com.finora.imports.pdf.PdfPreviewGenerator pdfPreviewGenerator;
    private ImportVerificationRecorder verificationRecorder;
    private final UUID userId = UUID.randomUUID();

    /** A report with real content, so "the right one arrived" is distinguishable from "some object arrived". */
    private static VerificationReport report(String rule, String outcome) {
        return new VerificationReport(List.of(
                new VerificationFinding(rule, outcome, Map.of("rowsChecked", 4)),
                new VerificationFinding("SUMMARY_TOTALS", "NOT_APPLICABLE", Map.of())));
    }

    private static StagedRow stagedRow() {
        return new StagedRow(LocalDate.of(2026, 7, 1), "Coffee Shop", new BigDecimal("150.00"),
                "EXPENSE", "Food & Dining", "rule", null, false, null, null);
    }

    private static StagedAccountSection sectionWith(VerificationReport verification) {
        return new StagedAccountSection(null, List.of(stagedRow()), 1, 0, List.of(), verification);
    }

    private ImportSession session() {
        ImportSession session = new ImportSession();
        ReflectionTestUtils.setField(session, "id", UUID.randomUUID());
        session.setUserId(userId);
        session.setFileName("statement.pdf");
        session.setFileContent(new byte[]{1});
        session.setExpiresAt(Instant.now().plusSeconds(600));
        session.setStatus(ImportSession.STATUS_STAGED);
        return session;
    }

    @BeforeEach
    void setUp() {
        AccountRepository accountRepository = mock(AccountRepository.class);
        AccountService accountService = mock(AccountService.class);
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        StatementImportRepository statementImportRepository = mock(StatementImportRepository.class);
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        ReconciliationService reconciliationService = mock(ReconciliationService.class);
        RecurringService recurringService = mock(RecurringService.class);
        importSessionService = mock(ImportSessionService.class);
        when(importSessionService.createSession(any(), any(), any(), any(), any(), any())).thenReturn(session());
        when(importSessionService.createMultiSection(any(), any(), any(), any(), any())).thenReturn(session());

        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService,
                duplicateDetector, TestRuleEngines.empty());
        StatementValidator statementValidator = new StatementValidator(
                com.finora.imports.product.ProductDiscovery.standard());
        PreviewGenerator previewGenerator = new PreviewGenerator(new CsvParser(), transactionNormalizer,
                statementValidator, new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new RowAccountingValidator(), new com.finora.imports.CreditCardStatementTotalsValidator(), new com.finora.imports.CreditCardFlowReconciliationValidator()), TestRuleEngines.empty());
        ImportRuleLearningService ruleLearningService = new ImportRuleLearningService(categorizationService);
        pdfPreviewGenerator = mock(com.finora.imports.pdf.PdfPreviewGenerator.class);
        verificationRecorder = mock(ImportVerificationRecorder.class);

        importService = new ImportService(accountRepository, accountService, transactionRepository,
                merchantRepository, statementImportRepository, categorizationService, reconciliationService,
                recurringService, previewGenerator, duplicateDetector, ruleLearningService, importSessionService,
                pdfPreviewGenerator, new com.finora.imports.product.ProductIdentityResolver(accountRepository),
                new com.finora.imports.storage.StatementContentService(java.util.Optional.empty(), "", ""),
                mock(StatementAnalysisRecorder.class), verificationRecorder,
                mock(com.finora.service.MerchantLearningEventPublisher.class), mock(LayoutRegistryService.class),
                mock(com.finora.imports.evidence.ClosingBalanceEvidenceShadowObserver.class));
    }

    private void stubSections(List<StagedAccountSection> sections) throws Exception {
        when(pdfPreviewGenerator.generateSectionsWithContext(any(), any(), any(), any())).thenReturn(
                new com.finora.imports.pdf.PdfPreviewGenerator.PdfGenerationResult(sections,
                        new DocumentContext("PDF", "test")));
        when(pdfPreviewGenerator.generateSections(any(), any(), any(), any())).thenReturn(sections);
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "statement.pdf", "application/pdf", new byte[]{1});
    }

    @SuppressWarnings("unchecked")
    private List<VerificationReport> recordedReports() {
        ArgumentCaptor<List<VerificationReport>> captor = ArgumentCaptor.forClass(List.class);
        verify(verificationRecorder).recordForAnalysis(any(), captor.capture());
        return captor.getValue();
    }

    // ---------------------------------------------------------------- the fix

    /**
     * The field the frontend actually consumes. {@code Import.tsx:268} reads
     * {@code staging.verification} and {@code VerificationPanel.tsx:27} renders nothing when it is
     * absent — so a null here is not a missing detail, it is a review screen that cannot tell a
     * clean import from a broken balance chain.
     */
    @Test
    void singleAccountPdfUpload_returnsTheVerificationReportOnTheStagingResponse() throws Exception {
        VerificationReport computed = report("BALANCE_CHAIN", "FAILED");
        stubSections(List.of(sectionWith(computed)));

        var response = importService.parseAndStagePdfWithSession(userId, pdf(), null);

        assertThat(response.multiAccount()).isFalse();
        assertThat(response.staging().verification())
                .as("the exact report the section computed, on the field the frontend reads")
                .isSameAs(computed);
        assertThat(response.staging().verification().findings())
                .extracting(VerificationFinding::rule, VerificationFinding::outcome)
                .containsExactly(org.assertj.core.api.Assertions.tuple("BALANCE_CHAIN", "FAILED"),
                        org.assertj.core.api.Assertions.tuple("SUMMARY_TOTALS", "NOT_APPLICABLE"));
    }

    /**
     * The telemetry half. {@code recordPdfParsed} passes {@code singletonList(staged.verification())};
     * with the conversion dropping the report that was {@code singletonList(null)}, and
     * {@code ImportVerificationRecorder.save} skips a null report — so no single-account PDF import
     * has ever written a row to {@code import_verification_findings}.
     */
    @Test
    void singleAccountPdfUpload_handsTheReportToTheVerificationRecorder() throws Exception {
        VerificationReport computed = report("BALANCE_CHAIN", "VERIFIED");
        stubSections(List.of(sectionWith(computed)));

        importService.parseAndStagePdfWithSession(userId, pdf(), null);

        assertThat(recordedReports()).containsExactly(computed);
        assertThat(recordedReports()).doesNotContainNull();
    }

    /** Loss point A: the re-import of a single-account PDF, which routes through {@code generate()}. */
    @Test
    void singleAccountPdfReimport_returnsTheVerificationReport() throws Exception {
        VerificationReport computed = report("BALANCE_CHAIN", "WARNING");
        when(pdfPreviewGenerator.generate(any(), any(), any(), any()))
                .thenReturn(new com.finora.dto.ImportDto.StagingResponse(List.of(stagedRow()), 1, 0, null,
                        List.of(), computed));

        var staged = importService.parseAndStageAnyFormat(userId, "PDF", "statement.pdf", new byte[]{1}, null);

        assertThat(staged.verification()).isSameAs(computed);
    }

    /** Loss point B's second caller: the section-indexed re-import of one section of a composite PDF. */
    @Test
    void sectionIndexedPdfReimport_returnsThatSectionsVerificationReport() throws Exception {
        VerificationReport first = report("BALANCE_CHAIN", "VERIFIED");
        VerificationReport second = report("BALANCE_CHAIN", "FAILED");
        stubSections(List.of(sectionWith(first), sectionWith(second)));

        var staged = importService.parseAndStageAnyFormat(userId, "PDF", "composite.pdf", new byte[]{1}, 1);

        assertThat(staged.verification())
                .as("section 1's report, not section 0's")
                .isSameAs(second);
    }

    // ------------------------------------------------------- adversarial cases

    /**
     * A section that genuinely has no report must still produce a response, and a null one — "not
     * checked" is a real state and is distinct from a report saying NOT_APPLICABLE. Copying the
     * reference rather than reading through it is what keeps this from becoming an NPE.
     */
    @Test
    void singleAccountPdfUpload_withNoReportAtAll_stagesNormallyAndRecordsNothing() throws Exception {
        stubSections(List.of(sectionWith(null)));

        var response = importService.parseAndStagePdfWithSession(userId, pdf(), null);

        assertThat(response.staging().rows()).hasSize(1);
        assertThat(response.staging().verification()).isNull();
        assertThat(recordedReports()).containsExactly((VerificationReport) null);
    }

    /**
     * A report that ran and found nothing is not the same object as no report at all, and the
     * conversion must not collapse the two — an empty findings list still means "we checked".
     */
    @Test
    void singleAccountPdfUpload_withAnEmptyReport_keepsItDistinctFromNull() throws Exception {
        VerificationReport empty = new VerificationReport(List.of());
        stubSections(List.of(sectionWith(empty)));

        var response = importService.parseAndStagePdfWithSession(userId, pdf(), null);

        assertThat(response.staging().verification()).isNotNull();
        assertThat(response.staging().verification().findings()).isEmpty();
        assertThat(response.staging().verification()).isSameAs(empty);
    }

    /** A report whose findings list is itself null — the recorder tolerates it; the conversion must too. */
    @Test
    void singleAccountPdfUpload_withAReportCarryingNullFindings_doesNotThrow() throws Exception {
        VerificationReport odd = new VerificationReport(null);
        stubSections(List.of(sectionWith(odd)));

        var response = importService.parseAndStagePdfWithSession(userId, pdf(), null);

        assertThat(response.staging().verification()).isSameAs(odd);
    }

    /**
     * The shape that defeats the conversion fix on its own: a real HDFC-style combined statement
     * (savings account + a term-deposit table + a recurring-deposit schedule). Three sections are
     * detected, {@code onlySectionsThatAreActuallyAccounts} drops the two that hold no
     * transactions, and the single survivor is REBUILT to carry the dropped sections' unparseable
     * rows. That rebuild used the five-argument {@code StagedAccountSection} constructor, so the
     * surviving section arrived at {@code toStagingResponse} with its verification already gone --
     * the response is then faithfully null-preserving, and the user sees nothing, for exactly the
     * same reason and on a document shape that is not rare.
     */
    @Test
    void combinedStatementFilteredDownToOneAccount_stillCarriesThatAccountsVerification() throws Exception {
        VerificationReport computed = report("BALANCE_CHAIN", "FAILED");
        var savings = sectionWith(computed);
        var termDeposit = new StagedAccountSection(null, List.of(), 0, 0,
                List.of(new com.finora.dto.ImportDto.UnparseableRow(
                        Map.of("Maturity Date", "01/06/2027"), "no date column")), null);
        var recurringDeposit = new StagedAccountSection(null, List.of(), 0, 0, List.of(), null);
        stubSections(List.of(savings, termDeposit, recurringDeposit));

        var response = importService.parseAndStagePdfWithSession(userId, pdf(), null);

        assertThat(response.multiAccount()).as("one account, not three").isFalse();
        // The behaviour the filter exists for, re-asserted so the verification fix can be shown
        // not to have cost it: the dropped tables' contents survive on the surviving section.
        assertThat(response.staging().unparseableRows()).hasSize(1);
        assertThat(response.staging().verification()).isSameAs(computed);
        assertThat(recordedReports()).containsExactly(computed);
    }

    // ------------------------------------------------- unchanged paths (regression)

    /**
     * Multi-section PDFs return their sections directly, with no conversion, and always carried
     * their reports. Unchanged by the fix, per section and in section order.
     */
    @Test
    void multiSectionPdfUpload_stillReturnsAReportPerSection_inSectionOrder() throws Exception {
        VerificationReport first = report("BALANCE_CHAIN", "VERIFIED");
        VerificationReport second = report("BALANCE_CHAIN", "FAILED");
        stubSections(List.of(sectionWith(first), sectionWith(second)));

        var response = importService.parseAndStagePdfWithSession(userId, pdf(), null);

        assertThat(response.multiAccount()).isTrue();
        assertThat(response.staging()).isNull();
        assertThat(response.sections()).extracting(StagedAccountSection::verification)
                .containsExactly(first, second);
        assertThat(recordedReports()).containsExactly(first, second);
    }

    /**
     * CSV never went through the conversion — {@code PreviewGenerator} builds its response with the
     * six-argument constructor directly. Real parsing, not a stub, so this is a genuine end-to-end
     * check that the CSV path is untouched.
     */
    @Test
    void csvUpload_stillCarriesItsVerificationReport() throws Exception {
        String csv = "Date,Description,Debit,Credit,Balance\n"
                + "01/07/2026,OPENING BALANCE,,,50000.00\n"
                + "02/07/2026,SWIGGY ORDER,450.00,,49550.00\n"
                + "05/07/2026,SALARY,,75000.00,124550.00\n";

        var staged = importService.parseAndStage(userId, "statement.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(staged.verification()).isNotNull();
        assertThat(staged.verification().findings()).extracting(VerificationFinding::rule)
                .contains("BALANCE_CHAIN");
    }

    /** The CSV routing through parseAndStageAnyFormat is likewise unchanged. */
    @Test
    void csvReimport_stillCarriesItsVerificationReport() throws Exception {
        String csv = "Date,Description,Debit,Credit,Balance\n"
                + "02/07/2026,SWIGGY ORDER,450.00,,49550.00\n"
                + "05/07/2026,SALARY,,75000.00,124550.00\n";

        var staged = importService.parseAndStageAnyFormat(userId, "CSV", "statement.csv",
                csv.getBytes(StandardCharsets.UTF_8), null);

        assertThat(staged.verification()).isNotNull();
        verifyNoInteractions(pdfPreviewGenerator);
    }

    /**
     * The single-vs-multi routing itself, re-asserted alongside the fix: one section stays the
     * single {@code staging} shape, two stay {@code sections}. The fix copies a field and must not
     * have moved this boundary.
     */
    @Test
    void theSingleVersusMultiSectionBoundaryIsUnchanged() throws Exception {
        stubSections(List.of(sectionWith(report("BALANCE_CHAIN", "VERIFIED"))));
        assertThat(importService.parseAndStagePdfWithSession(userId, pdf(), null).multiAccount()).isFalse();

        setUp();
        stubSections(List.of(sectionWith(report("BALANCE_CHAIN", "VERIFIED")),
                sectionWith(report("BALANCE_CHAIN", "VERIFIED"))));
        assertThat(importService.parseAndStagePdfWithSession(userId, pdf(), null).multiAccount()).isTrue();
    }
}
