package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import com.finora.imports.pdf.fixtures.PdfTrace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Captured from the same real ICICI CC.pdf {@link AccountNumberTransactionHeaderExtractor}'s own
 * doc comment describes: its unlabeled account number, printed directly under the transaction
 * table's "Date" column header.
 *
 * <p><b>Cannot prove the recovered VALUE from this committed trace.</b> The same redaction
 * limitation already documented for the Axis trace in {@code AccountNumberGridRegressionTest}
 * (and, before that, the SBI trace in {@code PaymentDueDateGridRegressionTest}) applies here too:
 * the redactor does not recompute rendered width for replaced text, so the value cell's {@code
 * width} comes back {@code 0.0} on this trace -- which breaks the x-overlap match {@code
 * StatementSummaryExtractor#valueUnder} uses to find the value cell aligned under the "Date"
 * header cell. This is a redaction-only artifact, confirmed via direct {@code
 * scripts/corpus-run.py} verification against the real, un-redacted PDF that the same extractor
 * correctly recovers the real masked number end to end (see {@code
 * AccountNumberTransactionHeaderExtractorTest}'s own real-coordinate unit tests for the isolated
 * mechanism, and this fix's PR description for the corpus-run.py evidence).
 *
 * <p>What this test proves instead: real geometry from the actual document, once redacted the same
 * way any real document's PII would be, correctly produces NO value rather than fabricating one
 * from a cell whose overlap can no longer be measured.
 */
class AccountNumberTransactionHeaderRegressionTest {

    @Test
    void declinesOnTheRedactedTraceRatherThanFabricatingFromAnUnmeasurableOverlap() {
        List<PositionedText> runs = PdfTrace.load("icici-credit-card-account-number-above-transactions");
        DocumentContext ctx = new DocumentContext("PDF", "test");

        assertThat(AccountNumberTransactionHeaderExtractor.extract(runs, ctx)).isNull();
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .doesNotContain("PRINTED_ACCOUNT_NUMBER_ABOVE_TRANSACTIONS");
    }
}
