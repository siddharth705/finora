package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import com.finora.imports.pdf.fixtures.PdfTrace;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Captured from two real credit-card statements (Axis, SBI) whose "Payment Summary" grid gets
 * scrambled once joined into {@link PdfMetadataExtractor}'s line-based {@code preTableLines} view
 * -- see {@link PaymentDueDateGridExtractor}'s own doc comment.
 */
class PaymentDueDateGridRegressionTest {

    // Axis's committed trace keeps its dates unredacted (captured for the pre-existing
    // TransactionTableDateRangeExtractor/GRID_DUE_DATE_LABEL evidence, whose own requiredHeaders
    // already lists "Payment Due Date") -- proves the real recovered VALUE, not just the shape.
    @Test
    void extract_readsTheRealAxisPaymentDueDate() {
        DocumentContext ctx = new DocumentContext("PDF", "test");
        LocalDate date = PaymentDueDateGridExtractor.extract(
                PdfTrace.load("axis-credit-card-statement"), ctx);

        assertThat(date).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("PRINTED_PAYMENT_DUE_DATE_GRID");
    }

    // SBI's committed trace has its due-date VALUE redacted ("99 Xxx 9999", the same policy every
    // date-shaped value elsewhere in this trace is under) -- so this cannot prove the recovered
    // date VALUE. What it proves instead: the real document's own "Payment Due Date" label row IS
    // present, and the extractor correctly declines to fabricate a value from the redacted text
    // that sits near it, rather than guessing.
    //
    // Note: this trace's redacted date entry also carries width=0.0 (the redactor does not
    // recompute a rendered width for text it has replaced), which independently makes
    // StatementSummaryExtractor.valueUnder's x-overlap match fail on this specific fixture even
    // before date-parsing would -- both are correct, fail-safe outcomes on redacted data, not a
    // real document's own behaviour (the direct PositionedText diagnostic this extractor's own
    // doc comment cites confirms real widths align cleanly on the actual PDF).
    @Test
    void extract_returnsNull_onTheRealRedactedSbiTrace() {
        List<PositionedText> runs = PdfTrace.load("sbi-credit-card-statement");
        boolean labelPresent = runs.stream()
                .anyMatch(t -> t.text().trim().equalsIgnoreCase("Payment Due Date"));
        assertThat(labelPresent).as("'Payment Due Date' label must be present in the real trace").isTrue();

        DocumentContext ctx = new DocumentContext("PDF", "test");
        assertThat(PaymentDueDateGridExtractor.extract(runs, ctx)).isNull();
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .doesNotContain("PRINTED_PAYMENT_DUE_DATE_GRID");
    }

    // HDFC's own committed trace has both the label AND the intervening "(Including Cash)" row --
    // the shape that needed the forward-scan fix -- but redacts the due-date VALUE itself (width
    // 0.0, same redaction policy as SBI's own trace above), so this cannot prove the recovered date
    // VALUE. What it proves instead: this real document's own "DUE DATE" label (distinct from the
    // "Payment Due Date" spelling Axis/SBI use) IS recognized, and the extractor correctly declines
    // on the redacted text near it rather than guessing.
    @Test
    void extract_returnsNull_onTheRealRedactedHdfcTrace() {
        List<PositionedText> runs = PdfTrace.load("hdfc-credit-card-ledger-validation");
        boolean labelPresent = runs.stream().anyMatch(t -> t.text().trim().equalsIgnoreCase("Due Date"));
        assertThat(labelPresent).as("'Due Date' label must be present in the real trace").isTrue();

        DocumentContext ctx = new DocumentContext("PDF", "test");
        assertThat(PaymentDueDateGridExtractor.extract(runs, ctx)).isNull();
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .doesNotContain("PRINTED_PAYMENT_DUE_DATE_GRID");
    }
}
