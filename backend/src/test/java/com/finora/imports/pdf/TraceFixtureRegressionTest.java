package com.finora.imports.pdf;

import com.finora.imports.CsvParser;
import com.finora.imports.DocumentContext;
import com.finora.imports.pdf.fixtures.PdfTrace;
import com.finora.util.BankRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests driven by traces captured from REAL statements and redacted before being
 * committed (see PdfTrace / PdfTraceRedactor, and PdfPipelineDiagnostic#captureRedactedTrace).
 *
 * These complement rather than replace the synthetic fixtures in PdfFixtureBuilder. A synthetic
 * fixture states what we BELIEVE a layout looks like; a trace records what a bank actually emitted,
 * including the fragmentation, the stray runs and the coordinate quirks nobody would think to
 * invent. Every bug fixed against a real document should leave one of these behind, so the document
 * that broke the engine can never break it silently again.
 *
 * The pipeline is driven from PdfTableLocator down rather than from bytes, because a trace IS the
 * output of PdfTextExtractor -- replaying from there is what makes the fixture independent of
 * PDFBox and of the original file.
 */
class TraceFixtureRegressionTest {

    /**
     * Captured from an HDFC savings statement that extracted ZERO transactions before the header
     * detection fix: its "Txn Date" / "Narration" / "Withdrawals" / "Deposits" / "Closing Balance"
     * header matched none of the hints the locator recognised, so no table was found at all and
     * the import silently succeeded with nothing in it.
     */
    private static final String HDFC_TRACE = "hdfc-txn-date-narration-header";

    @Test
    void theHdfcStatementThatExtractedNothing_nowYieldsExactlyOneTableWithARecognizedHeader() {
        DocumentContext ctx = new DocumentContext("PDF", "TraceFixtureRegressionTest");

        PdfTableLocator.LocatedDocument doc =
                new PdfTableLocator().locateAll(PdfTrace.load(HDFC_TRACE), ctx);

        assertThat(doc.sections())
                .as("one savings account, not zero (no table found) and not several")
                .hasSize(1);
        assertThat(doc.sections().get(0).rows()).isNotEmpty();
    }

    @Test
    void everyTransactionInThatStatement_parsesItsDate() {
        // The failure this guards is subtler than "no rows": a row whose date cell holds wrapped
        // narration instead of a date is a row that will be dropped later, silently. Asserting the
        // dates parse is asserting the columns are aligned.
        List<Map<String, String>> rows = new PdfTableLocator()
                .locateAll(PdfTrace.load(HDFC_TRACE), null).sections().get(0).rows();

        List<String> datesFound = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String raw = CsvParser.firstNonBlank(row, "txn date", "date", "value dt");
            if (raw != null && !raw.isBlank() && CsvParser.parseDate(raw.trim()) != null) {
                datesFound.add(raw.trim());
            }
        }

        assertThat(datesFound)
                .as("the statement's own SUMMARY block reports 3 debits and 1 credit")
                .hasSize(4);
    }

    @Test
    void theBankIsIdentifiedFromTheStatementsOwnLabelledIfsc_notFromTheFilename() {
        // The trace keeps each IFSC's 4-letter bank prefix and masks only the branch code, so this
        // exercises the real detection path against a real document's real letterhead layout while
        // the branch itself never enters the repository.
        List<String> auxiliaryText = new PdfTableLocator()
                .locateAll(PdfTrace.load(HDFC_TRACE), null).sections().get(0).auxiliaryText();

        BankRegistry.BankInfo bank = BankRegistry.detect("statement.pdf", auxiliaryText);

        assertThat(bank.id()).isEqualTo("HDFC");
    }

    /**
     * Captured from the Bank of Baroda statement that was split into THREE accounts, because its
     * per-page "Savings Account ... &lt;number&gt;" banner opened a new section every time it was
     * reprinted.
     */
    private static final String BOB_TRACE = "bob-repeated-account-banner";

    @Test
    void aBannerReprintedOnEveryPage_isOneAccountAndNotThree() {
        DocumentContext ctx = new DocumentContext("PDF", "TraceFixtureRegressionTest");

        PdfTableLocator.LocatedDocument doc =
                new PdfTableLocator().locateAll(PdfTrace.load(BOB_TRACE), ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(ctx.capabilities()).extracting("capability").contains("REPEATED_ACCOUNT_BANNER");
    }

    @Test
    void thatSameStatementKeepsItsWholeMonthOfTransactions() {
        // The account split did not merely mislabel things -- it broke the balance chain, because
        // each fabricated account started from whatever row happened to follow its banner.
        List<Map<String, String>> rows = new PdfTableLocator()
                .locateAll(PdfTrace.load(BOB_TRACE), null).sections().get(0).rows();

        assertThat(rows.size()).isGreaterThanOrEqualTo(50);
    }

    /**
     * Captured from an HDFC COMBINED statement: a savings account plus a term-deposit summary plus
     * a recurring-deposit installment schedule, three genuine tables in one document.
     */
    private static final String HDFC_COMBINED_TRACE = "hdfc-composite-deposit-schedules";

    @Test
    void aCombinedStatementsSavingsTransactionsAreExtractedAlongsideItsDepositTables() {
        PdfTableLocator.LocatedDocument doc =
                new PdfTableLocator().locateAll(PdfTrace.load(HDFC_COMBINED_TRACE), null);

        // Four tables, not three. The fourth is this statement's fixed-deposit schedule, and it
        // was not "classified wrongly" before WRAPPED_HEADER -- it was not located AT ALL, because
        // its heading is printed across two visual lines and neither line is a header on its own
        // (see PdfTableLocator.HEADER_WRAP_MAX_GAP). Nine deposits were invisible while the import
        // reported success. What each table IS (savings / term deposit / recurring deposit) is the
        // job of the product-classification stage; this test pins the extraction it is built on.
        assertThat(doc.sections()).hasSize(4);

        // Counted by date-anchored rows rather than by total rows. This assertion used to read
        // "more than 100 rows", and 52 of those were not transactions: this statement wraps each
        // narration onto a third line that exceeded MAX_TRAILING_CONTINUATION_ROWS and was emitted
        // as its own orphan row ({Txn Date=Xxxxxx Value Dt 01/06/2026 Ref 999999999999}). Once
        // BLOCK_PITCH_TOLERANCE let those lines rejoin the transactions they belong to, the total
        // fell to 84 while the transaction count did not move -- so the total was measuring how
        // badly the document was being split, and a threshold on it would have read that repair as
        // a regression. The number of real transactions is the property this test means.
        long transactions = doc.sections().get(0).rows().stream()
                .map(row -> CsvParser.firstNonBlank(row, "txn date"))
                .filter(date -> date != null && CsvParser.parseDate(date.trim()) != null)
                .count();
        assertThat(transactions)
                .as("the savings account's own transaction table")
                .isGreaterThan(70);
    }

    @Test
    void theFixedDepositScheduleIsLocated_withItsHeadingReadAcrossBothLines() {
        DocumentContext ctx = new DocumentContext("PDF", "TraceFixtureRegressionTest");

        PdfTableLocator.LocatedDocument doc =
                new PdfTableLocator().locateAll(PdfTrace.load(HDFC_COMBINED_TRACE), ctx);

        assertThat(ctx.capabilities()).extracting("capability").contains("WRAPPED_HEADER");

        // Asserted on the COLUMN NAMES rather than on a row count, because the names are what
        // prove the two heading lines were joined the right way round and on the right x. Each one
        // below is a cell from the upper line followed by the cell printed beneath it -- "FD" over
        // "Number", "Open/Value" over "Date". Recognising only the lower line (the shape of the
        // original bug) would name this column "Date" and anchor it 3.11pt to the left; joining on
        // the wrong neighbour would pair "Date" with the wrong word entirely.
        //
        // Several names are still partly masked: the redactor's allowlist had no deposit
        // vocabulary when this trace was captured, so "Principal", "Maturity" and "Rate Of
        // Interest" were replaced with same-length filler. That limits what this document can be
        // asked about its CONTENT -- see FinancialProductClassifierTest for the same limitation --
        // but not what it proves about STRUCTURE, which is geometry and survives redaction intact.
        List<String> columns = List.copyOf(doc.sections().get(1).rows().stream()
                .flatMap(row -> row.keySet().stream()).distinct().toList());
        assertThat(columns).contains("FD Number", "FD CCY", "Open/Xxxx Xxxxx Date", "Nomination Registered");

        long dated = doc.sections().get(1).rows().stream()
                .map(row -> row.get("Open/Xxxx Xxxxx Date"))
                .filter(date -> date != null && CsvParser.parseDate(date.trim()) != null)
                .count();
        assertThat(dated)
                .as("deposits that now anchor on their own date, where the whole table used to "
                        + "collapse into a single unparseable row")
                .isGreaterThanOrEqualTo(5);
    }

    @Test
    void theRecurringDepositInstallmentScheduleKeepsEveryInstallment() {
        // The same fix reaches a second table in this document. This schedule's heading is also
        // wrapped ("Instalment" over "Number", "Instalment Amt" over "Due"), and every installment
        // after the first used to merge into one row for the same reason: no row could anchor,
        // because the date column was named "Xxxxxxx. Due Date" and the anchor check compared that
        // whole string against "date". See PdfTableLocator.hasDateValue.
        List<Map<String, String>> rows = new PdfTableLocator()
                .locateAll(PdfTrace.load(HDFC_COMBINED_TRACE), null).sections().get(3).rows();

        long installments = rows.stream()
                .map(row -> CsvParser.firstNonBlank(row, "due date"))
                .filter(date -> date != null && CsvParser.parseDate(date.trim()) != null)
                .count();
        assertThat(installments)
                .as("the statement prints six paid installments in this schedule")
                .isEqualTo(6);
    }

    @Test
    void theCommittedTraceContainsNoUnredactedPersonalData() {
        // A privacy control with no test is a privacy control that quietly stops working. This
        // asserts the invariant on the committed artifact itself rather than on the redactor, so it
        // fails if anyone hand-edits a trace or commits one captured with redaction disabled.
        String text = java.util.stream.Stream.of(HDFC_TRACE, BOB_TRACE, HDFC_COMBINED_TRACE)
                .flatMap(name -> PdfTrace.load(name).stream())
                .map(PositionedText::text).reduce("", (a, b) -> a + "\n" + b);

        // A masked email is entirely 'x'/'X'/'9', so the test for an UNREDACTED one is the presence
        // of any letter that is not the mask character -- matching [a-z]{3} would happily accept
        // "xxx" and pass on a fully redacted address, which is the bug this comment exists to stop
        // someone reintroducing.
        assertThat(text)
                .as("email addresses must be masked")
                .doesNotContainPattern("(?i)[a-z0-9._%+-]*[a-wyz][a-z0-9._%+-]*@[a-z0-9.-]+\\.[a-z]{2,}");
        assertThat(text)
                .as("account and reference numbers must be masked to 9s")
                .doesNotContainPattern("(?<![0-9])(?!9{8,})[0-9]{8,}");
        assertThat(text)
                .as("an IFSC keeps its bank prefix but never its branch code")
                .doesNotContainPattern("\\b[A-Z]{4}0(?!XXXXXX)[A-Z0-9]{6}\\b");
    }
}
