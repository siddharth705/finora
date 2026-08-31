package com.finora.imports.analysis;

import com.finora.imports.pdf.fixtures.GroundTruthDocument;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
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
 * Regenerates the mechanism-proof fixture into {@code target/synthetic-corpus-regression/} --
 * build output, never committed. The reviewable artefact is this class's own {@link #definition()},
 * not a rendered PDF: a raw PDF's bytes cannot be scanned for customer data, so this repository's
 * pre-commit policy refuses to let one be committed at all (see the Synthetic Fixture Policy in
 * {@code docs/engineering/financial-document-intelligence-principles.md}). Every other synthetic
 * fixture in this codebase already follows the same shape --
 * {@code SyntheticGroundTruthTest} renders in-memory and never touches disk. This class exists only
 * because the CI step that runs {@code scripts/run-corpus-ground-truth.py} needs an actual file
 * path to hand the probe process; it regenerates that file fresh on every run rather than
 * committing one.
 *
 * <p>Fictional statement, plainly invented: no resemblance to any real institution or any real
 * customer's statement text intended or reviewed for.
 */
public final class SyntheticFixtureGenerator {

    private static final Path OUT_DIR =
            Path.of("target/synthetic-corpus-regression");

    public static void main(String[] args) throws Exception {
        SyntheticStatementDefinition definition = definition();

        Files.createDirectories(OUT_DIR.resolve("ground-truth"));
        Files.write(OUT_DIR.resolve("mechanism-proof.pdf"), PdfFixtureBuilder.render(definition));
        Files.writeString(OUT_DIR.resolve("ground-truth/mechanism-proof.json"),
                GroundTruthDocument.of(definition));

        System.out.println("wrote " + OUT_DIR.resolve("mechanism-proof.pdf"));
        System.out.println("wrote " + OUT_DIR.resolve("ground-truth/mechanism-proof.json"));
    }

    static SyntheticStatementDefinition definition() {
        ExpectedEntity savings = new ExpectedEntity("savings-primary", "SAVINGS",
                Presence.DETECTED, "••••7890", ZeroTransactions.FALSE, List.of(
                        new Row(LocalDate.of(2026, 7, 3), "Salary credit",
                                new BigDecimal("60000.00"), true),
                        new Row(LocalDate.of(2026, 7, 9), "Grocery store",
                                new BigDecimal("2450.75"), false),
                        new Row(LocalDate.of(2026, 7, 21), "ATM withdrawal",
                                new BigDecimal("5000.00"), false)));

        return new SyntheticStatementDefinition("mechanism-proof-001", List.of(savings), List.of());
    }

    private SyntheticFixtureGenerator() {}
}
