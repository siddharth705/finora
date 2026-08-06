package com.finora.imports;

import com.finora.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class StatementUploadTest {

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, null, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsAnEmptyUpload_beforeAnyParsingIsAttempted() {
        var thrown = catchThrowableOfType(
                () -> StatementUpload.requireReadable(file("statement.csv", ""), StatementUpload.Format.CSV),
                ApiException.class);
        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Format used to be inferred purely from which URL was called, so this produced a confusing
     *  parse failure deep in the CSV reader instead of a clear rejection. */
    @Test
    void rejectsAPdfPostedToTheCsvEndpoint_with415() {
        var thrown = catchThrowableOfType(
                () -> StatementUpload.requireReadable(file("statement.csv", "%PDF-1.7\nbinary"), StatementUpload.Format.CSV),
                ApiException.class);
        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void rejectsANonPdfPostedToThePdfEndpoint_with415() {
        var thrown = catchThrowableOfType(
                () -> StatementUpload.requireReadable(file("statement.pdf", "Date,Description,Amount"), StatementUpload.Format.PDF),
                ApiException.class);
        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    /** Magic bytes decide, not the extension or the content type -- browsers disagree wildly about
     *  what they attach to a .csv, and rejecting on that would refuse real uploads. */
    @Test
    void acceptsARealUploadOfEitherFormat() {
        StatementUpload.requireReadable(file("anything.txt", "Date,Description,Amount"), StatementUpload.Format.CSV);
        StatementUpload.requireReadable(file("no-extension", "%PDF-1.4 ..."), StatementUpload.Format.PDF);
    }

    @Test
    void stripsDirectorySegmentsFromTheStoredFileName() {
        assertThat(StatementUpload.safeFileName(file("C:\\Users\\me\\jan.pdf", "x"), "fallback")).isEqualTo("jan.pdf");
        assertThat(StatementUpload.safeFileName(file("../../jan.pdf", "x"), "fallback")).isEqualTo("jan.pdf");
    }

    /** The name is persisted, rendered in the admin Diagnostics list, echoed in a
     *  Content-Disposition header and reaches log lines -- a CR/LF in it can forge a log entry. */
    @Test
    void removesControlCharacters() {
        assertThat(StatementUpload.safeFileName(file("jan\r\nFAKE LOG LINE.csv", "x"), "fallback"))
                .isEqualTo("janFAKE LOG LINE.csv");
    }

    @Test
    void fallsBackWhenNothingUsableSurvives() {
        assertThat(StatementUpload.safeFileName(file("", "x"), "statement.csv")).isEqualTo("statement.csv");
        assertThat(StatementUpload.safeFileName(file("..", "x"), "statement.csv")).isEqualTo("statement.csv");
        assertThat(StatementUpload.safeFileName(null, "statement.csv")).isEqualTo("statement.csv");
    }

    @Test
    void boundsTheLength() {
        String huge = "a".repeat(500) + ".csv";
        assertThat(StatementUpload.safeFileName(file(huge, "x"), "fallback").length()).isLessThanOrEqualTo(120);
    }

    @Test
    void requireReadableRejectsANullFile() {
        assertThatThrownBy(() -> StatementUpload.requireReadable(null, StatementUpload.Format.CSV))
                .isInstanceOf(ApiException.class);
    }
}
