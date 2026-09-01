package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import com.finora.imports.pdf.fixtures.PdfTrace;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single-cell exception to {@code refinesRatherThanRedefines}' Gate 1, real-document
 * regression: {@code scb-savings-single-cell-header-rename}, a real Standard Chartered savings
 * statement. A bare "Date" wraps under "Value" -- one lower cell, which the strict floor would
 * otherwise refuse as a footnote -- and is admitted anyway because its ENTIRE text exactly
 * equals a recognized column-name word. See {@link WrappedHeaderOnAScoringLinePdfTableLocatorTest}
 * for the synthetic version of this same exception and its own doc comment for the full
 * reasoning (the gate's own doc comment, on {@code PdfTableLocator}, has the real-document trace).
 *
 * <p>Redaction destroys this real document's OTHER defect (month-first yearless dates -- "May" is
 * masked to "Xxx", which no longer matches {@code WEAK_MONTH_DAY}), so that mechanism -- and this
 * capability's real payoff on the ROW-anchor gate, not just the header's own name -- is covered
 * separately by {@link MonthFirstYearlessDatePdfTableLocatorTest}, a synthetic fixture built with
 * real, unredacted coordinates and values.
 */
class SingleCellHeaderRenameRealCorpusRegressionTest {

    private static final String TRACE = "scb-savings-single-cell-header-rename";

    @Test
    void theRealDocumentsBareDateCellRenamesValueToValueDate() {
        DocumentContext ctx = new DocumentContext("PDF", "SingleCellHeaderRenameRealCorpusRegressionTest");

        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(PdfTrace.load(TRACE), ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(ctx.buildMetadata().headers())
                .as("\"Value\" alone names nothing TransactionNormalizer knows; \"Value Date\" is in its DATE_HINTS")
                .contains("Value Date")
                .doesNotContain("Value");
        assertThat(ctx.capabilities()).extracting("capability").contains("WRAPPED_HEADER");
    }
}
