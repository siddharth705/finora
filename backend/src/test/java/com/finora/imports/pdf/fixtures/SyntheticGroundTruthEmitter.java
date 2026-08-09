package com.finora.imports.pdf.fixtures;

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
                new ExpectedEntity("savings-primary", "SAVINGS", Presence.DETECTED, null,
                        ZeroTransactions.FALSE, List.of(
                                new Row(LocalDate.of(2026, 6, 5), "SALARY CREDIT", salary, true),
                                new Row(LocalDate.of(2026, 6, 10), "GROCERY STORE",
                                        new BigDecimal("2000.00"), false),
                                new Row(LocalDate.of(2026, 6, 18), "ELECTRICITY BILL",
                                        new BigDecimal("1404.91"), false)))),
                List.of());
    }

    /** The declaration as the DOCUMENT should print it under a given mutation. Truth never moves. */
    private static SyntheticStatementDefinition mutated(SyntheticStatementDefinition full, String how) {
        if (how == null) return full;
        ExpectedEntity e = full.entities().get(0);
        List<Row> rows = new java.util.ArrayList<>(e.rows());
        switch (how) {
            case "--drop-row" -> rows.remove(rows.size() - 1);
            // The canonical one: right number of rows, one wrong digit. Count-based matching cannot
            // see this, which is why the value axis exists.
            case "--wrong-amount" -> rows.set(0, new Row(rows.get(0).date(), rows.get(0).description(),
                    new java.math.BigDecimal("35000.00"), rows.get(0).credit()));
            case "--wrong-date" -> rows.set(0, new Row(rows.get(0).date().plusMonths(1),
                    rows.get(0).description(), rows.get(0).amount(), rows.get(0).credit()));
            case "--flip-direction" -> rows.set(1, new Row(rows.get(1).date(), rows.get(1).description(),
                    rows.get(1).amount(), !rows.get(1).credit()));
            default -> throw new IllegalArgumentException("unknown mutation: " + how);
        }
        return new SyntheticStatementDefinition(full.documentId(),
                List.of(new ExpectedEntity(e.id(), e.product(), e.presence(), e.accountNumberMasked(),
                        e.zeroTransactionsLegitimate(), List.copyOf(rows))),
                full.layout());
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: SyntheticGroundTruthEmitter <dir> [--mutate]");
            System.exit(2);
        }
        Path dir = Path.of(args[0]);
        String mutation = args.length > 1 ? args[1] : null;
        Files.createDirectories(dir.resolve("statements"));

        // Truth always states the declared figure. The DOCUMENT is what --mutate changes, so a
        // mutated run is a genuine disagreement rather than two consistent artefacts.
        BigDecimal declared = new BigDecimal("55000.00");
        SyntheticStatementDefinition truth = declaration(declared);
        Files.writeString(dir.resolve("ground-truth.json"), GroundTruthDocument.of(truth));

        // Truth is always the declaration. Only the DOCUMENT is mutated, so a mutated run is a
        // genuine disagreement rather than two consistent artefacts. Each mutation is a different
        // KIND of wrongness, because a harness that catches a missing row is not thereby catching a
        // wrong digit -- which is exactly the failure that passed before the value axis existed.
        Files.write(dir.resolve("statements").resolve("synthetic-ledger-001.pdf"),
                PdfFixtureBuilder.render(mutated(truth, mutation)));

        System.out.println("emitted ground-truth.json and statements/synthetic-ledger-001.pdf"
                + (mutation == null ? "" : " (document mutated: " + mutation + ")"));
    }
}
