package com.finora.support;

import com.finora.exception.ApiException;
import com.finora.imports.StatementUpload;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * What a support-ticket attachment has to be before it is stored.
 *
 * <p>Modelled on {@link StatementUpload}, whose own doc argues the point this class relies on:
 * browsers and mobile clients disagree wildly about the content type they attach to a file, so
 * requiring a specific one rejects real uploads for a cosmetic reason. Magic bytes are the
 * opposite — definitional where the format has them — so PDF, PNG and JPEG are identified that
 * way. Plain text has no magic bytes to check, so it falls back to the one thing that IS evidence
 * for it: the bytes decode as UTF-8 with no NUL — see {@link #looksLikeText}.
 *
 * <p>A static utility, not a bean, in the same spirit as {@code StatementUpload}: bytes in, a
 * verdict out, no collaborators and no state.
 */
public final class SupportAttachmentUpload {

    private SupportAttachmentUpload() {}

    /** V146's own size guidance: attachments are "small, low-volume" by design, well under the
     *  10 MB Spring-wide {@code max-file-size} — that ceiling exists for statement uploads, not
     *  for a support screenshot or a one-page PDF. */
    private static final long MAX_ATTACHMENT_SIZE_BYTES = 5L * 1024 * 1024;

    private static final byte[] PDF_MAGIC = { '%', 'P', 'D', 'F', '-' };
    private static final byte[] PNG_MAGIC = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };
    private static final byte[] JPEG_MAGIC = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };

    public enum Format {
        PDF("application/pdf"),
        PNG("image/png"),
        JPEG("image/jpeg"),
        TXT("text/plain");

        private final String contentType;

        Format(String contentType) { this.contentType = contentType; }

        public String contentType() { return contentType; }
    }

    /** The result of a successful validation: a sanitized filename, the detected format, the raw
     *  bytes, and their hex SHA-256 — everything {@code SupportTicketService} needs to build a
     *  {@code SupportTicketAttachment} without re-deriving any of it. */
    public record Validated(Format format, String filename, byte[] content, String sha256Hash) {}

    /**
     * @throws ApiException 400 if the file is absent, empty, or over {@link #MAX_ATTACHMENT_SIZE_BYTES};
     *                      415 if the bytes match none of PDF, PNG, JPEG or plain text
     */
    public static Validated validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No file was uploaded, or the file is empty.");
        }
        if (file.getSize() > MAX_ATTACHMENT_SIZE_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Attachments are limited to 5 MB.");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Could not read the uploaded file.");
        }

        Format format = detectFormat(content);
        if (format == null) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Attachments must be a PDF, PNG, JPEG, or plain text file.");
        }

        // Reuses StatementUpload's own sanitizer rather than a second copy: same attacker-chosen
        // value, same reasons to strip it (persisted, rendered in an admin list, echoed in a
        // Content-Disposition header), same 120-char bound V146's filename column declares.
        String filename = StatementUpload.safeFileName(file, "attachment");

        return new Validated(format, filename, content, sha256Hex(content));
    }

    private static Format detectFormat(byte[] content) {
        if (startsWith(content, PDF_MAGIC)) return Format.PDF;
        if (startsWith(content, PNG_MAGIC)) return Format.PNG;
        if (startsWith(content, JPEG_MAGIC)) return Format.JPEG;
        if (looksLikeText(content)) return Format.TXT;
        return null;
    }

    private static boolean startsWith(byte[] content, byte[] magic) {
        if (content.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (content[i] != magic[i]) return false;
        }
        return true;
    }

    /** Plain text has no magic bytes, so this is the closest thing to evidence: the whole file
     *  must decode as strict UTF-8 (malformed sequences rejected, not replaced) with no NUL byte —
     *  a PDF, PNG or JPEG that fell through the checks above will not survive either test. */
    private static boolean looksLikeText(byte[] content) {
        for (byte b : content) {
            if (b == 0) return false;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static String sha256Hex(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // Every JDK ships SHA-256; this is not a reachable branch in practice.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
