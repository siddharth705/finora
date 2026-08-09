package com.finora.imports.pdf.ocr;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tesseract, as a candidate.
 *
 * <h2>The whole adapter</h2>
 *
 * This is the one file OCR-3A predicted would be needed to evaluate an engine: rasterise, recognise,
 * convert coordinates, hand back {@link OcrEngine.RecognisedText}. Nothing in production changed to
 * accommodate it, no dependency entered the build, and the harness it plugs into was written and
 * calibrated before Tesseract was installed. If this file had needed to reach into the parser, the
 * acquisition-strategy claim would have been wrong.
 *
 * <h2>Why the command line rather than a Python binding</h2>
 *
 * {@code tesseract <image> stdout tsv} already emits a word, a bounding box and a confidence per
 * run, which is exactly and only what the contract asks for. pytesseract would add a Python
 * dependency, a virtualenv and a serialisation hop to obtain the same four numbers. Fewer moving
 * parts between the engine and the scorecard also means fewer places for the evaluation to be wrong
 * in a way that flatters the engine.
 *
 * <h2>Coordinates</h2>
 *
 * Tesseract reports pixels from the top-left of the rendered image; PDFBox's
 * {@code getYDirAdj} -- what native {@link com.finora.imports.pdf.PositionedText} carries -- also
 * increases downward. So the conversion is a scale and no flip. The scale is derived from the page's
 * own width in points against the image's width in pixels rather than from the DPI constant,
 * because the renderer rounds to whole pixels and a statement's columns are separated by tens of
 * points; deriving it keeps a half-pixel rounding error from becoming a column error.
 */
public final class TesseractEngine implements OcrEngine {

    /** Word level in Tesseract's TSV. Lower levels describe blocks and lines, not runs. */
    private static final int WORD_LEVEL = 5;

    public TesseractEngine() {}

    @Override
    public String name() {
        return "tesseract";
    }

    /** Whether the binary is on PATH, so a missing engine reports itself rather than failing oddly. */
    public static boolean available() {
        try {
            return new ProcessBuilder("tesseract", "--version").start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Override
    public List<RecognisedText> recognise(byte[] pdf, int dpi) throws IOException {
        List<RecognisedText> runs = new ArrayList<>();
        Path work = Files.createTempDirectory("tesseract-eval-");
        try (PDDocument in = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(in);
            for (int page = 0; page < in.getNumberOfPages(); page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, dpi);
                File png = work.resolve("page-" + page + ".png").toFile();
                ImageIO.write(image, "png", png);

                PDRectangle size = in.getPage(page).getMediaBox();
                float scale = size.getWidth() / image.getWidth();
                runs.addAll(parse(run(png), page, scale));
            }
        }
        deleteRecursively(work);
        return runs;
    }

    /** {@code tesseract <png> stdout tsv} -- one row per recognised element. */
    private String run(File png) throws IOException {
        Process process = new ProcessBuilder("tesseract", png.getAbsolutePath(), "stdout", "tsv")
                .redirectErrorStream(false)
                .start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (process.waitFor() != 0) {
                throw new IOException("tesseract exited non-zero for " + png);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while recognising " + png, e);
        }
        return out;
    }

    /**
     * TSV to runs.
     *
     * <p>Columns are {@code level page block par line word left top width height conf text}. Only
     * word-level rows carry text; the rest describe structure this contract deliberately does not
     * accept, since a recogniser's idea of a "line" is not evidence about a statement's rows.
     *
     * <p>Blank text is dropped and a negative confidence is reported as none. Tesseract uses -1 for
     * rows it did not score, and turning that into 0.0 would claim the engine was certain the run
     * was worthless rather than that it said nothing about it.
     */
    private static List<RecognisedText> parse(String tsv, int page, float scale) {
        List<RecognisedText> runs = new ArrayList<>();
        String[] lines = tsv.split("\n");
        for (int i = 1; i < lines.length; i++) {
            String[] c = lines[i].split("\t", -1);
            if (c.length < 12 || Integer.parseInt(c[0].trim()) != WORD_LEVEL) continue;

            String text = c[11];
            if (text == null || text.isBlank()) continue;

            float conf = Float.parseFloat(c[10].trim());
            runs.add(new RecognisedText(text,
                    Integer.parseInt(c[6].trim()) * scale,
                    Integer.parseInt(c[7].trim()) * scale,
                    Integer.parseInt(c[8].trim()) * scale,
                    Integer.parseInt(c[9].trim()) * scale,
                    page,
                    conf < 0 ? null : conf / 100f));
        }
        return runs;
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (var entries = Files.walk(directory)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // A leftover temp file is not a reason to fail an evaluation run.
                }
            });
        }
    }
}
