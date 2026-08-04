package com.finora.imports.pdf;

import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Password-protected statements, which most Indian banks e-mail by default.
 *
 * Encryption is a property of the file container rather than of the statement's layout, so this
 * class encrypts an EXISTING layout fixture (see PdfFixtureBuilder.encrypt) instead of introducing
 * a new one -- which also lets the last test here assert the thing that actually matters
 * downstream: once the document opens, parsing must produce exactly what the unencrypted copy
 * produces, with no separate code path for protected files.
 */
class PasswordProtectedPdfTest {

    private static final String PASSWORD = "AAAA1234";

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    @Test
    void unencryptedDocumentStillParsesWithNoPassword() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample();

        assertThat(extractor.extract(pdf)).isNotEmpty();
    }

    @Test
    void passwordOnAnUnencryptedDocumentIsHarmless() throws Exception {
        // The client cannot know whether a file is encrypted before uploading it, so it may send a
        // password for a document that does not need one. That must not become an error.
        byte[] pdf = PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample();

        assertThat(extractor.extract(pdf, PASSWORD))
                .as("an unneeded password is ignored, not rejected")
                .isEqualTo(extractor.extract(pdf, null));
    }

    @Test
    void encryptedDocumentWithNoPasswordAsksForOne() throws Exception {
        byte[] pdf = PdfFixtureBuilder.encrypt(PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample(), PASSWORD);

        ApiException e = catchThrowableOfType(() -> extractor.extract(pdf, null), ApiException.class);

        assertThat(e.getCode()).isEqualTo(ErrorCode.IMPORT_PDF_PASSWORD_REQUIRED);
        assertThat(e.getStatus().value()).as("a protected file is the user's to fix, not a server fault").isEqualTo(422);
    }

    @Test
    void blankPasswordCountsAsNoPasswordRatherThanAWrongOne() throws Exception {
        // Matters for the UI: the field is always shown for a PDF, so an untouched field arrives as
        // "". Reporting that as INVALID would tell a user their password was wrong when they never
        // entered one.
        byte[] pdf = PdfFixtureBuilder.encrypt(PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample(), PASSWORD);

        for (String empty : List.of("", "   ")) {
            ApiException e = catchThrowableOfType(() -> extractor.extract(pdf, empty), ApiException.class);
            assertThat(e.getCode()).as("[%s]", empty).isEqualTo(ErrorCode.IMPORT_PDF_PASSWORD_REQUIRED);
        }
    }

    @Test
    void encryptedDocumentWithTheWrongPasswordSaysSo() throws Exception {
        byte[] pdf = PdfFixtureBuilder.encrypt(PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample(), PASSWORD);

        ApiException e = catchThrowableOfType(() -> extractor.extract(pdf, "WRONG9999"), ApiException.class);

        assertThat(e.getCode())
                .as("distinct from REQUIRED so the UI can keep the prompt open with an inline error")
                .isEqualTo(ErrorCode.IMPORT_PDF_PASSWORD_INVALID);
    }

    @Test
    void neitherFailureLeaksThePasswordItWasGiven() throws Exception {
        byte[] pdf = PdfFixtureBuilder.encrypt(PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample(), PASSWORD);
        String secret = "TOPSECRET42";

        assertThatThrownBy(() -> extractor.extract(pdf, secret))
                .as("the message and the whole cause chain reach logs and error reports")
                .hasNoCause()
                .hasMessageNotContaining(secret);
    }

    @Test
    void correctPasswordParsesIdenticallyToTheUnencryptedCopy() throws Exception {
        byte[] plain = PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample();
        byte[] encrypted = PdfFixtureBuilder.encrypt(plain, PASSWORD);

        List<PositionedText> fromEncrypted = extractor.extract(encrypted, PASSWORD);

        assertThat(fromEncrypted)
                .as("decryption is the only difference -- no separate parsing path for protected files")
                .isEqualTo(extractor.extract(plain, null))
                .isNotEmpty();
    }
}
