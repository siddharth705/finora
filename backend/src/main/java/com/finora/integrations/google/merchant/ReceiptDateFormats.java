package com.finora.integrations.google.merchant;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * The date formats seen across merchant receipt templates so far — extracted from
 * {@code AmazonEmailParser} once a second and third caller (Ola, the C5.2 template engine) needed
 * the identical "given raw captured text, guess which format it's in" logic. Not merchant-specific:
 * every parser's own regex decides WHERE the date text is; this only decides HOW to read it once
 * captured.
 *
 * <p>Extraction, not new capability — {@code AmazonEmailParserTest}'s existing date-parsing
 * coverage (two templates, two formats) still passes unchanged through this class, proving the
 * move didn't alter behavior.
 */
final class ReceiptDateFormats {

    /**
     * "MMM d, yyyy" (abbreviated month) is tried before "MMMM d, yyyy" (full month) deliberately —
     * the two are mutually exclusive for any real input (a captured string is either abbreviated or
     * full, never both), so the order only affects how many guaranteed-to-fail attempts a call
     * burns before its actual format succeeds, never which format wins. PhonePe/CRED both only ever
     * produce the abbreviated form; putting it first means their (now two of six) callers succeed
     * on the first try instead of the second.
     */
    private static final List<DateTimeFormatter> FORMATS = List.of(
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH));

    private ReceiptDateFormats() {}

    /** Null rather than throwing — an unrecognised format is the same "template moved" signal
     *  every caller already reports as {@code ParserResult.malformed}, not a crash. */
    static LocalDate tryParse(String rawText) {
        if (rawText == null) return null;
        for (DateTimeFormatter format : FORMATS) {
            try {
                return LocalDate.parse(rawText.strip(), format);
            } catch (DateTimeParseException ignored) {
                // Try the next format -- merchant templates are not consistent about this.
            }
        }
        return null;
    }
}
