package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;

import java.util.List;

/**
 * Reads a credit-card statement's own masked account/card number from raw {@link PositionedText},
 * bypassing {@link PdfMetadataExtractor}'s line-based {@code preTableLines} view -- the same "read
 * positioned text directly, before the corrupted line view has a chance to eat it" pattern {@link
 * PaymentDueDateGridExtractor}/{@link CreditCardSummaryExtractor} already establish for this exact
 * document family.
 *
 * <p>Evidenced on a real Axis credit-card statement, which prints its own card number TWICE, in two
 * different real layouts, neither of which survives into {@code preTableLines} (both get folded
 * into the same scrambled "Credit Card Number Previous Balance - Payments - ..." line {@link
 * PaymentDueDateGridExtractor}'s own doc comment already documents for this document's payment-due-
 * date field): once as a stacked label-row/value-row GRID ("Credit Card Number" then, one row
 * below, its masked value), and once as a SAME-ROW label-left/value-right pair ("Card No:"
 * immediately followed on the same visual row by the identical value). Both strategies are tried,
 * GRID first since it is the more specific match once a label has actually anchored a row;
 * SAME-ROW is the fallback a grid-only reading would miss.
 *
 * <p>Reuses {@link StatementSummaryExtractor}'s row-grouping/value-matching utilities and {@link
 * PdfMetadataExtractor}'s {@code CARD_NUMBER_LABEL}/{@code CARD_NUMBER_VALUE}/
 * {@code looksLikeCardOrAccountNumber}/{@code normalizeCardOrAccountNumberValue} (all widened to
 * package-private for exactly this reuse) rather than re-declaring the same label/value vocabulary
 * a second time to drift from the line-based reading's own copy.
 *
 * <p>Narrow by design: matches only a row whose label text is {@link
 * PdfMetadataExtractor#CARD_NUMBER_LABEL}-shaped, with a card/account-number-shaped value either
 * directly below it or immediately to its right on the same row -- not a general "find any masked
 * number on this page" pattern.
 */
public final class AccountNumberGridExtractor {

    private AccountNumberGridExtractor() {}

    // Same order of magnitude as PaymentDueDateGridExtractor's own MAX_ROW_GAP, for the same
    // "label row, value row below" grid shape this document family already establishes.
    private static final float MAX_ROW_GAP = 40.0f;

    // How far to the right of a label its value may sit on the SAME row -- mirrors
    // CreditCardSummaryExtractor.SAME_ROW_MAX_X_DISTANCE's own reasoning and bound for the
    // analogous same-row money-field layout.
    private static final float SAME_ROW_MAX_X_DISTANCE = 200.0f;

    public static String extract(List<PositionedText> runs) {
        return extract(runs, null);
    }

    public static String extract(List<PositionedText> runs, DocumentContext ctx) {
        if (runs == null || runs.isEmpty()) return null;

        List<List<PositionedText>> rows = StatementSummaryExtractor.groupIntoRows(runs);
        for (int i = 0; i < rows.size(); i++) {
            PositionedText label = cardNumberLabel(rows.get(i));
            if (label == null) continue;

            String fromGrid = tryGrid(rows, i, label);
            if (fromGrid != null) {
                if (ctx != null) ctx.record("PRINTED_ACCOUNT_NUMBER_GRID");
                return fromGrid;
            }

            String fromSameRow = trySameRow(rows.get(i), label);
            if (fromSameRow != null) {
                if (ctx != null) ctx.record("PRINTED_ACCOUNT_NUMBER_GRID");
                return fromSameRow;
            }
        }
        return null;
    }

    /**
     * Unlike {@link PaymentDueDateGridExtractor}, which only ever checks the LITERAL next row
     * ({@link StatementSummaryExtractor#rowBelow}), this scans every subsequent row within {@code
     * MAX_ROW_GAP} for the first one whose column-aligned cell is card/account-number-shaped.
     * Confirmed necessary on the real Axis document this class is evidenced against: its "Credit
     * Card Number" label and value are NOT on adjacent rows -- an unrelated row (a same-page,
     * differently-positioned notice) sits physically between them, so {@code rowBelow} alone
     * finds nothing. {@link CreditCardSummaryExtractor#tryGrid}'s own {@code valueRowWithinGap}
     * cannot be reused as-is for the same reason it is not reused here at all: it classifies a
     * whole candidate row as "the value row" by requiring every cell to be NUMERIC, which a masked
     * card number (containing X/x/* mask characters) never is -- so this tries {@link
     * StatementSummaryExtractor#valueUnder} against each candidate row directly instead of
     * pre-classifying the row.
     */
    private static String tryGrid(List<List<PositionedText>> rows, int labelRowIndex, PositionedText label) {
        int page = label.pageIndex();
        float labelY = label.y();
        for (int j = labelRowIndex + 1; j < rows.size(); j++) {
            List<PositionedText> candidateRow = rows.get(j);
            if (candidateRow.isEmpty() || candidateRow.get(0).pageIndex() != page) return null;
            if (candidateRow.get(0).y() - labelY > MAX_ROW_GAP) return null;

            PositionedText value = StatementSummaryExtractor.valueUnder(label, candidateRow);
            if (value == null) continue;
            String normalized = normalizedMaskedValue(value.text());
            if (normalized != null) return normalized;
        }
        return null;
    }

    private static String trySameRow(List<PositionedText> row, PositionedText label) {
        PositionedText best = null;
        for (PositionedText candidate : row) {
            if (candidate == label) continue;
            if (candidate.x() <= label.endX()) continue;
            if (candidate.x() - label.endX() > SAME_ROW_MAX_X_DISTANCE) continue;
            if (!PdfMetadataExtractor.CARD_NUMBER_VALUE.matcher(candidate.text().trim()).matches()) continue;
            // Exactly one candidate required, the same "refuse rather than guess" discipline
            // CreditCardSummaryExtractor.trySameRow already applies for its own same-row fields.
            if (best != null) return null;
            best = candidate;
        }
        return best == null ? null : normalizedMaskedValue(best.text());
    }

    private static String normalizedMaskedValue(String rawText) {
        String candidate = rawText.trim();
        if (!PdfMetadataExtractor.looksLikeCardOrAccountNumber(candidate)) return null;
        String[] normalized = PdfMetadataExtractor.normalizeCardOrAccountNumberValue(candidate);
        return normalized[0];
    }

    // Strips a trailing colon (a real cell can read "Card No:", the label's own punctuation, not
    // intervening prose) before matching CARD_NUMBER_LABEL as a FULL match -- deliberately
    // matches(), not find(): find() would let "Card No" match as a bare PREFIX of an unrelated
    // longer word ("Card Nominee", a genuine nomination-section label on some real statements),
    // exactly the false-positive class PdfMetadataExtractor's own F22-era fixes already guard
    // against elsewhere for this same label family. A whole PositionedText cell is either the
    // label by itself or it is not -- unlike a joined line, there is no "value trails the label
    // in the same string" case here to search forward for.
    private static PositionedText cardNumberLabel(List<PositionedText> row) {
        for (PositionedText t : row) {
            String stripped = t.text().trim().replaceAll(":$", "").trim();
            if (PdfMetadataExtractor.CARD_NUMBER_LABEL.matcher(stripped).matches()) return t;
        }
        return null;
    }
}
