package com.finora.imports.pdf;

import com.finora.imports.CsvParser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns the flat list of positioned text runs from {@link PdfTextExtractor} into rows, and rows
 * into header-keyed {@code Map<String,String>} data -- deliberately the SAME row shape
 * {@code CsvParser.zipRow()} already produces for CSV, so that {@code TransactionNormalizer} and
 * {@code StatementValidator} (which only ever operate on that map shape, nothing CSV-specific)
 * are directly reusable for PDF too. See this package's own doc comment for why that reuse
 * wasn't planned in advance -- it fell out of building this class.
 *
 * Column assignment is nearest-X bucketing against the header row's own token positions: once
 * a header row is found (matching the same hint words CsvParser's header detection uses, run
 * through the same {@link CsvParser#normalizeHeaderCell} normalization so "AMOUNT (Rs.)" and
 * "Amount(INR)" both reduce to "amount"), each header token's x becomes that column's anchor.
 * Every later row's tokens get assigned to whichever anchor they're closest to. This is what
 * correctly tells a debit amount from a credit amount even though both are plain numbers with no
 * other distinguishing feature -- their x position is the only signal, and this is the class
 * responsible for using it.
 *
 * A single PDF is no longer assumed to contain exactly one table: {@link #locateAll} splits the
 * document into one {@link LocatedSection} per detected account/table (see that method's own doc
 * comment) -- e.g. HSBC's "Composite Statement" bundles a savings-account section and a
 * credit-card section in one file. {@link #locate} remains as a single-table convenience
 * wrapper for the (still common) single-section case.
 */
@Component
public class PdfTableLocator {

    // Text runs whose y differs by less than this are treated as the same visual row. Not
    // measured against a large corpus of real statements (there is no such corpus in this
    // sandbox) -- 3pt comfortably covers normal body-text line heights without this needing to
    // be exact; revisit if a real statement's row spacing turns out to need a different value.
    private static final float ROW_Y_TOLERANCE = 3.0f;

    // How close (in the same y-units as ROW_Y_TOLERANCE) a dateless/amountless row has to sit
    // beneath the previous data row to be folded into it as a description continuation line
    // (HDFC's layout wraps a transaction's description onto a second visual row -- a customer
    // name/CKYC-ID line, then the real merchant line). Not measured against a real corpus either;
    // a small multiple of ROW_Y_TOLERANCE comfortably covers normal single-line-gap spacing
    // without also swallowing an unrelated line further down the page.
    private static final float MAX_CONTINUATION_ROW_GAP = ROW_Y_TOLERANCE * 4;

    private static final List<String> HEADER_HINTS = List.of(
            "date", "description", "debit", "credit", "balance",
            "amount", "transaction details", "transaction description", "merchant category",
            "type", "remarks", "deposits", "withdrawals", "instrument id", "details", "date & time");

    // A line naming an account-type word alongside an account-number-shaped digit run marks the
    // start of a brand-new account section -- e.g. HSBC's composite-statement banner
    // "SAVINGS ACCOUNT-RES  120-070727-006", which introduces a second account partway through a
    // single PDF. Seeing this while a section is already active closes it immediately; this is a
    // stronger, more explicit signal than the header-signature-difference fallback below, so it's
    // checked first.
    private static final Pattern SECTION_MARKER = Pattern.compile(
            "(?i)\\b(SAVINGS|CURRENT|CREDIT\\s+CARD|DEPOSIT|LOAN)\\s+ACCOUNT\\b.*\\d{4,}");

    public record LocatedTable(List<Map<String, String>> rows, List<String> preTableLines) {}

    /** One detected account/table within a document -- {@code auxiliaryText} is the free-standing
     *  text (account holder/number/branch/IFSC lines, a credit-card payment-summary block, etc.)
     *  that appeared before this section's own header row, for {@link PdfMetadataExtractor} and
     *  credit-card-signal detection to scan. */
    public record LocatedSection(List<String> auxiliaryText, List<Map<String, String>> rows) {}

    public record LocatedDocument(List<LocatedSection> sections) {}

    /** Single-table convenience wrapper over {@link #locateAll} for the common single-section
     *  case -- returns the FIRST section found (or an empty table with all text treated as
     *  "preTableLines" if no header was ever recognized, same "well-formed empty result rather
     *  than a 500" contract as before). Callers that need every section in a multi-account
     *  document (see {@link com.finora.imports.pdf.PdfPreviewGenerator#generateSections}) call
     *  {@link #locateAll} directly instead. */
    public LocatedTable locate(List<PositionedText> positionedText) {
        LocatedDocument doc = locateAll(positionedText);
        if (doc.sections().isEmpty()) {
            return new LocatedTable(List.of(), rowsToLines(foldedRows(positionedText)));
        }
        LocatedSection first = doc.sections().get(0);
        return new LocatedTable(first.rows(), first.auxiliaryText());
    }

    /**
     * Splits a document into one section per detected account/table. Two independent signals
     * close the active section (if any) and open a new one:
     *   1. An explicit {@link #SECTION_MARKER} banner line (HSBC's composite statement).
     *   2. A header-shaped row whose normalized column-name set differs from the active section's
     *      own header signature -- a *repeated* header with the SAME signature (Axis repeats its
     *      header every page) is instead recognized as "more of the same table" and skipped
     *      outright, never becoming a data row.
     * Text that appears before a section's header row (or, for the very first section, before any
     * header at all) is collected as that section's {@code auxiliaryText} rather than data.
     */
    public LocatedDocument locateAll(List<PositionedText> positionedText) {
        List<List<PositionedText>> rows = foldedRows(positionedText);

        List<LocatedSection> sections = new ArrayList<>();
        List<String> pendingAuxiliary = new ArrayList<>();
        List<Map<String, String>> currentRows = null;
        List<String> headerNames = null;
        List<Float> headerAnchors = null;
        Set<String> currentHeaderSignature = null;

        for (List<PositionedText> row : rows) {
            String rowLine = lineOf(row);

            if (SECTION_MARKER.matcher(rowLine).find()) {
                if (currentRows != null) {
                    sections.add(new LocatedSection(pendingAuxiliary, currentRows));
                }
                pendingAuxiliary = new ArrayList<>();
                currentRows = null;
                headerNames = null;
                headerAnchors = null;
                currentHeaderSignature = null;
                pendingAuxiliary.add(rowLine);
                continue;
            }

            if (looksLikeHeaderRow(row)) {
                Set<String> signature = headerSignature(row);
                if (currentRows != null && signature.equals(currentHeaderSignature)) {
                    continue; // repeated header of the table already in progress -- not a data row
                }
                if (currentRows != null) {
                    // A different header shape with no explicit marker line -- fallback signal
                    // for a new section in a document without a banner line.
                    sections.add(new LocatedSection(pendingAuxiliary, currentRows));
                    pendingAuxiliary = new ArrayList<>();
                }
                headerNames = new ArrayList<>();
                headerAnchors = new ArrayList<>();
                for (PositionedText t : row) {
                    headerNames.add(t.text().trim());
                    headerAnchors.add(t.x());
                }
                currentHeaderSignature = signature;
                currentRows = new ArrayList<>();
                continue;
            }

            if (currentRows == null) {
                pendingAuxiliary.add(rowLine);
            } else {
                Map<String, String> bucketed = bucketRow(row, headerNames, headerAnchors);
                if (!bucketed.isEmpty()) currentRows.add(bucketed);
            }
        }
        if (currentRows != null) {
            sections.add(new LocatedSection(pendingAuxiliary, currentRows));
        }
        return new LocatedDocument(sections);
    }

    /** Groups raw positioned text into visual rows, then folds description-continuation rows
     *  into the data row above them, before any header/section logic ever sees them. */
    private List<List<PositionedText>> foldedRows(List<PositionedText> positionedText) {
        List<List<PositionedText>> rows = groupIntoRows(positionedText);
        return foldContinuationRows(rows);
    }

    /**
     * Folds a row that carries no date/amount-shaped token of its own into the immediately
     * preceding row, appending its text -- this is what makes HDFC's wrapped transaction
     * descriptions (a customer-name/CKYC-ID line, then the real merchant line, both on separate
     * visual rows) land as ONE row/description instead of the continuation line becoming its own
     * dateless, amountless row that {@code TransactionNormalizer.normalize()} would otherwise
     * silently drop, losing the real merchant text.
     *
     * Deliberately conservative: only folds when (a) the row has no token that itself looks like
     * a date or a monetary amount, (b) it isn't a recognized header row itself, and (c) it sits
     * within {@link #MAX_CONTINUATION_ROW_GAP} of the previous row on the same page -- a
     * genuinely unrelated line (a footer note, a page number) sitting far below the table won't
     * accidentally get folded into the last real transaction row.
     */
    private List<List<PositionedText>> foldContinuationRows(List<List<PositionedText>> rows) {
        List<List<PositionedText>> result = new ArrayList<>();
        for (List<PositionedText> row : rows) {
            boolean isContinuationCandidate = !row.isEmpty()
                    && !looksLikeHeaderRow(row)
                    && !SECTION_MARKER.matcher(lineOf(row)).find()
                    && !hasDateOrAmountToken(row)
                    && !result.isEmpty();
            if (isContinuationCandidate) {
                List<PositionedText> previous = result.get(result.size() - 1);
                float gap = Math.abs(row.get(0).y() - previous.get(previous.size() - 1).y());
                boolean samePage = row.get(0).pageIndex() == previous.get(0).pageIndex();
                if (samePage && gap <= MAX_CONTINUATION_ROW_GAP) {
                    List<PositionedText> merged = new ArrayList<>(previous);
                    merged.addAll(row);
                    result.set(result.size() - 1, merged);
                    continue;
                }
            }
            result.add(row);
        }
        return result;
    }

    private boolean hasDateOrAmountToken(List<PositionedText> row) {
        for (PositionedText t : row) {
            String s = t.text().trim();
            if (s.matches("\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}")) return true; // dd/mm/yyyy or dd-mm-yyyy
            if (s.matches("[+-]?[₹$]?\\s*[\\d,]+\\.\\d{1,2}\\s*(Dr\\.?|Cr\\.?)?")) return true; // a monetary-looking number
        }
        return false;
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
            String normalized = CsvParser.normalizeHeaderCell(t.text());
            if (HEADER_HINTS.contains(normalized)) matches++;
        }
        // "date" + at least one other recognized column name -- same two-signal requirement
        // CsvParser.findHeaderRowIndex uses for CSV, adapted to this row's token set instead of
        // a whole line's raw text.
        boolean hasDate = row.stream().anyMatch(t -> {
            String normalized = CsvParser.normalizeHeaderCell(t.text());
            return normalized.equals("date") || normalized.equals("date & time");
        });
        return hasDate && matches >= 2;
    }

    /** Normalized set of this header row's own column names -- used to tell "the same table's
     *  header, repeated on a later page" (identical signature) from "a genuinely different
     *  section's header" (a different signature), once a marker-line banner isn't present. */
    private Set<String> headerSignature(List<PositionedText> row) {
        Set<String> signature = new LinkedHashSet<>();
        for (PositionedText t : row) signature.add(CsvParser.normalizeHeaderCell(t.text()));
        return signature;
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
            lines.add(lineOf(row));
        }
        return lines;
    }

    private String lineOf(List<PositionedText> row) {
        StringBuilder line = new StringBuilder();
        for (PositionedText t : row) {
            if (!line.isEmpty()) line.append(' ');
            line.append(t.text());
        }
        return line.toString();
    }
}
