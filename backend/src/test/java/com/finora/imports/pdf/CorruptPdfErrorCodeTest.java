package com.finora.imports.pdf;

import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A structurally broken PDF -- a truncated download, corruption in transit -- used to throw a
 * codeless {@code ApiException} ({@code PdfTextExtractor.loadOrExplain}'s {@code IOException}
 * branch), which meant {@code StatementAnalysisRecorder} recorded {@code failureCode = null} for
 * it: indistinguishable from any other codeless failure in the failure_code histogram, the
 * customer-facing failures list (Premium Import Reliability v1, §2.1), and any future retry
 * classification. This fix adds {@link ErrorCode#IMPORT_CORRUPT_PDF} without changing the
 * user-facing message at all -- only a machine-readable code is added alongside it.
 *
 * <p>The corrupted bytes here are a {@link PdfFixtureBuilder} sample (wholly synthetic, not
 * derived from any real statement) truncated partway through its object stream -- cutting from
 * the very end is not enough, PDFBox recovers from a missing trailer in many cases; the cut point
 * below was found empirically to reliably trigger a genuine {@code IOException} ("Page tree root
 * must be a dictionary"), not the password branch this fix does not touch.
 */
class CorruptPdfErrorCodeTest {

    private static byte[] corruptedPdf() throws Exception {
        byte[] valid = PdfFixtureBuilder.buildSummaryWithOneTransactionalSectionSample();
        return Arrays.copyOf(valid, valid.length * 2 / 3);
    }

    @Test
    void aCorruptedPdf_throwsWithTheCorruptPdfCode() throws Exception {
        assertThatThrownBy(() -> new PdfTextExtractor().extract(corruptedPdf(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getCode()).isEqualTo(ErrorCode.IMPORT_CORRUPT_PDF);
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    // The user-facing message is unchanged by this fix -- only the code is new.
                    assertThat(api.getMessage())
                            .contains("appears to be damaged or incomplete")
                            .contains("Downloading it again from your bank usually fixes this");
                });
    }

    @Test
    void aValidPdf_isCompletelyUnaffected() throws Exception {
        byte[] valid = PdfFixtureBuilder.buildSummaryWithOneTransactionalSectionSample();
        assertThat(new PdfTextExtractor().extract(valid, null)).isNotEmpty();
    }

    @Test
    void aPasswordProtectedPdf_isNotReclassifiedAsCorrupt() throws Exception {
        byte[] protectedPdf = PdfFixtureBuilder.encrypt(
                PdfFixtureBuilder.buildSummaryWithOneTransactionalSectionSample(), "AAAA1234");

        assertThatThrownBy(() -> new PdfTextExtractor().extract(protectedPdf, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .as("a locked PDF must still be reported as locked, not corrupt")
                        .isEqualTo(ErrorCode.IMPORT_PDF_PASSWORD_REQUIRED));
    }
}
