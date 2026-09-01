package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CreditLimitGridExtractorTest {

    private static PositionedText run(String text, float x, float endX, float y) {
        return new PositionedText(text, x, y, 0, endX - x);
    }

    // Coordinates copied verbatim from a direct PositionedText inspection of the real Axis
    // credit.pdf's "Payment Summary" grid (label row y=253.0, value row y=266.5): "Credit Limit" at
    // x=172.0-210.0, its value "219,000.00" at x=173.0-209.0 (fully inside the label's own x-span)
    // directly below it. Other same-row labels/values present at their own real x positions
    // specifically to prove the column match picks the right one, not just the only one. Also
    // includes "For hassle free payments register for" -- a separate, off-column run only 4.0pt
    // below the label row that becomes its own row bucket ahead of the true value row (see
    // extract_looksPastAnInterveningOffColumnRow_toReachTheTrueValueRow for the isolated case).
    @Test
    void extract_readsTheRealAxisPaymentSummaryGrid() {
        var runs = List.of(
                run("Credit Card Number", 50.5f, 115.5f, 253.0f),
                run("Credit Limit", 172.0f, 210.0f, 253.0f),
                run("Available Credit Limit", 265.0f, 336.0f, 253.0f),
                run("Available Cash Limit", 379.0f, 445.0f, 253.0f),
                run("For hassle free payments register for", 486.0f, 572.0f, 257.0f),
                run("653047******7550", 51.0f, 115.0f, 266.5f),
                run("219,000.00", 173.0f, 209.0f, 266.5f),
                run("191,334.84", 282.5f, 318.5f, 266.5f));

        assertThat(CreditLimitGridExtractor.extract(runs)).isEqualByComparingTo(new BigDecimal("219000.00"));
    }

    // Coordinates copied verbatim from a direct PositionedText inspection of the real SBI Credit
    // Card.PDF's own grid (label row y=190.5, value row y=207.1): "Credit Limit" at x=43.3-84.6, its
    // value "1,00,000.00" (Indian digit grouping) at x=63.0-105.1 directly below it, overlapping the
    // label's own span. Proves the extractor tolerates the Indian grouping style, not just the
    // Western one Axis uses.
    @Test
    void extract_readsTheRealSbiPaymentSummaryGrid() {
        var runs = List.of(
                run("Credit Limit", 43.3f, 84.6f, 190.5f),
                run("Cash Limit", 173.4f, 210.3f, 190.5f),
                run("1,00,000.00", 63.0f, 105.1f, 207.1f),
                run("10,000.00", 196.3f, 231.8f, 207.1f));

        assertThat(CreditLimitGridExtractor.extract(runs)).isEqualByComparingTo(new BigDecimal("100000.00"));
    }

    // Coordinates copied verbatim from a direct PositionedText inspection of the real HDFC
    // credit.pdf (previously entirely out of scope for "font/glyph corruption"): "TOTAL CREDIT
    // LIMIT" at y=273.9, x=64.6-123.7; its own sub-label "(Including Cash)" at y=282.4,
    // x=71.0-117.2 -- genuinely x-overlapping the label, unlike Axis's sliver -- 8.5pt below; the
    // true value "C78,000" ("C" is this document's own corrupted Rupee glyph, stripped by
    // CsvParser.parseNumeric) at y=301.8, x=81.0-107.3, 27.9pt below the label. "AVAILABLE CREDIT
    // LIMIT"/"MINIMUM DUE"/"DUE DATE" and their own values present at their real positions to
    // prove the column match picks the right one. Proves both fixes together: the "Total "-prefixed
    // label variant, and skipping an x-overlapping-but-non-numeric intervening row.
    @Test
    void extract_readsTheRealHdfcPaymentSummaryGrid() {
        var runs = List.of(
                run("TOTAL CREDIT LIMIT", 64.6f, 123.7f, 273.9f),
                run("AVAILABLE CREDIT LIMIT", 182.7f, 254.9f, 278.2f),
                run("AVAILABLE CASH LIMIT", 317.8f, 384.9f, 278.2f),
                run("MINIMUM DUE", 445.9f, 491.2f, 279.9f),
                run("DUE DATE", 512.1f, 541.6f, 279.9f),
                run("(Including Cash)", 71.0f, 117.2f, 282.4f),
                run("C200.00", 445.9f, 472.3f, 297.7f),
                run("09 Aug, 2026", 512.1f, 555.0f, 297.7f),
                run("C78,000", 81.0f, 107.3f, 301.8f),
                run("C76,183", 205.6f, 231.9f, 301.8f),
                run("C31,200", 338.2f, 364.5f, 301.8f));

        assertThat(CreditLimitGridExtractor.extract(runs)).isEqualByComparingTo(new BigDecimal("78000"));
    }

    @Test
    void extract_doesNotMatch_availableCreditLimitAsTheLabel() {
        // "Available Credit Limit" is its own separate run, distinct from bare "Credit Limit" --
        // must not be treated as a match. No bare "Credit Limit" run exists in this input at all.
        var runs = List.of(
                run("Available Credit Limit", 45.21f, 121.03f, 233.05f),
                run("191,334.84", 45.21f, 100.00f, 248.00f));

        assertThat(CreditLimitGridExtractor.extract(runs)).isNull();
    }

    @Test
    void extract_returnsNull_whenNoCreditLimitLabelRowExists() {
        var runs = List.of(
                run("Payment Due Date", 45.21f, 121.03f, 233.05f),
                run("11/08/2026", 45.21f, 100.00f, 248.00f));

        assertThat(CreditLimitGridExtractor.extract(runs)).isNull();
    }

    @Test
    void extract_returnsNull_whenTheColumnUnderTheLabelIsNotNumberShaped() {
        var runs = List.of(
                run("Credit Limit", 172.0f, 210.0f, 253.0f),
                run("N/A", 173.0f, 190.0f, 266.5f));

        assertThat(CreditLimitGridExtractor.extract(runs)).isNull();
    }

    @Test
    void extract_returnsNull_whenTheValueRowIsTooFarBelow() {
        var runs = List.of(
                run("Credit Limit", 172.0f, 210.0f, 253.0f),
                run("219,000.00", 173.0f, 209.0f, 400.0f));

        assertThat(CreditLimitGridExtractor.extract(runs)).isNull();
    }

    @Test
    void extract_returnsNull_onEmptyInput() {
        assertThat(CreditLimitGridExtractor.extract(List.of())).isNull();
    }
}
