package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import com.finora.imports.pdf.fixtures.PdfTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Captured from a real Kotak Mahindra Bank SAVINGS statement whose page 1 prints its statement
 * period as a bare, unlabeled date range directly beneath its own "Account Statement" title --
 * see StatementTitleDateRangeExtractor's own doc comment for why this needs a dedicated,
 * narrowly-scoped fallback rather than a generic bare-date-range pattern.
 *
 * <p>This trace's date text is redacted ("99 Xxx 9999 - 99 Xxx 9999", the same digit/letter
 * redaction every transaction date in this trace is under), so it cannot prove the real recovered
 * date VALUE -- {@link StatementTitleDateRangeExtractorTest#extract_readsTheRealKotakSavingsTitleShape}
 * does that, using the real unredacted date string at these exact coordinates, confirmed via direct
 * PositionedText inspection of the original PDF. What THIS test proves instead: the real document's
 * own geometry -- the title row and its immediately-adjacent, left-aligned, date-shaped row -- is
 * exactly the precondition the extractor keys off, independent of the extractor's own
 * implementation (re-derived here from the raw trace rather than by calling into
 * StatementTitleDateRangeExtractor), so a future PDFBox/table-locator change that silently altered
 * this document's real row grouping would fail this test even if the extractor's own logic never
 * changed.
 */
class KotakSavingsTitleDateRangeRegressionTest {

    private static final String TRACE = "kotak-savings-ledger-validation";
    private static final String TITLE_TEXT = "account statement";

    // Deliberately re-derived rather than reusing StatementTitleDateRangeExtractor.BARE_DATE_RANGE
    // (private) -- this test independently confirms the real trace's raw geometry has the shape the
    // extractor relies on, not that the extractor correctly recognizes its own fixture.
    private static final Pattern DATE_RANGE_SHAPE = Pattern.compile(
            "^\\S{1,2}\\s+\\S{3,9}\\s+\\S{4}\\s*-\\s*\\S{1,2}\\s+\\S{3,9}\\s+\\S{4}$");

    @Test
    void theRealDocumentPrintsTheTitleImmediatelyAboveADateShapedRow_leftAlignedWithASmallGap() {
        List<PositionedText> runs = PdfTrace.load(TRACE);
        List<List<PositionedText>> rows = StatementSummaryExtractor.groupIntoRows(runs);

        int titleIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (joined(rows.get(i)).equalsIgnoreCase(TITLE_TEXT)) {
                titleIndex = i;
                break;
            }
        }
        assertThat(titleIndex).as("title row 'Account Statement' must be present in the real trace").isGreaterThanOrEqualTo(0);

        List<PositionedText> titleRow = rows.get(titleIndex);
        List<PositionedText> dateRow = StatementSummaryExtractor.rowBelow(rows, titleIndex, 40.0f);
        assertThat(dateRow).as("a row must sit directly below the title, within the extractor's gap tolerance").isNotNull();

        assertThat(Math.abs(dateRow.get(0).x() - titleRow.get(0).x()))
                .as("the row below the title must be left-aligned with it")
                .isLessThanOrEqualTo(3.0f);
        assertThat(joined(dateRow))
                .as("the row below the title must be shaped like a bare date range")
                .matches(DATE_RANGE_SHAPE);

        // Confirms the extractor itself correctly declines rather than fabricating a value from the
        // redacted month token -- see the class doc comment.
        var range = StatementTitleDateRangeExtractor.extract(runs, new DocumentContext("PDF", "test"));
        assertThat(range).isSameAs(StatementTitleDateRangeExtractor.PrintedDateRange.NONE);
    }

    private static String joined(List<PositionedText> row) {
        StringBuilder line = new StringBuilder();
        for (PositionedText t : row) {
            if (!line.isEmpty()) line.append(' ');
            line.append(t.text());
        }
        return line.toString().trim();
    }
}
