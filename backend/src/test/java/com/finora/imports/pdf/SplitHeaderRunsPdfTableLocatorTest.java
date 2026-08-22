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

import java.math.BigDecimal;
import java.util.ArrayList;
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
 * P-001 Fix A: a header cell that PDFBox split into separate text runs is put back together before
 * the columns are named.
 *
 * <p>Captured from three real HDFC savings statements. Their header prints on ONE y band --
 * "Date | Narration | Chq./Ref.No. | Value Dt | Withdrawal Amt. | Deposit Amt. | Closing Balance" --
 * but arrives as ELEVEN runs, because every multi-word cell is split at its space. One column per
 * run gave a header with TWO columns literally named "Amt.", which collapse onto the same key in
 * {@code bucketRow}'s map, and "amt" names nothing in {@code TransactionNormalizer}'s hint lists.
 *
 * <p>The damage was NOT lost rows. The rows staged; their amounts and their signs were wrong.
 * {@code AMOUNT_HINTS} fell through to its last-resort "balance" entry, so a transaction's amount
 * became the account's running balance, and with no credit column recognized every deposit staged
 * as an EXPENSE. Measured before the fix, per trace: 230 / 343 / 7 rows resolved their amount from
 * the balance column, and 0 / 1 / 1 rows were recognized as credits. This is the same
 * silently-wrong-data failure already documented for Kotak's "Deposit (Cr.)" in
 * {@code TransactionNormalizer}, reaching the same place through a different mechanism -- which is
 * why it is asserted on the STAGED rows and not only on the column names.
 *
 * <p>Driven from the locator down rather than from bytes: a trace IS
 * {@code PdfTextExtractor}'s output, and replaying from there is what keeps the exact run
 * fragmentation that caused the bug (rebuilding a PDF would re-lay it out and lose it).
 */
class SplitHeaderRunsPdfTableLocatorTest {

    private static final List<String> HDFC_SAVINGS_TRACES = List.of(
            "hdfc-savings-ledger-validation",
            "hdfc-savings-multi-page-ledger",
            "hdfc-savings-single-page-ledger");

    private final UUID userId = UUID.randomUUID();

    private List<Map<String, String>> locate(String trace) {
        PdfTableLocator.LocatedDocument doc =
                new PdfTableLocator().locateAll(PdfTrace.load(trace), null);
        List<Map<String, String>> rows = new ArrayList<>();
        for (PdfTableLocator.LocatedSection section : doc.sections()) rows.addAll(section.rows());
        return rows;
    }

