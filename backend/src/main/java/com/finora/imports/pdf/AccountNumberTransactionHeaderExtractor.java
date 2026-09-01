package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;

import java.util.List;

/**
 * Reads a credit-card statement's own masked account/card number when it is printed with NO label
 * at all, sitting directly under the transaction table's own "Date" column header, on its own row,
 * before the first real transaction row.
 *
 * <p>Evidenced on a real ICICI credit-card statement: the value survives in raw {@link
 * PositionedText} but is completely absent from {@link PdfMetadataExtractor}'s {@code
 * preTableLines} view, since {@code preTableLines} stops at the table header and this value's own
 * row sits physically BELOW the header, inside what {@link PdfTableLocator} treats as the table
 * body. It is not scrambled the way {@link AccountNumberGridExtractor}'s evidencing document's
 * value is -- it is simply never reached by a line-based reader that only looks above the table.
 * There is no adjacent label text of any kind near it; the only signal that this specific row is
 * "the account number" rather than a real transaction is its POSITION -- immediately below the
 * "Date" column of the transaction table's own header -- and its SHAPE, which a real date column
 * value never has (see below).
 *
 * <p>Anchors on the header row containing a cell reading exactly "Date" alongside a second cell
 * whose text contains "amount" (case-insensitive) -- the same two columns every transaction table
 * in this codebase's corpus prints together -- so this never fires on an unrelated "Date" label
 * elsewhere on the page (e.g. a "STATEMENT DATE" or "PAYMENT DUE DATE" field, which are two-word
 * labels and never co-occur on a row with an "amount" cell). From that anchor it scans forward
 * through subsequent rows (mirroring {@link AccountNumberGridExtractor#extract}'s own forward scan,
 * for the same reason: a real document's header can wrap onto two visual rows, so the very next row
 * is not necessarily the value's row) for the first row whose Date-column-aligned cell is
 * card/account-number-shaped. A genuine transaction date (e.g. "17/06/2026") never matches this
 * shape -- {@link PdfMetadataExtractor#CARD_NUMBER_VALUE} requires the whole cell to be digits/mask
 * characters only, and a date's slashes break that -- so this safely returns {@code null} on any
 * statement that does not print this specific unlabeled row, without needing to distinguish "no
 * such row exists" from "the row below the header is already a real transaction" as a separate
 * case.
 *
 * <p>Reuses {@link StatementSummaryExtractor}'s row-grouping/value-matching utilities and {@link
 * PdfMetadataExtractor}'s {@code CARD_NUMBER_VALUE}/{@code looksLikeCardOrAccountNumber}/
 * {@code normalizeCardOrAccountNumberValue}, exactly as {@link AccountNumberGridExtractor} does.
 */
public final class AccountNumberTransactionHeaderExtractor {

    private AccountNumberTransactionHeaderExtractor() {}

    // Same order of magnitude as AccountNumberGridExtractor's own MAX_ROW_GAP -- the evidencing
    // document's own gap (header to value row) is ~22pt; this also comfortably covers a two-line
    // header's second line and one intervening row before giving up.
    private static final float MAX_ROW_GAP = 40.0f;

    public static String extract(List<PositionedText> runs, DocumentContext ctx) {
        if (runs == null || runs.isEmpty()) return null;

        List<List<PositionedText>> rows = StatementSummaryExtractor.groupIntoRows(runs);
        for (int i = 0; i < rows.size(); i++) {
            PositionedText dateHeader = transactionDateHeaderCell(rows.get(i));
            if (dateHeader == null) continue;

            String value = tryGrid(rows, i, dateHeader);
            if (value != null) {
                if (ctx != null) ctx.record("PRINTED_ACCOUNT_NUMBER_ABOVE_TRANSACTIONS");
                return value;
            }
        }
        return null;
    }

    private static String tryGrid(List<List<PositionedText>> rows, int headerRowIndex, PositionedText dateHeader) {
        int page = dateHeader.pageIndex();
        float headerY = dateHeader.y();
        for (int j = headerRowIndex + 1; j < rows.size(); j++) {
            List<PositionedText> candidateRow = rows.get(j);
            if (candidateRow.isEmpty() || candidateRow.get(0).pageIndex() != page) return null;
            if (candidateRow.get(0).y() - headerY > MAX_ROW_GAP) return null;

            PositionedText value = StatementSummaryExtractor.valueUnder(dateHeader, candidateRow);
            if (value == null) continue;
            String candidate = value.text().trim();
            if (!PdfMetadataExtractor.CARD_NUMBER_VALUE.matcher(candidate).matches()) continue;
            if (!PdfMetadataExtractor.looksLikeCardOrAccountNumber(candidate)) continue;
            return PdfMetadataExtractor.normalizeCardOrAccountNumberValue(candidate)[0];
        }
        return null;
    }

    private static PositionedText transactionDateHeaderCell(List<PositionedText> row) {
        PositionedText dateCell = null;
        boolean hasAmountCell = false;
        for (PositionedText t : row) {
            String stripped = t.text().trim();
            if (stripped.equalsIgnoreCase("Date")) dateCell = t;
            if (stripped.toLowerCase(java.util.Locale.ROOT).contains("amount")) hasAmountCell = true;
        }
        return hasAmountCell ? dateCell : null;
    }
}
