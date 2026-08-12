package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.CsvParser;
import com.finora.imports.DocumentContext;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.fixtures.PdfTrace;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-001 Fix B: a header printed across two bands is merged even when the UPPER band already scores
 * as a header on its own.
 *
 * <p>Captured from a real Central Bank of India savings statement. Its header is two genuine bands
 * 11.64pt apart -- already inside {@code HEADER_WRAP_MAX_GAP} -- but the merge was never attempted,
 * because {@code locateAll} only asked about a wrap on a line that did NOT already score. Band 1
 * scores on its own (token-aware matching sees "date" inside "Post Date", plus Debit/Credit/
 * Balance), so band 2 -- "Date | Code | Number" -- fell through and was consumed as the table's
 * first data row, and the date column stayed named "Value" rather than "Value Date".
 *
 * <p>The consumed junk row was the small half of the damage. {@code TransactionNormalizer} resolves
 * its date column by WHOLE-CELL comparison, and neither "value" nor "post date" is in its hints.
 * Measured before the fix: of 224 located rows, <b>0</b> carried a column the normalizer could read
 * as a date, so all 222 transactions were rejected downstream while the locator recorded a
 * successful single-section parse. It was the only 100% row loss in the committed corpus, and it
 * was silent.
 *
 * <p>Lifting that guard is the highest-risk change in this area, because on an already-scoring line
 * the alternative reading -- "band 2 is the table's first data row" -- is the CORRECT one on most
 * documents. So the merge is admitted only under {@code refinesRatherThanRedefines}' four gates,
 * and each of them is asserted here on its own adversarial layout: a case that clears every other
 * gate and is refused by exactly the one under test. Without that, a single over-broad gate would
 * be invisible behind the other three.
 */
class WrappedHeaderOnAScoringLinePdfTableLocatorTest {

    private static final String CBI = "central-bank-savings-ledger-validation";

    private final UUID userId = UUID.randomUUID();

