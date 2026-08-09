package com.finora.imports.pdf.ocr;

import com.finora.imports.pdf.PositionedText;
import com.finora.imports.pdf.fixtures.GroundTruthDocument;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.pdf.fixtures.ScannedPdfFixture;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.ExpectedEntity;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.LayoutGroundTruth;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Presence;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Row;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.ZeroTransactions;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the files a scorecard is computed from: one ground truth, one observation per engine.
 *
 * <p>A {@code main} for the same reason {@link com.finora.imports.pdf.fixtures.SyntheticGroundTruthEmitter}
 * is one -- the comparison lives in Python and that boundary is crossed by files rather than by a
 * call. The matcher is not reimplemented here, and deliberately so: OCR output is judged by exactly
 * the comparator that judges native output, or the claim that both are the same kind of evidence is
 * decoration.
 *
 * <h2>Scenarios</h2>
 *
 * Truth never moves. A scenario changes what the DOCUMENT says, so that an engine reading it
 * correctly must be judged wrong. That is the only way to learn whether a passing scorecard means
 * the engine read the statement or means the harness cannot fail -- and an engine that passes every
 * dimension on the first run, as Tesseract did, is exactly when that question needs answering.
 */
public final class OcrScorecardEmitter {

    private OcrScorecardEmitter() {}

    /**
     * The declaration every artefact descends from.
     *
     * <p>Values chosen so a single misplaced column inverts a direction: the credit is the largest
     * figure and sits one column right of the debits. A fixture whose columns were interchangeable
     * would score every engine identically and tell us nothing.
     *
     * <p>The layout expectation names where SALARY CREDIT must appear. Position is scored because a
     * recogniser that reads perfectly and reports boxes loosely produces a correct-looking ledger
     * for the wrong reason, and only geometry distinguishes the two.
     */
    static SyntheticStatementDefinition declaration() {
        return new SyntheticStatementDefinition("ocr-eval-001", List.of(savings(rows())),
                List.of(new LayoutGroundTruth(0, "SALARY CREDIT", 120f, 320f, 100f, 160f)));
    }

    private static ExpectedEntity savings(List<Row> rows) {
        return new ExpectedEntity("savings-primary", "SAVINGS", Presence.DETECTED, null,
                ZeroTransactions.FALSE, rows);
    }

    private static List<Row> rows() {
        return List.of(
                new Row(LocalDate.of(2026, 6, 5), "SALARY CREDIT", new BigDecimal("55000.00"), true),
                new Row(LocalDate.of(2026, 6, 10), "GROCERY STORE", new BigDecimal("2000.00"), false),
                new Row(LocalDate.of(2026, 6, 18), "ELECTRICITY BILL", new BigDecimal("1404.91"), false));
    }

    /**
     * What the DOCUMENT prints under a given scenario. The ground truth is always
     * {@link #declaration()}, so any divergence here must be caught.
     *
     * <p>{@code multi-page} is a coverage scenario rather than a mutation: it prints the same
     * ledger with enough additional rows to spill onto a second page, so that truth and document
     * still agree and a failure means the engine or the parser lost something at the page boundary.
     */
    static SyntheticStatementDefinition document(String scenario) {
        List<Row> rows = new ArrayList<>(rows());
        switch (scenario) {
            case "baseline" -> { }
            // One digit. Right count, right dates, right directions, wrong money.
            case "wrong-amount" -> rows.set(0, new Row(rows.get(0).date(), rows.get(0).description(),
                    new BigDecimal("35000.00"), true));
            // The credit printed in the withdrawal column. Every character correct.
            case "wrong-direction" -> rows.set(0, new Row(rows.get(0).date(), rows.get(0).description(),
                    rows.get(0).amount(), false));
            case "multi-page" -> {
                for (int i = 0; i < 60; i++) {
                    rows.add(new Row(LocalDate.of(2026, 6, 20), "FILLER TRANSACTION " + i,
                            new BigDecimal("11.00"), false));
                }
            }
            default -> throw new IllegalArgumentException("no such scenario: " + scenario);
        }
        return new SyntheticStatementDefinition("ocr-eval-001", List.of(savings(rows)),
                declaration().layout());
    }

    /** Truth for a scenario. Only multi-page moves it, because there the extra rows are declared. */
    static SyntheticStatementDefinition truth(String scenario) {
        return scenario.equals("multi-page") ? document(scenario) : declaration();
    }

    static OcrEngine engine(String name, byte[] sourcePdf) {
        return switch (name) {
            case "ceiling" -> StubEngines.ceiling(sourcePdf, 0.99f);
            case "misread-amount" -> StubEngines.misreadsOneAmount(sourcePdf);
            case "drifted-column" -> StubEngines.driftsValueColumn(sourcePdf, 380f, 80f);
            case "blind" -> StubEngines.blind();
            case "tesseract" -> new TesseractEngine();
            default -> throw new IllegalArgumentException("no such engine: " + name
                    + " (available: ceiling, misread-amount, drifted-column, blind, tesseract)");
        };
    }

    /** {@code <out-dir> <dpi> <scenario> <engine>...} */
    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: OcrScorecardEmitter <out-dir> <dpi> <scenario> <engine>...");
            System.exit(2);
        }
        Path out = Path.of(args[0]);
        int dpi = Integer.parseInt(args[1]);
        String scenario = args[2];
        Files.createDirectories(out);

        var printed = document(scenario);
        byte[] source = PdfFixtureBuilder.render(printed);

        Files.writeString(out.resolve("ground-truth.json"), GroundTruthDocument.of(truth(scenario)));
        Files.write(out.resolve("scanned.pdf"), ScannedPdfFixture.scan(source, dpi));

        for (int i = 3; i < args.length; i++) {
            String name = args[i];
            var observed = OcrEvaluation.run(engine(name, source), printed, dpi);
            Files.writeString(out.resolve("observed-" + name + ".json"), observed.json());
            System.out.printf("%-18s runs=%-5d confidence=%-8s position=%s%n", name,
                    observed.runsRecognised(),
                    observed.meanConfidence() == null ? "none" : observed.meanConfidence(),
                    position(observed.recognised(), truth(scenario)));
        }
    }

    /**
     * Whether each declared region actually contains the text declared for it.
     *
     * <p>The value dimensions can all pass on a document whose geometry is wrong -- the ledger comes
     * out right because the columns happened to survive, not because the engine placed anything
     * correctly. This asks the question directly, against regions declared before any engine ran.
     */
    private static String position(List<PositionedText> runs, SyntheticStatementDefinition truth) {
        if (truth.layout().isEmpty()) return "not declared";
        List<String> results = new ArrayList<>();
        for (LayoutGroundTruth region : truth.layout()) {
            boolean found = runs.stream().anyMatch(r -> r.pageIndex() == region.page()
                    && region.expectedText().toUpperCase().contains(r.text().toUpperCase().trim())
                    && !r.text().isBlank()
                    && r.x() >= region.xFrom() && r.x() <= region.xTo()
                    && r.y() >= region.yFrom() && r.y() <= region.yTo());
            results.add(region.expectedText() + (found ? "=IN" : "=OUT"));
        }
        return String.join(" ", results);
    }
}
