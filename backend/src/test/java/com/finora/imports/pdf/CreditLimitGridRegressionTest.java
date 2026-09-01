package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import com.finora.imports.pdf.fixtures.PdfTrace;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Captured from four real credit-card statements (Axis, SBI, IndusInd, HDFC) whose "Payment Summary"
 * grid scrambles the "Credit Limit" label away from its own value once joined into
 * {@link PdfMetadataExtractor}'s line-based {@code preTableLines} view -- see
 * {@link CreditLimitGridExtractor}'s own doc comment.
 *
 * <p>Unlike {@link PaymentDueDateGridRegressionTest}, none of these three traces redact the credit
 * limit value itself -- so every test here proves the real recovered VALUE, not just that the
 * capability fires.
 */
class CreditLimitGridRegressionTest {

    @Test
    void extract_readsTheRealAxisCreditLimit() {
        DocumentContext ctx = new DocumentContext("PDF", "test");
        BigDecimal creditLimit = CreditLimitGridExtractor.extract(
                PdfTrace.load("axis-credit-card-statement"), ctx);

        assertThat(creditLimit).isEqualByComparingTo(new BigDecimal("219000.00"));
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("PRINTED_CREDIT_LIMIT_GRID");
    }

    @Test
    void extract_readsTheRealSbiCreditLimit() {
        DocumentContext ctx = new DocumentContext("PDF", "test");
        BigDecimal creditLimit = CreditLimitGridExtractor.extract(
                PdfTrace.load("sbi-credit-card-statement"), ctx);

        assertThat(creditLimit).isEqualByComparingTo(new BigDecimal("100000.00"));
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("PRINTED_CREDIT_LIMIT_GRID");
    }

    // The real evidence for CreditLimitGridExtractor.valueBelow's own doc comment: this committed
    // trace independently reproduces the exact misleading shape found on the real document -- a
    // stray "Credit" run from an unrelated sidebar sits 5.28pt below the label row, immediately
    // followed by "1,491.00" (a different field's own value, NOT the credit limit) 1.68pt further
    // down, with the true value "2,19,000.00" one more row down still (10.6pt below the label,
    // still well inside MAX_ROW_GAP). Proves the x-overlap search does not settle for the nearer,
    // wrong-column candidate.
    @Test
    void extract_looksPastTheMisleadingNearerValue_onTheRealInduslandTrace() {
        DocumentContext ctx = new DocumentContext("PDF", "test");
        BigDecimal creditLimit = CreditLimitGridExtractor.extract(
                PdfTrace.load("indusland-credit-card-account-number-inheritance"), ctx);

        assertThat(creditLimit).isEqualByComparingTo(new BigDecimal("219000.00"));
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("PRINTED_CREDIT_LIMIT_GRID");
    }

    // HDFC's own committed trace has the "TOTAL CREDIT LIMIT" label and the intervening
    // "(Including Cash)" row -- the exact shape that needed both fixes (the "Total "-prefixed
    // label, and skipping an x-overlapping-but-non-numeric row) -- but redacts the credit limit
    // VALUE itself ("X99,999", width 0.0), so this cannot prove the recovered VALUE. What it proves
    // instead: this real document's own label IS recognized, and the extractor correctly declines
    // on the redacted text near it rather than guessing.
    @Test
    void extract_returnsNull_onTheRealRedactedHdfcTrace() {
        List<PositionedText> runs = PdfTrace.load("hdfc-credit-card-ledger-validation");
        boolean labelPresent = runs.stream()
                .anyMatch(t -> t.text().trim().equalsIgnoreCase("Total Credit Limit"));
        assertThat(labelPresent).as("'Total Credit Limit' label must be present in the real trace").isTrue();

        DocumentContext ctx = new DocumentContext("PDF", "test");
        assertThat(CreditLimitGridExtractor.extract(runs, ctx)).isNull();
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .doesNotContain("PRINTED_CREDIT_LIMIT_GRID");
    }
}
