package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads a credit-card statement's payment due date from its own "Payment Summary" grid -- a label
 * row containing the literal column header "Payment Due Date", with its value one row below,
 * matched by x-overlap the same way {@link StatementSummaryExtractor#valueUnder} already matches a
 * savings statement's own printed totals.
 *
 * <p>Evidenced on two real documents (Axis, SBI) whose dense, multi-column "Payment Summary"/
 * billing-header panel gets scrambled once joined into {@link PdfMetadataExtractor}'s line-based
 * {@code preTableLines} view -- confirmed directly, on both documents, that the literal "Payment
 * Due Date" label text is either dropped entirely or detached from its value once several
 * same-page, similarly-positioned visual rows get joined into one text line. No line-based pattern
 * can ever recover this, because the label substring simply isn't present in any line by the time
 * {@link PdfMetadataExtractor} sees it.
 *
 * <p>The raw {@link PositionedText} geometry itself is clean on both real documents -- the label
 * and value columns align by x-overlap with no ambiguity (confirmed: on Axis, "Payment Due Date"
 * spans x=381.0-443.0 and its value "11/08/2026" spans x=393.0-431.0, fully inside; every other
 * same-row value on both documents has zero x-overlap with this label). Only the line-joining step
 * used to build {@code preTableLines} is broken -- so this reads positioned text directly, the same
 * way {@link CreditCardSummaryExtractor} already successfully reads this exact panel for its own
 * money fields, and the same "read positioned text directly, before the corrupted line view has a
 * chance to eat it" pattern {@link TransactionTableDateRangeExtractor}/
 * {@link StatementTitleDateRangeExtractor} already establish.
 *
 * <p>HDFC evidence (font/glyph-corrupted document, previously scoped entirely out on the claim that
 * "the due-date text is genuinely dropped from preTableLines"): that claim was about the LINE-based
 * view specifically, and was right about that view, but wrong that the value is unreachable at
 * all -- confirmed via direct {@link PositionedText} inspection that HDFC labels this field "DUE
 * DATE" (not "Payment Due Date"), with a clean value 17.8pt below it, x-overlapping fully
 * (x=512.1-541.6 label, x=512.1-555.0 value). Between them sits an intervening row -- HDFC's own
 * "(Including Cash)" sub-label, part of the same panel's credit-limit column -- which happens not to
 * x-overlap THIS label's column, so {@link #dueDateLabel}/{@link #valueBelow}'s forward scan (see
 * that method's own doc comment) reaches the true value correctly once the label text itself is
 * recognized.
 *
 * <p>Narrow by design: matches only a label row containing "Payment Due Date" or "Due Date" and a
 * value column-aligned directly beneath it -- not a general "find any date on this page" pattern.
 */
public final class PaymentDueDateGridExtractor {

    private PaymentDueDateGridExtractor() {}

    private static final Pattern LABEL = Pattern.compile("(?i)^(?:payment\\s+)?due\\s+date$");

    // Real evidencing gaps: Axis 12.5-13.5pt, SBI 14.95-15.74pt, HDFC 17.8pt. Generous margin above
    // all three without being unbounded, matching the same order of magnitude
    // StatementSummaryExtractor's own MAX_VALUE_ROW_GAP (40.0f) uses for an analogous "label row,
    // value row below" shape.
    private static final float MAX_ROW_GAP = 40.0f;

    private static final DateTimeFormatter[] DATE_FORMATS = {
            new DateTimeFormatterBuilder().appendPattern("dd/MM/uuuu")
                    .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM uuuu")
                    .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            // HDFC evidence: "09 Aug, 2026" -- a comma between the month and year, same
            // comma-optional coexistence PdfMetadataExtractor.DATE_FORMATS already establishes for
            // this exact shape ("d MMM, yyyy" alongside "d MMM yyyy").
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM, uuuu")
                    .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
    };

    public static LocalDate extract(List<PositionedText> runs) {
        return extract(runs, null);
    }

    public static LocalDate extract(List<PositionedText> runs, DocumentContext ctx) {
        if (runs == null || runs.isEmpty()) return null;

        List<List<PositionedText>> rows = StatementSummaryExtractor.groupIntoRows(runs);
        for (int i = 0; i < rows.size(); i++) {
            PositionedText label = dueDateLabel(rows.get(i));
            if (label == null) continue;

            LocalDate date = valueBelow(rows, i, label);
            if (date != null) {
                if (ctx != null) ctx.record("PRINTED_PAYMENT_DUE_DATE_GRID");
                return date;
            }
        }
        return null;
    }

    /** Unlike {@link StatementSummaryExtractor#rowBelow}, does not commit to the single immediately
     *  -following row bucket, and does not commit to the first row whose x-overlap merely produces
     *  SOME text -- scans every row bucket within {@link #MAX_ROW_GAP} of the label's own y, on the
     *  same page, and returns the first one whose x-overlapping text actually parses as a date.
     *
     *  <p>Needed for HDFC: its "DUE DATE" label sits several row buckets above its own value, with
     *  an intervening row ("(Including Cash)", part of the same panel's unrelated credit-limit
     *  column) in between -- see this class's own doc comment. That intervening row happens not to
     *  x-overlap this label's own column, so on HDFC specifically {@link StatementSummaryExtractor
     *  #valueUnder} alone already returns null for it and the scan continues; the date-parse check
     *  here exists for the same reason {@link CreditLimitGridExtractor#extract} needs one -- an
     *  x-overlapping-but-wrong-shaped intervening value is a real, evidenced shape on a sibling
     *  field of the same document, not a hypothetical. */
    private static LocalDate valueBelow(List<List<PositionedText>> rows, int labelRowIndex, PositionedText label) {
        List<PositionedText> labelRow = rows.get(labelRowIndex);
        int page = labelRow.get(0).pageIndex();
        for (int j = labelRowIndex + 1; j < rows.size(); j++) {
            List<PositionedText> candidateRow = rows.get(j);
            PositionedText first = candidateRow.get(0);
            if (first.pageIndex() != page) break;
            if (first.y() - label.y() > MAX_ROW_GAP) break;
            PositionedText value = StatementSummaryExtractor.valueUnder(label, candidateRow);
            if (value == null) continue;
            LocalDate date = parseDate(value.text());
            if (date != null) return date;
        }
        return null;
    }

    private static PositionedText dueDateLabel(List<PositionedText> row) {
        for (PositionedText t : row) {
            if (LABEL.matcher(t.text().trim()).matches()) return t;
        }
        return null;
    }

    private static LocalDate parseDate(String raw) {
        String text = raw.trim().replaceAll("\\s+", " ");
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
