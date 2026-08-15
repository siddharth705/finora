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

    private static final List<DateTimeFormatter> FORMATS = List.of(
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH));

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
