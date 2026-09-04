package com.finora.support;

import com.finora.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure-Java, no Spring context — matches {@code StatementUpload} having no test that needs one
 *  either: bytes in, a verdict out, no collaborators. */
class SupportAttachmentUploadTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4 fake but well-formed enough for the header check".getBytes();
    private static final byte[] PNG_BYTES = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0 };
    private static final byte[] JPEG_BYTES = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0 };

    @Test
    void recognizesPdfByMagicBytes() {
        var validated = SupportAttachmentUpload.validate(
                new MockMultipartFile("file", "statement.txt", "application/octet-stream", PDF_BYTES));

        assertThat(validated.format()).isEqualTo(SupportAttachmentUpload.Format.PDF);
        assertThat(validated.filename()).isEqualTo("statement.txt");
    }

    @Test
    void recognizesPngByMagicBytes() {
        var validated = SupportAttachmentUpload.validate(
                new MockMultipartFile("file", "screenshot.bin", "application/octet-stream", PNG_BYTES));

        assertThat(validated.format()).isEqualTo(SupportAttachmentUpload.Format.PNG);
    }

    @Test
    void recognizesJpegByMagicBytes() {
        var validated = SupportAttachmentUpload.validate(
                new MockMultipartFile("file", "photo.bin", "application/octet-stream", JPEG_BYTES));

        assertThat(validated.format()).isEqualTo(SupportAttachmentUpload.Format.JPEG);
    }

    @Test
    void fallsBackToUtf8DecodabilityForPlainText() {
        var validated = SupportAttachmentUpload.validate(
                new MockMultipartFile("file", "notes.dat", "application/octet-stream",
                        "plain text with no magic bytes at all".getBytes()));

        assertThat(validated.format()).isEqualTo(SupportAttachmentUpload.Format.TXT);
    }

    @Test
    void rejectsBytesThatMatchNoKnownFormat() {
        // Neither a recognised magic prefix nor valid UTF-8 -- an arbitrary binary blob.
        byte[] garbage = { 0x00, (byte) 0xC3, 0x28, (byte) 0xA0, 0x01 };

        assertThatThrownBy(() -> SupportAttachmentUpload.validate(
                new MockMultipartFile("file", "mystery.bin", "application/octet-stream", garbage)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
    }

    @Test
    void rejectsAnEmptyFile() {
        assertThatThrownBy(() -> SupportAttachmentUpload.validate(
                new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0])))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void rejectsAFileOverTheFiveMegabyteCap() {
        byte[] tooBig = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PDF_BYTES, 0, tooBig, 0, PDF_BYTES.length);

        assertThatThrownBy(() -> SupportAttachmentUpload.validate(
                new MockMultipartFile("file", "huge.pdf", "application/pdf", tooBig)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void computesTheSha256HashOfTheStoredBytes() {
        var validated = SupportAttachmentUpload.validate(
                new MockMultipartFile("file", "statement.txt", "application/octet-stream", PDF_BYTES));

        assertThat(validated.sha256Hash()).hasSize(64).matches("[0-9a-f]{64}");
    }
}
