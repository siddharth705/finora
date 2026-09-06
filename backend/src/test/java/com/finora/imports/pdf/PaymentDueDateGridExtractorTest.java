package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentDueDateGridExtractorTest {

    private static PositionedText run(String text, float x, float endX, float y) {
        return new PositionedText(text, x, y, 0, endX - x);
    }

    // Coordinates copied verbatim from a direct PositionedText inspection of the real Axis
    // credit.pdf's "Payment Summary" grid (label row y=224.00, value row y=236.50-237.50):
    // "Payment Due Date" at x=381.00-443.01, its value "11/08/2026" at x=393.00-431.01 (fully
    // inside the label's own x-span) directly below it. Other same-row values (the statement
    // period's two halves, the statement generation date, the two money figures) are present at
    // their own real x positions specifically to prove the column match picks the right one, not
    // just the only one.
    @Test
    void extract_readsTheRealAxisPaymentSummaryGrid() {
        var runs = List.of(
                run("Total Payment Due", 51.50f, 114.50f, 224.00f),
                run("Minimum Payment Due Statement Period", 151.50f, 329.51f, 224.00f),
                run("Payment Due Date", 381.00f, 443.01f, 224.00f),
                run("Statement Generation Date", 472.00f, 564.00f, 224.00f),
                run("24/06/2026 - 22/07/2026", 259.50f, 341.51f, 236.50f),
                run("11/08/2026", 393.00f, 431.01f, 236.50f),
                run("22/07/2026", 510.00f, 548.01f, 236.50f),
                run("27,665.16   Dr", 60.00f, 105.99f, 237.50f),
                run("577.00   Dr", 173.00f, 208.99f, 237.50f));

        assertThat(PaymentDueDateGridExtractor.extract(runs)).isEqualTo(LocalDate.of(2026, 8, 11));
    }

    // Coordinates copied verbatim from a direct PositionedText inspection of the real SBI Credit
    // Card.PDF's own grid (label row y=233.05, value row y=248.00-248.79): "Payment Due Date" at
    // x=329.03-394.56, its value "27 Aug 2026" at x=331.12-375.42 directly below it. Proves the
    // extractor also handles a spelled-month value, not just the slash-numeric shape Axis uses.
    @Test
    void extract_readsTheRealSbiPaymentSummaryGrid() {
        var runs = List.of(
                run("Available Credit Limit", 45.21f, 121.03f, 233.05f),
                run("Available Cash Limit", 186.39f, 257.75f, 233.05f),
                run("Payment Due Date", 329.03f, 394.56f, 233.05f),
                run("26,089.88", 66.77f, 102.26f, 248.00f),
                run("27 Aug 2026", 331.12f, 375.42f, 248.00f),
                run("10,000.00", 196.29f, 231.78f, 248.79f));

        assertThat(PaymentDueDateGridExtractor.extract(runs)).isEqualTo(LocalDate.of(2026, 8, 27));
    }

    // Coordinates copied verbatim from a direct PositionedText inspection of the real HDFC
    // credit.pdf (previously entirely out of scope): "DUE DATE" (not "Payment Due Date") at
    // y=279.9, x=512.1-541.6; its value "09 Aug, 2026" at y=297.7, x=512.1-555.0, 17.8pt below.
    // "(Including Cash)" -- part of the same panel's unrelated credit-limit column -- sits in an
    // intervening row bucket (y=282.4) but does not x-overlap this label's own column, unlike its
    // effect on CreditLimitGridExtractor's own label. "MINIMUM DUE" and its value present at their
    // real positions to prove the column match picks the right one.
    @Test
    void extract_readsTheRealHdfcPaymentSummaryGrid() {
        var runs = List.of(
                run("MINIMUM DUE", 445.9f, 491.2f, 279.9f),
                run("DUE DATE", 512.1f, 541.6f, 279.9f),
                run("(Including Cash)", 71.0f, 117.2f, 282.4f),
                run("C200.00", 445.9f, 472.3f, 297.7f),
                run("09 Aug, 2026", 512.1f, 555.0f, 297.7f));

        assertThat(PaymentDueDateGridExtractor.extract(runs)).isEqualTo(LocalDate.of(2026, 8, 9));
    }

    @Test
    void extract_returnsNull_whenNoDueDateLabelRowExists() {
        var runs = List.of(
                run("Credit Limit", 45.21f, 121.03f, 233.05f),
                run("100,000.00", 45.21f, 100.00f, 248.00f));

        assertThat(PaymentDueDateGridExtractor.extract(runs)).isNull();
    }

    @Test
    void extract_returnsNull_whenTheColumnUnderTheLabelIsNotDateShaped() {
        // A value physically under the label, but not a date -- must not be coerced into one.
        var runs = List.of(
                run("Payment Due Date", 381.00f, 443.01f, 224.00f),
                run("N/A", 393.00f, 420.00f, 236.50f));

        assertThat(PaymentDueDateGridExtractor.extract(runs)).isNull();
    }

    @Test
    void extract_returnsNull_whenTheValueRowIsTooFarBelow() {
        var runs = List.of(
                run("Payment Due Date", 381.00f, 443.01f, 224.00f),
                run("11/08/2026", 393.00f, 431.01f, 400.00f));

        assertThat(PaymentDueDateGridExtractor.extract(runs)).isNull();
    }

    @Test
    void extract_returnsNull_whenTheDueDateIsNotARealCalendarDay() {
        var runs = List.of(
                run("Payment Due Date", 381.00f, 443.01f, 224.00f),
                run("30/02/2026", 393.00f, 431.01f, 236.50f));

        assertThat(PaymentDueDateGridExtractor.extract(runs)).isNull();
    }

    @Test
    void extract_returnsNull_onEmptyInput() {
        assertThat(PaymentDueDateGridExtractor.extract(List.of())).isNull();
    }
}
