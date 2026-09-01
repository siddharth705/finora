package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a statement's period from a bare, unlabeled date range printed directly beneath the
 * document's own title -- the one real shape evidenced on a Kotak Mahindra Bank savings-account
 * statement, whose page 1 prints "Account Statement" as its title and, on the very next line, "01
 * Jul 2026 - 31 Jul 2026" with no "Statement Period"/"From"/"To" label anywhere near it.
 * {@link PdfMetadataExtractor} can never recognise this: every one of its period patterns requires
 * some label word to appear ON the date-range line itself, and this line has none.
 *
 * <p>Deliberately NOT a general "find any unlabeled date range on page 1" pattern -- a bare date
 * range floating anywhere on a page is not, by itself, reliable evidence of anything (a corpus
 * sweep across all 27 real documents currently held found this Kotak shape is the ONLY unlabeled
 * date range in the whole corpus; every other bare-looking match carried its own label on the same
 * line and is already handled by {@link PdfMetadataExtractor} or {@link
 * TransactionTableDateRangeExtractor}). Safety instead comes entirely from the document-structure
 * relationship to the title: the date range must sit directly beneath a row whose full text is (up
 * to whitespace and case) exactly "Account Statement", left-aligned with that title row within a
 * few points, and within a small vertical gap -- the same "label row, value row directly below"
 * shape {@link StatementSummaryExtractor} already reads a printed summary panel with (see its
 * {@code rowBelow}, reused here). A bare date range anywhere else on the page -- a transaction row,
 * a footer, an address block -- does not match, because it does not sit beneath this literal title
 * text at this literal offset.
 *
 * <p>Read the same way {@link TransactionTableDateRangeExtractor} is: directly over the full
 * positioned-text run, before table location or {@link PdfMetadataExtractor}'s preTableLines split
 * ever gets a chance to eat or discard this row. Wired as the last of three fallback tiers (see
 * PdfPreviewGenerator): PdfMetadataExtractor's own labelled fields first, then
 * TransactionTableDateRangeExtractor's table-header reading, then this -- run only when neither of
 * the other two ever found a period.
 */
public final class StatementTitleDateRangeExtractor {

    private StatementTitleDateRangeExtractor() {}

    private static final String TITLE_TEXT = "account statement";

    // Same value StatementSummaryExtractor.MAX_VALUE_ROW_GAP uses for its own "label row, value row
    // directly below" shape -- the real evidencing gap is 15.5pt, well inside it.
    private static final float MAX_ROW_GAP = 40.0f;

    // The real evidencing document places both rows at the identical x (33.9); a few points of
    // tolerance absorbs ordinary floating-point/kerning noise without loosening the match to
    // "roughly the same side of the page."
    private static final float X_ALIGNMENT_TOLERANCE = 3.0f;

    // Narrow to the one real observed separator ("-") rather than also accepting "to" -- no
    // evidencing document uses "to" here, and PdfMetadataExtractor's own labelled patterns already
    // cover "to"-separated ranges wherever they carry a label. Anchored start-to-end (not "find
    // anywhere in the line") so a row carrying any other text alongside the range -- e.g. a real
    // label -- is correctly left to the extractors that already handle labelled shapes.
    private static final Pattern BARE_DATE_RANGE = Pattern.compile(
            "^(\\d{1,2}\\s+[A-Za-z]{3,9}\\s+\\d{4})\\s*-\\s*(\\d{1,2}\\s+[A-Za-z]{3,9}\\s+\\d{4})$");

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    public record PrintedDateRange(LocalDate start, LocalDate end) {
        public static final PrintedDateRange NONE = new PrintedDateRange(null, null);
    }

    public static PrintedDateRange extract(List<PositionedText> runs) {
        return extract(runs, null);
    }

    public static PrintedDateRange extract(List<PositionedText> runs, DocumentContext ctx) {
        if (runs == null || runs.isEmpty()) return PrintedDateRange.NONE;

        List<List<PositionedText>> rows = StatementSummaryExtractor.groupIntoRows(runs);
        for (int i = 0; i < rows.size(); i++) {
            List<PositionedText> titleRow = rows.get(i);
            if (!isTitleRow(titleRow)) continue;

            List<PositionedText> dateRow = StatementSummaryExtractor.rowBelow(rows, i, MAX_ROW_GAP);
            if (dateRow == null) continue;
            if (Math.abs(dateRow.get(0).x() - titleRow.get(0).x()) > X_ALIGNMENT_TOLERANCE) continue;

            Matcher m = BARE_DATE_RANGE.matcher(joined(dateRow));
            if (!m.matches()) continue;

            LocalDate start = parseDate(m.group(1));
            LocalDate end = parseDate(m.group(2));
            if (start != null && end != null) {
                if (ctx != null) ctx.record("PRINTED_TITLE_ADJACENT_DATE_RANGE");
                return new PrintedDateRange(start, end);
            }
        }
        return PrintedDateRange.NONE;
    }

    private static boolean isTitleRow(List<PositionedText> row) {
        return joined(row).equalsIgnoreCase(TITLE_TEXT);
    }

    private static String joined(List<PositionedText> row) {
        StringBuilder line = new StringBuilder();
        for (PositionedText t : row) {
            if (!line.isEmpty()) line.append(' ');
            line.append(t.text());
        }
        return line.toString().trim();
    }

    private static LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw.trim().replaceAll("\\s+", " "), DATE_FORMAT);
        } catch (Exception ignored) {
            return null;
        }
    }
}
