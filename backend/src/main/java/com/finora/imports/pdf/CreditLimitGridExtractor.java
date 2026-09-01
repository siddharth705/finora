package com.finora.imports.pdf;

import com.finora.imports.CsvParser;
import com.finora.imports.DocumentContext;

import java.math.BigDecimal;
import java.util.List;

/**
 * Reads a credit-card statement's own credit limit from its "Payment Summary" grid -- a label row
 * containing the literal column header "Credit Limit", with its value one row below, matched by
 * x-overlap. Same mechanism, same two real evidencing documents (Axis, SBI), as
 * {@link PaymentDueDateGridExtractor} -- see that class's own doc comment for why the dense,
 * multi-column payment-summary panel gets scrambled once joined into {@link PdfMetadataExtractor}'s
 * line-based {@code preTableLines} view: several same-page, similarly-positioned visual rows are
 * joined into one text line, detaching "Credit Limit" from its own value the same way "Payment Due
 * Date" is detached from its.
 *
 * <p>Confirmed directly on both real documents that the raw {@link PositionedText} geometry itself
 * is clean: on Axis, "Credit Limit" spans x=172.0-210.0 and its value "219,000.00" spans
 * x=173.0-209.0 (fully inside), 13.5pt below; on SBI, "Credit Limit" spans x=43.3-84.6 and its value
 * "1,00,000.00" spans x=63.0-105.1 (positive overlap), 16.6pt below. Both comfortably inside
 * {@link PaymentDueDateGridExtractor}'s own {@code MAX_ROW_GAP} margin, reused here rather than
 * re-derived.
 *
 * <p>Exact-token label match, not a substring/regex search across a line: "Credit Limit" is always
 * its own separate {@link PositionedText} run on both real documents (never merged with "Available
 * Credit Limit" or "Available Cash Limit", which are themselves separate runs), so an exact,
 * case-insensitive match on one run's own trimmed text is enough -- no negative lookbehind needed
 * the way {@code PdfMetadataExtractor.GRID_CREDIT_LIMIT_LABEL}'s line-based regex requires to avoid
 * "Available Credit Limit".
 *
 * <p>Narrow by design, same as {@link PaymentDueDateGridExtractor}: matches only a label row
 * containing the literal "Credit Limit" text and a value column-aligned directly beneath it. Two
 * other real documents (HDFC, HSBC) also fail to extract a credit limit today, but for unrelated,
 * out-of-scope reasons -- HDFC's own currency glyphs are corrupted by an unmapped font (its whole
 * statement, not just this field, needs a separate investigation), and HSBC CC.pdf fails to parse
 * at all (zero rows staged). Neither is this extractor's problem to solve.
 */
public final class CreditLimitGridExtractor {

    private CreditLimitGridExtractor() {}

    private static final String LABEL_TEXT = "credit limit";

    // Same margin as PaymentDueDateGridExtractor's own MAX_ROW_GAP: real evidencing gaps are 13.5pt
    // (Axis) and 16.6pt (SBI), comfortably inside without being unbounded.
    private static final float MAX_ROW_GAP = 40.0f;

    public static BigDecimal extract(List<PositionedText> runs) {
        return extract(runs, null);
    }

    public static BigDecimal extract(List<PositionedText> runs, DocumentContext ctx) {
        if (runs == null || runs.isEmpty()) return null;

        List<List<PositionedText>> rows = StatementSummaryExtractor.groupIntoRows(runs);
        for (int i = 0; i < rows.size(); i++) {
            PositionedText label = creditLimitLabel(rows.get(i));
            if (label == null) continue;

            PositionedText value = valueBelow(rows, i, label);
            if (value == null) continue;

            BigDecimal creditLimit = CsvParser.parseNumeric(value.text());
            if (creditLimit != null) {
                if (ctx != null) ctx.record("PRINTED_CREDIT_LIMIT_GRID");
                return creditLimit;
            }
        }
        return null;
    }

    /** Unlike {@link StatementSummaryExtractor#rowBelow}, does not commit to the single immediately
     *  -following row bucket -- scans every row bucket within {@link #MAX_ROW_GAP} of the label's own
     *  y, on the same page, stopping at the first one whose x-overlap with the label actually
     *  produces a value.
     *
     *  <p>Evidenced on the real Axis credit.pdf: an unrelated, off-column text run ("For hassle free
     *  payments register for", part of the same header line as the label but a different visual
     *  column) sits only 4.0pt below the "Credit Limit" label -- inside {@code ROW_TOLERANCE} of
     *  nothing else, so it becomes its OWN row bucket, immediately after the label's, and well before
     *  the true value row 13.5pt down. A strict "next bucket wins" read (rowBelow's own contract, and
     *  what {@link PaymentDueDateGridExtractor} uses -- safe there because its label/value pair has no
     *  such intervening sliver on either real evidencing document) would land on that sliver instead,
     *  find zero x-overlap with the label, and return null even though the true value is one row
     *  further down and well within the search window. */
    private static PositionedText valueBelow(List<List<PositionedText>> rows, int labelRowIndex, PositionedText label) {
        List<PositionedText> labelRow = rows.get(labelRowIndex);
        int page = labelRow.get(0).pageIndex();
        for (int j = labelRowIndex + 1; j < rows.size(); j++) {
            List<PositionedText> candidateRow = rows.get(j);
            PositionedText first = candidateRow.get(0);
            if (first.pageIndex() != page) break;
            if (first.y() - label.y() > MAX_ROW_GAP) break;
            PositionedText value = StatementSummaryExtractor.valueUnder(label, candidateRow);
            if (value != null) return value;
        }
        return null;
    }

    private static PositionedText creditLimitLabel(List<PositionedText> row) {
        for (PositionedText t : row) {
            if (t.text().trim().equalsIgnoreCase(LABEL_TEXT)) return t;
        }
        return null;
    }
}
