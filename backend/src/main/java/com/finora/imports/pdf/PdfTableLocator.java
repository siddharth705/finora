package com.finora.imports.pdf;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the flat list of positioned text runs from {@link PdfTextExtractor} into rows, and rows
 * into header-keyed {@code Map<String,String>} data -- deliberately the SAME row shape
 * {@code CsvParser.zipRow()} already produces for CSV, so that {@code TransactionNormalizer} and
 * {@code StatementValidator} (which only ever operate on that map shape, nothing CSV-specific)
 * are directly reusable for PDF too. See this package's own doc comment for why that reuse
 * wasn't planned in advance -- it fell out of building this class.
 *
 * Column assignment is nearest-X bucketing against the header row's own token positions: once
 * the header row is found (matching the same hint words CsvParser's header detection uses --
 * "date", "description", "debit", "credit", "balance"), each header token's x becomes that
 * column's anchor. Every later row's tokens get assigned to whichever anchor they're closest to.
 * This is what correctly tells a debit amount from a credit amount even though both are plain
 * numbers with no other distinguishing feature -- their x position is the only signal, and this
 * is the class responsible for using it.
 */
@Component
public class PdfTableLocator {

    // Text runs whose y differs by less than this are treated as the same visual row. Not
    // measured against a large corpus of real statements (there is no such corpus in this
    // sandbox) -- 3pt comfortably covers normal body-text line heights without this needing to
    // be exact; revisit if a real statement's row spacing turns out to need a different value.
    private static final float ROW_Y_TOLERANCE = 3.0f;

    private static final List<String> HEADER_HINTS = List.of("date", "description", "debit", "credit", "balance");

    public record LocatedTable(List<Map<String, String>> rows, List<String> preTableLines) {}

    public LocatedTable locate(List<PositionedText> positionedText) {
        List<List<PositionedText>> rows = groupIntoRows(positionedText);

        int headerRowIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (looksLikeHeaderRow(rows.get(i))) {
                headerRowIndex = i;
                break;
            }
        }

        if (headerRowIndex < 0) {
            // Nothing recognizable as a transaction table anywhere in the document -- return
            // everything as "pre-table" text (still useful to PdfMetadataExtractor) and no rows,
            // same "well-formed empty result rather than a 500" contract CSV's own
            // PreviewGenerator follows when it can't find a header either.
            return new LocatedTable(List.of(), rowsToLines(rows));
        }

        List<String> headerNames = new ArrayList<>();
        List<Float> headerAnchors = new ArrayList<>();
        for (PositionedText t : rows.get(headerRowIndex)) {
            headerNames.add(t.text().trim());
            headerAnchors.add(t.x());
        }

        List<Map<String, String>> dataRows = new ArrayList<>();
        for (int i = headerRowIndex + 1; i < rows.size(); i++) {
            Map<String, String> row = bucketRow(rows.get(i), headerNames, headerAnchors);
            if (!row.isEmpty()) dataRows.add(row);
        }

        List<String> preTableLines = rowsToLines(rows.subList(0, headerRowIndex));
        return new LocatedTable(dataRows, preTableLines);
    }

    /** Groups text runs into visual rows: same page, sorted top-to-bottom (ascending y --
     *  TextPosition.getYDirAdj() is direction-ADJUSTED, unlike raw PDF y (bottom-left origin,
     *  increases upward): it increases DOWNWARD, top-left-origin, same convention as screen/image
     *  coordinates -- confirmed empirically against the golden fixture (title text at the top of
     *  the page has the smallest y, the disclaimer at the bottom has the largest). Sorting
     *  descending here (as an earlier version of this method did, on the mistaken assumption that
     *  this was raw, non-direction-adjusted PDF space) put the bottom of the page first, which
     *  left the header row stranded mid-list with the real transaction rows on one side of it and
     *  the real metadata lines on the other -- exactly backwards from what locate() expects.
     *  Then left-to-right (ascending x) within a row. */
    private List<List<PositionedText>> groupIntoRows(List<PositionedText> positionedText) {
        List<PositionedText> sorted = new ArrayList<>(positionedText);
        sorted.sort((a, b) -> {
            if (a.pageIndex() != b.pageIndex()) return Integer.compare(a.pageIndex(), b.pageIndex());
            int byY = Float.compare(a.y(), b.y()); // ascending -- see this method's own doc comment
            return byY != 0 ? byY : Float.compare(a.x(), b.x());
        });


        List<List<PositionedText>> rows = new ArrayList<>();
        List<PositionedText> current = new ArrayList<>();
        Float currentRowY = null;
        int currentPage = -1;
        for (PositionedText t : sorted) {
            boolean sameRow = currentRowY != null && t.pageIndex() == currentPage
                    && Math.abs(t.y() - currentRowY) <= ROW_Y_TOLERANCE;
            if (!sameRow) {
                if (!current.isEmpty()) rows.add(current);
                current = new ArrayList<>();
                currentRowY = t.y();
                currentPage = t.pageIndex();
            }
            current.add(t);
        }
        if (!current.isEmpty()) rows.add(current);
        return rows;
    }

    private boolean looksLikeHeaderRow(List<PositionedText> row) {
        int matches = 0;
        for (PositionedText t : row) {
            String normalized = t.text().trim().toLowerCase();
            if (HEADER_HINTS.contains(normalized)) matches++;
        }
        // "date" + at least one amount-column name -- same two-signal requirement
        // CsvParser.findHeaderRowIndex uses for CSV, adapted to this row's token set instead of
        // a whole line's raw text.
        boolean hasDate = row.stream().anyMatch(t -> t.text().trim().equalsIgnoreCase("date"));
        return hasDate && matches >= 2;
    }

    private Map<String, String> bucketRow(List<PositionedText> row, List<String> headerNames, List<Float> headerAnchors) {
        Map<String, String> result = new LinkedHashMap<>();
        for (PositionedText t : row) {
            int nearest = nearestColumn(t.x(), headerAnchors);
            String columnName = headerNames.get(nearest);
            String existing = result.get(columnName);
            // Two text runs landing in the same column on the same row (e.g. a multi-word
            // description PDFBox split into separate runs) get joined with a space rather than
            // the second one silently overwriting the first.
            result.put(columnName, existing == null ? t.text() : existing + " " + t.text());
        }
        return result;
    }

    private int nearestColumn(float x, List<Float> anchors) {
        int best = 0;
        float bestDistance = Math.abs(x - anchors.get(0));
        for (int i = 1; i < anchors.size(); i++) {
            float distance = Math.abs(x - anchors.get(i));
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    private List<String> rowsToLines(List<List<PositionedText>> rows) {
        List<String> lines = new ArrayList<>();
        for (List<PositionedText> row : rows) {
            StringBuilder line = new StringBuilder();
            for (PositionedText t : row) {
                if (!line.isEmpty()) line.append(' ');
                line.append(t.text());
            }
            lines.add(line.toString());
        }
        return lines;
    }
}
