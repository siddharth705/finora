package com.finora.imports.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Mechanical PDF text extraction: given the raw file bytes, returns every text run PDFBox finds,
 * each with its page-relative x/y coordinates -- nothing more. Deliberately does NOT use
 * PDFTextStripper's default plain-text output ({@code stripper.getText(document)}); that API
 * discards column position entirely, which is the exact information needed to tell a debit
 * amount from a credit amount in a real bank statement table (verified empirically before
 * writing this class -- see this package's own doc comment). Overriding
 * {@code writeString(String, List<TextPosition>)} instead is what keeps that position
 * information available to {@link PdfTableLocator}.
 */
@Component
public class PdfTextExtractor {

    public List<PositionedText> extract(byte[] fileBytes) throws IOException {
        List<PositionedText> result = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(fileBytes)) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String string, List<TextPosition> textPositions) throws IOException {
                    if (string == null || string.isBlank() || textPositions.isEmpty()) return;
                    TextPosition first = textPositions.get(0);
                    // getCurrentPageNo() is 1-based and reflects whichever page the stripper is
                    // currently walking -- correct even for a multi-page statement, since this
                    // override fires once per text run as PDFBox processes pages in order.
                    result.add(new PositionedText(string, first.getXDirAdj(), first.getYDirAdj(), getCurrentPageNo() - 1));
                }
            };
            stripper.setSortByPosition(true);
            stripper.getText(document); // return value discarded -- writeString() above is what we actually want
        }
        return result;
    }
}
