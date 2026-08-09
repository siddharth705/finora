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

    /**
     * Truncation used to cut the extension off, and the extension is not decoration here.
     *
     * <p>Found while closing BH-029. {@code ImportService} writes
     * {@code statement_imports.source_format} as
     * {@code fileName.toLowerCase().endsWith(".pdf") ? "PDF" : "CSV"} against the name this method
     * returns, and {@code parseAndStageAnyFormat} routes {@code reimport()} on that column. A PDF
     * whose filename exceeded 120 characters was therefore recorded as CSV, and re-importing it
     * fed a PDF's bytes to {@code CsvParser} — which is the exact regression V36 was added to
     * prevent, described in {@code parseAndStageAnyFormat}'s own comment, reintroduced through the
     * length bound rather than through the routing.
     *
     * <p>The CSV direction is silent rather than wrong, which is why this was never noticed: a
     * truncated {@code .csv} falls into the same default branch as a name with no extension at
     * all, so it keeps working by luck.
     */
    @Test
    void truncationKeepsTheExtension() {
        String longPdf = "b".repeat(200) + ".pdf";
        String truncated = StatementUpload.safeFileName(file(longPdf, "x"), "fallback");

        assertThat(truncated.length()).isLessThanOrEqualTo(120);
        assertThat(truncated)
                .as("the extension is what statement_imports.source_format is derived from")
                .endsWith(".pdf");
        assertThat(StatementUpload.looksLike(truncated, StatementUpload.Format.PDF))
                .as("and it must still read as a PDF to the code that asks")
                .isTrue();
    }

    /** A name that is long and has no extension must still be bounded -- the extension-preserving
     *  branch must not become a way to skip the bound. */
    @Test
    void truncationStillBoundsANameWithNoExtension() {
        assertThat(StatementUpload.safeFileName(file("c".repeat(400), "x"), "fallback").length())
                .isLessThanOrEqualTo(120);
    }

    /**
     * A dot late in a very long name is not an extension, and must not be treated as one.
     *
     * <p>Without a bound on what counts as an extension, {@code "a".repeat(300) + ".statement"}
     * and worse — a name that is one long dotted string — would let an arbitrary tail survive, or
     * produce a result that is mostly suffix. The stem has to keep enough of itself to still
     * identify the document to a human reading the admin list.
     */
    @Test
    void aLongTailIsNotTreatedAsAnExtension() {
        String odd = "d".repeat(200) + "." + "e".repeat(60);
        String truncated = StatementUpload.safeFileName(file(odd, "x"), "fallback");

        assertThat(truncated.length()).isLessThanOrEqualTo(120);
        assertThat(truncated).startsWith("dddd");
    }

    @Test
    void requireReadableRejectsANullFile() {
        assertThatThrownBy(() -> StatementUpload.requireReadable(null, StatementUpload.Format.CSV))
                .isInstanceOf(ApiException.class);
    }
}
