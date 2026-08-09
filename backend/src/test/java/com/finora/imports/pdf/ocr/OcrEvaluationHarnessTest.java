package com.finora.imports.pdf.ocr;

import com.finora.imports.pdf.PositionedText;
import com.finora.imports.pdf.TextSource;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.ExpectedEntity;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Presence;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Row;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.ZeroTransactions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calibrates the evaluation before any engine is installed.
 *
 * <h2>The thing being tested is the ruler, not the engine</h2>
 *
 * Every criterion OCR-3A will judge PaddleOCR and Tesseract on is exercised here against stubs whose
 * answers are known in advance. A perfect input must score perfectly; a one-digit misread must fail;
 * correct characters in the wrong places must fail; recognising nothing must not be mistaken for
 * recognising an empty document.
 *
 * <p>Without this, a scorecard showing both engines at 100% would be indistinguishable from a
 * scorecard produced by a comparison that never checked anything. Every previous mistake in this
 * work has had that shape -- a test that passed against the bug, a mutation aimed outside the
 * model's assertion surface -- so the ruler gets checked first this time.
 */
class OcrEvaluationHarnessTest {

    /** The declaration. 55,000 in, two payments out, on known dates. */
    private static SyntheticStatementDefinition declaration() {
        return new SyntheticStatementDefinition("ocr-eval-001", List.of(
                new ExpectedEntity("savings-primary", "SAVINGS", Presence.DETECTED, null,
                        ZeroTransactions.FALSE, List.of(
                                new Row(LocalDate.of(2026, 6, 5), "SALARY CREDIT",
                                        new BigDecimal("55000.00"), true),
                                new Row(LocalDate.of(2026, 6, 10), "GROCERY STORE",
                                        new BigDecimal("2000.00"), false),
                                new Row(LocalDate.of(2026, 6, 18), "ELECTRICITY BILL",
                                        new BigDecimal("1404.91"), false)))),
                List.of());
    }

    private static byte[] source() throws Exception {
        return PdfFixtureBuilder.render(declaration());
    }

    @Test
    void aFlawlessRecogniserProducesTheDeclaredLedger() throws Exception {
        var observed = OcrEvaluation.run(StubEngines.ceiling(source(), 0.99f), declaration(), 150);

        assertThat(observed.json())
                .as("every declared value, through the real parser, from recognised runs")
                .contains("\"amount\":\"55000.00\"")
                .contains("\"amount\":\"2000.00\"")
                .contains("\"amount\":\"1404.91\"")
                .contains("\"date\":\"2026-06-05\"")
                .contains("\"date\":\"2026-06-10\"")
                .contains("\"date\":\"2026-06-18\"")
                .contains("\"direction\":\"CREDIT\"")
                .contains("\"direction\":\"DEBIT\"")
                .contains("\"rows\":3");
    }

    /**
     * THE negative case. One digit, and the harness must not call it a pass.
     *
     * <p>Asserted as "the declared amount is absent and the misread one is present" rather than as a
     * verdict string, because the verdict is the Python matcher's to give and this test's job is to
     * prove the observation carries enough to reach it.
     */
    @Test
    void aOneDigitMisreadIsVisibleInTheObservation() throws Exception {
        var observed = OcrEvaluation.run(StubEngines.misreadsOneAmount(source()), declaration(), 150);

        assertThat(observed.json())
                .as("the misread value reached the ledger")
                .contains("\"amount\":\"35000.00\"");
        assertThat(observed.json())
                .as("and the declared value did not -- otherwise the matcher would find it and pass")
                .doesNotContain("\"amount\":\"55000.00\"");
        assertThat(observed.json())
                .as("count, dates and directions are untouched: only the value axis can catch this")
                .contains("\"rows\":3")
                .contains("\"date\":\"2026-06-05\"");
    }

