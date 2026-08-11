package com.finora.imports.pdf.fixtures;

import com.finora.imports.pdf.PositionedText;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A statement's extracted text layer -- every {@link PositionedText} run with its coordinates --
 * as a flat, human-readable file.
 *
 * This exists because the bugs worth regression-testing in this pipeline are POSITIONAL. Column
 * anchors that don't line up with their headers, a page banner whose x lands in the date column, a
 * multi-word header arriving as two separate runs: none of that survives being described in prose,
 * and none of it can be reproduced by hand-building a synthetic PDF, because rebuilding the PDF
 * rebuilds the layout and loses the exact fragmentation that caused the bug.
 *
 * A trace is captured from the real file once (see PdfPipelineDiagnostic's capture mode), redacted
 * (see {@link PdfTraceRedactor}), and committed. What lands in the repo is structurally identical
 * to the customer's document and contains none of their data -- which is what makes the Synthetic
 * Fixture Policy affordable to follow rather than a tax people route around.
 *
 * The format is deliberately plain text rather than anything binary or generated: a fixture nobody
 * can read is a fixture nobody will check for PII before committing.
 *
 * <pre>
 * # finora-pdf-trace v1
 * # page &lt;tab&gt; x &lt;tab&gt; y &lt;tab&gt; text
 * 0	35.00	720.50	Txn Date
 * </pre>
 */
public final class PdfTrace {

    private PdfTrace() {}

    static final String MAGIC = "# finora-pdf-trace v1";

    /** v1 traces carry no metadata; v2 adds a provenance block (see {@link TraceMetadata}) so a
     *  trace states which redactor and allowlist produced it. Both parse identically -- every
     *  metadata line is a comment, which the v1 parser already skipped. */
    static final String MAGIC_V2 = "# finora-pdf-trace v2";

    /** v3 adds a width column to every run. See {@link TraceMetadata#CURRENT_TRACE_VERSION} for why
     *  a version bump rather than an extra column under v2. */
    static final String MAGIC_V3 = "# finora-pdf-trace v3";

    /** Backward-compatible: writes a v1 trace with no provenance. Retained only so existing
     *  round-trip tests keep exercising the parser; {@link #format(List, TraceMetadata)} is what
     *  the capture path uses. */
    public static String format(List<PositionedText> runs) {
        StringBuilder sb = new StringBuilder(MAGIC).append('\n')
                .append("# page\tx\ty\ttext\n");
        for (PositionedText run : runs) {
            // A tab inside the text would corrupt the column split on the way back in. PDFBox
            // yields tabs as spacing rather than characters, so this is belt-and-braces.
            String text = run.text().replace('\t', ' ');
            sb.append(run.pageIndex()).append('\t')
                    .append(String.format(Locale.ROOT, "%.2f", run.x())).append('\t')
                    .append(String.format(Locale.ROOT, "%.2f", run.y())).append('\t')
                    .append(text).append('\n');
        }
        return sb.toString();
    }

    /** A v2 trace: the same run data, preceded by the provenance block that says which redactor and
     *  allowlist produced it and which capability it exists to protect. */
    public static String format(List<PositionedText> runs, TraceMetadata metadata) {
        return MAGIC_V3 + '\n'
                + metadata.toHeaderLines()
                + "# page\tx\ty\twidth\ttext\n"
                + formatRuns(runs);
    }

    private static String formatRuns(List<PositionedText> runs) {
        StringBuilder sb = new StringBuilder();
        for (PositionedText run : runs) {
            String text = run.text().replace('\t', ' ');
            sb.append(run.pageIndex()).append('\t')
                    .append(String.format(Locale.ROOT, "%.2f", run.x())).append('\t')
                    .append(String.format(Locale.ROOT, "%.2f", run.y())).append('\t')
                    .append(String.format(Locale.ROOT, "%.2f", run.width())).append('\t')
                    .append(text).append('\n');
        }
        return sb.toString();
    }

    /** The provenance of a committed trace, or {@link TraceMetadata#legacyV1()} when it predates
     *  metadata. */
    public static TraceMetadata metadata(String traceName) {
        return TraceMetadata.parse(read(traceName));
    }

    /**
     * Reads either row shape.
     *
     * <p>v3 rows are {@code page, x, y, width, text}; v1 and v2 rows are {@code page, x, y, text}.
     * Decided per line by field count rather than by the magic line, so a hand-edited file with a
     * mixed body still parses to something rather than throwing at an unrelated place.
     *
     * <p>A 4-field row yields width 0, which is exactly what it meant before — those runs keep
     * their previous bucketing, and any capability guarded on a measured width stays unreachable
     * on them. That is a real limitation of the older traces, not a defect in this parser, and
     * {@link TraceMetadata#hasNoWidths(List)} is how a caller asks about it -- of the parsed runs,
     * since a file's version stamp and its actual width data are written independently.
     */
    public static List<PositionedText> parse(String content) {
        List<PositionedText> runs = new ArrayList<>();
        for (String line : content.split("\n", -1)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            // Split limit is one past the last coordinate, so a text field containing tabs (see
            // format()) can never shift the columns.
            String[] wide = line.split("\t", 5);
            boolean hasWidth = wide.length == 5 && isNumeric(wide[3]);
            if (hasWidth) {
                runs.add(new PositionedText(stripTrailingCarriageReturn(wide[4]),
                        Float.parseFloat(wide[1]), Float.parseFloat(wide[2]),
                        Integer.parseInt(wide[0]), Float.parseFloat(wide[3])));
                continue;
            }

            String[] parts = line.split("\t", 4);
            if (parts.length < 4) {
                throw new IllegalArgumentException("Malformed trace line, expected page/x/y/text or "
                        + "page/x/y/width/text but got " + parts.length + " field(s): " + line);
            }
            runs.add(new PositionedText(stripTrailingCarriageReturn(parts[3]),
                    Float.parseFloat(parts[1]), Float.parseFloat(parts[2]), Integer.parseInt(parts[0])));
        }
        return runs;
    }

    /** Whether a field is a bare number, which is what separates a v3 width column from the first
     *  tab-delimited chunk of a v1/v2 text field. A text run that happens to be entirely numeric is
     *  the ambiguous case, and it is resolved by the field COUNT: a 5-field v1 row would need three
     *  tabs inside its text, which format() strips on the way out. */
    private static boolean isNumeric(String field) {
        if (field.isEmpty()) return false;
        try {
            Float.parseFloat(field);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Loads a committed trace from {@code src/test/resources/traces/}. */
    public static List<PositionedText> load(String traceName) {
        return parse(read(traceName));
    }

    /** The raw file contents, metadata block included. */
    public static String read(String traceName) {
        String path = "/traces/" + traceName + ".trace";
        try (InputStream in = PdfTrace.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalArgumentException("No such trace fixture: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read trace fixture " + path, e);
        }
    }

    /** Every committed trace, by name. The corpus is small enough to enumerate from disk, and
     *  deriving it means a newly captured trace is covered by the corpus-health checks the moment
     *  it lands rather than when someone adds it to a list. */
    public static List<String> committedTraceNames() {
        java.io.File dir = new java.io.File("src/test/resources/traces");
        String[] files = dir.list((d, name) -> name.endsWith(".trace"));
        if (files == null) return List.of();
        return java.util.Arrays.stream(files)
                .map(name -> name.substring(0, name.length() - ".trace".length()))
                .sorted()
                .toList();
    }

    /** Git on Windows checks these out with CRLF; the trailing \r would otherwise become part of
     *  the last column's text and silently break every string comparison in a fixture test. */
    private static String stripTrailingCarriageReturn(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }
}
