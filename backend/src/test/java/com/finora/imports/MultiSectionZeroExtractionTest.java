package com.finora.imports;

import com.finora.accounts.AccountService;
import com.finora.dto.ImportDto.PdfStagingSessionResponse;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.entity.ImportSession;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.analysis.ImportVerificationRecorder;
import com.finora.imports.analysis.StatementAnalysisRecorder;
import com.finora.imports.pdf.PdfMetadataExtractor;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.PdfTableLocator;
import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.pdf.PositionedText;
import com.finora.imports.pdf.acquisition.AcquiredDocument;
import com.finora.imports.pdf.acquisition.DocumentTextAcquirer;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.pdf.fixtures.PdfTrace;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import com.finora.service.ReconciliationService;
import com.finora.service.RecurringService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A document that stages no transaction ANYWHERE is refused, however many sections were located.
 *
 * <p>P-002 Fix 1. {@code rejectIfNothingWasExtracted} existed and worked, but it was called only
 * inside {@code parseAndStagePdfWithSession}'s {@code sections.size() <= 1} branch. The
 * multi-section branch had no zero-extraction guard at all, and
 * {@link StagedAccountSectionFilter} deliberately returns every section unfiltered when none of
 * them staged a row -- its doc comment defers that verdict to "the caller's zero-transaction
 * guard", which on this path did not run. So a one-section document yielding nothing was cleanly
 * rejected, while the SAME failure spread across eight located sections was presented to the user
 * as eight accounts to confirm, all empty, seven of them prefilled SAVINGS.
 *
 * <p>The fixtures are the committed redacted traces, driven through the real generator by handing
 * their captured runs to the acquirer seam -- no PDF is re-rendered, so what these tests parse is
 * exactly what was captured from the real documents. Four corpus documents change behaviour
 * (kotak, sbi, au, hdfc credit card) and they are the four the investigation measured; every
 * document that stages a transaction is untouched, which
 * {@link #everyCorpusDocumentThatStagesTransactions_stagesExactlyWhatItStagedBefore()} asserts as
 * a table rather than by sampling.
 */
class MultiSectionZeroExtractionTest {

    private final UUID userId = UUID.randomUUID();

    // ---------------------------------------------------------------- the bug

    /**
     * The primary reproduction. Eight located sections, every one of them a fragment of the card's
     * fee schedule or MITC prose, zero transactions in the document -- which reached the review
     * screen as eight accounts. The rejection is the ordinary one, not a new one.
     */
    @Test
    void kotak_eightSectionsAndNoTransactionAnywhere_isRejectedRatherThanStagedAsEightAccounts() {
        assertThat(stagedSectionCountOf("kotak-credit-card-ledger-validation"))
                .as("the shape of the bug: eight sections survive the filter, all empty")
                .isEqualTo(8);

        assertThatThrownBy(() -> stage("kotak-credit-card-ledger-validation"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getCode()).isEqualTo(ErrorCode.IMPORT_NO_TRANSACTIONS_FOUND);
                    assertThat(api.getMessage())
                            .as("the message the single-section path has always produced, reused verbatim")
                            .startsWith("Finora found a transaction table in this statement but could "
                                    + "not read any transactions from it.")
                            // "Never lose information": the recovered text is counted across the
                            // WHOLE document, not just its first section.
                            .contains("12 line(s) of text were recovered");
                });
    }

    /**
     * The same failure at five sections. Measured, not assumed: every one of SBI's five sections
     * stages zero transactions (§3 of the investigation), including the two genuine transaction
     * blocks the parser cannot yet read -- so the honest outcome is the same rejection. Making
     * those blocks parse is a separate problem; presenting them as five empty accounts was never
     * the right answer to it.
     */
    @Test
    void sbi_fiveSectionsAndNoTransactionAnywhere_isRejected() {
        assertThat(stagedSectionCountOf("sbi-credit-card-statement")).isEqualTo(5);

        assertThatThrownBy(() -> stage("sbi-credit-card-statement"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.IMPORT_NO_TRANSACTIONS_FOUND));
    }

    // ---------------------------------------------------------------- what must not change

    /**
     * The regression control. Four genuinely different products in one file (savings ledger, FD
     * schedule, RD summary, RD installment schedule), 75 real transactions in the ledger -- so the
     * guard must not fire, and nothing about the outcome may move.
     *
     * <p>Asserted at full detail rather than by row count: the filter's existing behaviour (drop
     * the three deposit sections, carry their rows onto the survivor as unparseable) collapses this
     * to a single-account response, and every figure the review screen shows comes out of it.
     */
    @Test
    void hdfcComposite_fourGenuineProducts_isCompletelyUnaffected() throws Exception {
        PdfStagingSessionResponse response = stage("hdfc-composite-deposit-schedules");

        assertThat(rawSectionCountOf("hdfc-composite-deposit-schedules"))
                .as("still four located sections -- this fix touches no parser or locator code")
                .isEqualTo(4);
        assertThat(response.multiAccount()).isFalse();
        assertThat(response.staging().rows()).hasSize(75);
        assertThat(response.staging().totalParsed()).isEqualTo(75);
        assertThat(response.staging().detectedAccount().openingBalance())
                .isEqualTo(reference("hdfc-composite-deposit-schedules").detectedAccount().openingBalance());
        assertThat(response.staging().detectedAccount().closingBalance())
                .isEqualTo(reference("hdfc-composite-deposit-schedules").detectedAccount().closingBalance());
        assertThat(response.staging().detectedAccount().suggestedAccountType()).isEqualTo("SAVINGS");
        assertThat(describe(response)).isEqualTo(describe(reference("hdfc-composite-deposit-schedules")));
    }

    /**
     * A multi-section document that DOES stage transactions still stages, as a multi-account
     * session, with both sections intact. The corpus has no such document post-filter (every
     * multi-section trace in it is an all-empty one), so this is asserted on the synthetic
     * composite fixture -- otherwise the "at least one section has rows" half of the rule would be
     * untested and could be tightened by accident.
     */
    @Test
    void aMultiSectionDocumentWithTransactions_stillStagesBothSections() throws Exception {
        PdfStagingSessionResponse response = serviceFor(new NativeAcquirerHolder())
                .parseAndStagePdfWithSession(userId, "composite.pdf",
                        PdfFixtureBuilder.buildMultiSectionCompositeStatementSample(), null);

        assertThat(response.multiAccount()).isTrue();
        assertThat(response.sections()).hasSize(2);
        assertThat(response.sections().stream().mapToInt(s -> s.rows().size()).sum()).isEqualTo(3);
    }

    /**
     * Low is not zero. A legitimate statement with a handful of transactions must never be caught
     * by a guard whose whole subject is the empty case -- the corpus's smallest genuine documents
     * stage 4 and 8 rows, and both must survive.
     */
    @Test
    void aGenuineDocumentWithVeryFewTransactions_isNotRejected() {
        assertThatCode(() -> stage("hdfc-txn-date-narration-header")).doesNotThrowAnyException();
        assertThatCode(() -> stage("hdfc-savings-single-page-ledger")).doesNotThrowAnyException();

        assertThat(stagedRowCountOf("hdfc-txn-date-narration-header")).isEqualTo(4);
        assertThat(stagedRowCountOf("hdfc-savings-single-page-ledger")).isEqualTo(8);
    }

    // ---------------------------------------------------------------- the whole corpus

    /**
     * Every committed trace, and exactly what it stages. The eleven documents listed here staged
     * these counts before this fix and stage them after it; any change to this table is this fix
     * having reached a document that parses, which it must not.
     */
    private static final Map<String, Integer> STAGES_TRANSACTIONS = stagesTransactions();

    private static Map<String, Integer> stagesTransactions() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("axis-credit-card-statement", 108);
        m.put("bob-repeated-account-banner", 53);
        m.put("bob-savings-ledger-validation", 53);
        m.put("canara-savings-ledger-validation", 58);
        m.put("central-bank-savings-ledger-validation", 222);
        m.put("hdfc-composite-deposit-schedules", 75);
        m.put("hdfc-savings-ledger-validation", 243);
        m.put("hdfc-savings-multi-page-ledger", 360);
        m.put("hdfc-savings-single-page-ledger", 8);
        m.put("hdfc-txn-date-narration-header", 4);
        m.put("icici-credit-card-statement", 3);
        m.put("pnb-savings-ledger-validation", 61);
        m.put("union-bank-savings-ledger-validation", 19);
        return m;
    }

    /**
     * The four documents this fix changes, and the three that were ALREADY rejected before it
     * (single-section documents whose every row failed to normalise). Listing both together is the
     * point: the rejected set grew by exactly the four the investigation measured, and by nothing
     * else.
     */
    private static final List<String> REJECTED_BY_THIS_FIX = List.of(
            "au-credit-card-statement",
            "hdfc-credit-card-ledger-validation",
            "kotak-credit-card-ledger-validation",
            "sbi-credit-card-statement");

    private static final List<String> ALREADY_REJECTED_BEFORE_THIS_FIX = List.of(
            "hsbc-savings-ledger-validation",
            "icici-savings-ledger-validation",
            "kotak-savings-ledger-validation");

    @Test
    void everyCorpusDocumentThatStagesTransactions_stagesExactlyWhatItStagedBefore() {
        for (Map.Entry<String, Integer> expected : STAGES_TRANSACTIONS.entrySet()) {
            assertThatCode(() -> stage(expected.getKey()))
                    .as("%s stages transactions, so the zero-extraction guard must not see it",
                            expected.getKey())
                    .doesNotThrowAnyException();
            assertThat(stagedRowCountOf(expected.getKey()))
                    .as("staged row count for %s", expected.getKey())
                    .isEqualTo(expected.getValue());
        }
    }

    @Test
    void everyRejectedCorpusDocument_isRejectedForTheSameReasonWithTheSameCode() {
        for (String trace : REJECTED_BY_THIS_FIX) {
            assertThatThrownBy(() -> stage(trace))
                    .as("%s stages nothing anywhere", trace)
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getCode())
                            .isEqualTo(ErrorCode.IMPORT_NO_TRANSACTIONS_FOUND));
        }
        for (String trace : ALREADY_REJECTED_BEFORE_THIS_FIX) {
            assertThatThrownBy(() -> stage(trace))
                    .as("%s was rejected before this fix too, by the single-section guard", trace)
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getCode())
                            .isEqualTo(ErrorCode.IMPORT_NO_TRANSACTIONS_FOUND));
        }
    }

    /** The corpus is enumerated from disk, so a newly captured trace lands in neither list and says
     *  so here rather than being silently uncovered. */
    @Test
    void theThreeListsAboveCoverTheWholeCommittedCorpus() {
        assertThat(PdfTrace.committedTraceNames())
                .containsExactlyInAnyOrderElementsOf(
                        java.util.stream.Stream.of(STAGES_TRANSACTIONS.keySet().stream(),
                                        REJECTED_BY_THIS_FIX.stream(), ALREADY_REJECTED_BEFORE_THIS_FIX.stream())
                                .flatMap(s -> s).toList());
    }

    // ---------------------------------------------------------------- harness

    /** Hands a committed trace's captured runs straight to the generator, so the pipeline under
     *  test consumes the real document's geometry rather than a re-rendered approximation of it. */
    private record TraceAcquirer(List<PositionedText> runs) implements DocumentTextAcquirer {
        TraceAcquirer(String trace) { this(PdfTrace.load(trace)); }
        @Override public AcquiredDocument acquire(byte[] fileBytes, String password) {
            return AcquiredDocument.of(runs);
        }
        @Override public boolean supports(byte[] fileBytes) { return true; }
    }

    /** Marker for "use the ordinary native text extractor", for the one fixture that is real PDF
     *  bytes rather than a trace. */
    private record NativeAcquirerHolder() {}

    private PdfStagingSessionResponse stage(String trace) throws Exception {
        return serviceFor(new TraceAcquirer(trace))
                .parseAndStagePdfWithSession(userId, trace + ".pdf", new byte[]{1}, null);
    }

    private int stagedRowCountOf(String trace) {
        try {
            PdfStagingSessionResponse r = stage(trace);
            return r.multiAccount()
                    ? r.sections().stream().mapToInt(s -> s.rows().size()).sum()
                    : r.staging().rows().size();
        } catch (Exception e) {
            throw new IllegalStateException(trace + " was rejected: " + e.getMessage(), e);
        }
    }

    /** What the review screen would be offered: the filter's output, which is where the phantom
     *  accounts came from. Read below the rejection so the bug's shape stays assertable. */
    private int stagedSectionCountOf(String trace) {
        return StagedAccountSectionFilter.onlySectionsThatAreActuallyAccounts(rawSections(trace)).size();
    }

    private int rawSectionCountOf(String trace) {
        return rawSections(trace).size();
    }

    private List<StagedAccountSection> rawSections(String trace) {
        try {
            return generatorFor(new TraceAcquirer(trace))
                    .generateSections(userId, trace + ".pdf", new byte[]{1}, null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The generator's own answer for a document, unfiltered and unguarded -- the "before" side of
     *  the control document's comparison, computed rather than transcribed. */
    private StagedAccountSection reference(String trace) {
        return StagedAccountSectionFilter.onlySectionsThatAreActuallyAccounts(rawSections(trace)).get(0);
    }

    private String describe(PdfStagingSessionResponse response) {
        return describe(new StagedAccountSection(response.staging().detectedAccount(),
                response.staging().rows(), response.staging().totalParsed(),
                response.staging().flaggedDuplicates(), response.staging().unparseableRows(),
                response.staging().verification()));
    }

    /** Every field of a staged section, flattened, so "unaffected" is a whole-object claim. */
    private String describe(StagedAccountSection section) {
        return "rows=" + section.rows()
                + "\ntotalParsed=" + section.totalParsed()
                + "\nflaggedDuplicates=" + section.flaggedDuplicates()
                + "\nunparseable=" + section.unparseableRows()
                + "\ndetected=" + section.detectedAccount()
                + "\nverification=" + section.verification();
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

    private TransactionNormalizer normalizer(CategorizationService categorizationService) {
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        return new TransactionNormalizer(categorizationService, new DuplicateDetector(transactionRepository),
                TestRuleEngines.empty());
    }

    private CategorizationService categorization() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        return categorizationService;
    }

    private ImportVerifier verifier() {
        return new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                new SummaryTotalsValidator(), new ColumnAmbiguityValidator());
    }

    private PdfPreviewGenerator generatorFor(Object acquirer) {
        CategorizationService categorizationService = categorization();
        TransactionNormalizer transactionNormalizer = normalizer(categorizationService);
        if (acquirer instanceof DocumentTextAcquirer textAcquirer) {
            return new PdfPreviewGenerator(textAcquirer, new PdfTableLocator(), new PdfMetadataExtractor(),
                    transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard(),
                    new com.finora.imports.product.ProductAttributeExtractor(), verifier(),
                    TestRuleEngines.empty());
        }
        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(), new PdfMetadataExtractor(),
                transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard(),
                new com.finora.imports.product.ProductAttributeExtractor(), verifier(),
                TestRuleEngines.empty());
    }

    /**
     * The real {@link ImportService} with its persistence collaborators mocked: what is under test
     * is which documents it refuses, and every parsing collaborator on the path from bytes to that
     * decision is the production one.
     */
    private ImportService serviceFor(Object acquirer) {
        CategorizationService categorizationService = categorization();
        TransactionNormalizer transactionNormalizer = normalizer(categorizationService);
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        AccountRepository accountRepository = mock(AccountRepository.class);
        ImportSessionService importSessionService = mock(ImportSessionService.class);
        when(importSessionService.createSession(any(), any(), any(), any(), any(), any())).thenReturn(session());
        when(importSessionService.createMultiSection(any(), any(), any(), any(), any())).thenReturn(session());

        PreviewGenerator previewGenerator = new PreviewGenerator(new CsvParser(), transactionNormalizer,
                new StatementValidator(com.finora.imports.product.ProductDiscovery.standard()), verifier(),
                TestRuleEngines.empty());

        return new ImportService(accountRepository, mock(AccountService.class), transactionRepository,
                mock(MerchantRepository.class), mock(StatementImportRepository.class), categorizationService,
                mock(ReconciliationService.class), mock(RecurringService.class), previewGenerator, duplicateDetector,
                new ImportRuleLearningService(categorizationService), importSessionService, generatorFor(acquirer),
                new com.finora.imports.product.ProductIdentityResolver(accountRepository),
                new com.finora.imports.storage.StatementContentService(java.util.Optional.empty(), "", ""),
                mock(StatementAnalysisRecorder.class), mock(ImportVerificationRecorder.class),
                mock(com.finora.service.MerchantLearningEventPublisher.class), mock(LayoutRegistryService.class),
                mock(com.finora.imports.evidence.ClosingBalanceEvidenceShadowObserver.class));
    }
}
