package com.finora.imports.pdf.ocr;

import com.finora.imports.pdf.fixtures.GroundTruthDocument;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.pdf.fixtures.ScannedPdfFixture;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.ExpectedEntity;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Presence;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Row;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.ZeroTransactions;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Writes the files the scorecard is computed from: one ground truth, one observation per engine.
 *
 * <p>A {@code main} for the same reason {@link com.finora.imports.pdf.fixtures.SyntheticGroundTruthEmitter}
 * is one -- the comparison lives in Python and that boundary is crossed by files rather than by a
 * call. The matcher is not reimplemented here, and deliberately so: OCR output is judged by exactly
 * the comparator that judges native output, or the claim that both are the same kind of evidence is
 * decoration.
 *
 * <p>The scanned PDF is written out too, because a real engine needs a file to read and because a
 * scorecard nobody can reproduce is an opinion.
 */
public final class OcrScorecardEmitter {

    private OcrScorecardEmitter() {}

    /**
     * The declaration every artefact descends from.
     *
     * <p>Values chosen so that a single misplaced column inverts a direction: the credit is the
     * largest figure on the statement and sits one column right of the debits. A fixture where the
     * columns were interchangeable would score every engine identically and tell us nothing.
     */
    static SyntheticStatementDefinition declaration() {
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

    /**
     * Candidate lookup.
     *
     * <p>Real engines join this switch and nothing else changes -- that is the point of doing the
     * harness first. The stubs are not candidates and are named so that no scorecard can quietly
     * present one as a result.
     */
    static OcrEngine engine(String name, byte[] sourcePdf) {
        return switch (name) {
            case "ceiling" -> StubEngines.ceiling(sourcePdf, 0.99f);
            case "misread-amount" -> StubEngines.misreadsOneAmount(sourcePdf);
            case "drifted-column" -> StubEngines.driftsValueColumn(sourcePdf, 380f, 80f);
            case "blind" -> StubEngines.blind();
            default -> throw new IllegalArgumentException("no such engine: " + name
                    + " (available: ceiling, misread-amount, drifted-column, blind)");
        };
    }

    /** {@code <out-dir> <dpi> <engine>...} */
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: OcrScorecardEmitter <out-dir> <dpi> <engine>...");
            System.exit(2);
        }
        Path out = Path.of(args[0]);
        int dpi = Integer.parseInt(args[1]);
        Files.createDirectories(out);

        var declared = declaration();
        byte[] source = PdfFixtureBuilder.render(declared);

        Files.writeString(out.resolve("ground-truth.json"), GroundTruthDocument.of(declared));
        Files.write(out.resolve("scanned.pdf"), ScannedPdfFixture.scan(source, dpi));

        for (int i = 2; i < args.length; i++) {
            String name = args[i];
            var observed = OcrEvaluation.run(engine(name, source), declared, dpi);
            Files.writeString(out.resolve("observed-" + name + ".json"), observed.json());
            System.out.printf("%-18s runs=%-5d confidence=%s%n", name, observed.runsRecognised(),
                    observed.meanConfidence() == null ? "not reported" : observed.meanConfidence());
        }
    }
}
