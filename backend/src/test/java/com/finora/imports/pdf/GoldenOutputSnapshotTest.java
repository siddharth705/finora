package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import com.finora.imports.product.ProductDiscovery;
import com.finora.imports.product.ProductEvidenceCollector;
import com.finora.imports.pdf.fixtures.PdfTrace;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every committed trace's full pipeline output, frozen as a committed text file.
 *
 * The point is NOT to assert any particular value is correct -- the capability tests do that, each
 * against the specific behaviour it owns. The point is that a behavioural change anywhere in the
 * pipeline shows up as a reviewable DIFF instead of as silence. A change to column bucketing, to
 * continuation merging, or to product classification touches documents nobody was thinking about
 * at the time; without a snapshot, the only ones that fail are the ones some test happened to
 * assert on, and the rest change unnoticed.
 *
 * A failing snapshot is therefore not automatically a bug. It is a question: "you changed the
 * output for this document -- did you mean to?" Regenerate with
 * {@code -Dfinora.golden.regenerate=true}, then READ THE DIFF before committing it. A snapshot
 * regenerated without reading is worse than no snapshot, because it converts a signal into a
 * rubber stamp.
 *
 * Safe to commit: traces are redacted at capture (see {@link com.finora.imports.pdf.fixtures.PdfTraceRedactor}),
 * so a snapshot derived from one contains no customer data either.
 */
class GoldenOutputSnapshotTest {

    private static final List<String> TRACES = List.of(
            "hdfc-composite-deposit-schedules",
            "hdfc-txn-date-narration-header",
            "bob-repeated-account-banner");

    private static final Path GOLDEN_DIR = Path.of("src", "test", "resources", "golden");

    private final ProductDiscovery discovery = ProductDiscovery.standard();

    @Test
    void everyTraceProducesItsCommittedOutput() throws IOException {
        StringBuilder mismatches = new StringBuilder();

        for (String trace : TRACES) {
            String actual = render(trace);
            Path golden = GOLDEN_DIR.resolve(trace + ".golden.txt");

            if (Boolean.getBoolean("finora.golden.regenerate") || !Files.exists(golden)) {
                Files.createDirectories(GOLDEN_DIR);
                Files.writeString(golden, actual, StandardCharsets.UTF_8);
                continue;
            }

            String expected = readNormalized(golden);
            if (!expected.equals(actual)) {
                mismatches.append("\n=== ").append(trace).append(" ===\n")
                        .append(firstDifference(expected, actual));
            }
        }

        assertThat(mismatches.toString())
                .as("""
                    Pipeline output changed for a committed trace.

                    This is a question, not a verdict: did you mean to change how these documents
                    parse? Read the difference below. If it is the improvement you intended,
                    regenerate with -Dfinora.golden.regenerate=true and commit the updated snapshot
                    as part of the same change, so the diff is reviewable. If it is not, you have
                    found a regression in a document no other test covers.""")
                .isEmpty();
    }

    /** A stable, human-readable rendering of everything the pipeline concluded about a document. */
    private String render(String traceName) {
        List<PositionedText> runs = PdfTrace.load(traceName);
        DocumentContext ctx = new DocumentContext("PDF", "PdfTableLocator");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);
        PdfMetadataExtractor metadataExtractor = new PdfMetadataExtractor();

        StringBuilder out = new StringBuilder();
        out.append("trace: ").append(traceName).append('\n');
        out.append("sections: ").append(doc.sections().size()).append('\n');

        for (int i = 0; i < doc.sections().size(); i++) {
            PdfTableLocator.LocatedSection section = doc.sections().get(i);
            List<String> columns = section.rows().isEmpty()
                    ? List.of() : List.copyOf(section.rows().get(0).keySet());

            out.append("\n[section ").append(i).append("]\n");
            out.append("  columns: ").append(columns).append('\n');
            out.append("  rows: ").append(section.rows().size()).append('\n');
            out.append("  auxLines: ").append(section.auxiliaryText().size()).append('\n');

            var metadata = metadataExtractor.extract(section.auxiliaryText(), null);
            out.append("  accountNumberMasked: ").append(metadata.accountNumberMasked()).append('\n');
            out.append("  accountHolderName: ").append(metadata.accountHolderName()).append('\n');
            out.append("  ifscCode: ").append(metadata.ifscCode()).append('\n');
            out.append("  statementPeriod: ").append(metadata.statementPeriodStart())
                    .append(" .. ").append(metadata.statementPeriodEnd()).append('\n');

            var product = discovery.discover(new ProductEvidenceCollector.Section(
                    columns, section.auxiliaryText(), null, section.rows().size(), i, doc.sections().size()));
            out.append("  product: ").append(product.type())
                    .append(" (").append(Math.round(product.confidence() * 100)).append("%, ")
                    .append(product.validation().verdict()).append(")\n");
            out.append("  mayCreateAutomatically: ").append(product.mayCreateAutomatically()).append('\n');
        }

        out.append("\ncapabilities: ")
                .append(ctx.capabilities().stream().map(a -> a.capability()).sorted().distinct().toList())
                .append('\n');
        return out.toString();
    }

    /** Git on Windows checks committed text out with CRLF; without this every snapshot would
     *  "differ" on every line for reasons that have nothing to do with the pipeline. */
    private String readNormalized(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** Reports the first differing line with context, rather than dumping two whole files and
     *  leaving the reader to find it. */
    private String firstDifference(String expected, String actual) {
        String[] e = expected.split("\n", -1);
        String[] a = actual.split("\n", -1);
        for (int i = 0; i < Math.max(e.length, a.length); i++) {
            String left = i < e.length ? e[i] : "<end of file>";
            String right = i < a.length ? a[i] : "<end of file>";
            if (!left.equals(right)) {
                return "  line " + (i + 1) + "\n    committed: " + left + "\n    now:       " + right + "\n";
            }
        }
        return "  (files differ only in trailing whitespace)\n";
    }

    /** Guards the guard: a snapshot file that silently stops being compared -- because the trace
     *  was renamed, or the resource went missing -- is a test that passes by doing nothing. */
    @Test
    void everyListedTraceActuallyExists() {
        for (String trace : TRACES) {
            try (InputStream in = GoldenOutputSnapshotTest.class
                    .getResourceAsStream("/traces/" + trace + ".trace")) {
                assertThat(in).as("trace fixture " + trace + " is listed but missing").isNotNull();
            } catch (IOException e) {
                throw new AssertionError("could not read trace " + trace, e);
            }
        }
    }
}
