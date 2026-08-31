package com.finora.imports.pdf.fixtures;

import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.ExpectedEntity;

import java.util.stream.Collectors;

/**
 * The versioned interchange document a {@link SyntheticStatementDefinition} asserts about itself.
 *
 * <h2>This is a boundary, not a serialisation convenience</h2>
 *
 * Java produces this document; {@code scripts/ground-truth-match.py} interprets it. Neither side owns
 * the other's internal model, which is the point: the matcher is the reviewed reference
 * implementation of docs/engineering/ground-truth-model-design.md with its own tests, and porting it
 * here would create two authorities that can eventually disagree about the same document. One
 * ground-truth implementation, reachable across a stated format.
 *
 * <p>{@code schemaVersion} is present from the first version for the same reason the trace format
 * carries one: a reader must be able to tell from the artefact alone what it is promised, rather
 * than inferring it from whichever fields happen to be present.
 *
 * <h2>Produced from the definition alone</h2>
 *
 * Nothing here renders, parses, or reads a PDF. That is the property the whole approach rests on --
 * an expected value that had been anywhere near the extractor would make the comparison circular.
 * Written by hand rather than through a JSON library so that the emitted shape is visible in this
 * file next to the model it implements, and so the test fixtures gain no serialisation dependency.
 */
public final class GroundTruthDocument {

    public static final int SCHEMA_VERSION = 1;

    private GroundTruthDocument() {}

    public static String of(SyntheticStatementDefinition definition) {
        String entities = definition.entities().stream()
                .map(GroundTruthDocument::entity)
                .collect(Collectors.joining(",\n"));
        return "{\n"
                + "  \"schemaVersion\": " + SCHEMA_VERSION + ",\n"
                + "  \"documentId\": " + quote(definition.documentId()) + ",\n"
                + "  \"provenance\": \"SYNTHETIC_DEFINITION\",\n"
                + "  \"entities\": [\n" + entities + "\n  ]\n"
                + "}\n";
    }

    /**
     * {@code provenance} is stamped on every entity, not only on the document. An expected value
     * that cannot say where it came from is indistinguishable from one copied out of parser output,
     * and the model's central rule is that ground truth is never derived from parser output. Making
     * the claim explicit means a future reader can check it rather than trust it.
     */
    private static String entity(ExpectedEntity e) {
        StringBuilder sb = new StringBuilder();
        sb.append("    {\n");
        sb.append("      \"id\": ").append(quote(e.id())).append(",\n");
        sb.append("      \"provenance\": \"SYNTHETIC_DEFINITION\",\n");
        sb.append("      \"expectedPresence\": ").append(quote(e.presence().name())).append(",\n");
        sb.append("      \"expectedProduct\": ").append(quote(e.product())).append(",\n");
        // Identity is omitted rather than emitted null when the definition declares none. The model
        // is explicit that identity must never be fabricated to make a match succeed, and an absent
        // key states that more clearly than a null does.
        if (e.accountNumberMasked() != null) {
            sb.append("      \"expectedIdentity\": { \"accountNumberMasked\": ")
              .append(quote(e.accountNumberMasked())).append(" },\n");
        }
        sb.append("      \"expectedTransactions\": ").append(e.expectedTransactions()).append(",\n");
        sb.append("      \"expectedTransactionValues\": [\n");
        sb.append(e.rows().stream().map(GroundTruthDocument::row)
                .collect(Collectors.joining(",\n"))).append("\n      ],\n");
        sb.append("      \"zeroTransactionsLegitimate\": {\n");
        sb.append("        \"value\": ").append(quote(e.zeroTransactionsLegitimate().name())).append(",\n");
        sb.append("        \"evidence\": { \"source\": \"GROUND_TRUTH\", \"reason\": ")
          .append(quote("asserted by the synthetic definition that generated this document")).append(" }\n");
        sb.append("      }\n");
        sb.append("    }");
        return sb.toString();
    }

    /**
     * One transaction, as the definition INTENDS it.
     *
     * <p>The value axis exists because entity, product and count agreeing is not the same as the
     * money being right -- a recogniser's characteristic failure is the correct number of rows with
     * a wrong digit in one of them, which count-based matching cannot see.
     *
     * <p>Direction is emitted as DEBIT/CREDIT rather than as a sign, because that is the distinction
     * the pipeline actually makes and the one a misread flips. Amount is the plain string of the
     * declared decimal: comparing rendered text avoids a float round-trip deciding whether two
     * amounts are equal.
     */
    private static String row(SyntheticStatementDefinition.Row r) {
        return "        { \"date\": " + quote(r.date().toString())
                + ", \"description\": " + quote(r.description())
                + ", \"amount\": " + quote(r.amount().toPlainString())
                + ", \"direction\": " + quote(r.credit() ? "CREDIT" : "DEBIT")
                + ", \"currency\": \"INR\" }";
    }

    private static String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
