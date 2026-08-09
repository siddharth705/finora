package com.finora.imports.pdf.ocr;

import com.finora.imports.pdf.PositionedText;
import com.finora.imports.pdf.TextSource;

import java.util.List;

/**
 * Turns a recogniser's output into the one representation the pipeline already understands.
 *
 * <p>This is the whole architectural bet of the OCR work, in six lines: everything downstream of
 * acquisition must be unable to tell whether a character was read from a text layer or recognised
 * from pixels. If an engine's output can become {@link PositionedText}, the existing parser,
 * capabilities and verification rules apply to it unchanged -- and if it cannot, no amount of
 * recognition accuracy will help.
 *
 * <p>Every run is stamped {@link TextSource#OCR} and carries its engine's confidence, including
 * when that confidence is null. An engine that reports none is recorded as reporting none.
 */
public final class RecognisedTextAdapter {

    private RecognisedTextAdapter() {}

    public static List<PositionedText> toPositionedText(List<OcrEngine.RecognisedText> recognised) {
        return recognised.stream()
                .map(r -> new PositionedText(r.text(), r.x(), r.y(), r.pageIndex(), r.width(),
                        r.height(), r.confidence(), TextSource.OCR))
                .toList();
    }
}
