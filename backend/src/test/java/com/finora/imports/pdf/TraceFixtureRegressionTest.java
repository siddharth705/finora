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

        // All three tables are correctly LOCATED -- that part was never the bug. What each one IS
        // (savings / term deposit / recurring deposit) is the job of the product-classification
        // stage; this test pins the extraction it will be built on top of.
        assertThat(doc.sections()).hasSize(3);
        assertThat(doc.sections().get(0).rows())
                .as("the savings account's own transaction table")
                .hasSizeGreaterThan(100);
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
