package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;

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
 * <p>Narrow by design: matches only a label row containing the literal "Payment Due Date" text and
 * a value column-aligned directly beneath it -- not a general "find any date on this page" pattern.
 */
public final class PaymentDueDateGridExtractor {

    private PaymentDueDateGridExtractor() {}

    private static final String LABEL_TEXT = "payment due date";

    // Real evidencing gaps: Axis 12.5-13.5pt, SBI 14.95-15.74pt. Generous margin above both
    // without being unbounded, matching the same order of magnitude StatementSummaryExtractor's
    // own MAX_VALUE_ROW_GAP (40.0f) uses for an analogous "label row, value row below" shape.
    private static final float MAX_ROW_GAP = 40.0f;

    private static final DateTimeFormatter[] DATE_FORMATS = {
            new DateTimeFormatterBuilder().appendPattern("dd/MM/uuuu")
                    .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM uuuu")
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

            List<PositionedText> valueRow = StatementSummaryExtractor.rowBelow(rows, i, MAX_ROW_GAP);
            if (valueRow == null) continue;

            PositionedText value = StatementSummaryExtractor.valueUnder(label, valueRow);
            if (value == null) continue;

            LocalDate date = parseDate(value.text());
            if (date != null) {
                if (ctx != null) ctx.record("PRINTED_PAYMENT_DUE_DATE_GRID");
                return date;
            }
        }
        return null;
    }

    private static PositionedText dueDateLabel(List<PositionedText> row) {
        for (PositionedText t : row) {
            if (t.text().trim().equalsIgnoreCase(LABEL_TEXT)) return t;
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
