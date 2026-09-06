package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import com.finora.imports.pdf.fixtures.PdfTrace;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Captured from the real Axis credit-card statement whose "Payment Summary" grid glues "Statement
 * Period" to its left-hand neighbour -- see {@link StatementPeriodGridExtractor}'s own doc comment.
 */
class StatementPeriodGridRegressionTest {

    // Axis's committed trace keeps its statement-period dates unredacted (the same trace
    // PaymentDueDateGridRegressionTest already uses to prove its own recovered VALUE) -- proves
    // the real recovered range end to end, not just that the label is present.
    @Test
    void extract_readsTheRealAxisStatementPeriod() {
        DocumentContext ctx = new DocumentContext("PDF", "test");
        StatementPeriodGridExtractor.PrintedDateRange range = StatementPeriodGridExtractor.extract(
                PdfTrace.load("axis-credit-card-statement"), ctx);

        assertThat(range.start()).isEqualTo(LocalDate.of(2026, 6, 24));
        assertThat(range.end()).isEqualTo(LocalDate.of(2026, 7, 22));
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("PRINTED_STATEMENT_PERIOD_GRID");
    }
}
