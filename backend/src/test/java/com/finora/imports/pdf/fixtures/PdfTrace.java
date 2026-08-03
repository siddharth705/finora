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

    public static List<PositionedText> parse(String content) {
        List<PositionedText> runs = new ArrayList<>();
        for (String line : content.split("\n", -1)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            // Limit 4 so a text field containing tabs (see format()) can never shift the columns.
            String[] parts = line.split("\t", 4);
            if (parts.length < 4) {
                throw new IllegalArgumentException("Malformed trace line, expected 4 tab-separated "
                        + "fields (page/x/y/text) but got " + parts.length + ": " + line);
            }
            runs.add(new PositionedText(stripTrailingCarriageReturn(parts[3]),
                    Float.parseFloat(parts[1]), Float.parseFloat(parts[2]), Integer.parseInt(parts[0])));
        }
        return runs;
    }

    /** Loads a committed trace from {@code src/test/resources/traces/}. */
    public static List<PositionedText> load(String traceName) {
        String path = "/traces/" + traceName + ".trace";
        try (InputStream in = PdfTrace.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalArgumentException("No such trace fixture: " + path);
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read trace fixture " + path, e);
        }
    }

    /** Git on Windows checks these out with CRLF; the trailing \r would otherwise become part of
     *  the last column's text and silently break every string comparison in a fixture test. */
    private static String stripTrailingCarriageReturn(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }
}
