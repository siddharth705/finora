package com.finora.imports.pdf;

import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SEC-02 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). The multipart
 * max-file-size cap bounds what was uploaded, not what PDFBox materializes once it decompresses
 * the document -- a small, spec-valid PDF with a pathological page count sails straight past that
 * cap and into an unbounded full-document {@code stripper.getText()} pass. These are blank pages,
 * not a decompression-bomb reproduction (constructing one deliberately is its own separate, much
 * larger exercise) -- what's under test here is only the ceiling itself: does it fire before the
 * expensive pass runs, at exactly the right boundary, and leave an ordinary document alone.
 */
class PdfTextExtractorPageLimitTest {

    private byte[] pdfWithPages(int pageCount) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                document.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void rejectsADocumentOverThePageCeiling() throws IOException {
        byte[] pdf = pdfWithPages(11);
        PdfTextExtractor extractor = new PdfTextExtractor(10);

        assertThatThrownBy(() -> extractor.extract(pdf))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.IMPORT_PDF_TOO_LARGE));
    }

    @Test
    void acceptsADocumentExactlyAtThePageCeiling() throws IOException {
        byte[] pdf = pdfWithPages(10);
        PdfTextExtractor extractor = new PdfTextExtractor(10);

        assertThat(extractor.extract(pdf)).isNotNull();
    }

    @Test
    void ordinaryDocumentsAreUnaffectedByTheDefaultCeiling() throws IOException {
        byte[] pdf = pdfWithPages(3);
        PdfTextExtractor extractor = new PdfTextExtractor();

        assertThat(extractor.extract(pdf)).isNotNull();
    }
}