    /**
     * Perfect characters, one column of drift, and the ledger inverts.
     *
     * <p>Measured, not supposed: with value runs displaced 80pt -- the exact distance between this
     * fixture's deposit column at x=380 and its withdrawal column at x=300 -- the salary credit is
     * read as 55,000.00 leaving the account. Every digit is correct, all three dates are correct,
     * the row count is correct, and the money moves the wrong way. This is the failure that started
     * the whole verification effort, arriving here purely from geometry.
     *
     * <p>An evaluation scoring character accuracy would rank such an engine first. That is the
     * entire reason the harness pushes recognised runs through the real parser rather than diffing
     * strings, and this test is what proves it does.
     *
     * <p>The comparison is against the {@code observed} block rather than the whole document,
     * because the whole document also carries the engine's name and would differ between any two
     * engines for free -- an earlier version of this test asserted on the full JSON and passed
     * without ever exercising the parser.
     */
    @Test
    void correctCharactersInTheWrongColumnInvertTheLedger() throws Exception {
        var drifted = OcrEvaluation.run(
                StubEngines.driftsValueColumn(source(), 380f, 80f), declaration(), 150);

        assertThat(observedOf(drifted.json()))
                .as("the deposit is now a withdrawal")
                .contains("{\"date\":\"2026-06-05\",\"amount\":\"55000.00\",\"direction\":\"DEBIT\"");
        assertThat(observedOf(drifted.json()))
                .as("nothing else moved -- transcription, count and dates are all still perfect, "
                        + "which is precisely why a text-accuracy score cannot see this")
                .contains("\"rows\":3")
                .contains("\"amount\":\"55000.00\"")
                .contains("\"date\":\"2026-06-18\"");
        assertThat(observedOf(drifted.json()))
                .as("and it is genuinely a different reading from the flawless one")
                .isNotEqualTo(observedOf(OcrEvaluation.run(
                        StubEngines.ceiling(source(), 0.99f), declaration(), 150).json()));
    }

    /** The reading only, with the engine's name stripped, so two engines are compared on the ledger. */
    private static String observedOf(String json) {
        return json.substring(json.indexOf("\"observed\""));
    }

    /**
     * An engine that returns nothing must not look like a document containing nothing.
     *
     * <p>This is the OCR-2D distinction arriving from the other direction: there, a PDF with no text
     * layer had to stop reporting a clean empty import; here, a recogniser that failed must not
     * either. Both are the same rule -- absence of evidence is not evidence of absence.
     */
    @Test
    void anEngineThatRecognisesNothingIsNotAnEmptyStatement() throws Exception {
        var observed = OcrEvaluation.run(StubEngines.blind(), declaration(), 150);

        assertThat(observed.runsRecognised()).isZero();
        assertThat(observed.json())
                .as("no transaction may be reported from no recognition")
                .doesNotContain("\"amount\":\"55000.00\"");
    }

    /** Confidence is reported as the engine reported it, including not at all. */
    @Test
    void anEngineThatReportsNoConfidenceIsRecordedAsReportingNone() throws Exception {
        assertThat(OcrEvaluation.run(StubEngines.ceiling(source(), null), declaration(), 150)
                .meanConfidence())
                .as("null means 'this engine cannot tell you', which is a finding -- not 1.0")
                .isNull();

        assertThat(OcrEvaluation.run(StubEngines.ceiling(source(), 0.9f), declaration(), 150)
                .meanConfidence())
                .isEqualTo(0.9f);
    }

    /** Everything the adapter emits is stamped OCR, so no recognised run can pass as native. */
    @Test
    void everyAdaptedRunIsStampedAsRecognised() {
        List<PositionedText> adapted = RecognisedTextAdapter.toPositionedText(List.of(
                new OcrEngine.RecognisedText("SALARY CREDIT", 120f, 700f, 80f, 10f, 0, 0.97f),
                new OcrEngine.RecognisedText("55,000.00", 400f, 700f, 50f, 10f, 0, null)));

        assertThat(adapted).allSatisfy(p -> assertThat(p.source()).isEqualTo(TextSource.OCR));
        assertThat(adapted.get(0).confidence()).isEqualTo(0.97f);
        assertThat(adapted.get(1).confidence())
                .as("a run the engine was not sure enough to score keeps that, rather than gaining certainty")
                .isNull();
    }
}
