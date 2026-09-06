package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import com.finora.imports.pdf.fixtures.PdfTrace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Captured from the same real Axis credit-card statement {@link PaymentDueDateGridRegressionTest}
 * uses -- its own "Credit Card Number" field is scrambled the same way its "Payment Due Date" field
 * is (see {@link AccountNumberGridExtractor}'s own doc comment).
 *
 * <p><b>Cannot prove the recovered VALUE from this committed trace.</b> Two independent redaction
 * artifacts collide on this specific field: the value cells carry {@code width=0.0} (the redactor
 * does not recompute rendered width for replaced text, the same limitation already documented for
 * the SBI trace in {@code PaymentDueDateGridRegressionTest}), which breaks the GRID strategy's
 * x-overlap match; and the same-row strategy's candidate is genuinely ambiguous on this trace --
 * the redacted cardholder name sitting further along the same row ("XXXXXXXXX XXXXXX") is now
 * indistinguishable in shape from a masked card number, so the "exactly one candidate" safety
 * check correctly refuses rather than guessing which of the two is the real value. Both are
 * redaction-only artifacts: confirmed via direct {@code scripts/corpus-run.py} verification against
 * the real, un-redacted PDF that the SAME two strategies correctly recover the real masked number
 * end to end (see AccountNumberGridExtractorTest's own real-coordinate unit tests for the isolated
 * mechanism, and this fix's PR description for the corpus-run.py evidence).
 *
 * <p>What this test proves instead: real geometry from the actual document, once redacted the same
 * way any real document's PII would be, correctly produces NO value rather than fabricating one
 * from an ambiguous or malformed candidate.
 */
class AccountNumberGridRegressionTest {

    @Test
    void declinesOnTheRedactedTraceRatherThanFabricatingOrGuessingAmbiguously() {
        List<PositionedText> runs = PdfTrace.load("axis-credit-card-statement");
        DocumentContext ctx = new DocumentContext("PDF", "test");

        assertThat(AccountNumberGridExtractor.extract(runs, ctx)).isNull();
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .doesNotContain("PRINTED_ACCOUNT_NUMBER_GRID");
    }
}
