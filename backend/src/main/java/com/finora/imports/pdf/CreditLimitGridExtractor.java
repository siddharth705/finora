package com.finora.imports.pdf;

import com.finora.imports.CsvParser;
import com.finora.imports.DocumentContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

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
 * <p>Whole-run label match anchored to the run's own full text (not a substring/regex search
 * across a line): "Credit Limit" (or, on HDFC, "Total Credit Limit") is always its own separate
 * {@link PositionedText} run on every real evidencing document (never merged with "Available
 * Credit Limit" or "Available Cash Limit", which are themselves separate runs), so an anchored,
 * case-insensitive match on one run's own trimmed text is enough -- no negative lookbehind needed
 * the way {@code PdfMetadataExtractor.GRID_CREDIT_LIMIT_LABEL}'s line-based regex requires to avoid
 * "Available Credit Limit". The optional "Total " prefix mirrors
 * {@code PdfMetadataExtractor.CREDIT_LIMIT}'s own same-line pattern, which already tolerates it.
 *
 * <p>HDFC evidence (font/glyph-corrupted document, previously scoped entirely out): confirmed via
 * direct {@link PositionedText} inspection that "TOTAL CREDIT LIMIT" and its value "C78,000" --
 * "C" is this document's own corrupted rendering of the Rupee glyph, already stripped by
 * {@link CsvParser#parseNumeric} -- sit at clean, unambiguous x-overlapping positions, 27.9pt
 * apart. The font corruption turned out to block nothing this extractor's positioned-text reading
 * depends on; the earlier "out of scope" call was about the WHOLE document's currency rendering,
 * not this specific field's own geometry, which is intact.
 *
 * <p>Narrow by design, same as {@link PaymentDueDateGridExtractor}: matches only a label row
 * containing "(Total )?Credit Limit" and a value column-aligned directly beneath it. One real
 * document (HSBC CC.pdf) still fails to extract a credit limit, for a separate, unrelated reason --
 * it fails to parse at all (zero rows staged) -- not this extractor's problem to solve.
 */
public final class CreditLimitGridExtractor {

    private CreditLimitGridExtractor() {}

    private static final Pattern LABEL = Pattern.compile("(?i)^(?:total\\s+)?credit\\s+limit$");

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

            BigDecimal creditLimit = valueBelow(rows, i, label);
            if (creditLimit != null) {
                if (ctx != null) ctx.record("PRINTED_CREDIT_LIMIT_GRID");
                return creditLimit;
            }
        }
        return null;
    }

    /** Unlike {@link StatementSummaryExtractor#rowBelow}, does not commit to the single immediately
     *  -following row bucket, and does not commit to the first row whose x-overlap merely produces
     *  SOME text -- scans every row bucket within {@link #MAX_ROW_GAP} of the label's own y, on the
     *  same page, and returns the first one whose x-overlapping text actually parses as a number.
     *
     *  <p>Both real-document gaps this covers are the SAME shape (an intervening row poaches the
     *  match before the true value row is reached), but differ in exactly how the poaching row
     *  fails, so both checks are needed:
     *
     *  <p>Evidenced on the real Axis credit.pdf: an unrelated, off-column text run ("For hassle free
     *  payments register for", part of the same header line as the label but a different visual
     *  column) sits only 4.0pt below the "Credit Limit" label, becoming its own row bucket ahead of
     *  the true value row 13.5pt down, with ZERO x-overlap against the label -- so
     *  {@link StatementSummaryExtractor#valueUnder} itself already returns null for that row, and the
     *  scan must continue past it rather than stopping at the single next bucket the way
     *  {@link PaymentDueDateGridExtractor} safely does on its own two evidencing documents (which
     *  have no such intervening sliver at all).
     *
     *  <p>Evidenced on the real HDFC credit.pdf: its "TOTAL CREDIT LIMIT" label is immediately
     *  followed, 8.5pt down, by its own sub-label "(Including Cash)" -- genuinely POSITIVE x-overlap
     *  with the label (both start near x=65-70), unlike Axis's sliver. {@code valueUnder} alone
     *  cannot tell this apart from a real value; only attempting to parse it as a number and
     *  rejecting the row when that fails (continuing to the true value row 27.9pt down) does. */
    private static BigDecimal valueBelow(List<List<PositionedText>> rows, int labelRowIndex, PositionedText label) {
        List<PositionedText> labelRow = rows.get(labelRowIndex);
        int page = labelRow.get(0).pageIndex();
        for (int j = labelRowIndex + 1; j < rows.size(); j++) {
            List<PositionedText> candidateRow = rows.get(j);
            PositionedText first = candidateRow.get(0);
            if (first.pageIndex() != page) break;
            if (first.y() - label.y() > MAX_ROW_GAP) break;
            PositionedText value = StatementSummaryExtractor.valueUnder(label, candidateRow);
            if (value == null) continue;
            BigDecimal parsed = CsvParser.parseNumeric(value.text());
            if (parsed != null) return parsed;
        }
        return null;
    }

    private static PositionedText creditLimitLabel(List<PositionedText> row) {
        for (PositionedText t : row) {
            if (LABEL.matcher(t.text().trim()).matches()) return t;
        }
        return null;
    }
}
