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
     * The primary reproduction, updated for P-002 Fix 2. Before Fix 2, eight located sections --
     * every one of them a fragment of the card's fee schedule or MITC prose -- reached the review
     * screen as eight accounts, and Fix 1 alone caught that with {@code IMPORT_NO_TRANSACTIONS_FOUND}
     * (a table was "located", just unreadable). Fix 2 stops those prose paragraphs from being
     * accepted as headers in the first place ({@code PdfTableLocator.looksLikeHeaderRow}'s
     * {@code MAX_HEADER_CELL_WORDS} guard), so {@link PdfTableLocator#locateAll} now locates ZERO
     * sections on this document -- asserted directly against {@code PdfTableLocator}, not inferred.
     * {@link PdfTableLocator#locate} folds that into a single {@code preTableLines}-only table
     * (see {@link com.finora.imports.pdf.PdfPreviewGenerator#generateSectionsWithContext}, which is
     * why the GENERATOR still reports one section -- an all-unparseable one -- rather than zero),
     * and that located table carries no header, so the rejection this test asserts changes CODE too:
     * {@code IMPORT_NO_HEADER_DETECTED} rather than {@code IMPORT_NO_TRANSACTIONS_FOUND}. That is a
     * more honest answer for this document -- Finora genuinely never found a table on it -- not a
     * weaker one, and it is exactly what {@link ExtractionCheck}'s own doc comment says the
     * distinction is for.
     */
    @Test
    void kotak_zeroLocatedSectionsAndNoTransactionAnywhere_isRejectedRatherThanStagedAsEightAccounts() {
        PdfTableLocator.LocatedDocument located = new PdfTableLocator()
                .locateAll(PdfTrace.load("kotak-credit-card-ledger-validation"));
        assertThat(located.sections())
                .as("P-002 Fix 2: the prose-header guard rejects every one of Kotak's eight phantom "
                        + "headers, so PdfTableLocator locates no sections at all -- not eight, zero")
                .isEmpty();

        // The generator still reports ONE section for this document -- not zero -- because with no
        // located sections at all, PdfPreviewGenerator folds every line into a single
        // preTableLines-only section reported as entirely unparseable. That single section is what
        // the zero-extraction rejection below actually sees.
        assertThat(rawSectionCountOf("kotak-credit-card-ledger-validation"))
                .as("PdfPreviewGenerator's fallback: zero located sections becomes one all-unparseable section")
                .isEqualTo(1);

        assertThatThrownBy(() -> stage("kotak-credit-card-ledger-validation"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getCode())
                            .as("no table was located anywhere in the document post-Fix-2, which is "
                                    + "the more specific and more honest of the two zero-extraction codes")
                            .isEqualTo(ErrorCode.IMPORT_NO_HEADER_DETECTED);
                    assertThat(api.getMessage())
                            .startsWith("Finora could not find a transaction table anywhere in this statement.")
                            // "Never lose information": every line of the document, prose included,
                            // is still recovered and offered for review -- a bigger number than
                            // before Fix 2, because the whole document is now one section instead of
                            // eight, and every one of its lines counts as recovered text. 213 (Fix 2)
                            // -> 195 under Phase 2E.5's HSBC row-formation fix: groupIntoRows' now
                            // chain-based clustering (header-reconstruction-design.md §9.4) correctly
                            // merges physical lines this document's own native-PDF layout had been
                            // over-split into two, the same benign line-count reduction confirmed
                            // against hdfc-composite-deposit-schedules and hdfc-txn-date-narration-
                            // header in GoldenOutputSnapshotTest -- content merges, nothing is lost.
                            .contains("195 line(s) of text were recovered");
                });
    }

    /**
     * The same failure, now at four sections rather than five. Measured, not assumed: SBI's fifth
     * section (a 221-character/31-word EMI-legal-text paragraph, per the investigation) no longer
     * scores as a header post-Fix-2 and its two rows fall to auxiliary text instead of opening a
     * section -- every one of SBI's remaining four sections still stages zero transactions,
     * including the two genuine transaction blocks the parser cannot yet read, so the honest
     * outcome is still the same rejection, unchanged in code. Making those blocks parse is a
     * separate problem; presenting them as accounts was never the right answer to it.
     */
    @Test
    void sbi_fourSectionsAndNoTransactionAnywhere_isRejected() {
        assertThat(rawSectionCountOf("sbi-credit-card-statement"))
                .as("P-002 Fix 2: five sections before, four after -- the fifth was prose; "
                        + "PdfTableLocator.looksLikePaymentSummaryPanel then drops one more -- one "
                        + "of those four was itself a misdetected payment-summary panel")
                .isEqualTo(3);

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
     * Every committed trace, and exactly what it stages. The documents listed here staged these
     * counts before this fix and stage them after it; any change to this table is this fix having
     * reached a document that parses, which it must not. icici-savings-ledger-validation moved into
     * this map from ALREADY_REJECTED_BEFORE_THIS_FIX in Phase 2E.5, whose leading-narration and
     * header-reconstruction fixes are what now let it stage transactions at all -- see
     * SplitHeaderRunsPdfTableLocatorTest and WrappedHeaderOnAScoringLinePdfTableLocatorTest for the
     * row-count evidence behind the 12.
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
        // PdfTableLocator locates 12 rows in this section (see SplitHeaderRunsPdfTableLocatorTest /
        // WrappedHeaderOnAScoringLinePdfTableLocatorTest), but only 11 stage as transactions here --
        // the 12th, page-2 glossary content carried in by the pre-existing flushPendingLeading
        // mechanism, does not parse as a transaction and is dropped at staging, same as before this
        // fix touched anything downstream of location.
        m.put("icici-savings-ledger-validation", 11);
        m.put("kotak-credit-card-category-sections-and-page-footer", 21);
        m.put("pnb-savings-ledger-validation", 61);
        m.put("union-bank-savings-ledger-validation", 19);
        m.put("cbi-account-discrepancy-disclaimer-trailer", 222);
        m.put("pnb-one-account-discrepancy-disclaimer-trailer", 61);
        m.put("bob-transaction-row-x-ordering", 53);
        // Captured for PdfPreviewGenerator.inheritAccountNumberAcrossSections (docs/superpowers/
        // plans -- see AccountNumberInheritanceRegressionTest, which verifies this trace's actual
        // content, not this row count) -- listed here only so this inventory sweep accounts for it.
        m.put("indusland-credit-card-account-number-inheritance", 8);
        // Captured for AccountNumberTransactionHeaderExtractor -- same real document as
        // icici-credit-card-statement above (3 real transactions), captured separately because that
        // trace predates this fix. See AccountNumberTransactionHeaderRegressionTest, which verifies
        // this trace's actual capability behavior, not this row count -- listed here only so this
        // inventory sweep accounts for it.
        m.put("icici-credit-card-account-number-above-transactions", 3);
        // Captured for PdfTableLocator.mergeHeaderLinesAdmittingInteriorTierColumns -- see
        // InteriorTierWrappedHeaderRealCorpusRegressionTest, which verifies this trace's actual
        // header/capability behavior, not this row count -- listed here only so this inventory
        // sweep accounts for it.
        m.put("iob-savings-interior-tier-header", 15);
        return m;
    }

    /**
     * The four documents Fix 1 changed, and the ERROR CODE each is rejected with today. Three keep
     * {@code IMPORT_NO_TRANSACTIONS_FOUND} (a table was located, just unreadable). Kotak does not:
     * P-002 Fix 2 additionally removes every one of its located sections (all eight were prose), so
     * post-Fix-2 no table is located anywhere in the document at all, and {@link ExtractionCheck}
     * correctly reports the more specific {@code IMPORT_NO_HEADER_DETECTED} instead. This is a
     * genuine, deliberate difference from Fix 1's original shape, not an oversight -- see
     * {@link #kotak_zeroLocatedSectionsAndNoTransactionAnywhere_isRejectedRatherThanStagedAsEightAccounts()}.
     */
    private static final Map<String, ErrorCode> REJECTED_BY_FIX_1 = new LinkedHashMap<>() {{
        put("au-credit-card-statement", ErrorCode.IMPORT_NO_TRANSACTIONS_FOUND);
        put("hdfc-credit-card-ledger-validation", ErrorCode.IMPORT_NO_TRANSACTIONS_FOUND);
        put("kotak-credit-card-ledger-validation", ErrorCode.IMPORT_NO_HEADER_DETECTED);
        put("sbi-credit-card-statement", ErrorCode.IMPORT_NO_TRANSACTIONS_FOUND);
    }};

    private static final List<String> ALREADY_REJECTED_BEFORE_THIS_FIX = List.of(
            "hsbc-savings-ledger-validation",
            "kotak-savings-ledger-validation",
            // Captured later, for a different fix entirely (PdfTableLocator.resolveYearlessDate
            // -- see docs/superpowers/plans/2026-09-01-hsbc-yearless-date-resolution.md), and
            // rejected for a reason unrelated to that fix or to this one: its header-based path
            // (untouched by either fix) picks up 2 garbage rows from this document's own
            // unrelated Loan Summary table, none of which normalize into a real transaction, so
            // rejectIfNothingWasExtracted correctly refuses it -- IMPORT_NO_TRANSACTIONS_FOUND,
            // same as every other entry in this list. Belongs here, not in REJECTED_BY_FIX_1,
            // because P-002 Fix 1 has nothing to do with why this one is rejected.
            "hsbc-credit-card-yearless-dates",
            // Captured for the single-cell exception to refinesRatherThanRedefines' Gate 1 -- see
            // SingleCellHeaderRenameRealCorpusRegressionTest, which verifies that half of this
            // trace's real behavior directly. Rejected here for a reason unrelated to either
            // fix: this real document's OTHER defect (month-first yearless transaction dates,
            // "May 01") is destroyed by redaction -- "May" masks to "Xxx", which no longer
            // matches WEAK_MONTH_DAY -- so no row ever registers as a transaction anchor on this
            // REDACTED trace specifically. The real, unredacted mechanism is covered separately
            // by MonthFirstYearlessDatePdfTableLocatorTest, a synthetic fixture built with real
            // coordinates and values; the real PDF itself (not this trace) is verified end to end
            // via scripts/corpus-run.py.
            "scb-savings-single-cell-header-rename");

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
        for (Map.Entry<String, ErrorCode> expected : REJECTED_BY_FIX_1.entrySet()) {
            assertThatThrownBy(() -> stage(expected.getKey()))
                    .as("%s stages nothing anywhere", expected.getKey())
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getCode())
                            .as("rejection code for %s", expected.getKey())
                            .isEqualTo(expected.getValue()));
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
                                        REJECTED_BY_FIX_1.keySet().stream(), ALREADY_REJECTED_BEFORE_THIS_FIX.stream())
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
        when(transactionRepository.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any())).thenReturn(List.of());
        return new TransactionNormalizer(categorizationService, new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive()),
                TestRuleEngines.empty());
    }

    private CategorizationService categorization() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        return categorizationService;
    }

    private ImportVerifier verifier() {
        return new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new RowAccountingValidator(), new com.finora.imports.CreditCardStatementTotalsValidator(), new com.finora.imports.CreditCardFlowReconciliationValidator());
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
        when(transactionRepository.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive());
        AccountRepository accountRepository = mock(AccountRepository.class);
        ImportSessionService importSessionService = mock(ImportSessionService.class);
        when(importSessionService.createSession(any(), any(), any(), any(), any(), any(), any())).thenReturn(session());
        when(importSessionService.createMultiSection(any(), any(), any(), any(), any(), any())).thenReturn(session());

        PreviewGenerator previewGenerator = new PreviewGenerator(new CsvParser(), transactionNormalizer,
                new StatementValidator(com.finora.imports.product.ProductDiscovery.standard()), verifier(),
                TestRuleEngines.empty());

        return new ImportService(accountRepository, mock(AccountService.class), transactionRepository,
                mock(MerchantRepository.class), mock(StatementImportRepository.class), categorizationService,
                mock(ReconciliationService.class), mock(RecurringService.class), previewGenerator, duplicateDetector,
                new ImportRuleLearningService(categorizationService), importSessionService, generatorFor(acquirer),
                new com.finora.imports.product.ProductIdentityResolver(accountRepository),
                mock(com.finora.imports.ownership.OwnershipMatchService.class),
                new com.finora.imports.storage.StatementContentService(java.util.Optional.empty(), mock(com.finora.security.crypto.EncryptionService.class), "", ""),
                mock(StatementAnalysisRecorder.class), mock(ImportVerificationRecorder.class),
                mock(com.finora.service.MerchantLearningEventPublisher.class), mock(LayoutRegistryService.class),
                mock(com.finora.imports.evidence.ClosingBalanceEvidenceShadowObserver.class));
    }
}
