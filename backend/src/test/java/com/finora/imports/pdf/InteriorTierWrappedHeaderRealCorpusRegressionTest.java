package com.finora.imports.pdf;

import com.finora.imports.CsvParser;
import com.finora.imports.DocumentContext;
import com.finora.imports.pdf.fixtures.PdfTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTERIOR_TIER_COLUMNS, real-document regression: {@code iob-savings-interior-tier-header}, a
 * real third-party-generated Indian Overseas Bank savings statement. See {@link
 * InteriorTierWrappedHeaderPdfTableLocatorTest} for the synthetic reduction of this same shape
 * and the mechanism's own doc comment ({@code PdfTableLocator.mergeHeaderLinesAdmittingInteriorTierColumns}).
 * This test proves the real document, not just the shape: before this fix, this exact trace
 * located a table with 2 garbled columns and staged zero transaction rows while the import
 * reported success.
 */
class InteriorTierWrappedHeaderRealCorpusRegressionTest {

    private static final String TRACE = "iob-savings-interior-tier-header";

    private PdfTableLocator.LocatedDocument locate(DocumentContext ctx) {
        return new PdfTableLocator().locateAll(PdfTrace.load(TRACE), ctx);
    }

    @Test
    void theRealDocumentsThreeLineHeaderMergesIntoSevenColumns() {
        DocumentContext ctx = new DocumentContext("PDF", "InteriorTierWrappedHeaderRealCorpusRegressionTest");

        PdfTableLocator.LocatedDocument doc = locate(ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(ctx.buildMetadata().headers()).containsExactlyInAnyOrder(
                "Date(Value Date)", "Ref No. /Cheque No", "Transaction Type",
                "Particulars", "Debit(Rs)", "Credit(Rs)", "Balance(Rs)");
        assertThat(ctx.capabilities()).extracting("capability")
                .contains("WRAPPED_HEADER", "WRAPPED_HEADER_INTERIOR_TIER_COLUMNS");
    }

    @Test
    void everyRealTransactionStagesWithAParseableDate() {
        List<Map<String, String>> rows = locate(null).sections().get(0).rows();

        // Before this fix: 0 rows staged, with the import reporting success. The document has
        // 15 real transactions plus one dateless closing-summary line the locator also bucketed.
        // "Date(Value Date)" normalizes to "date" -- CsvParser.normalizeHeaderCell strips the
        // trailing parenthetical, same as it does for the real Central Bank of India header this
        // class's own sibling test (WrappedHeaderOnAScoringLinePdfTableLocatorTest) reads by "date".
        long dated = rows.stream()
                .map(row -> CsvParser.firstNonBlank(row, "date"))
                .filter(date -> date != null && CsvParser.parseDate(date.trim()) != null)
                .count();
        assertThat(dated).as("the real document's own 15 transactions, where before the fix there were none")
                .isEqualTo(15);
    }
}
