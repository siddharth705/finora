package com.finora.imports.pdf;

import com.finora.imports.CsvParser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
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

    // Column-name hints for locating "the date column" within an already-bucketed row -- used by
    // the continuation-row merge in locateAll() below, kept in sync with
    // TransactionNormalizer's own date hints (not reused directly since that method also checks
    // a couple of CSV-only variants that never apply to a PDF-bucketed row).
    private static final List<String> DATE_HINTS = List.of(
            "date", "transaction date", "txn date", "value date", "date & time");

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

    // A trailing amount (optionally Dr/Cr-suffixed) embedded at the end of an otherwise-ordinary
    // cell's text, e.g. "FUEL SURCHARGE                                  10.00 Dr" or
    // "MEDICAL 500.00 Dr" -- see splitTrailingAmountIfMissing's own doc comment for why this comes
    // up at all (some rows in a real statement render a fee/charge line's label and its amount as
    // ONE combined PDFBox text run, not the usual two separate ones bucketRow's per-run logic
    // expects). Requires two decimal places, matching every amount format already handled
    // elsewhere in this pipeline.
    private static final Pattern TRAILING_AMOUNT = Pattern.compile(
            "(?i)^(.*\\S)\\s+([\\d,]+\\.\\d{2}\\s*(?:dr|cr)?\\.?)\\s*$");

    // A page-footer/page-number line ("Page 1 of 2") has no date of its own, same as a genuine
    // continuation line -- but it isn't one, and merging it into the last real row on that page
    // pollutes (or, if it lands in the amount column, outright breaks parsing of) an otherwise
    // valid transaction. Loosely matched (just "page" ... "of" as substrings) rather than a strict
    // "Page \d+ of \d+" shape, since a real PDF's page-number glyphs don't always extract as plain
    // ASCII digits (verified against a real Union Bank of India statement, whose page-number line
    // extracted as "Page �1� of� 2" -- a font/encoding artifact on the digits
    // themselves, not just an isolated quirk this pattern needs to special-case digit-by-digit).
    private static final Pattern PAGE_FOOTER = Pattern.compile("(?i)\\bpage\\b.*\\bof\\b");

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
            return new LocatedTable(List.of(), rowsToLines(groupIntoRows(positionedText)));
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
        List<List<PositionedText>> rows = groupIntoRows(positionedText);

        List<LocatedSection> sections = new ArrayList<>();
        List<String> pendingAuxiliary = new ArrayList<>();
        List<Map<String, String>> currentRows = null;
        List<String> headerNames = null;
        List<Float> headerAnchors = null;
        Set<String> currentHeaderSignature = null;
        Integer lastRowPage = null; // page index of the most recently added row in currentRows

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
                lastRowPage = null;
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
                lastRowPage = null;
                continue;
            }

            if (currentRows == null) {
                pendingAuxiliary.add(rowLine);
            } else if (PAGE_FOOTER.matcher(rowLine).find()) {
                continue; // a page-number line is never a transaction or a continuation of one
            } else {
                Map<String, String> bucketed = bucketRow(row, headerNames, headerAnchors);
                if (bucketed.isEmpty()) continue;

                // Bug fix: a description that wraps onto a second visual row (HDFC's layout --
                // see this method's own doc comment) used to be handled by a y-distance heuristic
                // ("fold anything within N points of the previous row that has no date/amount
                // token") -- verified against a REAL uploaded HDFC statement to be badly wrong:
                // ordinary single-line spacing between UNRELATED lines throughout the whole
                // document (page header, footer notes, disclaimer text) is well within any y-gap
                // threshold that also covers genuine same-cell line wrapping, so it collapsed the
                // entire transaction table -- and the surrounding letterhead -- into one garbage
                // row. The real, reliable signal is structural, not positional: a continuation
                // row is one with NO value in the date column at all. Every genuine transaction
                // row has its own date; a wrapped second line of the same transaction (or, in the
                // real HDFC file, the line the amount itself lands on) does not. Merging is scoped
                // to rows already inside a known table (this loop only runs once a header has
                // been found) and stops the moment a new header/section marker is seen, so it can
                // never reach into unrelated document text the way the old y-gap check could.
                //
                // Second bug fix, same session, a different real file (Union Bank of India): a
                // repeated per-page title banner ("Savings Account," on its own line at the top of
                // page 2) also has no date, and without a page-boundary guard it merged into the
                // LAST row of page 1 instead -- crossing a page break is never a real continuation
                // of a transaction, so this is scoped to same-page rows only, same spirit as never
                // crossing a header/section boundary above.
                boolean samePage = lastRowPage != null && !row.isEmpty() && row.get(0).pageIndex() == lastRowPage;
                if (!hasDateValue(bucketed) && !currentRows.isEmpty() && samePage) {
                    mergeInto(currentRows.get(currentRows.size() - 1), bucketed);
                } else {
                    currentRows.add(bucketed);
                    lastRowPage = row.isEmpty() ? lastRowPage : row.get(0).pageIndex();
                }
            }
        }
        if (currentRows != null) {
            sections.add(new LocatedSection(pendingAuxiliary, currentRows));
        }
        return new LocatedDocument(sections);
    }

    private boolean hasDateValue(Map<String, String> bucketed) {
        return CsvParser.firstNonBlank(bucketed, DATE_HINTS.toArray(new String[0])) != null;
    }

    /** Merges a continuation row's non-blank column values into the transaction row above it --
     *  per column, appending with a space when both already have a value (same join convention
     *  {@link #bucketRow} itself uses for two text runs landing in the same column), or simply
     *  filling it in when the target's own value for that column is blank/absent. */
    private void mergeInto(Map<String, String> target, Map<String, String> continuation) {
        for (Map.Entry<String, String> e : continuation.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            String existing = target.get(e.getKey());
            target.put(e.getKey(), (existing == null || existing.isBlank()) ? e.getValue() : existing + " " + e.getValue());
        }
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

    // No real statement header seen so far (across every capability this class handles) has more
    // than 6 columns -- a generous ceiling, not a tight fit to any one layout. Bug fix, found
    // against a real Axis Bank credit-card statement's fine-print "Schedule of Charges" boilerplate:
    // wrapped paragraph text gets split into many small PDFBox text runs (one or two words each),
    // and a long enough paragraph has decent odds of containing two of them that happen to be bare
    // HEADER_HINTS words ("date", "amount") purely as ordinary English, at which point the old
    // hasDate+matches>=2 check alone misread an entire sentence as a new table's header -- closing
    // the real transaction section early and opening a second, bogus one (fine print masquerading
    // as a second account). A genuine header row is a short, deliberate list of column names; a
    // 13-cell row is prose, not a header, regardless of what two of its words happen to be.
    private static final int MAX_HEADER_ROW_CELLS = 8;

    private boolean looksLikeHeaderRow(List<PositionedText> row) {
        if (row.size() > MAX_HEADER_ROW_CELLS) return false;
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
            // Bug fix, found against a real Axis Bank credit-card statement: a date cell holds
            // exactly one value, so once it already has one that fully parses as a date, a FURTHER
            // run whose x happens to be nearest to that same column doesn't actually belong there
            // -- it belongs in the next column over. That statement's "TRANSACTION DETAILS" header
            // cell sits at x=183.5, but the column's own description data starts at x=90.25 (this
            // layout centers header labels over a wide column while data is left-aligned within
            // it) -- much nearer to the DATE column's anchor (49.5) than to its own, so plain
            // nearest-anchor silently swallowed every description into the DATE cell, and every
            // row was dropped downstream for having an unparseable date. Deliberately narrow
            // (date-specific, not a general "advance past a full column" rule for every column):
            // unlike a date, an amount or description column can legitimately receive more than
            // one text run on the same row (PDFBox splitting one multi-word cell into several
            // runs), so a general rule would risk breaking that instead.
            if (existing != null && isDateColumn(columnName) && CsvParser.parseDate(existing.trim()) != null
                    && nearest + 1 < headerNames.size()) {
                nearest = nearest + 1;
                columnName = headerNames.get(nearest);
                existing = result.get(columnName);
            }
            // Same shape as the date redirect above, for the opposite end of the row: an amount
            // (a plain number, optionally Dr/Cr-suffixed) that would otherwise be appended onto an
            // already-non-blank description or merchant-category cell almost certainly overshot
            // its own, later, amount-shaped column instead -- e.g. a short amount like "500.00 Dr"
            // sitting nearer to a short merchant-category word like "MEDICAL" than to the amount
            // column's own header anchor. Redirects forward to the nearest LATER amount-shaped
            // column, never backward, and never into an otherwise-empty cell (a genuinely blank
            // merchant-category column with just a number in it is left alone).
            if (existing != null && !isAmountColumn(columnName) && CsvParser.parseNumeric(t.text().trim()) != null) {
                int laterAmountColumn = nextAmountColumn(headerNames, nearest);
                if (laterAmountColumn >= 0) {
                    nearest = laterAmountColumn;
                    columnName = headerNames.get(nearest);
                    existing = result.get(columnName);
                }
            }
            // Two text runs landing in the same column on the same row (e.g. a multi-word
            // description PDFBox split into separate runs) get joined with a space rather than
            // the second one silently overwriting the first.
            result.put(columnName, existing == null ? t.text() : existing + " " + t.text());
        }
        splitTrailingAmountIfMissing(result, headerNames);
        return result;
    }

    // Handles the case the two redirects above can't: some rows in a real statement render a
    // fee/charge line's label and its amount as ONE combined PDFBox text run to begin with (e.g.
    // "FUEL SURCHARGE                                  10.00 Dr" as a single run, internal spacing
    // baked in to visually right-align the number) rather than the usual two separate runs -- so
    // there's no separate run for the per-run redirects to catch. Only acts when this row's single
    // "amount" column (the DR_CR_SUFFIX capability's shape specifically -- see AMOUNT_COLUMN_HINTS'
    // broader definition, deliberately not reused here) came back with no value at all, and only
    // ever pulls off a trailing amount, never touches a column that already has one.
    private void splitTrailingAmountIfMissing(Map<String, String> result, List<String> headerNames) {
        String amountColumn = headerNames.stream()
                .filter(h -> CsvParser.normalizeHeaderCell(h).equals("amount"))
                .findFirst().orElse(null);
        if (amountColumn == null || result.containsKey(amountColumn)) return;
        for (String column : List.copyOf(result.keySet())) {
            Matcher m = TRAILING_AMOUNT.matcher(result.get(column));
            if (m.matches()) {
                result.put(column, m.group(1));
                result.put(amountColumn, m.group(2));
                return;
            }
        }
    }

    private boolean isDateColumn(String columnName) {
        String normalized = CsvParser.normalizeHeaderCell(columnName);
        return normalized.equals("date") || normalized.equals("date & time");
    }

    private static final List<String> AMOUNT_COLUMN_HINTS = List.of("amount", "debit", "credit", "balance");

    private boolean isAmountColumn(String columnName) {
        return AMOUNT_COLUMN_HINTS.contains(CsvParser.normalizeHeaderCell(columnName));
    }

    private int nextAmountColumn(List<String> headerNames, int afterIndex) {
        for (int i = afterIndex + 1; i < headerNames.size(); i++) {
            if (isAmountColumn(headerNames.get(i))) return i;
        }
        return -1;
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
