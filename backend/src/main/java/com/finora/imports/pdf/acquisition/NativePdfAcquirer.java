package com.finora.imports.pdf.acquisition;

import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.pdf.PositionedText;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Reads a PDF's own text layer, via the extractor the pipeline has always used.
 *
 * <p>A thin delegation on purpose. {@link PdfTextExtractor} carries a long history of real-document
 * fixes -- password handling that distinguishes "none supplied" from "wrong", the right-edge
 * measurement that separates two adjacent amount columns, the direction-adjusted coordinates that
 * a rotated page needs. Reimplementing any of that behind a new interface would discard evidence
 * this repository paid for. This adapts; it does not replace.
 *
 * <p>Runs come back stamped {@link com.finora.imports.pdf.TextSource#NATIVE_PDF} with no confidence
 * value, which is the honest reading: nothing was inferred, so there is no estimate to report.
 */
@Component
public class NativePdfAcquirer implements DocumentTextAcquirer {

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F'};

    private final PdfTextExtractor extractor;

    public NativePdfAcquirer(PdfTextExtractor extractor) {
        this.extractor = extractor;
    }

    @Override
    public AcquiredDocument acquire(byte[] fileBytes, String password) throws IOException {
        List<PositionedText> runs = extractor.extract(fileBytes, password);
        // Already NATIVE_PDF: PositionedText defaults every run built without an explicit source,
        // which is every run the extractor produces. Stamping them again here would be a second
        // place for that default to be decided, and eventually to disagree.
        return AcquiredDocument.of(runs);
    }

    /**
     * Bytes that begin with a PDF header. Deliberately the same magic-number question the upload
     * guard already asks rather than trusting a filename -- and deliberately NOT "does this PDF
     * have a usable text layer", which is a judgement about quality that belongs to routing and
     * has no evidence behind it yet.
     */
    @Override
    public boolean supports(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length < PDF_MAGIC.length) return false;
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (fileBytes[i] != PDF_MAGIC[i]) return false;
        }
        return true;
    }
}
