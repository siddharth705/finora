package com.finora.imports.pdf.fixtures;

import com.finora.imports.pdf.PositionedText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The redactor is a privacy control, so it gets tested like one: with input that WOULD be a
 * disclosure if it survived. Every value below is invented -- the point is that each is
 * structurally indistinguishable from what a real statement carries, so if the redactor lets one
 * through it would let the real thing through too.
 */
class PdfTraceRedactorTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "JANE EXAMPLE                          | XXXX XXXXXXX",
            "Flat No 404, Sample Gardens           | Xxxx No 999, Xxxxxx Xxxxxxx",
            "Account No : 50100999999999           | Account No : 99999999999999",
            "jane.example@sample.test              | xxxx.xxxxxxx@xxxxxx.xxxx",
            "Cust ID : 288080705                   | Cust ID : 999999999",
            // Only the counterparty is masked. "UPI" and "PAYMENT FROM PHONE" are instrument and
            // boilerplate -- structural, and identifying nobody once the name between them is gone.
            "UPI-JANE EXAMPLE-PAYMENT FROM PHONE   | UPI-XXXX XXXXXXX-PAYMENT FROM PHONE",
    })
    void personalDataIsMaskedCharacterForCharacter(String input, String expected) {
        // Length is preserved deliberately: it is what makes a narration wrap onto a second line,
        // which is the structure a trace exists to reproduce.
        assertThat(redact(input)).isEqualTo(expected.trim());
        assertThat(redact(input)).hasSameSizeAs(input.trim());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // Column headers and metadata labels -- redacting these would leave a trace unable to
            // exercise the header detection it was captured to cover.
            "Txn Date Narration Withdrawals Deposits Closing Balance",
            "Statement From : 03/07/2026 To 31/07/2026",
            "RTGS/NEFT IFSC : HDFC0XXXXXX",
            "Opening Balance Debit Amount Credit Amount Closing Balance",
            "0.00 538.00 25,000.00 24,462.00",
    })
    void statementStructureSurvivesUntouched(String structuralLine) {
        assertThat(redact(structuralLine)).isEqualTo(structuralLine);
    }

    @Test
    void aPageFooterKeepsItsShapeThoughNotItsNumbers() {
        // Deliberate: PdfTableLocator's PAGE_FOOTER matches on the words "page ... of" and never
        // reads the digits, so preserving them would buy no fidelity while widening what a trace
        // is allowed to carry. Masking every standalone number keeps one simple rule.
        assertThat(redact("Page 1 of 2")).isEqualTo("Page 9 of 9");
    }

    @Test
    void anIfscKeepsItsBankPrefixAndLosesItsBranchCode() {
        // The prefix is what bank detection reads; the branch code identifies a specific branch and
        // is the part worth removing. Masking the whole token would make every captured trace
        // useless for regression-testing detection.
        assertThat(redact("RTGS/NEFT IFSC: HDFC0001234 MICR: 500240002")) // synthetic-ok invented IFSC
                .isEqualTo("RTGS/NEFT IFSC: HDFC0XXXXXX MICR: 999999999");
    }

    @Test
    void datesAndAmountsAreKept_soATraceCanStillTestArithmetic() {
        assertThat(redact("10/07/2026 25,000.00 (Cr) 24,462.00"))
                .isEqualTo("10/07/2026 25,000.00 (Cr) 24,462.00");
    }

    @Test
    void anUnrecognizedTokenIsMasked_becauseTheAllowlistMustFailClosed() {
        // The whole design rests on this: a denylist fails OPEN on the case nobody anticipated, and
        // here that failure mode is a customer's data in a public repository. An unfamiliar word is
        // redacted even when it turns out to have been harmless.
        assertThat(redact("Zorbulax Quingle")).isEqualTo("Xxxxxxxx Xxxxxxx");
    }

    @Test
    void coordinatesAreNeverAltered() {
        List<PositionedText> redacted = PdfTraceRedactor.redact(
                List.of(new PositionedText("JANE EXAMPLE", 35.5f, 720.25f, 3)));

        assertThat(redacted).singleElement().satisfies(run -> {
            assertThat(run.text()).isEqualTo("XXXX XXXXXXX");
            assertThat(run.x()).isEqualTo(35.5f);
            assertThat(run.y()).isEqualTo(720.25f);
            assertThat(run.pageIndex()).isEqualTo(3);
        });
    }

    /**
     * A run redaction left byte-identical keeps its measured width.
     *
     * <p>This is the whole point of capturing width at all: {@code PdfTableLocator}'s right-edge
     * correction is guarded on {@code width() > 0} and only ever reads pure numbers and header
     * labels -- exactly the runs the allowlist preserves verbatim. Zeroing them made the capability
     * unreachable from any trace, and the width discloses nothing, because the text it measures is
     * printed in cleartext on the same line of the same file.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "24,462.00   | 41.02",   // amount -- the RIGHT_ALIGNED_AMOUNTS input
            "0.00        | 15.57",   // the short amount that mis-bucketed on the real statement
            "Narration   | 44.91",   // structural header label -- the headerEnds input
            "Closing Balance | 72.34",
            "10/07/2026  | 49.83",   // date
            "Page 9 of 9 | 40.12",   // already-masked shape: unchanged by redaction, so width stays
    })
    void anUnmaskedRunKeepsItsMeasuredWidth(String text, float width) {
        List<PositionedText> redacted = PdfTraceRedactor.redact(
                List.of(new PositionedText(text.trim(), 300f, 100f, 0, width)));

        assertThat(redacted).singleElement().satisfies(run -> {
            assertThat(run.text()).as("this input must be preserved verbatim").isEqualTo(text.trim());
            assertThat(run.width()).isEqualTo(width);
            assertThat(run.endX()).isEqualTo(300f + width);
        });
    }

    /**
     * A run redaction changed loses its width, whatever it was measured at.
     *
     * <p>For digits this leaks nothing anyway (digit glyphs are uniform-width in every text font), but
     * for letters a width constrains the multiset of masked characters, and nothing in the pipeline
     * reads a masked run's geometry. An unnecessary disclosure is declined rather than priced.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "JANE EXAMPLE     | 61.34",   // a name
            "50100999999999   | 77.84",   // an account number
            "HDFC0001234      | 58.71",   // synthetic-ok invented IFSC -- prefix kept, branch code masked, so it CHANGED
            "Zorbulax         | 44.02",   // an unrecognised word
            "Flat No 404, Sample Gardens | 118.90",
    })
    void aMaskedRunLosesItsWidth(String text, float width) {
        List<PositionedText> redacted = PdfTraceRedactor.redact(
                List.of(new PositionedText(text.trim(), 300f, 100f, 0, width)));

        assertThat(redacted).singleElement().satisfies(run -> {
            assertThat(run.text()).as("this input must have been changed by redaction")
                    .isNotEqualTo(text.trim());
            assertThat(run.width()).isZero();
            assertThat(run.endX()).isEqualTo(run.x());
        });
    }

    /** A partially masked run is a masked run: one hidden character is enough to drop the width,
     *  because the width describes the original glyphs and would no longer match the text beside it. */
    @Test
    void aRunWithOneMaskedTokenAmongStructuralOnesLosesItsWidth() {
        List<PositionedText> redacted = PdfTraceRedactor.redact(List.of(
                new PositionedText("UPI-JANE EXAMPLE-PAYMENT FROM PHONE", 50f, 100f, 0, 180.5f)));

        assertThat(redacted).singleElement().satisfies(run -> {
            assertThat(run.text()).isEqualTo("UPI-XXXX XXXXXXX-PAYMENT FROM PHONE");
            assertThat(run.width()).isZero();
        });
    }

    /**
     * See {@code TRAILING_CURRENCY_MARKER_LETTER}'s own doc comment: a real Kotak Mahindra Bank
     * credit-card statement's amount-column header is one single run reading "(Rs.)R" -- the
     * trailing "R" a rupee-glyph rendering artifact, not a recognizable word. Masking it alone used
     * to zero the WHOLE run's width (redaction's own all-or-nothing rule for a run whose text
     * changed at all), which broke PdfTableLocator's RIGHT_ALIGNED_AMOUNTS correction for that
     * column and staged every real purchase row on that statement as unparseable.
     */
    @Test
    void aCurrencyMarkersTrailingLetterSurvivesWhole_soItsRunKeepsItsRealWidth() {
        List<PositionedText> redacted = PdfTraceRedactor.redact(
                List.of(new PositionedText("(Rs.)R", 545.59f, 498.50f, 0, 24.41f)));

        assertThat(redacted).singleElement().satisfies(run -> {
            assertThat(run.text()).isEqualTo("(Rs.)R");
            assertThat(run.width()).isEqualTo(24.41f);
        });
    }

    /** The exception above is scoped to a currency marker's own closing paren, not any bare letter
     *  anywhere -- a stray single-letter initial elsewhere in the document must still be masked. */
    @Test
    void aBareLetterNotFollowingACurrencyMarker_isStillMasked() {
        List<PositionedText> redacted = PdfTraceRedactor.redact(
                List.of(new PositionedText("JANE R SMITH", 50f, 100f, 0, 90f)));

        assertThat(redacted).singleElement().satisfies(run -> {
            assertThat(run.text()).isEqualTo("XXXX X XXXXX");
            assertThat(run.width()).isZero();
        });
    }

    private String redact(String text) {
        return PdfTraceRedactor.redact(List.of(new PositionedText(text.trim(), 0f, 0f, 0)))
                .get(0).text();
    }
}
