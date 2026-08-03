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

    private String redact(String text) {
        return PdfTraceRedactor.redact(List.of(new PositionedText(text.trim(), 0f, 0f, 0)))
                .get(0).text();
    }
}
