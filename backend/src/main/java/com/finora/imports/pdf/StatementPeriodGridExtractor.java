package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a statement's printed period from a payment-summary grid -- a label row carrying
 * "Statement Period", with the range one row below it, matched by x-overlap.
 *
 * <p>The fourth and last source of a printed period, after {@link PdfMetadataExtractor}'s
 * line-based label, {@link TransactionTableDateRangeExtractor}'s table-header reading, and
 * {@link StatementTitleDateRangeExtractor}'s title-adjacent range. It exists for the same reason
 * {@link PaymentDueDateGridExtractor} does, on the same panel of the same real documents: the
 * dense multi-column billing header is scrambled by the time it becomes
 * {@code PdfMetadataExtractor}'s line-based {@code preTableLines} view.
 *
 * <h2>The measurement this is built on</h2>
 *
 * On the real Axis credit-card statement in the corpus, the printed period was being dropped
 * entirely. Direct inspection of the section's {@code auxiliaryText} found 127 lines and <b>not one
 * containing the word "period"</b> -- so no line-based pattern, however written, could ever have
 * recovered it. The geometry, by contrast, is clean and unambiguous: the label sits on the grid's
 * header row and its range directly beneath, x-overlapping it.
 *
 * <h2>Why the label is matched as a SUBSTRING, unlike its sibling</h2>
 *
 * {@link PaymentDueDateGridExtractor} matches a whole run, because its label arrives as a run of
 * its own. This one cannot: on the real document "Statement Period" is glued to its left-hand
 * neighbour into a single run reading "Minimum Payment Due Statement Period". A whole-run match
 * would never fire, which is precisely the gap being closed.
 *
 * <p>The cost of that looser label match is a wider label x-span, and therefore more candidate
 * values overlapping it. That is paid for by a strict value shape: only a full, parseable
 * <em>range</em> is ever accepted, both halves or nothing. A neighbouring single date -- a payment
 * due date or a statement generation date, both of which really do sit on this row -- cannot
 * satisfy it, and a half-parsed range is refused outright rather than committing a period that is
 * half known. Committing half of one is the specific hazard the line-based reader guards against
 * too: downstream, a period reads as a stated fact about the document.
 */
public final class StatementPeriodGridExtractor {

    private StatementPeriodGridExtractor() {}

    /** Mirrors the sibling extractors' own result shape rather than sharing one: each names its own
     *  source, and a shared type would invite a caller to stop caring which reading it holds. */
    public record PrintedDateRange(LocalDate start, LocalDate end) {
        public static final PrintedDateRange NONE = new PrintedDateRange(null, null);
    }

    private static final Pattern LABEL =
            Pattern.compile("(?i)\\b(?:statement|billing)\\s+period\\b");

    /** Two date-shaped halves joined by "to", a hyphen or an en dash -- the same separator set
     *  {@code PdfMetadataExtractor.PERIOD_SEPARATOR} already recognises for the line-based form. */
    private static final Pattern RANGE =
            Pattern.compile("(?i)^(.{6,}?)\\s*(?:\\bto\\b|[-–])\\s*(.{6,})$");

    /** Same value-row window as {@link PaymentDueDateGridExtractor}, and for the same reason: this
     *  is the identical "label row, value row below" panel, measured on the identical documents. */
    private static final float MAX_ROW_GAP = 40.0f;

    private static final DateTimeFormatter[] DATE_FORMATS = {
            strict("dd/MM/uuuu"), strict("d/M/uuuu"), strict("dd-MM-uuuu"),
            strict("d MMM uuuu"), strict("d MMM, uuuu"), strict("dd-MMM-uuuu"),
    };

    private static DateTimeFormatter strict(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern)
                .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    }

    public static PrintedDateRange extract(List<PositionedText> runs) {
        return extract(runs, null);
    }

    public static PrintedDateRange extract(List<PositionedText> runs, DocumentContext ctx) {
        if (runs == null || runs.isEmpty()) return PrintedDateRange.NONE;

        List<List<PositionedText>> rows = StatementSummaryExtractor.groupIntoRows(runs);
        for (int i = 0; i < rows.size(); i++) {
            PositionedText label = periodLabel(rows.get(i));
            if (label == null) continue;

            PrintedDateRange found = rangeBelow(rows, i, label);
            if (found.start() != null) {
                if (ctx != null) ctx.record("PRINTED_STATEMENT_PERIOD_GRID");
                return found;
            }
        }
        return PrintedDateRange.NONE;
    }

    /** Scans every row bucket within {@link #MAX_ROW_GAP} below the label, on the same page, for
     *  the best x-overlapping run that parses as a whole range -- not just the immediately
     *  following bucket. A real grid's own value row is split across two buckets by ~1pt of
     *  baseline jitter, so "the next bucket" is not reliably the one holding this column's value. */
    private static PrintedDateRange rangeBelow(List<List<PositionedText>> rows, int labelRowIndex,
                                               PositionedText label) {
        int page = rows.get(labelRowIndex).get(0).pageIndex();
        for (int j = labelRowIndex + 1; j < rows.size(); j++) {
            List<PositionedText> candidateRow = rows.get(j);
            PositionedText first = candidateRow.get(0);
            if (first.pageIndex() != page) break;
            if (first.y() - label.y() > MAX_ROW_GAP) break;
            PositionedText value = StatementSummaryExtractor.valueUnder(label, candidateRow);
            if (value == null) continue;
            PrintedDateRange parsed = parseRange(value.text());
            if (parsed.start() != null) return parsed;
        }
        return PrintedDateRange.NONE;
    }

    private static PositionedText periodLabel(List<PositionedText> row) {
        for (PositionedText t : row) {
            if (LABEL.matcher(t.text()).find()) return t;
        }
        return null;
    }

    /** Both halves or nothing -- see this class's own doc comment for why a partial parse is
     *  refused rather than half-committed. */
    private static PrintedDateRange parseRange(String raw) {
        Matcher m = RANGE.matcher(raw.trim().replaceAll("\\s+", " "));
        if (!m.matches()) return PrintedDateRange.NONE;
        LocalDate start = parseDate(m.group(1));
        LocalDate end = parseDate(m.group(2));
        return start != null && end != null ? new PrintedDateRange(start, end) : PrintedDateRange.NONE;
    }

    private static LocalDate parseDate(String raw) {
        String text = raw.trim();
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(text, format);
            } catch (Exception ignored) {
                // try the next format
            }
        }
        return null;
    }
}
