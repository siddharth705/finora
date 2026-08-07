package com.finora.imports.pdf;

import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * TEMPORARY measurement harness for the PDFBox 3.0.3 -> 3.0.8 upgrade. Not part of the suite
 * (the class name deliberately does not match surefire's includes); run explicitly with
 * -Dtest=ExtractionParityDump.
 *
 * Two modes, selected by -Dfinora.parity.mode:
 *   generate  writes every PdfFixtureBuilder sample to <dir>/pdf/ as bytes AND dumps the
 *             extraction of each, plus the committed real-PDF fixture, to <dir>/dump/
 *   replay    re-extracts the PDFs previously written to <dir>/pdf/ (identical INPUT bytes,
 *             so only the extractor differs between runs) into <dir>/dump/
 */
class ExtractionParityDump {

    @Test
    void dump() throws Exception {
        Path root = Path.of(System.getProperty("finora.parity.dir"));
        String mode = System.getProperty("finora.parity.mode", "generate");
        Path pdfDir = root.resolve("pdf");
        Path dumpDir = root.resolve("dump");
        Files.createDirectories(pdfDir);
        Files.createDirectories(dumpDir);

        PdfTextExtractor extractor = new PdfTextExtractor();

        // 1. The committed real-document fixture -- identical bytes in both runs by construction.
        byte[] committed = Files.readAllBytes(
                Path.of("src/test/resources/pdf/separate_debit_credit_balance_sample.pdf"));
        Files.writeString(dumpDir.resolve("committed-sample.txt"),
                render(extractor.extract(committed)), StandardCharsets.UTF_8);

        // 2. Every synthetic fixture.
        List<Method> builders = new ArrayList<>(Arrays.asList(PdfFixtureBuilder.class.getMethods()));
        builders.removeIf(m -> !m.getName().startsWith("build")
                || m.getParameterCount() != 0
                || !m.getReturnType().equals(byte[].class));
        builders.sort((a, b) -> a.getName().compareTo(b.getName()));

        for (Method builder : builders) {
            String name = builder.getName().substring("build".length())
                    .replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
            Path pdf = pdfDir.resolve(name + ".pdf");

            byte[] bytes;
            if ("replay".equals(mode)) {
                bytes = Files.readAllBytes(pdf);
            } else {
                bytes = (byte[]) builder.invoke(null);
                Files.write(pdf, bytes);
            }
            Files.writeString(dumpDir.resolve(name + ".txt"),
                    render(extractor.extract(bytes)), StandardCharsets.UTF_8);
        }

        System.out.println("parity dump complete: mode=" + mode
                + " fixtures=" + (builders.size() + 1) + " -> " + dumpDir.toAbsolutePath());
    }

    /** One line per run: page, x, y, width, text -- everything PositionedText carries. */
    private String render(List<PositionedText> runs) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (PositionedText r : runs) {
            sb.append(r.pageIndex()).append('\t')
                    .append(String.format(Locale.ROOT, "%.4f", r.x())).append('\t')
                    .append(String.format(Locale.ROOT, "%.4f", r.y())).append('\t')
                    .append(String.format(Locale.ROOT, "%.4f", r.width())).append('\t')
                    .append(r.text().replace("\t", "\\t").replace("\n", "\\n")).append('\n');
        }
        return sb.toString();
    }
}
