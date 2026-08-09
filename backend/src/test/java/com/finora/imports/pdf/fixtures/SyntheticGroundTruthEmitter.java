package com.finora.imports.pdf.fixtures;

import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Entity;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Presence;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Row;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.ZeroTransactions;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Writes a synthetic statement and its ground truth to a directory, for the cross-language
 * integration check in {@code scripts/test-synthetic-ground-truth.py}.
 *
 * <p>Java produces both artefacts; Python interprets them with the reviewed matcher. This exists
 * because that boundary has to be crossed by files rather than by a call, and a main is the
 * smallest thing that can cross it.
 *
 * <p>Takes a destination directory as an argument and writes nothing anywhere else. The caller owns
 * the directory's lifetime, which is what lets the integration script use a temporary one and
 * guarantee nothing survives the run.
 *
 * <pre>
 *   java ... SyntheticGroundTruthEmitter &lt;dir&gt; [--mutate]
 * </pre>
 *
 * {@code --mutate} renders a document that disagrees with the emitted truth, so the script can
 * prove the matcher FAILS on disagreement rather than only passing on agreement.
 */
public final class SyntheticGroundTruthEmitter {

    private SyntheticGroundTruthEmitter() {}

    /**
     * The declaration. Both artefacts descend from this; neither descends from the other.
     *
     * @param rows how many of the declared transactions the DOCUMENT prints. Truth always declares
     *             all three; rendering fewer is the mutation, and it is deliberately a mutation the
     *             model actually asserts on. An earlier attempt changed an AMOUNT instead, and the
     *             matcher passed it -- correctly. The ground-truth model asserts entity presence,
     *             product and transaction COUNT; it carries no per-transaction values, so altering
     *             one changes nothing it claims to check. Mutating outside a model's assertion
     *             surface tests the mutation, not the model.
     */
    static SyntheticStatementDefinition declaration(BigDecimal salary) {
        return new SyntheticStatementDefinition("synthetic-ledger-001", List.of(
                new Entity("savings-primary", "SAVINGS", Presence.DETECTED, null,
                        ZeroTransactions.FALSE, List.of(
                                new Row(LocalDate.of(2026, 6, 5), "SALARY CREDIT", salary, true),
                                new Row(LocalDate.of(2026, 6, 10), "GROCERY STORE",
                                        new BigDecimal("2000.00"), false),
                                new Row(LocalDate.of(2026, 6, 18), "ELECTRICITY BILL",
                                        new BigDecimal("1404.91"), false)))),
                List.of());
    }

    /** The same declaration with its last transaction withheld from the DOCUMENT only. */
    private static SyntheticStatementDefinition withOneRowMissing(SyntheticStatementDefinition full) {
        Entity e = full.entities().get(0);
        List<Row> fewer = e.rows().subList(0, e.rows().size() - 1);
        return new SyntheticStatementDefinition(full.documentId(),
                List.of(new Entity(e.id(), e.product(), e.presence(), e.accountNumberMasked(),
                        e.zeroTransactionsLegitimate(), fewer)),
                full.layout());
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: SyntheticGroundTruthEmitter <dir> [--mutate]");
            System.exit(2);
        }
        Path dir = Path.of(args[0]);
        boolean mutate = args.length > 1 && "--mutate".equals(args[1]);
        Files.createDirectories(dir.resolve("statements"));

        // Truth always states the declared figure. The DOCUMENT is what --mutate changes, so a
        // mutated run is a genuine disagreement rather than two consistent artefacts.
        BigDecimal declared = new BigDecimal("55000.00");
        SyntheticStatementDefinition truth = declaration(declared);
        Files.writeString(dir.resolve("ground-truth.json"), GroundTruthDocument.of(truth));

        // The document, which under --mutate prints one FEWER transaction than the truth declares.
        // Truth still says three; the document shows two; the matcher must refuse to agree.
        SyntheticStatementDefinition rendered = mutate ? withOneRowMissing(truth) : truth;
        Files.write(dir.resolve("statements").resolve("synthetic-ledger-001.pdf"),
                PdfFixtureBuilder.render(rendered));

        System.out.println("emitted ground-truth.json and statements/synthetic-ledger-001.pdf"
                + (mutate ? " (MUTATED document)" : ""));
    }
}