    /** Every column name the located rows carry, in the order the document uses them. */
    private Set<String> columnsOf(String trace) {
        Set<String> columns = new LinkedHashSet<>();
        for (Map<String, String> row : locate(trace)) columns.addAll(row.keySet());
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

    // --- The column names themselves ---

    @Test
    void eachMultiWordHeaderCellIsOneColumn_notOnePerTextRun() {
        for (String trace : HDFC_SAVINGS_TRACES) {
            assertThat(columnsOf(trace))
                    .as("%s: the header's own seven columns, not eleven runs", trace)
                    .contains("Date", "Narration", "Value Dt", "Withdrawal Amt.", "Deposit Amt.",
                            "Closing Balance")
                    .doesNotContain("Amt.", "Withdrawal", "Deposit", "Closing", "Value", "Dt");
        }
    }

    @Test
    void theTwoAmountColumnsNoLongerCollapseOntoOneKey() {
        for (String trace : HDFC_SAVINGS_TRACES) {
            // The withdrawal and the deposit column were BOTH named "Amt." and both wrote into the
            // same LinkedHashMap key, so one of them silently overwrote the other on every row.
            Set<String> columns = columnsOf(trace);
            assertThat(columns).as("%s", trace).contains("Withdrawal Amt.", "Deposit Amt.");
            assertThat(columns.stream().map(CsvParser::normalizeHeaderCell).distinct().count())
                    .as("%s: no two columns normalize to the same key", trace)
                    .isEqualTo(columns.size());
        }
    }

    // --- What the rows actually stage as ---

    @Test
    void noRowResolvesItsAmountFromTheBalanceColumn() {
        // The measured before-fix corruption: 230 / 343 / 7 rows across these three documents took
        // the running balance as the transaction amount, because no real amount column was named.
        String[] realAmountColumns = {"withdrawal amt", "deposit amt", "amount", "debit", "credit"};
        String[] anyAmountColumn = {"withdrawal amt", "deposit amt", "amount", "debit", "credit",
                "closing balance", "balance"};

        for (String trace : HDFC_SAVINGS_TRACES) {
            long fallenBackToBalance = locate(trace).stream()
                    .filter(row -> CsvParser.firstNonBlank(row, realAmountColumns) == null
                            && CsvParser.firstNonBlank(row, anyAmountColumn) != null)
                    .count();
            assertThat(fallenBackToBalance).as("%s", trace).isZero();
        }
    }

    @Test
    void everyStagedAmountIsTheTransactionsOwnAmount_notItsRunningBalance() {
        TransactionNormalizer normalizer = normalizer();

        for (String trace : HDFC_SAVINGS_TRACES) {
            int checked = 0;
            for (Map<String, String> row : locate(trace)) {
                StagedRow staged = normalizer.normalize(userId, row);
                if (staged == null) continue;
                BigDecimal balance = CsvParser.parseNumeric(
                        String.valueOf(row.getOrDefault("Closing Balance", "")).trim());
                BigDecimal withdrawal = CsvParser.parseNumeric(
                        String.valueOf(row.getOrDefault("Withdrawal Amt.", "")).trim());
                BigDecimal deposit = CsvParser.parseNumeric(
                        String.valueOf(row.getOrDefault("Deposit Amt.", "")).trim());
                BigDecimal expected = withdrawal != null && withdrawal.signum() != 0 ? withdrawal
                        : deposit != null && deposit.signum() != 0 ? deposit : null;
                if (expected == null) continue;
                checked++;
                assertThat(staged.amount()).as("%s: amount of %s", trace, row)
                        .isEqualByComparingTo(expected.abs());
                if (balance != null && balance.abs().compareTo(expected.abs()) != 0) {
                    assertThat(staged.amount()).as("%s: staged the balance, not the amount", trace)
                            .isNotEqualByComparingTo(balance.abs());
                }
            }
            assertThat(checked).as("%s: rows actually asserted", trace).isPositive();
        }
    }

    @Test
    void aDepositStagesAsIncome_notSilentlyAsAnExpense() {
        TransactionNormalizer normalizer = normalizer();
        // Before the fix: 0 / 1 / 1 rows were recognized as credits across the three documents,
        // and every genuine deposit staged as an EXPENSE. Measured after: 30 / 34 / 2.
        List<Integer> expectedCredits = List.of(30, 34, 2);

        for (int i = 0; i < HDFC_SAVINGS_TRACES.size(); i++) {
            String trace = HDFC_SAVINGS_TRACES.get(i);
            int deposits = 0;
            int incomes = 0;
            for (Map<String, String> row : locate(trace)) {
                BigDecimal deposit = CsvParser.parseNumeric(
                        String.valueOf(row.getOrDefault("Deposit Amt.", "")).trim());
                if (deposit == null || deposit.signum() == 0) continue;
                deposits++;
                StagedRow staged = normalizer.normalize(userId, row);
                // A handful of these are dateless continuation lines that carry the amount for the
                // transaction above them; the normalizer declines them for want of a date, which is
                // its existing, separate behaviour and not this fix's business. What matters here is
                // that no row with a deposit value stages with the WRONG sign.
                if (staged == null) continue;
                assertThat(staged.type()).as("%s: deposit of %s", trace, deposit).isEqualTo("INCOME");
                incomes++;
            }
            assertThat(deposits).as("%s: rows with a value in the deposit column", trace)
                    .isEqualTo(expectedCredits.get(i));
            assertThat(incomes).as("%s: deposits that stage, all as income", trace).isPositive();
        }
    }

    @Test
    void everyTransactionNowHasAValueInARealAmountColumn() {
        // The headline quantity, measured with TransactionNormalizer's own TRANSACTION_AMOUNT_HINTS
        // and counting only values that parse as a non-zero number:
        //   before -> after:  7 -> 253,  3 -> 374,  1 -> 9.
        //
        // hdfc-savings-ledger-validation's count moved again, 253 -> 244, when the
        // OFFSET_COLUMN_ANCHORS redirect in bucketRow was taught not to move a number out of a
        // reference/cheque-number column (see that guard's own doc comment -- verified on a real,
        // unredacted HDFC statement, not reproduced here, where this exact redirect took a
        // transaction's genuine (16-digit, zero-padded) Chq./Ref.No. value and moved it into
        // Withdrawal Amt., turning a real
        // ₹454 deposit into a phantom >₹500,000,000 withdrawal).
        //
        // All 9 rows that dropped out of this count on THIS trace are confirmed, individually, to
        // be non-transaction boilerplate -- every one carries `Date=HDFC BANK LIMITED ... State
        // account branch ...` (letterhead/GSTIN disclaimer text merged into a row), never a
        // parseable date, so TransactionNormalizer drops them regardless of what lands in their
        // amount cell either way. Their loss from this count is not a loss of accuracy on any real
        // transaction. Row 255 in this same trace is the positive case the guard exists for: its
        // amount cell held the redacted-reference-number placeholder "9999999999999999" before this
        // fix and the real amount, "454.00", after it -- a genuine transaction, not boilerplate.
        //
        // hdfc-savings-multi-page-ledger's count moved the same way, 374 -> 360: individually
        // confirmed, all 14 rows that dropped out carry the identical boilerplate `Date=HDFC BANK
        // LIMITED ... State account branch ...` shape, never a parseable date. Two more genuine
        // transactions on THIS trace (real dates 28/07/25 and 30/10/25) had the same
        // "9999999999999999" placeholder-as-amount bug this fix corrects -- to 1,360.12 and
        // 3,965.01 respectively -- but neither changes the count, since a placeholder and a real
        // amount both already counted as "a real number" either way; only the VALUE was wrong.
        //
        // hdfc-savings-ledger-validation's count moved again, 244 -> 241, when the same
        // OFFSET_COLUMN_ANCHORS redirect was additionally taught to require a decimal point in
        // the redirected value -- see that guard's own doc comment (verified on a real Kotak
        // credit-card statement, where a bare 3-digit card-ending suffix printed next to a
        // merchant name was wrongly read as an overshot amount and merged into the real one).
        // Individually confirmed: all 3 rows that dropped out of this count carry `Date=XXXXX`
        // (redacted/masked entirely, not a real date under any format) with a redacted 4-digit
        // placeholder amount "9999" that no longer gets rescued by the now-decimal-only redirect
        // -- a row whose date can never parse stages nothing regardless of what its amount cell
        // resolves to, so this is the same "boilerplate/unparseable either way" shape as the two
        // moves above, not a new loss of accuracy on any real transaction.
        List<Integer> expected = List.of(241, 360, 9);
        String[] transactionAmountColumns = {"withdrawal amt", "deposit amt", "amount", "debit",
                "credit", "deposit", "withdrawal", "deposits", "withdrawals"};

        for (int i = 0; i < HDFC_SAVINGS_TRACES.size(); i++) {
            String trace = HDFC_SAVINGS_TRACES.get(i);
            long withRealAmount = locate(trace).stream()
                    .map(row -> CsvParser.firstNonBlank(row, transactionAmountColumns))
                    .filter(raw -> raw != null && CsvParser.parseNumeric(raw.trim()) != null
                            && CsvParser.parseNumeric(raw.trim()).signum() != 0)
                    .count();
            assertThat(withRealAmount).as("%s", trace).isEqualTo(expected.get(i).longValue());
        }
    }

    @Test
    void theRowsThemselvesAreUnchangedInNumber() {
        // Fix A renames columns; it must not find or lose a single row. These are the counts the
        // pre-fix engine produced, asserted so a "fix" that quietly changed the table is visible.
        List<Integer> expected = List.of(331, 569, 9);
        for (int i = 0; i < HDFC_SAVINGS_TRACES.size(); i++) {
            assertThat(locate(HDFC_SAVINGS_TRACES.get(i)).size())
                    .as("%s", HDFC_SAVINGS_TRACES.get(i)).isEqualTo(expected.get(i));
        }
    }

    // --- Adversarial: the risks the P-001 investigation identified ---

    @Test
    void joiningRunsNeverDecidesWhetherARowIsAHeader_axisFinePrintStaysFinePrint() {
        // Joining runs SHRINKS a row's cell count while leaving its hint count alone, which makes
        // looksLikeHeaderRow's density test (matches * 3 >= size) strictly easier to pass. Applied
        // to every line rather than only to an already-accepted header, this invented an extra
        // section on this document out of its "Schedule of Charges" fine print
        // ("Txn Date | Type | Cr/Xx | Amount ... | of Axis Bank..."), which is exactly the
        // false-positive class MAX_HEADER_ROW_CELLS and the density check exist to stop. That fine
        // print contains an adjacent 7.99pt pair, the smallest genuine inter-run gap in the corpus
        // -- so this document would merge if the gate were not there.
        DocumentContext ctx = new DocumentContext("PDF", "SplitHeaderRunsPdfTableLocatorTest");

        PdfTableLocator.LocatedDocument doc = new PdfTableLocator()
                .locateAll(PdfTrace.load("axis-credit-card-statement"), ctx);

        // One section, not two: the OTHER section this trace used to have was itself a misdetected
        // payment-summary panel (PdfTableLocator.looksLikePaymentSummaryPanel), not fine print --
        // a separate fix, now also landing on this trace. What this test actually guards -- no
        // BOGUS third section from the fine-print run-joining bug -- still holds.
        assertThat(doc.sections()).as("one section, as before this fix -- no second, bogus one").hasSize(1);
        // 110, not 111: TRANSACTION_TABLE_CLOSED (PdfTableLocator.STATEMENT_CLOSING_MARKER) now
        // stops bucketing at this trace's own "*** End of Statement ***" line, one row earlier than
        // before -- see docs/architecture/system-design/transaction-boundary-phase2a-investigation.md.
        assertThat(doc.sections().get(0).rows()).hasSize(110);
        assertThat(doc.sections().stream().flatMap(s -> s.rows().stream())
                .flatMap(r -> r.keySet().stream()).distinct())
                .as("no column named out of a fine-print sentence")
                .doesNotContain("Txn Date Type");
    }

    @Test
    void theWrappedHeaderDocumentsKeepTheirSectionsAndTheirRows() {
        // Run-joining is applied only AFTER header acceptance and after mergeHeaderLines, because
        // mergeHeaderLines seeds its columns from the first line's RUNS: joining them first changes
        // which columns exist and therefore which joins are made, which moved section boundaries on
        // SBI in simulation. Both committed WRAPPED_HEADER documents are asserted structurally.
        // P-002 Fix 2: SBI's fifth section was a 221-char/31-word EMI-legal-text paragraph
        // misread as a header. It no longer opens a section, so SBI drops from 5 sections to 4.
        // A second, separate fix (PdfTableLocator.looksLikePaymentSummaryPanel) then drops one
        // more: what was section 2 of those four was itself a misdetected payment-summary panel,
        // the same shape found on the real Axis and HDFC credit statements -- 4 sections to 3.
        PdfTableLocator.LocatedDocument sbi = new PdfTableLocator()
                .locateAll(PdfTrace.load("sbi-credit-card-statement"), null);
        assertThat(sbi.sections()).hasSize(3);
        assertThat(sbi.sections().stream().map(s -> s.rows().size()).toList())
                .isEqualTo(List.of(1, 2, 2));

        DocumentContext ctx = new DocumentContext("PDF", "SplitHeaderRunsPdfTableLocatorTest");
        PdfTableLocator.LocatedDocument composite = new PdfTableLocator()
                .locateAll(PdfTrace.load("hdfc-composite-deposit-schedules"), ctx);
        assertThat(composite.sections()).hasSize(4);
        assertThat(composite.sections().get(0).rows()).hasSize(84);
        assertThat(ctx.capabilities()).extracting("capability").contains("WRAPPED_HEADER");
    }

    @Test
    void runsWithNoMeasuredWidthAreNeverJoined() {
        // width == 0 means endX() == x, so the "gap" degenerates into the raw distance between two
        // LEFT edges -- which says nothing about whether the runs touch, and would join two
        // genuinely separate columns. Older v1/v2 traces are exactly that shape. Same header,
        // built twice: once with measured widths, once with none.
        List<PositionedText> measured = List.of(
                new PositionedText("Date", 40f, 100f, 0, 16f),
                new PositionedText("Withdrawal", 405f, 100f, 0, 41.3f),
                new PositionedText("Amt.", 448.7f, 100f, 0, 17.1f),
                new PositionedText("Deposit", 491f, 100f, 0, 25.8f),
                new PositionedText("Amt.", 518.8f, 100f, 0, 17.1f),
                new PositionedText("01/04/2026", 40f, 120f, 0, 40f),
                new PositionedText("500.00", 405f, 120f, 0, 20f),
                new PositionedText("02/04/2026", 40f, 140f, 0, 40f),
                new PositionedText("250.00", 495f, 140f, 0, 20f));
        List<PositionedText> unmeasured = new ArrayList<>();
        for (PositionedText t : measured) {
            unmeasured.add(new PositionedText(t.text(), t.x(), t.y(), t.pageIndex()));
        }

        Set<String> withWidths = new LinkedHashSet<>();
        new PdfTableLocator().locateAll(measured, null).sections()
                .forEach(s -> s.rows().forEach(r -> withWidths.addAll(r.keySet())));
        Set<String> withoutWidths = new LinkedHashSet<>();
        new PdfTableLocator().locateAll(unmeasured, null).sections()
                .forEach(s -> s.rows().forEach(r -> withoutWidths.addAll(r.keySet())));

        assertThat(withWidths).contains("Withdrawal Amt.", "Deposit Amt.");
        assertThat(withoutWidths)
                .as("no width measured, so exactly today's behaviour -- one column per run")
                .contains("Withdrawal", "Deposit")
                .doesNotContain("Withdrawal Amt.", "Deposit Amt.");
    }

    @Test
    void twoAdjacentValuesAreNeverGluedIntoOneColumnName() {
        // A header cell is a word. Two neighbouring narrow columns whose VALUES sit 2pt apart --
        // a date beside an amount, or two amounts -- must not become one fabricated column name,
        // which is what the date/number exclusion is for. Here the header row itself carries a
        // date-shaped and a number-shaped run right next to the words.
        List<PositionedText> runs = List.of(
                new PositionedText("Date", 40f, 100f, 0, 16f),
                new PositionedText("Narration", 100f, 100f, 0, 34f),
                new PositionedText("Withdrawal", 405f, 100f, 0, 41.3f),
                new PositionedText("Amt.", 448.7f, 100f, 0, 17.1f),
                new PositionedText("01/04/2026", 480f, 100f, 0, 40f),
                new PositionedText("1000.00", 522f, 100f, 0, 25f),
                new PositionedText("Balance", 560f, 100f, 0, 27f),
                new PositionedText("01/04/2026", 40f, 120f, 0, 40f),
                new PositionedText("Rent", 100f, 120f, 0, 20f),
                new PositionedText("500.00", 405f, 120f, 0, 20f));

        Set<String> columns = new LinkedHashSet<>();
        new PdfTableLocator().locateAll(runs, null).sections()
                .forEach(s -> s.rows().forEach(r -> columns.addAll(r.keySet())));

        assertThat(columns).contains("Withdrawal Amt.");
        assertThat(columns.stream().anyMatch(c -> c.contains("01/04/2026") || c.contains("1000.00")))
                .as("a value-shaped run was joined into a column name")
                .isFalse();
    }

    @Test
    void noOtherCommittedTraceGainsOrLosesASection() {
        // Corpus sweep for the threshold itself: 6pt sits between HDFC's 2.00pt intra-cell gaps and
        // the smallest inter-run gap anywhere else in the corpus (7.99pt, on the Axis fine-print
        // line above; 13.38pt on any accepted header row). These are the section and row counts the
        // pre-fix engine produced for every trace that is not one of the three HDFC savings
        // documents -- all of them unchanged, measured, not assumed.
        Map<String, List<Integer>> expected = Map.ofEntries(
                Map.entry("au-credit-card-statement", List.of(3, 2, 2, 2)),
                // 2 sections before PdfTableLocator.looksLikePaymentSummaryPanel, 1 after -- the
                // dropped section was a misdetected payment-summary panel, not fine print. 110, not
                // 111, since TRANSACTION_TABLE_CLOSED (STATEMENT_CLOSING_MARKER) started stopping at
                // this trace's own "*** End of Statement ***" line.
                Map.entry("axis-credit-card-statement", List.of(1, 110)),
                Map.entry("bob-repeated-account-banner", List.of(1, 58)),
                Map.entry("bob-savings-ledger-validation", List.of(1, 58)),
                Map.entry("canara-savings-ledger-validation", List.of(1, 60)),
                // 224 before P-001 Fix B, 223 after. Not a Fix A regression: Fix B merges this
                // document's second header band into its header instead of letting it stand as the
                // table's first data row, which is the one row that went. See
                // WrappedHeaderOnAScoringLinePdfTableLocatorTest. Every other entry here is
                // untouched by both fixes.
                Map.entry("central-bank-savings-ledger-validation", List.of(1, 223)),
                Map.entry("hdfc-composite-deposit-schedules", List.of(4, 84, 9, 2, 7)),
                // 2 sections before looksLikePaymentSummaryPanel, 1 after -- same panel shape.
                Map.entry("hdfc-credit-card-ledger-validation", List.of(1, 4)),
                Map.entry("hdfc-txn-date-narration-header", List.of(1, 5)),
                Map.entry("hsbc-savings-ledger-validation", List.of(1, 2)),
                // icici, kotak, sbi: post P-002 Fix 2 (commit pending). Each document's spurious
                // prose-header sections (a fee/EMI paragraph misread as a header) no longer open a
                // section; every genuine section here is the same section, with the same rows, it
                // always was. See HeaderProseRejectionTest for the full before/after and the
                // pollution checks proving the rejected prose didn't leak into these rows.
                Map.entry("icici-credit-card-statement", List.of(1, 6)),
                // 2 before Phase 2E.5's leading-narration fix, 12 after -- the fix now attaches the
                // narration-only line right under the header to the transaction it belongs to instead
                // of swallowing it as a false-header prose block. All 11 real transactions verified via
                // BALANCE_CHAIN and STATEMENT_TOTALS with zero discrepancies; the 12th row is the
                // pre-existing flushPendingLeading mechanism carrying page-2 glossary content, not new
                // behavior. See LeadingNarrationBeforeFirstAnchorPdfTableLocatorTest.
                Map.entry("icici-savings-ledger-validation", List.of(1, 12)),
                Map.entry("kotak-credit-card-ledger-validation", List.of(0)),
                Map.entry("kotak-savings-ledger-validation", List.of(1, 2)),
                Map.entry("pnb-savings-ledger-validation", List.of(1, 62)),
                // 4 sections before looksLikePaymentSummaryPanel, 3 after -- one of the four was
                // itself a payment-summary panel (see this file's other tests for the detail).
                Map.entry("sbi-credit-card-statement", List.of(3, 1, 2, 2)),
                Map.entry("union-bank-savings-ledger-validation", List.of(1, 20)));

        for (Map.Entry<String, List<Integer>> e : expected.entrySet()) {
            PdfTableLocator.LocatedDocument doc =
                    new PdfTableLocator().locateAll(PdfTrace.load(e.getKey()), null);
            List<Integer> shape = new ArrayList<>();
            shape.add(doc.sections().size());
            doc.sections().forEach(s -> shape.add(s.rows().size()));
            assertThat(shape).as("%s: sections and rows per section", e.getKey())
                    .isEqualTo(e.getValue());
        }
    }
}
