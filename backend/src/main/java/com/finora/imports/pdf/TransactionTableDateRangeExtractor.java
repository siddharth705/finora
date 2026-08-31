package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the transaction date range a statement states on its own table header, when that range
 * never reaches {@link PdfMetadataExtractor} at all.
 *
 * <p><b>Why this exists.</b> {@link PdfMetadataExtractor} only ever sees a section's {@code
 * auxiliaryText} -- the lines that appear BEFORE a header is recognized. A real Kotak Mahindra Bank
 * credit-card statement never states its own period there; instead, the transaction table's own
 * repeated column-header row reads "Date&nbsp;&nbsp;Transaction details from 16-Feb-2026 to
 * 15-Mar-2026&nbsp;&nbsp;Spends Area&nbsp;&nbsp;Amount (Rs.)" -- and that text is consumed as the
 * header's own column definition by {@link PdfTableLocator}, never entering auxiliaryText. Read the
 * same way {@link StatementSummaryExtractor} reads a document-wide fact the ordinary header/
 * auxiliary-text split would otherwise consume: directly over the full positioned-text run, before
 * table location ever gets a chance to eat it.
 *
 * <p>Scanned document-wide, first match wins -- the same header line repeats on every page (it is,
 * after all, a repeated table header), so later occurrences are the same fact restated, never new
 * evidence.
 */
public final class TransactionTableDateRangeExtractor {

    private TransactionTableDateRangeExtractor() {}

    // Narrow to the one real observed phrasing (see the class doc comment) rather than broadened to
    // a generic "from X to Y" -- a genuine transaction narration could otherwise coincidentally
    // contain that shape (e.g. "transferred from A/c X to Y").
    private static final Pattern TRANSACTION_DETAILS_RANGE = Pattern.compile(
            "(?i)transaction\\s+details\\s+from\\s+(\\S+)\\s+to\\s+(\\S+)");

    // Bug fix: only formats a \S+-captured token can ever satisfy -- group(1)/group(2) above stop at
    // the first whitespace character by construction, so a space-separated shape like "16 Feb, 2026"
    // can never be captured whole (the group would end at "16"). PdfMetadataExtractor.DATE_FORMATS
    // carries "d MMM, yyyy"/"d MMM yyyy" because ITS matches come from whitespace-tolerant helpers
    // (firstMatchAfter/DATE_LIKE); copying those two entries here was dead code that could never
    // actually match through this class's own regex, silently masking that no real document using
    // that phrasing has ever been evidenced through this specific trigger sentence -- the one real
    // document this extractor is evidenced from (Kotak) hyphenates its dates.
    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
    };

    public record PrintedDateRange(LocalDate start, LocalDate end) {
        public static final PrintedDateRange NONE = new PrintedDateRange(null, null);
    }

    public static PrintedDateRange extract(List<PositionedText> runs) {
        return extract(runs, null);
    }

    public static PrintedDateRange extract(List<PositionedText> runs, DocumentContext ctx) {
        if (runs == null || runs.isEmpty()) return PrintedDateRange.NONE;

        for (List<PositionedText> row : StatementSummaryExtractor.groupIntoRows(runs)) {
            String line = joined(row);
            Matcher m = TRANSACTION_DETAILS_RANGE.matcher(line);
            if (!m.find()) continue;
            LocalDate start = parseDate(m.group(1));
            LocalDate end = parseDate(m.group(2));
            if (start != null && end != null) {
                if (ctx != null) ctx.record("PRINTED_TRANSACTION_TABLE_DATE_RANGE");
                return new PrintedDateRange(start, end);
            }
        }
        return PrintedDateRange.NONE;
    }

    private static String joined(List<PositionedText> row) {
        StringBuilder line = new StringBuilder();
        for (PositionedText t : row) {
            if (!line.isEmpty()) line.append(' ');
            line.append(t.text());
        }
        return line.toString();
    }

    private static LocalDate parseDate(String raw) {
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try { return LocalDate.parse(raw, fmt); } catch (Exception ignored) {}
        }
        return null;
    }
}
