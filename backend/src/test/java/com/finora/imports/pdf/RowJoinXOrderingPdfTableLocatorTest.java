package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * groupIntoRows sorts primarily by Y, using X only as a tiebreak on exactly-equal Y. That leaves
 * lineOf joining a row's members in whatever order the Y-primary sort produced, not left-to-right
 * reading order -- wrong whenever two runs on the same visual line have any Y jitter at all, which
 * real PDF glyph metrics commonly produce between punctuation/digits and letters.
 *
 * <p>Both fixtures below reproduce the real shapes found on a real SBI statement during the F23
 * investigation (2026-08-29) -- described structurally per this project's privacy discipline, no
 * real extracted value is used. All coordinates and text here are fully synthetic.
 */
class RowJoinXOrderingPdfTableLocatorTest {

    private static PositionedText run(String text, float x, float y) {
        return new PositionedText(text, x, y, 0, text.length() * 6f);
    }

    @Test
    void colonWithSlightlyLowerYThanItsLabel_stillJoinsAfterTheLabel() {
        // X order is unambiguous (label < colon < value). The colon's y is a hair below the
        // label's/value's y -- exactly the kind of sub-point baseline jitter real PDFs produce
        // between punctuation and letters/digits on the same printed line.
        List<PositionedText> positioned = List.of(
                run("Branch", 100f, 200.00f),
                run("Code", 155f, 200.00f),
                run(":", 210f, 199.90f),
                run("XYZ001", 225f, 200.00f)
        );

        PdfTableLocator.LocatedTable table = new PdfTableLocator().locate(positioned);

        assertThat(table.preTableLines()).containsExactly("Branch Code : XYZ001");
    }

    @Test
    void twoAdjacentFieldsChainMergedWithinTolerance_stillJoinInLeftToRightOrder() {
        // Two physically-adjacent but unrelated label/value pairs land in the same physical row
        // because each consecutive Y-gap is within ROW_Y_TOLERANCE (3.0pt), even though the whole
        // chain's Y spans more than that end to end. Pure Y-ascending order (no exact-Y ties here)
        // interleaves the two pairs; X order does not.
        List<PositionedText> positioned = List.of(
                run("Branch", 100f, 300.00f),
                run("Phone", 155f, 300.00f),
                run(":", 210f, 300.90f),
                run("5550001234", 225f, 300.95f), // synthetic-ok: invented placeholder digits, not a real phone/account number
                run(":", 400f, 302.80f),
                run("1000.00", 415f, 302.85f),
                run("Clear", 40f, 303.80f),
                run("Balance", 90f, 303.80f)
        );

        PdfTableLocator.LocatedTable table = new PdfTableLocator().locate(positioned);

        assertThat(table.preTableLines())
                .containsExactly("Clear Balance Branch Phone : 5550001234 : 1000.00"); // synthetic-ok: invented placeholder digits, not a real phone/account number
    }
}