    private List<Map<String, String>> rowsOf(PdfTableLocator.LocatedDocument doc) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (PdfTableLocator.LocatedSection section : doc.sections()) rows.addAll(section.rows());
        return rows;
    }

    private PdfTableLocator.LocatedDocument locate(String trace, DocumentContext ctx) {
        return new PdfTableLocator().locateAll(PdfTrace.load(trace), ctx);
    }

    private Set<String> columnsOf(List<Map<String, String>> rows) {
        Set<String> columns = new LinkedHashSet<>();
        for (Map<String, String> row : rows) columns.addAll(row.keySet());
        return columns;
    }

    private TransactionNormalizer normalizer() {
        CategorizationService categorization = mock(CategorizationService.class);
        when(categorization.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, null, null));
        when(categorization.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, null, null));
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        return new TransactionNormalizer(categorization, new DuplicateDetector(transactions),
                com.finora.imports.TestRuleEngines.empty());
    }

    // ------------------------------------------------------------------
    // The real document
    // ------------------------------------------------------------------

    /**
     * The header the locator actually built, rather than the keys that happen to appear on data
     * rows. A column no value ever lands in ("Cheque Number") is missing from the latter, so the
     * keys are the wrong surface for asserting what the header IS.
     */
    private List<String> headerOf(String trace) {
        DocumentContext ctx = new DocumentContext("PDF", "WrappedHeaderOnAScoringLineTest");
        locate(trace, ctx);
        return ctx.buildMetadata().headers();
    }

    private List<String> headerOf(List<PositionedText> runs) {
        DocumentContext ctx = new DocumentContext("PDF", "WrappedHeaderOnAScoringLineTest");
        new PdfTableLocator().locateAll(runs, ctx);
        return ctx.buildMetadata().headers();
    }

    @Test
    void theTwoHeaderBandsBecomeOneHeader_andTheDateColumnIsNamedInFull() {
        DocumentContext ctx = new DocumentContext("PDF", "WrappedHeaderOnAScoringLineTest");
        locate(CBI, ctx);

        // "Value" alone names nothing the normalizer knows; "Value Date" is in its DATE_HINTS.
        assertThat(ctx.buildMetadata().headers())
                .containsExactly("Xxxx Date", "Value Date", "Branch Code", "Cheque Number",
                        "Transaction Description", "Debit", "Credit", "Balance");
        assertThat(ctx.capabilities()).extracting("capability").contains("WRAPPED_HEADER");
    }

    @Test
    void theSecondBandIsNoLongerConsumedAsTheTablesFirstDataRow() {
        List<Map<String, String>> rows = rowsOf(locate(CBI, null));

        // Before the fix this was literally {Value=Date, Branch=Code, Cheque=Number} -- the header's
        // own second band, staged as a transaction.
        assertThat(rows.get(0)).doesNotContainEntry("Value Date", "Date");
        assertThat(CsvParser.parseDate(rows.get(0).get("Value Date")))
                .as("the first located row is a real dated transaction")
                .isNotNull();
        // 224 -> 223: exactly the one fake row removed. The header's other eight reprints were
        // already being absorbed as continuation lines rather than standing as rows of their own.
        assertThat(rows).hasSize(223);
    }

    @Test
    void everyTransactionNowCarriesADateTheNormalizerCanRead() {
        // The headline quantity, measured with TransactionNormalizer's own whole-cell date hints:
        // 0 of 224 rows before the fix, 222 of 223 after (the remaining row is the statement's
        // dateless opening-balance line).
        String[] dateColumns = {"date", "transaction_date", "txn date", "transaction date",
                "value date", "date & time"};

        long dated = rowsOf(locate(CBI, null)).stream()
                .filter(row -> CsvParser.firstNonBlank(row, dateColumns) != null)
                .count();

        assertThat(dated).isEqualTo(222);
    }

    @Test
    void thoseRowsActuallyStageAsTransactions() {
        // The point of the whole fix: the locator already reported success on this document, so
        // only the staged count shows the loss. Before the fix the normalizer accepted zero rows.
        TransactionNormalizer normalizer = normalizer();

        List<StagedRow> staged = new ArrayList<>();
        for (Map<String, String> row : rowsOf(locate(CBI, null))) {
            StagedRow one = normalizer.normalize(userId, row);
            if (one != null) staged.add(one);
        }

        assertThat(staged).as("staged transactions, where before the fix there were none").hasSize(222);
        assertThat(staged).allSatisfy(row -> assertThat(row.date()).isNotNull());
    }

    @Test
    void theNineReprintedHeadersStillRecordAsOneRepeatedHeader_notNineSections() {
        // CBI reprints BOTH bands on all nine pages. If the merged signature differed page to page
        // the document would split into nine sections instead of recording REPEATED_HEADER.
        DocumentContext ctx = new DocumentContext("PDF", "WrappedHeaderOnAScoringLineTest");

        PdfTableLocator.LocatedDocument doc = locate(CBI, ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(ctx.capabilities()).extracting("capability").contains("REPEATED_HEADER");
    }

    // ------------------------------------------------------------------
    // The corpus-wide safety property
    // ------------------------------------------------------------------

    /**
     * Every trace in the corpus, with the section and row counts the engine produced BEFORE Fix B.
     * Central Bank of India is the only entry that moved (224 -> 223 rows).
     *
     * <p>This is the single most important assertion in this file. Lifting the
     * {@code !looksLikeHeaderRow} guard puts every already-recognized header in the corpus within
     * reach of a merge, and a merge that fires where the single-line reading was already correct
     * silently renames columns, eats a real first data row, or re-anchors the whole table. Counting
     * rows per document is what makes that visible.
     */
    private static final Map<String, int[]> CORPUS_SECTIONS_AND_ROWS = corpus();

    private static Map<String, int[]> corpus() {
        Map<String, int[]> expected = new LinkedHashMap<>();
        expected.put("au-credit-card-statement", new int[]{3, 6});
        expected.put("axis-credit-card-statement", new int[]{2, 113});
        expected.put("bob-repeated-account-banner", new int[]{1, 58});
        expected.put("bob-savings-ledger-validation", new int[]{1, 58});
        expected.put("canara-savings-ledger-validation", new int[]{1, 60});
        expected.put("central-bank-savings-ledger-validation", new int[]{1, 223}); // was {1, 224}
        expected.put("hdfc-composite-deposit-schedules", new int[]{4, 102});
        expected.put("hdfc-credit-card-ledger-validation", new int[]{2, 6});
        expected.put("hdfc-savings-ledger-validation", new int[]{1, 331});
        expected.put("hdfc-savings-multi-page-ledger", new int[]{1, 569});
        expected.put("hdfc-savings-single-page-ledger", new int[]{1, 9});
        expected.put("hdfc-txn-date-narration-header", new int[]{1, 5});
        expected.put("hsbc-savings-ledger-validation", new int[]{1, 2});
        expected.put("icici-credit-card-statement", new int[]{3, 8});
        expected.put("icici-savings-ledger-validation", new int[]{1, 2});
        expected.put("kotak-credit-card-ledger-validation", new int[]{8, 12});
        expected.put("kotak-savings-ledger-validation", new int[]{1, 2});
        expected.put("pnb-savings-ledger-validation", new int[]{1, 62});
        expected.put("sbi-credit-card-statement", new int[]{5, 9});
        expected.put("union-bank-savings-ledger-validation", new int[]{1, 20});
        return expected;
    }

    @Test
    void noOtherDocumentInTheCorpusChangesShape() {
        CORPUS_SECTIONS_AND_ROWS.forEach((trace, expected) -> {
            PdfTableLocator.LocatedDocument doc = locate(trace, null);
            assertThat(doc.sections().size()).as("%s: sections", trace).isEqualTo(expected[0]);
            assertThat(rowsOf(doc).size()).as("%s: rows", trace).isEqualTo(expected[1]);
        });
    }

    @Test
    void theThreeHdfcSavingsHeadersFixARepairedAreUntouchedByFixB() {
        // Fix A coalesces those headers HORIZONTALLY, on a single y band. Fix B works VERTICALLY.
        // The two must not interact: if Fix B pulled the row below into the coalesced header, the
        // seven column names Fix A produces would change and every amount would move.
        for (String trace : List.of("hdfc-savings-ledger-validation", "hdfc-savings-multi-page-ledger",
                "hdfc-savings-single-page-ledger")) {
            assertThat(columnsOf(rowsOf(locate(trace, null))))
                    .as("%s: Fix A's seven columns, unchanged", trace)
                    .containsExactlyInAnyOrder("Date", "Narration", "Chq./Xxx.Xx.", "Value Dt",
                            "Withdrawal Amt.", "Deposit Amt.", "Closing Balance");
        }
    }

    @Test
    void aGenuineFirstDataRowUnderAHeaderIsStillReadAsData() {
        // The Axis credit-card statement prints its first transaction 10.13pt under its header --
        // inside HEADER_WRAP_MAX_GAP, and on a header line that already scores. It must stay a
        // transaction. It is refused because it carries a date and an amount (carriesNoDataValue),
        // which is a check the merge already made and Fix B deliberately did not touch.
        List<Map<String, String>> rows = rowsOf(locate("axis-credit-card-statement", null));

        assertThat(columnsOf(rows)).contains("DATE", "TRANSACTION DETAILS", "AMOUNT (Xx.)");
        assertThat(rows.stream().filter(r -> CsvParser.parseDate(
                String.valueOf(r.getOrDefault("DATE", ""))) != null).count())
                .as("the transactions under that header are still transactions")
                .isEqualTo(108);
    }

    // ------------------------------------------------------------------
    // The four gates, one adversarial layout each
    // ------------------------------------------------------------------

    /**
     * The CBI shape, reduced to its essentials: an upper band that already scores as a header on
     * its own, and a lower band that refines two of its columns. Every adversarial case below is
     * this layout with exactly one property spoiled, so the gate that refuses it is unambiguous.
     */
    private List<PositionedText> layout(List<PositionedText> lowerBand) {
        List<PositionedText> runs = new ArrayList<>(List.of(
                new PositionedText("Date", 26f, 100f, 0, 16f),
                new PositionedText("Value", 91f, 100f, 0, 25f),
                new PositionedText("Branch", 138f, 100f, 0, 31f),
                new PositionedText("Cheque", 188f, 100f, 0, 35f),
                new PositionedText("Description", 242f, 100f, 0, 60f),
                new PositionedText("Debit", 375f, 100f, 0, 23f),
                new PositionedText("Credit", 439f, 100f, 0, 26f),
                new PositionedText("Balance", 512f, 100f, 0, 36f)));
        runs.addAll(lowerBand);
        // Two ordinary transactions, a full row pitch below the heading.
        runs.addAll(List.of(
                new PositionedText("01/05/2026", 26f, 140f, 0, 40f),
                new PositionedText("01/05/2026", 91f, 140f, 0, 40f),
                new PositionedText("RENT", 242f, 140f, 0, 30f),
                new PositionedText("500.00", 375f, 140f, 0, 25f),
                new PositionedText("1,000.00", 512f, 140f, 0, 30f),
                new PositionedText("02/05/2026", 26f, 160f, 0, 40f),
                new PositionedText("02/05/2026", 91f, 160f, 0, 40f),
                new PositionedText("SALARY", 242f, 160f, 0, 40f),
                new PositionedText("900.00", 439f, 160f, 0, 25f),
                new PositionedText("1,900.00", 512f, 160f, 0, 30f)));
        return runs;
    }

    @Test
    void theBaselineLayoutDoesMerge_soEveryRefusalBelowIsTheGateAndNotTheLayout() {
        // Two lower cells, both within 5pt of an anchor, no new column, and the merge adds
        // "Value Date" -- a name the normalizer knows and "Value" alone is not.
        assertThat(headerOf(layout(List.of(
                new PositionedText("Date", 93.44f, 111.64f, 0, 21f),
                new PositionedText("Code", 142f, 111.64f, 0, 24f)))))
                .containsExactly("Date", "Value Date", "Branch Code", "Cheque", "Description",
                        "Debit", "Credit", "Balance");
    }

    @Test
    void gate1_oneStrayTokenBelowAHeaderIsNotASecondBand() {
        // Identical to the baseline except the lower band has ONE cell. It is 2.44pt from its
        // anchor, adds no column, and would still improve the whole-cell count ("Value Date") --
        // so only the cell-count floor can refuse it. A footnote, a unit annotation and a narration
        // fragment all look exactly like this, and the corpus has several.
        assertThat(headerOf(layout(List.of(
                new PositionedText("Date", 93.44f, 111.64f, 0, 21f)))))
                .containsExactly("Date", "Value", "Branch", "Cheque", "Description",
                        "Debit", "Credit", "Balance");
    }

    @Test
    void gate2_aLowerCellOffItsColumnMeansTheseAreNotOneHeading() {
        // Two lower cells, but "Code" now sits 12pt from Branch's anchor -- inside the existing
        // 40pt HEADER_WRAP_MAX_COLUMN_JOIN (so mergeHeaderLines itself is happy to join it) and
        // outside the 5pt strict bound. This is the gate that separates a printed second band from
        // a line that merely sits nearby, and the 40pt bound cannot do it.
        assertThat(headerOf(layout(List.of(
                new PositionedText("Date", 93.44f, 111.64f, 0, 21f),
                new PositionedText("Code", 150f, 111.64f, 0, 24f)))))
                .containsExactly("Date", "Value", "Branch", "Cheque", "Description",
                        "Debit", "Credit", "Balance");
    }

    @Test
    void gate3_aMergeThatWouldAddAColumnIsRefused() throws Exception {
        // Asserted on the predicate directly, and deliberately so. mergeHeaderLines returns null
        // rather than a row the moment a lower cell joins no column, so no run list can currently
        // reach this gate through locateAll -- it is an invariant, not a filter. It is still coded
        // and still tested, because it is the property that makes "one header over two lines" mean
        // refinement rather than redefinition, and mergeHeaderLines' own doc comment records real
        // pressure to relax exactly that (the half-named recurring-deposit heading).
        PdfTableLocator locator = new PdfTableLocator();
        Method refines = PdfTableLocator.class.getDeclaredMethod(
                "refinesRatherThanRedefines", List.class, List.class);
        refines.setAccessible(true);

        List<PositionedText> upper = List.of(
                new PositionedText("Date", 26f, 100f, 0, 16f),
                new PositionedText("Value", 91f, 100f, 0, 25f),
                new PositionedText("Debit", 375f, 100f, 0, 23f),
                new PositionedText("Sheet", 512f, 100f, 0, 24f));
        List<List<PositionedText>> block = List.of(upper, List.of(
                new PositionedText("Date", 93f, 111f, 0, 21f),
                new PositionedText("Balance", 514f, 111f, 0, 36f)));

        // A well-formed refinement of that block: four columns in, four columns out -> admitted.
        assertThat((Boolean) refines.invoke(locator, block, List.of(
                new PositionedText("Date", 26f, 111f, 0, 16f),
                new PositionedText("Value Date", 91f, 111f, 0, 25f),
                new PositionedText("Debit", 375f, 111f, 0, 23f),
                new PositionedText("Sheet Balance", 512f, 111f, 0, 38f)))).isTrue();

        // The same block, merged into FIVE columns -- the lower band introduced one of its own
        // rather than renaming what was above it. Refused, even though it names strictly more
        // columns the normalizer knows than the upper line did.
        assertThat((Boolean) refines.invoke(locator, block, List.of(
                new PositionedText("Date", 26f, 111f, 0, 16f),
                new PositionedText("Value Date", 91f, 111f, 0, 25f),
                new PositionedText("Debit", 375f, 111f, 0, 23f),
                new PositionedText("Sheet", 512f, 111f, 0, 24f),
                new PositionedText("Balance", 514f, 111f, 0, 36f)))).isFalse();
    }

    @Test
    void gate4_aMergeThatNamesNoMoreColumnsThanBeforeIsRefused() {
        // Two lower cells, both tightly aligned, no new column -- gates 1, 2 and 3 all pass, and
        // the merged row still scores as a header. But "Branch Code" and "Cheque Number" name
        // nothing TransactionNormalizer knows by whole-cell comparison, so the merge is a rename,
        // not an improvement, and the unmerged reading stands. This is the safety valve: without
        // it, every already-correct header in the corpus is one tight-alignment coincidence away
        // from being renamed.
        assertThat(headerOf(layout(List.of(
                new PositionedText("Code", 142f, 111.64f, 0, 24f),
                new PositionedText("Number", 188.5f, 111.64f, 0, 35f)))))
                .containsExactly("Date", "Value", "Branch", "Cheque", "Description",
                        "Debit", "Credit", "Balance");
    }

    @Test
    void gate4CountsWholeCellMatches_notTheTokenAwareOnesLooksLikeHeaderRowScores() throws Exception {
        // The distinction is the entire reason CBI's merge is admitted. Token-aware matching sees
        // "date" inside "Post Date" and calls the date column found on BOTH readings, which would
        // make the merge look like no improvement at all. Whole-cell matching -- what the
        // normalizer actually does -- sees the difference between "Value" and "Value Date".
        PdfTableLocator locator = new PdfTableLocator();
        Method wholeCell = PdfTableLocator.class.getDeclaredMethod("wholeCellHintMatches", List.class);
        wholeCell.setAccessible(true);
        Method looksLike = PdfTableLocator.class.getDeclaredMethod("looksLikeHeaderRow", List.class);
        looksLike.setAccessible(true);

        List<PositionedText> unmerged = List.of(
                new PositionedText("Post Date", 26f, 100f, 0, 40f),
                new PositionedText("Value", 91f, 100f, 0, 25f),
                new PositionedText("Balance", 512f, 100f, 0, 36f));
        List<PositionedText> merged = List.of(
                new PositionedText("Post Date", 26f, 111f, 0, 40f),
                new PositionedText("Value Date", 91f, 111f, 0, 45f),
                new PositionedText("Balance", 512f, 111f, 0, 36f));

        assertThat((Boolean) looksLike.invoke(locator, unmerged))
                .as("token-aware scoring already calls this a header -- which is why the merge was refused")
                .isTrue();
        assertThat((Integer) wholeCell.invoke(locator, unmerged)).isEqualTo(1); // "balance" only
        assertThat((Integer) wholeCell.invoke(locator, merged)).isEqualTo(2);   // + "value date"
    }
}
