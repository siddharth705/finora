package com.finora.imports;

import com.finora.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;

/**
 * What an uploaded statement has to be before the engine spends anything on it.
 *
 * <p>Both staging endpoints used to accept a {@code MultipartFile} with no validation of any
 * kind -- no emptiness check, no content-type check, no extension check, no magic-byte sniff --
 * and inferred the format purely from which URL had been called: anything posted to
 * {@code /csv/stage} was treated as CSV, anything posted to {@code /pdf/stage} as PDF.
 *
 * <p>The consequences were bounded rather than severe, and worth stating precisely, because the
 * layers that bound them are real: the parsers fail closed, {@code max-file-size} caps uploads at
 * 10 MB, {@code importStageLimiter} bounds call frequency, and storage never trusts the filename
 * (object keys come from {@code ContentAddress.forContent}, and
 * {@code FilesystemStatementStorage.resolve} independently enforces containment). What was
 * actually missing is three things: a clean 415 instead of a confusing parse failure when someone
 * posts a PDF to the CSV endpoint; a cheap rejection BEFORE the expensive parse that
 * {@code ImportConcurrencyLimiter} exists to protect; and any control at all over an
 * attacker-chosen filename that is persisted on {@code ImportSession} and {@code StatementImport},
 * rendered in the admin Diagnostics list, and echoed back in a {@code Content-Disposition} header.
 *
 * <p>A static utility rather than a bean, in the same spirit as {@code CsvParser} and
 * {@code StatementSummaryExtractor}: bytes in, a verdict out, no collaborators and no state.
 */
public final class StatementUpload {

    private StatementUpload() {}

    /** Every PDF begins with this, per the specification's own file-structure rule. */
    private static final byte[] PDF_MAGIC = { '%', 'P', 'D', 'F', '-' };

    /** Long enough for any real statement filename; short enough that the stored value, the
     *  admin list cell and the response header all stay bounded. */
    private static final int MAX_FILE_NAME_LENGTH = 120;

    public enum Format {
        CSV("text/csv", ".csv"),
        PDF("application/pdf", ".pdf");

        private final String contentType;
        private final String extension;

        Format(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        public String contentType() { return contentType; }
        public String extension() { return extension; }
    }

    /**
     * Rejects an upload the engine has no business parsing.
     *
     * <p>Deliberately lenient about what it ACCEPTS and strict only about what it can positively
     * disprove. Browsers and mobile clients disagree wildly about the content type they attach to
     * a .csv (text/csv, application/vnd.ms-excel, text/plain, application/octet-stream), so
     * requiring a specific one would reject real uploads for a cosmetic reason. Magic bytes are
     * the opposite: {@code %PDF-} is definitional, so its presence or absence is evidence rather
     * than a hint, and that is what the format check rests on.
     *
     * @throws ApiException 400 when the file is absent or empty, 415 when the bytes plainly are
     *                      not the format this endpoint parses
     */
    public static void requireReadable(MultipartFile file, Format expected) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "No file was uploaded, or the file is empty.");
        }

        boolean looksLikePdf = startsWithPdfMagic(file);
        if (expected == Format.PDF && !looksLikePdf) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "This endpoint reads PDF statements, and this file is not a PDF. "
                            + "If it is a CSV or spreadsheet export, upload it as a CSV instead.");
        }
        if (expected == Format.CSV && looksLikePdf) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "This file is a PDF, and this endpoint reads CSV statements. "
                            + "Upload it as a PDF statement instead.");
        }
    }

    /**
     * A filename safe to persist, to render in the admin portal, and to put in a
     * {@code Content-Disposition} header.
     *
     * <p>{@code file.getOriginalFilename()} is attacker-chosen and was previously taken verbatim
     * with only a null fallback. This is not a path-traversal fix -- storage never derives a key
     * from the filename -- it is about the value being stored and displayed. Strips directory
     * separators (some clients send a full path), removes control characters including CR and LF
     * so the value cannot forge a log line or inject a response header, and bounds the length.
     *
     * @param fallback used when nothing usable survives, so a caller always gets a real name
     */
    public static String safeFileName(MultipartFile file, String fallback) {
        String raw = file == null ? null : file.getOriginalFilename();
        if (raw == null || raw.isBlank()) return fallback;

        // Last segment only: "C:\Users\x\jan.pdf" and "../../jan.pdf" both become "jan.pdf".
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("\\p{Cntrl}", "").trim();

        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) return fallback;
        if (name.length() > MAX_FILE_NAME_LENGTH) {
            name = name.substring(0, MAX_FILE_NAME_LENGTH);
        }
        return name;
    }

    /** True when the first bytes are {@code %PDF-}. Reads only the header, never the whole file:
     *  this runs before the concurrency limiter's expensive work, and pulling 10 MB into memory to
     *  decide whether to reject it would defeat the point. */
    private static boolean startsWithPdfMagic(MultipartFile file) {
        try (var in = file.getInputStream()) {
            byte[] header = in.readNBytes(PDF_MAGIC.length);
            if (header.length < PDF_MAGIC.length) return false;
            for (int i = 0; i < PDF_MAGIC.length; i++) {
                if (header[i] != PDF_MAGIC[i]) return false;
            }
            return true;
        } catch (Exception e) {
            // Unreadable stream is not evidence of format either way. Let the parser produce the
            // real error rather than guessing 415 here.
            return false;
        }
    }

    /** Whether a filename claims a format, for callers that only have the name (e.g. a stored
     *  statement being re-read). Not used to ACCEPT an upload -- extensions are a claim, not
     *  evidence -- only to label one. */
    public static boolean looksLike(String fileName, Format format) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(format.extension());
    }
}
