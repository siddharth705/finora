package com.finora.imports.pdf;

import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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

    private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

    /** Unprotected documents, and every caller that predates password support. */
    public List<PositionedText> extract(byte[] fileBytes) throws IOException {
        return extract(fileBytes, null);
    }

    /**
     * @param password the document open password, or null/blank when none was supplied.
     *
     * <p>Passing a password to a document that is NOT encrypted is harmless -- PDFBox ignores it
     * and opens normally (verified against PDFBox 3.0.3), so callers never have to work out
     * whether a file needs one before deciding what to send.
     *
     * <p>This is the only place that learns a document is encrypted, so it is where the two
     * outcomes are told apart. PDFBox cannot distinguish them itself: opening an encrypted PDF
     * with NO password and with the WRONG password both raise InvalidPasswordException carrying
     * the identical message. What separates them is whether this call was GIVEN a password, which
     * is knowledge only this method has.
     *
     * <p>Without the translation below, both cases escaped as a bare IOException with no matching
     * ErrorCode and surfaced to the user as a generic 500 -- "Could not read that statement", with
     * nothing to suggest a password was the problem or that entering one would fix it.
     */
    public List<PositionedText> extract(byte[] fileBytes, String password) throws IOException {
        boolean passwordSupplied = password != null && !password.isBlank();
        List<PositionedText> result = new ArrayList<>();
        // PDFBox treats "" as "no password", which is exactly the previous behaviour.
        try (PDDocument document = loadOrExplain(fileBytes, passwordSupplied ? password : "", passwordSupplied)) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String string, List<TextPosition> textPositions) throws IOException {
                    if (string == null || string.isBlank() || textPositions.isEmpty()) return;
                    TextPosition first = textPositions.get(0);
                    // The run's right edge, from its LAST glyph rather than a sum of widths --
                    // glyph advances include kerning and inter-character spacing that summing
                    // would drop, and the whole point of this measurement is that it be exact
                    // enough to separate two adjacent right-aligned amount columns.
                    TextPosition last = textPositions.get(textPositions.size() - 1);
                    float width = Math.max(0f, (last.getXDirAdj() + last.getWidthDirAdj()) - first.getXDirAdj());
                    // getCurrentPageNo() is 1-based and reflects whichever page the stripper is
                    // currently walking -- correct even for a multi-page statement, since this
                    // override fires once per text run as PDFBox processes pages in order.
                    result.add(new PositionedText(string, first.getXDirAdj(), first.getYDirAdj(),
                            getCurrentPageNo() - 1, width));
                }
            };
            stripper.setSortByPosition(true);
            stripper.getText(document); // return value discarded -- writeString() above is what we actually want
        }
        return result;
    }

    private PDDocument loadOrExplain(byte[] fileBytes, String password, boolean passwordSupplied) throws IOException {
        try {
            return Loader.loadPDF(fileBytes, password);
        } catch (InvalidPasswordException e) {
            // The exception itself carries no user-safe detail, and its message is identical in
            // both branches -- do not pass it through. The supplied password is never included in
            // the message or the cause chain, so it cannot reach a log or an error report.
            throw new ApiException(passwordSupplied
                    ? ErrorCode.IMPORT_PDF_PASSWORD_INVALID
                    : ErrorCode.IMPORT_PDF_PASSWORD_REQUIRED);
        } catch (IOException e) {
            // A structurally broken PDF -- truncated by a failed download, corrupted in transit,
            // or saved by something that produced not-quite-valid output.
            //
            // Found by the e2e suite, which uploaded a deliberately corrupted file and got back
            // INTERNAL_ERROR carrying "Unexpected error: Missing root object specification in
            // trailer." Two things wrong with that, and the same two the sibling branches above
            // already get right.
            //
            // It is a 500 for a problem the server did not have. A malformed upload is the user's
            // to fix, exactly as a locked file is -- which is why IMPORT_008/009 are 422s. Reporting
            // it as a server fault sends the user to support rather than back to their bank's
            // download page, and buries it in whatever alerting watches 5xx rates.
            //
            // And it hands the user a PDFBox internal. "Missing root object specification in
            // trailer" is not something a person can act on, and library internals should not cross
            // this boundary at all. The cause is logged and kept out of the response.
            log.warn("Could not read an uploaded PDF -- treating as a damaged file rather than a "
                    + "server fault. PDFBox said: {}", e.getMessage());
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "This PDF could not be read -- the file appears to be damaged or incomplete. "
                            + "Downloading it again from your bank usually fixes this.");
        }
    }
}
