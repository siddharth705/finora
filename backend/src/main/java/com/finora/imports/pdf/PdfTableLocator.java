package com.finora.imports.pdf;

import com.finora.imports.CsvParser;
import com.finora.imports.DocumentContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
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
 *
 * <h2>Where this class stops</h2>
 *
 * This class reconstructs a document's PHYSICAL structure -- where the tables are, which runs form
 * a row, which column a value belongs to. It does not decide what any of it MEANS financially, and
 * it must not learn to. Product semantics (is this a savings ledger, a fixed deposit, a recurring
 * deposit; is this column a principal, an instalment, a maturity amount) belong downstream, in
 * product discovery and attribute extraction, where financial concepts and the evidence for them
 * are available.
 *
 * <p>Stated explicitly because the pressure to cross that line is real and arrives disguised as a
 * one-line fix. A real HDFC statement's fixed-deposit schedule extracts imperfectly here, and the
 * shortest path to improving it is a condition like {@code if (header.contains("Principal"))}
 * inside this class. That would buy one document and cost the boundary: every later product would
 * need its own vocabulary here, the rules would interact, and the layer that is supposed to be
 * purely geometric -- the one an OCR front end will eventually feed, with no vocabulary at all --
 * would be carrying a bank's terminology. Two known limitations of {@code WRAPPED_HEADER} are left
 * unfixed for exactly this reason; see that capability's registry entry in
 * docs/engineering/financial-document-intelligence-principles.md.
 */
@Component
public class PdfTableLocator {

    // Only ever written at DEBUG, and only by explainWrap -- see that method for why the
    // wrapped-header decision is explained here rather than recorded on DocumentContext.
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PdfTableLocator.class);

    // Text runs whose y differs by less than this are treated as the same visual row. Not
    // measured against a large corpus of real statements (there is no such corpus in this
    // sandbox) -- 3pt comfortably covers normal body-text line heights without this needing to
    // be exact; revisit if a real statement's row spacing turns out to need a different value.
    private static final float ROW_Y_TOLERANCE = 3.0f;

    // WRAPPED_HEADER. A header cell whose label is too long for its column is printed on two (or
    // three) visual lines, and groupIntoRows -- which only knows about y -- hands each of them to
    // looksLikeHeaderRow as a separate candidate. Neither half is the header: the semantic half
    // carries the column names but usually no date word, and the continuation half carries a date
    // word but too few recognized names to clear the density check. Measured on the committed
    // hdfc-composite-deposit-schedules trace, page 10 -- a real HDFC combined statement's
    // fixed-deposit schedule. Its upper line is 8 cells beginning "FD Number" with no date word
    // anywhere, so hasDate is false; its lower line is 7 cells of which exactly 2 are recognized
    // column names, short of the matches*3 >= size the density check requires. So NEITHER line was
    // recognized, the table was never located at all, and nine well-formed fixed deposits imported
    // as nothing while the import reported success.
    //
    // (Several of those cells read as "Xxxxxxxx" rather than as words. The trace was captured
    // before the redactor's allowlist carried any deposit vocabulary, so "Principal", "Maturity"
    // and "Rate Of Interest" were masked to same-length filler -- see TraceMetadata's own note on
    // that. It costs this document nothing here: the failure is geometric, and geometry survives
    // redaction exactly.)
    //
    // The two lines are one header and are merged before scoring. Three thresholds bound that:
    //
    // MAX_GAP -- a wrapped label's second line sits one line-height below the first (9.0pt on that
    // statement), while the table's own data rows are a full row pitch apart (19.67pt on the same
    // page). Sitting between the two is what makes "wrapped label" distinguishable from "the next
    // row", and 12pt is that gap with margin on both sides. Not a fitted constant: the data-value
    // guard in wrapsOnto is the real protection, and this only has to stay under a row pitch.
    //
    // MAX_LINES -- 3, the deepest wrap seen in a real document (this same statement's page-0
    // account summary prints its headings over three lines: "CR / Limit / ..." above
    // "Ccy / Account Type / Balance" above "DR / Amount / Balance"). That table is not located for
    // an unrelated reason -- it has no date column at all -- so 3 is an observed ceiling on the
    // shape rather than a figure any current test depends on.
    //
    // MAX_COLUMN_JOIN -- how far a continuation cell may sit from a column's anchor and still be
    // that column's second line. Needed because these labels are CENTER-aligned: the wider line
    // starts further LEFT, so "CCY" (x=117.24) sits 3.11pt left of "FD" (x=120.35) rather than
    // under it. Without an upper bound the nearest-anchor rule glues a genuinely separate
    // rightmost column onto the last one it can find -- on page 10's second header tier that put
    // "Withdrawable***" (x=428.02) into the column anchored at 261.46, 166pt away.
    private static final float HEADER_WRAP_MAX_GAP = 12.0f;
    private static final int HEADER_WRAP_MAX_LINES = 3;
    private static final float HEADER_WRAP_MAX_COLUMN_JOIN = 40.0f;


    // Column-name hints for locating "the date column" within an already-bucketed row -- used by
    // the continuation-row merge in locateAll() below, kept in sync with
    // TransactionNormalizer's own date hints (not reused directly since that method also checks
    // a couple of CSV-only variants that never apply to a PDF-bucketed row).
    private static final List<String> DATE_HINTS = List.of(
            "date", "transaction date", "txn date", "value date", "date & time");

    // Description-ish column names, kept in sync with TransactionNormalizer's own DESCRIPTION_HINTS
    // for the same reason DATE_HINTS above is -- used only to find where narration text that
    // mis-bucketed into the date column should be rehomed (see mergeInto).
    private static final List<String> DESCRIPTION_COLUMN_HINTS = List.of(
            "description", "narration", "remarks", "particulars",
            "transaction description", "transaction details");

    // Singular forms ("withdrawal"/"deposit") and "narration"/"particulars" were added after two
    // real HDFC statements failed header detection entirely -- their columns read "Withdrawal Amt.",
    // "Deposit Amt." and "Narration", none of which matched the plural/absent entries here. Matched
    // per-word by matchesAnyHint, so these also cover the qualified forms ("Closing Balance" ->
    // "balance") without needing an entry per qualifier.
    private static final List<String> HEADER_HINTS = List.of(
            "date", "description", "debit", "credit", "balance",
            "amount", "transaction details", "transaction description", "merchant category",
            "type", "remarks", "deposits", "withdrawals", "deposit", "withdrawal",
            "narration", "particulars", "instrument id", "details", "date & time");

    // A line naming an account-type word alongside an account-number-shaped digit run marks the
    // start of a brand-new account section -- e.g. HSBC's composite-statement banner
    // "SAVINGS ACCOUNT-RES  100-111111-002", which introduces a second account partway through a
    // single PDF. Seeing this while a section is already active closes it immediately; this is a
    // stronger, more explicit signal than the header-signature-difference fallback below, so it's
    // checked first.
    // Two banner shapes, because real statements use both.
    //
    // The first requires the literal word ACCOUNT ("SAVINGS ACCOUNT - <14 digits>") and is
    // unchanged. The second covers a deposit banner that names the product WITHOUT it -- a real
    // HDFC combined statement's deposit sections are headed "<kind> DEPOSIT - <number>", and the
    // committed hdfc-composite-deposit-schedules trace shows exactly that shape. Unrecognised, the
    // banner is not a marker at all, so it falls through to the dateless-row path and merges
    // BACKWARD into the last row of the section above it -- corrupting that row's final cell
    // ("24053.00 RECURRING DEPOSIT - 30000000000003", which then no longer parses as an amount).
    // The sections still split, via the header-signature-change fallback, which is why this hid:
    // the split looked right and only the last row of each preceding section was quietly wrong.
    //
    // The dash is mandatory in the second form, deliberately. "DEPOSIT" on its own is a ledger
    // column heading ("Deposits") and appears in ordinary narration text, and SECTION_MARKER's
    // digit requirement alone would not save it -- a transfer narration naming a destination
    // account has both. Requiring "DEPOSIT" immediately followed by a dash separator is what makes
    // this a banner shape rather than a word that happens to appear (see COMPOSITE_STATEMENT's own
    // "Known limitations" for the same false-positive class this is avoiding).
    private static final Pattern SECTION_MARKER = Pattern.compile(
            "(?i)\\b(SAVINGS|CURRENT|CREDIT\\s+CARD|DEPOSIT|LOAN)\\s+ACCOUNT\\b.*\\d{4,}"
                    + "|(?i)\\bDEPOSIT\\b\\s*-\\s*.*\\d{4,}");

    // The account-number-shaped run within a SECTION_MARKER banner -- 4+ digits, matching the same
    // "\\d{4,}" shape SECTION_MARKER itself requires, and tolerating the separators real account
    // numbers are printed with (HSBC's "100-111111-002"). See accountIdentityIn.
    private static final Pattern ACCOUNT_NUMBER_IN_MARKER = Pattern.compile("\\d[\\d-]{3,}\\d|\\d{4,}");

    // A trailing amount (optionally Dr/Cr-suffixed) embedded at the end of an otherwise-ordinary
    // cell's text, e.g. "FUEL SURCHARGE                                  10.00 Dr" or
    // "MEDICAL 500.00 Dr" -- see splitTrailingAmountIfMissing's own doc comment for why this comes
    // up at all (some rows in a real statement render a fee/charge line's label and its amount as
    // ONE combined PDFBox text run, not the usual two separate ones bucketRow's per-run logic
    // expects). Requires two decimal places, matching every amount format already handled
    // elsewhere in this pipeline.
    private static final Pattern TRAILING_AMOUNT = Pattern.compile(
            "(?i)^(.*\\S)\\s+([\\d,]+\\.\\d{2}\\s*(?:dr|cr)?\\.?)\\s*$");

    // A transaction amount with no dedicated Deposit/Withdrawal-column value of its own, combined
    // with the resulting running balance into one Balance cell -- e.g. "1.00 14,577.97" (a
    // cashback-reward row on a real Kotak Mahindra Bank statement, where such rows carry no value
    // in either amount column, only this combined pair). Deliberately just two decimal numbers and
    // nothing else, so an ordinary single balance value ("24,361.97") never matches.
    private static final Pattern LEADING_AMOUNT_IN_BALANCE = Pattern.compile(
            "^([\\d,]+\\.\\d{2})\\s+([\\d,]+\\.\\d{2})$");

    // A page-footer/page-number line ("Page 1 of 2") has no date of its own, same as a genuine
    // continuation line -- but it isn't one, and merging it into the last real row on that page
    // pollutes (or, if it lands in the amount column, outright breaks parsing of) an otherwise
    // valid transaction. Loosely matched (just "page" ... "of" as substrings) rather than a strict
    // "Page \d+ of \d+" shape, since a real PDF's page-number glyphs don't always extract as plain
    // ASCII digits (verified against a real Union Bank of India statement, whose page-number line
    // extracted as "Page �1� of� 2" -- a font/encoding artifact on the digits
    // themselves, not just an isolated quirk this pattern needs to special-case digit-by-digit).
    private static final Pattern PAGE_FOOTER = Pattern.compile(
            "(?i)\\bpage\\b.*\\bof\\b"
                    // A footer that numbers the page WITHOUT saying "of": a real Canara Bank
                    // statement ends each page with a bare "page 1", and a real HDFC one with
                    // "Page No .: 2". Neither says "of", so neither was excluded, and both were
                    // folded into the last transaction on the page once narration rehoming started
                    // placing text that used to be dropped. Anchored to the WHOLE line, so a
                    // narration that merely mentions a page cannot match.
                    + "|(?i)^\\s*page\\s*(no\\b[.:\\s]*)?\\d+\\s*$");

    // Same capability as PAGE_FOOTER above (PAGE_BOUNDARY_ISOLATION in the Capability Registry) --
    // a statement-closing marker line, same as a page-number footer, has no date of its own and
    // must never be folded into the last real transaction as if it were a continuation of its
    // description. Found against a real Axis Bank Neo Rupay statement whose last page ends with a
    // literal "**** End of Statement ****" line: with no exclusion for it, that text was being
    // appended onto the last real transaction's description via the ordinary trailing-continuation
    // merge (the same mechanism WRAPPED_DESCRIPTION uses for a genuine wrapped description line),
    // and the combined row was staged as one low-confidence transaction instead of the boilerplate
    // being discarded. Matched loosely (asterisks optional, whitespace-tolerant) rather than the
    // exact literal string, since the surrounding asterisk padding is decorative and could vary.
    private static final Pattern STATEMENT_CLOSING_MARKER = Pattern.compile("(?i)end\\s+of\\s+statement");

    // LEADING_NARRATION_CONTINUATION: how many dateless rows immediately after a transaction's
    // date row are still trusted to be genuinely TRAILING continuations of that same transaction,
    // before a further dateless row is instead treated as the LEADING narration of the NEXT
    // transaction (buffered forward -- see pendingLeading in locateAll()). Sized from two real,
    // independently-discovered layouts, not picked arbitrarily: HDFC's WRAPPED_DESCRIPTION needs
    // exactly 1 (a single description-wrap line); a real Canara Bank statement needs exactly 2 (a
    // transaction-time-plus-reference line, then a separate "Chq: <number>" line) -- its narration
    // wraps across several lines BEFORE its own date row, then closes with exactly these two
    // trailing detail lines before the NEXT transaction's leading narration begins. Set to the
    // larger of the two real requirements seen so far; revisit if a real document needs more.
    //
    // "Revisit if a real document needs more" happened: a real Bandhan Bank statement prints
    // exactly THREE trailing lines per transaction (the UPI narration wraps onto a payee line, a
    // VPA line, and an RRN line, all after the date row). The third exceeded this cap on every
    // transaction, so each one's last narration line was buffered forward and prepended to the
    // NEXT transaction instead -- every description in the table carried the tail of a different
    // transaction, and the last transaction lost its own tail entirely. No row was dropped and no
    // amount was wrong, which is precisely why it needed looking for.
    //
    // Raising the number would have traded one layout for another: Canara needs the boundary at
    // exactly 2, and its third dateless row genuinely IS the next transaction's leading narration.
    // The cap is not raised. See BLOCK_PITCH_TOLERANCE for the signal that separates the two cases
    // without a bigger number; this remains the answer for rows that signal offers no opinion on.
    private static final int MAX_TRAILING_CONTINUATION_ROWS = 2;

    /**
     * How closely a dateless row's line spacing must match the block's own for it to count as more
     * of the same transaction, in points.
     *
     * <p>The count cap above is a guess at a document's shape that the document itself can answer.
     * A transaction and its wrapped narration are printed as one visually continuous block, at the
     * font's line height; the gap to the NEXT transaction's block is larger, because a table puts
     * space between its rows. So "is this row still part of the block above it" is measurable:
     * compare its gap from the previous row against the gap that block already established between
     * its date row and its first continuation.
     *
     * <p>Measured on the two layouts that disagree about the count. Bandhan: 10.8pt within a
     * transaction, 16.1pt between transactions -- all three trailing lines match the pitch, so all
     * three are kept. Canara: 12pt within, and its trailing "Chq: <number>" line sits 24pt below at
     * a break in the pitch -- so no row is admitted past the cap and the boundary stays exactly
     * where MAX_TRAILING_CONTINUATION_ROWS puts it.
     *
     * <p>Deliberately a match against a pitch the SAME block established, not a threshold. A fixed
     * y-gap threshold is the heuristic this class already tried and documents as badly wrong (see
     * locateAll's own comment on the HDFC statement it collapsed): ordinary line spacing between
     * unrelated lines is indistinguishable from line spacing within a cell if all you have is a
     * constant. Comparing against a pitch this transaction itself printed asks a different, local
     * question, and it stays silent -- falling back to the count cap -- on any document whose
     * spacing is not regular.
     *
     * <p>Tight, at well under a line height: two lines of the same block are set by the same
     * leading and match to within rounding, so this only has to absorb float error, not variation.
     *
     * <p>Also the margin by which a document must prove it separates blocks at all -- see
     * {@code separatesItsBlocks} in {@link #continuesTheBlock}.
     */
    private static final float BLOCK_PITCH_TOLERANCE = 1.5f;

    /**
     * Absolute ceiling on trailing rows admitted by pitch, however well they match.
     *
     * <p>Same role as {@link #MAX_LEADING_CONTINUATION_ROWS}, and set to the same value for the
     * same reason: a document whose transaction rows and inter-row spacing happen to be identical
     * offers the pitch check no signal at all, and it would then admit every dateless row up to the
     * next date. That is a guard against pathology, not a model of narration -- a transaction whose
     * narration genuinely runs a dozen lines past its own date row has not been seen.
     */
    private static final int MAX_BLOCK_CONTINUATION_ROWS = 12;

    /**
     * How many consecutive dateless rows may accumulate as LEADING narration before the extractor
     * concludes it is not reading narration at all.
     *
     * <p>The trailing branch above was capped; this one was not, and that asymmetry silently
     * destroyed whole tables. When a layout's date column fails to bucket, no row is ever an
     * anchor, so every line falls through to the leading buffer and merges into a single map that
     * flushes as ONE row. Measured across the corpus before this cap existed:
     *
     * <pre>
     *   39-page statement   2541 lines -> 2 rows, largest cell  38,200 chars
     *   HSBC                 153 lines -> 2 rows, largest cell  12,605 chars
     *   HDFC credit card     112 lines -> 6 rows, largest cell   3,091 chars
     *   Canara (healthy!)    432 lines -> 60 rows, largest cell  1,103 chars
     * </pre>
     *
     * <p>Note the last line: even a document that parses well was carrying a 1,100-character cell,
     * so this was degrading everything and only becoming fatal at the extremes. It is also where
     * the 400-character "merchant" came from that aborted a JDBC batch and turned a misparsed Axis
     * statement into an HTTP 500.
     *
     * <p>Sized well above the real requirement rather than tightly: the Canara layout this
     * capability exists for needs a handful of leading lines, and a genuine multi-line narration
     * could plausibly run longer. The cap is a guard against pathology, not a model of narration,
     * so it should never fire on a document the engine actually understands.
     */
    private static final int MAX_LEADING_CONTINUATION_ROWS = 12;

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
        return locate(positionedText, null);
    }

    public LocatedTable locate(List<PositionedText> positionedText, DocumentContext ctx) {
        LocatedDocument doc = locateAll(positionedText, ctx);
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
        return locateAll(positionedText, null);
    }

    /** Same as {@link #locateAll(List)}, plus records structural facts (headers, page/table
     *  counts) and capability activations (REPEATED_HEADER, PAGE_BOUNDARY_ISOLATION,
     *  COMPOSITE_STATEMENT, WRAPPED_DESCRIPTION, LEADING_NARRATION_CONTINUATION,
     *  OFFSET_COLUMN_ANCHORS) onto {@code ctx} as they fire (Phase 1 "capture facts" --
     *  docs/engineering/financial-document-intelligence-principles.md). {@code ctx} is nullable. */
    public LocatedDocument locateAll(List<PositionedText> positionedText, DocumentContext ctx) {
        if (ctx != null) {
            int maxPageIndex = -1;
            for (PositionedText t : positionedText) maxPageIndex = Math.max(maxPageIndex, t.pageIndex());
            ctx.recordPages(maxPageIndex + 1);
        }
        List<List<PositionedText>> rows = groupIntoRows(positionedText);

        List<LocatedSection> sections = new ArrayList<>();
        List<String> pendingAuxiliary = new ArrayList<>();
        List<Map<String, String>> currentRows = null;
        List<String> headerNames = null;
        List<Float> headerAnchors = null;
        // Parallel to headerAnchors: the header labels' RIGHT edges, for placing right-aligned
        // numeric values -- see bucketRow's RIGHT_ALIGNED_AMOUNTS block for why a left edge alone
        // cannot separate two adjacent amount columns.
        List<Float> headerEnds = null;
        Set<String> currentHeaderSignature = null;
        // Account number named by the SECTION_MARKER banner that opened the active section, so a
        // later banner naming the SAME account is recognized as a repeated page header rather than
        // a new account -- see the marker-handling block below.
        String currentSectionAccountId = null;
        Integer lastRowPage = null; // page index of the most recently added row in currentRows
        // Parallel to lastRowPage: the y of that same row, and the line pitch the current
        // transaction block established between its date row and its first continuation -- the two
        // measurements the pitch check needs. Both reset wherever trailingCountSinceLastAnchor is,
        // so a pitch can never carry across an anchor, a page, a header or a section.
        Float lastRowY = null;
        Float blockPitch = null;
        Float blockSeparation = null;
        // The row physically above the one being processed, whatever it turned out to be -- a
        // header, a skipped page footer, a continuation. Tracked separately from lastRow* (which
        // follows only rows ATTACHED to a transaction) because blockSeparation has to be
        // measurable for the FIRST transaction under a header too, and on the first transaction of
        // every later page. Measured from lastRow* instead, both of those came back null, and the
        // very first transaction of a table -- the one a reader checks first -- kept the bug.
        Float previousRowY = null;
        Integer previousRowPage = null;
        int trailingCountSinceLastAnchor = 0;
        // LEADING_NARRATION_CONTINUATION: dateless rows that arrive once trailingCountSinceLastAnchor
        // has hit its cap -- narration for a transaction whose OWN date row hasn't been seen yet
        // (a real Canara Bank statement's layout; see MAX_TRAILING_CONTINUATION_ROWS's own doc
        // comment). Buffered here, in encounter order, until the next date-bearing row arrives and
        // claims it as its leading part -- see mergeLeadingInto's own doc comment for why that's a
        // prepend, not the ordinary append mergeInto does for trailing continuations.
        Map<String, String> pendingLeading = null;
        // Whether every row in that buffer got there because it was printed CLOSER to the next
        // transaction than to the previous one (see belongsToTheRowAbove), rather than merely
        // overflowing the trailing cap. Only the former is evidence about whose narration it is,
        // and only the former is rehomed into the next transaction's description -- see
        // mergeLeadingInto. Measured before this distinction existed: rehoming both scrambled a
        // real HDFC statement's descriptions, interleaving each transaction's wrapped tail into the
        // next one's narration, which is worse than the truncation it replaced.
        boolean pendingLeadingFromProximity = false;
        // Parallel to pendingLeading: how many rows have merged into it since the last date
        // anchor. Reset wherever pendingLeading is, or the cap would leak across sections.
        int leadingCount = 0;

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<PositionedText> row = rows.get(rowIndex);
            String rowLine = lineOf(row);

            // Captured here, at the top, because several branches below `continue` past the end of
            // the body -- a page footer still sits physically above the next row and still sets the
            // spacing a reader sees.
            Float gapFromPreviousRow = null;
            if (!row.isEmpty()) {
                int thisPage = row.get(0).pageIndex();
                float thisY = row.get(0).y();
                if (previousRowY != null && previousRowPage != null && previousRowPage == thisPage) {
                    gapFromPreviousRow = thisY - previousRowY;
                }
                previousRowPage = thisPage;
                previousRowY = thisY;
            }
            // The one row of lookahead the trailing/leading split needs -- see belongsToTheRowAbove.
            Float gapToNextRow = gapBetween(row, rowIndex + 1 < rows.size() ? rows.get(rowIndex + 1) : null);

            Matcher sectionMarker = SECTION_MARKER.matcher(rowLine);
            if (sectionMarker.find()) {
                // Bug fix, verified against a real Bank of Baroda statement: this banner is
                // printed at the top of EVERY page ("<HOLDER NAME> SAVINGS ACCOUNT  - <14 digits>"),
                // so a single 3-page savings statement was split into three separate "accounts" --
                // each offered to the user as its own account to create. The marker alone only says
                // "an account is named here", not "a DIFFERENT account starts here"; the account
                // number it names is the actual identity signal, and it was never compared. Same
                // account number as the section already in progress => this is a repeated page
                // banner, exactly analogous to the REPEATED_HEADER case below, and must not split.
                // (Independently corroborated on that file: the three sections' balances chain
                // perfectly, 38458.16 -> 31470.16 -> 48725.01 -> 45301.91, which three genuinely
                // distinct accounts would not do.)
                String markerAccountId = accountIdentityIn(rowLine);
                boolean sameAccountBannerRepeated = currentRows != null
                        && markerAccountId != null
                        && markerAccountId.equals(currentSectionAccountId);
                if (sameAccountBannerRepeated) {
                    if (ctx != null) ctx.record("REPEATED_ACCOUNT_BANNER");
                    continue; // repeated per-page banner for the account already in progress
                }
                if (currentRows != null) {
                    flushPendingLeading(currentRows, pendingLeading);
                    sections.add(new LocatedSection(pendingAuxiliary, currentRows));
                    if (ctx != null) ctx.record("COMPOSITE_STATEMENT");
                }
                pendingAuxiliary = new ArrayList<>();
                currentRows = null;
                headerNames = null;
                headerAnchors = null;
                headerEnds = null;
                currentHeaderSignature = null;
                currentSectionAccountId = markerAccountId;
                lastRowPage = null;
                lastRowY = null;
                blockPitch = null;
                blockSeparation = null;
                trailingCountSinceLastAnchor = 0;
                pendingLeading = null;
                pendingLeadingFromProximity = false;
                leadingCount = 0;
                pendingAuxiliary.add(rowLine);
                continue;
            }

            // WRAPPED_HEADER. Deliberately AFTER the section-marker branch above, not before it in
            // a pass over the whole row list. Tried that way first and it silently merged an
            // HSBC composite statement's "SAVINGS ACCOUNT-RES 100-111111-002" banner into the
            // header line beneath it: the banner is dateless and carries no parseable number, so
            // it reads exactly like the upper tier of a wrapped header. Consumed into a header
            // cell, the marker was never matched, the document stopped splitting into two
            // accounts, and the credit-card section's rows landed in the savings account. A
            // structural line has to be spent on the meaning it already has before this asks
            // whether it is half a header.
            List<PositionedText> headerRow = row;
            int wrappedHeaderLines = 0;
            if (!looksLikeHeaderRow(row)) {
                WrappedHeader wrapped = wrappedHeaderAt(rows, rowIndex);
                if (wrapped != null) {
                    headerRow = wrapped.row();
                    wrappedHeaderLines = wrapped.extraLines();
                }
            }
            if (looksLikeHeaderRow(headerRow)) {
                row = headerRow;
                if (wrappedHeaderLines > 0) {
                    rowIndex += wrappedHeaderLines;
                    if (ctx != null) ctx.record("WRAPPED_HEADER");
                    // The absorbed lines are never revisited, so the running "row physically above
                    // this one" pointer has to be advanced past them by hand -- left at the
                    // header's FIRST line, every spacing measurement taken below (blockSeparation,
                    // and with it the pitch check that decides whether a row is a continuation)
                    // would be overstated by the height of the header's own wrap.
                    if (!row.isEmpty()) {
                        previousRowPage = row.get(0).pageIndex();
                        previousRowY = row.get(0).y();
                    }
                }
                Set<String> signature = headerSignature(row);
                if (currentRows != null && signature.equals(currentHeaderSignature)) {
                    if (ctx != null) ctx.record("REPEATED_HEADER");
                    continue; // repeated header of the table already in progress -- not a data row
                }
                if (currentRows != null) {
                    // A different header shape with no explicit marker line -- fallback signal
                    // for a new section in a document without a banner line.
                    flushPendingLeading(currentRows, pendingLeading);
                    sections.add(new LocatedSection(pendingAuxiliary, currentRows));
                    if (ctx != null) ctx.record("COMPOSITE_STATEMENT");
                    pendingAuxiliary = new ArrayList<>();
                }
                headerNames = new ArrayList<>();
                headerAnchors = new ArrayList<>();
                headerEnds = new ArrayList<>();
                for (PositionedText t : row) {
                    headerNames.add(t.text().trim());
                    headerAnchors.add(t.x());
                    headerEnds.add(t.endX());
                }
                if (ctx != null) ctx.recordHeaders(headerNames);
                currentHeaderSignature = signature;
                currentRows = new ArrayList<>();
                lastRowPage = null;
                lastRowY = null;
                blockPitch = null;
                blockSeparation = null;
                trailingCountSinceLastAnchor = 0;
                pendingLeading = null;
                pendingLeadingFromProximity = false;
                leadingCount = 0;
                continue;
            }

            if (currentRows == null) {
                pendingAuxiliary.add(rowLine);
            } else if (PAGE_FOOTER.matcher(rowLine).find() || STATEMENT_CLOSING_MARKER.matcher(rowLine).find()) {
                if (ctx != null) ctx.record("PAGE_BOUNDARY_ISOLATION");
                continue; // a page-number line or closing marker is never a transaction or a continuation of one
            } else {
                Map<String, String> bucketed = bucketRow(row, headerNames, headerAnchors, headerEnds, ctx);
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

                if (hasDateValue(bucketed)) {
                    // A new transaction anchor. Any leading narration buffered since the last
                    // anchor belongs to THIS one -- claim it first (prepended, so it reads in the
                    // order it actually appeared), then this row becomes the new anchor, open to
                    // its own (capped) trailing continuations.
                    if (pendingLeading != null) {
                        if (!mergeLeadingInto(bucketed, pendingLeading, headerNames, pendingLeadingFromProximity)) {
                            // Refused: this buffer is a standalone noise line, not this
                            // transaction's leading narration -- see mergeLeadingInto. Kept as its
                            // own row so it still surfaces as unparseable rather than vanishing.
                            currentRows.add(pendingLeading);
                        }
                        pendingLeading = null;
                        pendingLeadingFromProximity = false;
                        leadingCount = 0;
                    }
                    currentRows.add(bucketed);
                    // How far this anchor sits below whatever preceded it -- the document's own
                    // evidence of how it separates one transaction's block from the next, and the
                    // precondition for trusting line pitch at all (see continuesTheBlock).
                    //
                    // Retained rather than cleared when this particular anchor offers no
                    // measurement (it opens a page, so nothing sits above it): "does this layout
                    // separate its blocks" is a property of the table, not of one row, and a table
                    // does not change how it is set at a page break. Cleared only at a header or a
                    // section marker, where a genuinely different table begins.
                    if (gapFromPreviousRow != null) blockSeparation = gapFromPreviousRow;
                    lastRowPage = row.get(0).pageIndex();
                    lastRowY = row.get(0).y();
                    blockPitch = null;
                    trailingCountSinceLastAnchor = 0;
                } else if (currentRows.isEmpty()) {
                    // Nothing to attach to at all yet (e.g. an "Opening Balance" summary line
                    // before any real transaction) -- stands on its own, same as before. Closed to
                    // trailing continuation immediately: a summary row isn't a transaction, and
                    // narration that follows it belongs to the FIRST real transaction as leading
                    // content, not to this row as trailing content.
                    currentRows.add(bucketed);
                    lastRowPage = row.isEmpty() ? lastRowPage : row.get(0).pageIndex();
                    lastRowY = row.isEmpty() ? lastRowY : row.get(0).y();
                    blockPitch = null;
                    blockSeparation = null;
                    trailingCountSinceLastAnchor = MAX_TRAILING_CONTINUATION_ROWS;
                } else if (samePage
                        && (continuesTheBlock(row, lastRowY, blockPitch, blockSeparation,
                                    trailingCountSinceLastAnchor)
                            || (trailingCountSinceLastAnchor < MAX_TRAILING_CONTINUATION_ROWS
                                && (!isNarrationOnly(bucketed)
                                    || belongsToTheRowAbove(gapFromPreviousRow, gapToNextRow))))) {
                    // The pitch this block prints its own wrapped lines at, learned from the first
                    // one and never revised -- so a later line that breaks the pitch cannot quietly
                    // redefine it and chain the whole page together (see BLOCK_PITCH_TOLERANCE).
                    if (trailingCountSinceLastAnchor == 0 && lastRowY != null) {
                        blockPitch = row.get(0).y() - lastRowY;
                    }
                    mergeInto(currentRows.get(currentRows.size() - 1), bucketed, headerNames);
                    if (ctx != null) ctx.record("WRAPPED_DESCRIPTION");
                    trailingCountSinceLastAnchor++;
                    lastRowPage = row.get(0).pageIndex();
                    lastRowY = row.get(0).y();
                } else {
                    // Past the trailing cap (or on a new page with nothing to trail into) -- this
                    // is leading narration for a transaction whose date row hasn't appeared yet.
                    // Not gated on samePage the way the trailing branch above is: unlike a page
                    // footer or repeated title banner (which must never cross a page boundary into
                    // the wrong row), genuine leading narration legitimately can span a page break
                    // -- verified against the real Canara statement this capability is modeled on.
                    if (leadingCount >= MAX_LEADING_CONTINUATION_ROWS) {
                        // Past the point where "leading narration" is a credible explanation. A
                        // dozen consecutive rows with no date does not mean one very wordy
                        // transaction; it means the date column is not bucketing for this layout,
                        // and every further merge destroys another row of a table that is plainly
                        // there. Unbounded, this collapsed a 2541-line statement into two rows and
                        // a 38,200-character cell.
                        //
                        // The line becomes auxiliary rather than being merged or dropped: it is
                        // still document text, it is simply not a transaction, and keeping it
                        // visible is what lets a human see what the extractor could not anchor.
                        if (ctx != null) {
                            // A diagnostic, not a capability: it describes what the parser could
                            // NOT do. Recorded through the capability channel, it made the coverage
                            // figure rise as the engine got worse -- more rows abandoned, more
                            // "capabilities" activated. See DocumentContext.recordDiagnostic.
                            ctx.recordDiagnostic("UNANCHORED_ROWS_ABANDONED");
                            // Counted, not merely flagged. The capability set answers "did this
                            // happen"; only the histogram answers "which fault dominates", and
                            // only that points at a subsystem. Measured with set semantics first,
                            // and every document -- including ones that parse perfectly -- lit
                            // every reason, which told us nothing.
                            ctx.recordUnanchored(anchorFailureReason(bucketed, headerNames));
                        }
                        pendingAuxiliary.add(rowLine);
                        continue;
                    }
                    boolean nearerToTheTransactionBelow =
                            trailingCountSinceLastAnchor < MAX_TRAILING_CONTINUATION_ROWS
                                    && isNarrationOnly(bucketed)
                                    && !belongsToTheRowAbove(gapFromPreviousRow, gapToNextRow);
                    if (pendingLeading == null) {
                        pendingLeading = new LinkedHashMap<>();
                        pendingLeadingFromProximity = nearerToTheTransactionBelow;
                    } else if (!nearerToTheTransactionBelow) {
                        pendingLeadingFromProximity = false;
                    }
                    mergeInto(pendingLeading, bucketed, headerNames);
                    leadingCount++;
                    if (ctx != null) ctx.record("LEADING_NARRATION_CONTINUATION");
                    lastRowPage = row.isEmpty() ? lastRowPage : row.get(0).pageIndex();
                    lastRowY = row.isEmpty() ? lastRowY : row.get(0).y();
                    // The block above is closed the moment a row is buffered forward instead of
                    // merged into it. Without this, a later row that happened to match the old
                    // pitch would be appended to a transaction whose narration this buffered row
                    // already moved past -- text rejoining a transaction out of order, behind text
                    // that had been given to the next one.
                    blockPitch = null;
                }
            }
        }
        if (currentRows != null) {
            flushPendingLeading(currentRows, pendingLeading);
            sections.add(new LocatedSection(pendingAuxiliary, currentRows));
        }
        if (ctx != null) ctx.recordTables(sections.size());
        return new LocatedDocument(sections);
    }

    /**
     * Which of its two neighbouring transactions a dateless line belongs to, decided by which one
     * it is printed closer to.
     *
     * <p>{@link #MAX_TRAILING_CONTINUATION_ROWS} answers "how many" dateless rows follow a
     * transaction; it cannot answer "whose", and on a layout that prints narration BEFORE its date
     * row the two questions have different answers. A real Bank of Baroda statement sets each
     * transaction as narration-head / date-row / wrapped-tail, and puts a blank line between
     * transactions: the tail sits 5.11pt below its own date row, and the NEXT transaction's
     * narration head sits 10.21pt below that and 5.10pt above the date row it actually belongs to.
     * Counting alone admits both as trailing, so every description on that statement carried the
     * following transaction's merchant -- the amounts, dates and balances all correct, and the
     * merchant that categorisation keys on wrong on every row.
     *
     * <p>Ties stay with the row above, which is the conservative reading and the one the count cap
     * already gave: a real Canara Bank statement's trailing "Chq: &lt;number&gt;" line sits 24pt
     * from the transaction above it and 24pt from the one below, and it belongs above -- its
     * cheque number is the reference printed on the line directly under that transaction's date.
     * Geometry cannot separate that case from the Bank of Baroda one by distance from the row
     * above; only the comparison between both sides can, and only strictly-closer-below moves a
     * line. That makes this narrow by construction: it changes nothing on a layout that spaces its
     * rows evenly, or one that has no row below to compare against.
     */
    private boolean belongsToTheRowAbove(Float gapFromPreviousRow, Float gapToNextRow) {
        if (gapFromPreviousRow == null || gapToNextRow == null) return true; // nothing to compare
        // The margin is not decoration. Measured without it, a real HDFC statement whose rows are
        // all set at 17.20pt -- a genuine tie, where this comparison should say nothing -- had the
        // tie broken by float noise in the fourth decimal, and its descriptions came apart:
        // each transaction's wrapped tail moved onto the next one's narration. A line has to be
        // VISIBLY closer to the transaction below to be read as belonging to it.
        return gapFromPreviousRow <= gapToNextRow + BLOCK_PITCH_TOLERANCE;
    }

    /**
     * True when nothing in this row reads as a number -- it is narration and nothing else.
     *
     * <p>The gate on {@link #belongsToTheRowAbove}. Proximity is a claim about which transaction a
     * line of TEXT describes, and it has no business moving a row that carries a figure: a dateless
     * row holding an amount is a continuation of the transaction whose columns it shares, and
     * reassigning it changes what that transaction is worth. Measured without this gate, on real
     * statements: a Union Bank row turned from an 18,298.00 credit into a 500.00 debit, and a PNB
     * row flipped from expense to income. Both are the failure this whole exercise is trying to
     * avoid -- a staged import that looks right and is not.
     */
    private boolean isNarrationOnly(Map<String, String> bucketed) {
        for (String value : bucketed.values()) {
            if (value != null && CsvParser.parseNumeric(value.trim()) != null) return false;
        }
        return true;
    }

    /** Vertical distance between two visual rows, or null when either is missing or they are on
     *  different pages -- a gap across a page break is a measure of page geometry, not of how the
     *  table sets its lines. */
    private Float gapBetween(List<PositionedText> above, List<PositionedText> below) {
        if (above == null || below == null || above.isEmpty() || below.isEmpty()) return null;
        if (above.get(0).pageIndex() != below.get(0).pageIndex()) return null;
        return below.get(0).y() - above.get(0).y();
    }

    /**
     * True when {@code row} sits at the same line pitch this transaction block already established
     * -- i.e. it is one more visually continuous line of the narration above it, not the start of
     * the next transaction's.
     *
     * <p>Only ever WIDENS what {@link #MAX_TRAILING_CONTINUATION_ROWS} admits, and only for a block
     * that has already printed at least one continuation to measure a pitch from. A document with
     * irregular spacing produces no match and keeps exactly the count-capped behaviour it had.
     *
     * <p>Two conditions, and the second is the one that makes this safe. The pitch must match, AND
     * the document must have DEMONSTRATED that it separates transaction blocks by more than a line
     * height -- evidenced by {@code blockSeparation}, the gap that preceded this very anchor. On a
     * layout that sets every row at one uniform spacing, "same pitch as the line above" is true of
     * the next transaction's leading narration exactly as it is of this one's trailing narration,
     * so the measurement carries no information and extending on it would silently pull the next
     * transaction's narration backwards. Requiring the document to show a wider gap somewhere is
     * what distinguishes "the pitch says these lines belong together" from "everything here is at
     * the same pitch." Where it cannot, the count cap decides, exactly as before.
     *
     * @param lastRowY        y of the row most recently merged into this transaction (null before any)
     * @param blockPitch      gap between this transaction's date row and its first continuation
     * @param blockSeparation gap between this transaction's date row and whatever preceded it, or
     *                        null when there was nothing before it on the same page to measure
     */
    private boolean continuesTheBlock(List<PositionedText> row, Float lastRowY, Float blockPitch,
                                       Float blockSeparation, int trailingCount) {
        if (blockPitch == null || lastRowY == null || row.isEmpty()) return false;
        if (trailingCount >= MAX_BLOCK_CONTINUATION_ROWS) return false;
        // A non-positive pitch would mean the block's own first continuation was not below its date
        // row -- rows arrive sorted top-to-bottom, so that can only be float noise within the
        // same-row tolerance, and it is not something to extrapolate from.
        if (blockPitch <= 0) return false;
        boolean separatesItsBlocks = blockSeparation != null
                && blockPitch + BLOCK_PITCH_TOLERANCE < blockSeparation;
        if (!separatesItsBlocks) return false;
        return Math.abs((row.get(0).y() - lastRowY) - blockPitch) <= BLOCK_PITCH_TOLERANCE;
    }

    /** A pending leading-narration buffer that never found a date-bearing row to attach to before
     *  its section ended (trailing boilerplate after the last real transaction, most commonly) --
     *  surfaced as its own row rather than silently discarded, consistent with "Never lose
     *  information": it still won't parse as a transaction (no date), but it'll be reported with a
     *  specific reason instead of just vanishing. */
    private void flushPendingLeading(List<Map<String, String>> currentRows, Map<String, String> pendingLeading) {
        if (pendingLeading != null && !pendingLeading.isEmpty()) {
            currentRows.add(pendingLeading);
        }
    }

    /**
     * Bug fix, verified against a real Bank of Baroda statement: this used to ask only "is the date
     * column non-blank", which is not the same question as "is this row a new transaction." A
     * wrapped narration line's text frequently lands in the DATE column via nearest-X bucketing
     * (that column's anchor is leftmost, and a continuation line's text starts at the left margin) --
     * e.g. "UPI/111122223333/02:44:32/UPI/paytm.s25j48". Under the old check that row looked like a
     * brand-new transaction anchor, so it was never merged into the transaction above it as a
     * continuation. Two failures fell out of that single misclassification: the row itself was
     * dropped at normalization ("didn't match any known date format" -- 114 of 169 rows on that one
     * file), AND the transaction it actually belonged to kept an empty/truncated description,
     * which is what surfaced in the review UI as a blank Description column.
     *
     * The reliable question is whether the date column holds something that actually parses as a
     * date -- CsvParser.parseDate is the same parser TransactionNormalizer itself uses to accept or
     * reject the row later, and the same one bucketRow already consults a few lines below, so this
     * now agrees with both instead of contradicting them.
     *
     * <p>Second bug fix, the third time this class has made the same mistake (see
     * {@link #isDateColumn} and {@link #isAmountColumn}, both of which were exact-matching where
     * their siblings matched per word): finding the date column via
     * {@code CsvParser.firstNonBlank} compares each hint against the WHOLE normalized column name,
     * so it only ever found a column named exactly "date", "txn date" or "value date". Meanwhile
     * {@code isDateColumn} -- consulted a few lines away, about the same column, on the same row --
     * matches per word and answered yes for the same header. The two disagreed, and the disagreement
     * became reachable the moment WRAPPED_HEADER started producing the compound names a wrapped
     * heading actually reads as: "Open/Value Date" is unmistakably the date column and matched
     * nothing here, so EVERY row of such a table failed this check, none became a transaction
     * anchor, and the whole table collapsed into one merged row. Now asked per word, the way every
     * other date-column decision in this class is asked.
     */
    private boolean hasDateValue(Map<String, String> bucketed) {
        for (Map.Entry<String, String> e : bucketed.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isBlank()) continue;
            if (isDateColumn(e.getKey()) && CsvParser.parseDate(e.getValue().trim()) != null) return true;
        }
        return false;
    }

    /**
     * WHY a row could not become a transaction anchor.
     *
     * <p>"Rows were abandoned" says a table was lost; it does not say where to look. These three
     * outcomes point at different subsystems, and telling them apart is the difference between
     * days of investigation and minutes:
     *
     * <ul>
     *   <li>{@code NO_DATE_COLUMN} — the header has no date-like column at all. The header
     *       detector matched on other hints; this is a table Finora cannot anchor by date.</li>
     *   <li>{@code DATE_COLUMN_EMPTY} — a date column exists but nothing bucketed into it. A
     *       GEOMETRY problem: the values are landing under a different anchor.</li>
     *   <li>{@code DATE_UNPARSEABLE} — a value is present and {@code parseDate} rejects it. A
     *       NORMALIZATION problem: a format the parser does not know.</li>
     * </ul>
     *
     * <p>Recorded as a capability marker so it reaches the fingerprint and, once the Evidence
     * Store lands, the per-layout diagnostics — rather than a log line nobody reads.
     */
    private String anchorFailureReason(Map<String, String> bucketed, List<String> headerNames) {
        boolean hasDateColumn = headerNames != null && headerNames.stream()
                .anyMatch(h -> matchesAnyHint(CsvParser.normalizeHeaderCell(h), DATE_HINTS));
        if (!hasDateColumn) return "UNANCHORED_NO_DATE_COLUMN";

        String dateRaw = CsvParser.firstNonBlank(bucketed, DATE_HINTS.toArray(new String[0]));
        if (dateRaw == null || dateRaw.isBlank()) return "UNANCHORED_DATE_COLUMN_EMPTY";
        return "UNANCHORED_DATE_UNPARSEABLE:" + shapeOf(dateRaw.trim());
    }

    /**
     * A value's SHAPE with its content removed: digits become 9, letters X, everything else kept.
     *
     * <p>"We rejected 97 values in the date column" says a format is unsupported; it does not say
     * which, and the obvious next step -- print the values -- means putting statement content in a
     * diagnostic. A shape carries the whole answer and none of the data: {@code 99/99/9999} and
     * {@code 99-XXX-99 99:99:99} are immediately actionable, and neither is anybody's transaction.
     *
     * <p>Truncated, because a mis-bucketed narration line landing in the date column would
     * otherwise produce a shape as long as the sentence.
     */
    private static String shapeOf(String value) {
        StringBuilder shape = new StringBuilder();
        for (int i = 0; i < value.length() && i < 24; i++) {
            char c = value.charAt(i);
            shape.append(Character.isDigit(c) ? '9' : Character.isLetter(c) ? 'X' : c);
        }
        if (value.length() > 24) shape.append('~');
        return shape.toString();
    }

    /** Merges a continuation row's non-blank column values into the transaction row above it --
     *  per column, appending with a space when both already have a value (same join convention
     *  {@link #bucketRow} itself uses for two text runs landing in the same column), or simply
     *  filling it in when the target's own value for that column is blank/absent. */
    private void mergeInto(Map<String, String> target, Map<String, String> continuation, List<String> headerNames) {
        for (Map.Entry<String, String> e : continuation.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            String existing = target.get(e.getKey());

            // Bug fix: a continuation row's wrapped narration very often mis-buckets into the DATE
            // column (that column's anchor is leftmost, and a wrapped line starts at the left
            // margin) -- e.g. "UPI/111122223333/02:44:32/UPI/paytm.s25j48". Appending that onto the
            // anchor row's own valid date produced "02/05/25 UPI/1111222..." which no longer parses
            // as a date, so the merge DESTROYED the very transaction it was supposed to complete --
            // every row on a real Bank of Baroda statement dropped this way. The anchor's date is
            // authoritative and must never be appended to; the incoming text is narration, so it's
            // redirected into the description column rather than discarded ("never lose
            // information" -- see the engineering principles doc).
            // Generalized from the date case to every structured column: a continuation merge is
            // additive enrichment, so it must never INVALIDATE a value the anchor row already
            // holds. A wrapped narration fragment mis-buckets into whichever column its x lands
            // nearest -- the date column (Bank of Baroda) or the amount column (a fine-print
            // paragraph landing on the row above it) -- and appending it turned "04/07/2026" into
            // unparseable text, or "10.00 Dr" into "10.00 Dr levied", dropping a transaction that
            // had parsed perfectly well a moment earlier. Text that would break an already-valid
            // date or amount is narration, so it goes to the description column instead of
            // overwriting real data (and is never simply discarded).
            boolean wouldBreakValidDate = isDateColumn(e.getKey()) && existing != null
                    && CsvParser.parseDate(existing.trim()) != null;
            boolean wouldBreakValidAmount = isAmountColumn(e.getKey()) && existing != null
                    && CsvParser.parseNumeric(existing.trim()) != null
                    && CsvParser.parseNumeric((existing + " " + e.getValue()).trim()) == null;
            if (wouldBreakValidDate || wouldBreakValidAmount) {
                String descriptionColumn = descriptionColumnIn(target, headerNames);
                if (descriptionColumn == null) {
                    // Bug fix: this used to `continue` here, silently dropping the fragment -- in
                    // the same block whose comment promises "never lose information" and that text
                    // "is never simply discarded." A layout with no description-hinted column at
                    // all (a bare Date/Amount/Balance grid) hit exactly that path.
                    //
                    // Falling back to the first non-structured column keeps the text in the row
                    // where a human reviewing the import can still see it. If every column is
                    // structured, the fragment goes nowhere -- but that is now a deliberate,
                    // narrow last resort rather than the ordinary case.
                    String fallback = firstUnstructuredColumn(target, headerNames);
                    if (fallback != null) {
                        String current = target.get(fallback);
                        target.put(fallback, (current == null || current.isBlank())
                                ? e.getValue() : current + " " + e.getValue());
                    }
                    continue;
                }
                String currentDescription = target.get(descriptionColumn);
                target.put(descriptionColumn, (currentDescription == null || currentDescription.isBlank())
                        ? e.getValue() : currentDescription + " " + e.getValue());
                continue;
            }

            target.put(e.getKey(), (existing == null || existing.isBlank()) ? e.getValue() : existing + " " + e.getValue());
        }
    }

    /**
     * The description-ish column of the table this row belongs to, or null when the layout has
     * none -- where narration that mis-bucketed into the date column gets rehomed (see mergeInto).
     *
     * <p>Asked of the TABLE'S HEADER, not of the row's own keys. That distinction is the whole bug,
     * found on a real Bank of Baroda statement: it prints each transaction's narration on its own
     * visual line above the date row, so nothing ever lands in NARRATION while the row is being
     * built, and the row's keys are only {@code {DATE, WITHDRAWAL (DR), BALANCE}}. Searching those
     * keys found no description column, {@link #firstUnstructuredColumn} then found nothing either
     * (every remaining key is a date or an amount), and the narration was dropped -- in the branch
     * whose own comment promises the text "is never simply discarded". Every description on that
     * statement came back blank.
     *
     * <p>The giveaway was which row DID keep its narration: only "Opening Balance", the one row
     * that happened to already hold a NARRATION value, so the redirect had somewhere to go.
     *
     * <p>A row's keys are always a subset of the header names ({@link #bucketRow} only ever writes
     * a key it took from {@code headerNames}), so consulting the header can only find MORE columns,
     * never a different one. The row-key search is kept as the fallback for a null header, which
     * only the single-table convenience path can produce.
     */
    private String descriptionColumnIn(Map<String, String> row, List<String> headerNames) {
        for (String column : headerNames == null ? row.keySet() : headerNames) {
            if (matchesAnyHint(column, DESCRIPTION_COLUMN_HINTS)) return column;
        }
        return null;
    }

    /** The first column in this row that isn't date-shaped or amount-shaped -- mergeInto's last
     *  resort for narration text on a layout with no description column at all, so the text stays
     *  visible to the person reviewing the import instead of being dropped on the floor.
     *
     *  <p>Deliberately excludes the structured columns rather than picking the first key outright:
     *  the whole reason the caller is here is that appending to a date or amount cell would
     *  invalidate it, so falling back onto one of those would recreate the bug being avoided. */
    private String firstUnstructuredColumn(Map<String, String> row, List<String> headerNames) {
        for (String column : headerNames == null ? row.keySet() : headerNames) {
            if (!isDateColumn(column) && !isAmountColumn(column)) return column;
        }
        return null;
    }

    /** Same column-merge semantics as {@link #mergeInto}, but PREPENDS instead of appending --
     *  used only for {@code pendingLeading} (see {@link #locateAll}): a leading narration buffer's
     *  text chronologically precedes whatever the new anchor row's own bucketed values already
     *  hold, so it has to read before them, not after. */
    private boolean mergeLeadingInto(Map<String, String> target, Map<String, String> leading,
                                      List<String> headerNames, boolean fromProximity) {
        // Bug fix, exposed by tightening hasDateValue to require a PARSEABLE date: a per-page title
        // banner ("Savings Account" at the top of page 2) has no date of its own, so it is no
        // longer mistaken for a transaction anchor -- correct -- but it was then buffered as
        // LEADING narration and prepended into the next real transaction. Its text sits in the date
        // column, so the prepend produced "Savings Account 02-05-2026", which no longer parses, and
        // the genuine transaction it was prepended to was dropped entirely. A buffer that would
        // destroy the anchor's own valid date must never be prepended onto it.
        //
        // What that used to mean was: refuse the whole merge, and let the caller keep the buffer as
        // its own unparseable row. That protected the anchor, but it also threw away every genuine
        // LEADING narration whose text mis-bucketed into the date column -- and mis-bucketing into
        // the date column is the NORMAL case for a wrapped narration line, since that column's
        // anchor is leftmost. On a real Bank of Baroda statement, whose narration is printed above
        // its own date row, that is every description in the file.
        //
        // So this now does what mergeInto has always done in the same situation: keep the anchor's
        // value, and rehome the incoming text into the description column instead of refusing it.
        // Same protection, without discarding the narration it was protecting the date from. The
        // page-banner case is still not appended to the date -- it lands in the description, which
        // is visible and correctable, rather than silently dropping a transaction.
        //
        // A false return is now reserved for the one case with genuinely nowhere to put the text:
        // a layout whose every column is a date or an amount. The caller still keeps the buffer as
        // its own row there, so nothing is lost.
        // Decided in full before anything is written, so a refusal leaves the anchor untouched --
        // the caller keeps the buffer as its own row, and a half-merged buffer would otherwise be
        // counted twice.
        String rehome = fromProximity ? descriptionColumnIn(target, headerNames) : null;
        if (fromProximity && rehome == null) rehome = firstUnstructuredColumn(target, headerNames);
        for (Map.Entry<String, String> e : leading.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            if (rehome == null && wouldInvalidate(target, e.getKey(), e.getValue())) return false;
        }

        for (Map.Entry<String, String> e : leading.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            String column = wouldInvalidate(target, e.getKey(), e.getValue()) ? rehome : e.getKey();
            String existing = target.get(column);
            target.put(column, (existing == null || existing.isBlank()) ? e.getValue() : e.getValue() + " " + existing);
        }
        return true;
    }

    /** True when prepending {@code value} to whatever {@code target} already holds for
     *  {@code column} would turn a date or amount it can currently read into one it cannot. Shared
     *  by {@link #mergeLeadingInto}'s two passes so the decision and the write cannot disagree. */
    private boolean wouldInvalidate(Map<String, String> target, String column, String value) {
        String existing = target.get(column);
        if (existing == null) return false;
        if (isDateColumn(column) && CsvParser.parseDate(existing.trim()) != null) {
            return CsvParser.parseDate((value + " " + existing).trim()) == null;
        }
        if (isAmountColumn(column) && CsvParser.parseNumeric(existing.trim()) != null) {
            return CsvParser.parseNumeric((value + " " + existing).trim()) == null;
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

    /** A header reconstructed from several visual lines, and how many lines past the first the
     *  caller must skip. */
    private record WrappedHeader(List<PositionedText> row, int extraLines) {}

    /**
     * WRAPPED_HEADER: the header that begins at {@code index} and continues onto the line(s)
     * below it, or null when the lines there are not one. See {@link #HEADER_WRAP_MAX_GAP}'s own
     * comment for the real statement this was measured on and why neither half of a wrapped
     * header is recognizable on its own.
     *
     * <p>Only ever called on a line that is NOT already a header by itself. That restriction is
     * load-bearing, not caution: it means no document whose header is recognized today can have
     * its header changed, so this can only turn "no table found" into "table found". It also
     * protects the one layout that would otherwise be at risk --
     * LEADING_NARRATION_CONTINUATION, where a real Canara Bank statement prints a dateless,
     * amountless narration line directly beneath the header, which is exactly the shape
     * {@link #wrapsOnto} accepts. There the header alone already scores as a header, so nothing
     * is merged and that narration line stays the data row it is.
     *
     * <p>Merging is also the SAFE direction for the prose false-positive that
     * {@link #MAX_HEADER_ROW_CELLS} and the density check exist to reject: joining lines adds
     * cells faster than it adds recognized column names, so a paragraph merged with its
     * neighbour scores strictly LESS dense than either line did, not more.
     */
    private WrappedHeader wrappedHeaderAt(List<List<PositionedText>> rows, int index) {
        List<PositionedText> first = rows.get(index);
        if (first.isEmpty()) return null;
        if (!carriesNoDataValue(first)) {
            explainWrap(first, () -> "NO_MERGE: upper line carries a date or a number, so it is a data row");
            return null;
        }
        if (carriesStructuralMeaning(first)) {
            explainWrap(first, () -> "NO_MERGE: upper line is a banner, page footer or closing marker");
            return null;
        }

        List<List<PositionedText>> block = new ArrayList<>();
        block.add(first);
        WrappedHeader found = null;
        for (int span = 1; span < HEADER_WRAP_MAX_LINES && index + span < rows.size(); span++) {
            List<PositionedText> next = rows.get(index + span);
            if (!wrapsOnto(block.get(block.size() - 1), next)) break;
            block.add(next);
            // Keeps extending while it can rather than stopping at the first span that scores: a
            // three-line header whose first two lines happen to clear the bar would otherwise
            // lose its third line's column names. Safe to be greedy because wrapsOnto has already
            // refused every line carrying a data value, so the run cannot reach into the table's
            // first row.
            List<PositionedText> candidate = mergeHeaderLines(block);
            if (candidate == null) break; // mergeHeaderLines has already explained which cell refused
            if (looksLikeHeaderRow(candidate)) {
                found = new WrappedHeader(candidate, span);
                int lines = span + 1;
                explainWrap(first, () -> "MERGED across " + lines + " lines: every lower cell joined a"
                        + " column above, and the joined row scores as a header -> "
                        + candidate.stream().map(t -> t.text().trim()).toList());
            } else {
                int lines = span + 1;
                explainWrap(first, () -> "NO_MERGE across " + lines + " lines: the joined row still does"
                        + " not score as a header (needs a date column, >= 2 recognized names, and"
                        + " >= 1/3 of cells recognized) -> "
                        + candidate.stream().map(t -> t.text().trim()).toList());
            }
        }
        return found;
    }

    /**
     * Why a wrapped-header merge was or was not made, at DEBUG.
     *
     * <p>Deliberately a log rather than a recorded capability: {@code DocumentContext} records
     * facts with set semantics -- a capability either fired on this document or it did not -- and
     * a per-decision narrative is neither a fact about the document nor something any caller acts
     * on. It is for the person holding an unusual statement asking "why did the engine read that
     * as one heading, or refuse to". Off unless enabled, so it costs a level check in production.
     *
     * <p>Both outcomes are logged, not just the merge. The refusals are the interesting half: a
     * heading that was nearly merged and was not is exactly the case that otherwise needs a
     * one-off probe to investigate -- which is how this capability's own bug took five diagnoses.
     *
     * <p>The outcome arrives as a {@link Supplier} rather than a String, and that is not style.
     * Built eagerly, every message here -- string concatenation, and in two cases a stream over the
     * merged row -- is constructed on every merge decision in every document and then thrown away,
     * because this is off outside an investigation. This runs once per non-header row of every
     * statement parsed.
     */
    private void explainWrap(List<PositionedText> upperLine, Supplier<String> outcome) {
        if (!log.isDebugEnabled() || upperLine.isEmpty()) return;
        PositionedText anchor = upperLine.get(0);
        log.debug("WRAPPED_HEADER page={} y={} first={} -- {}",
                anchor.pageIndex(), anchor.y(), anchor.text().trim(), outcome.get());
    }

    /** True when {@code next} can be the continuation of a header label begun on {@code line}:
     *  same page, printed below it by less than a data row's pitch, carrying no value of its own,
     *  and not a line that already means something structural. The value check is what separates
     *  a wrapped label from the table's first row -- a header cell is a name, and every data row
     *  carries at least one date or one number. */
    private boolean wrapsOnto(List<PositionedText> line, List<PositionedText> next) {
        if (line.isEmpty() || next.isEmpty()) return false;
        if (line.get(0).pageIndex() != next.get(0).pageIndex()) return false;
        float gap = next.get(0).y() - line.get(0).y();
        if (gap <= 0 || gap > HEADER_WRAP_MAX_GAP) return false;
        if (!carriesNoDataValue(next)) return false;
        return !carriesStructuralMeaning(next);
    }

    /**
     * True when this line already means something on its own -- a section banner, a page footer, a
     * statement-closing marker -- and so is not available as half of a heading. Same principle as
     * running the whole merge after the section-marker branch rather than before it: a line is
     * spent on the meaning it already has. A footer printed between two heading lines is also
     * positive evidence they are not one label; nothing is printed through the middle of a wrapped
     * cell.
     *
     * <p>Asked of the SEEDING line as well as of each absorbed one, which it was not at first. That
     * asymmetry was reachable: a "Page 1 of 5" footer extracting as two runs, directly above a
     * table whose columns sit close together, was absorbed as the upper half of that table's
     * heading. Its two runs seeded the columns, the three real heading cells collapsed into them,
     * and the table came out with two columns named "Page Date" and "1 of 5 Amount Balance" --
     * taking the amount and the balance into a single cell, which loses a value rather than just
     * mislabelling one. Narrow columns are what make it reachable, and nothing guarantees a
     * statement has wide ones.
     */
    private boolean carriesStructuralMeaning(List<PositionedText> row) {
        String line = lineOf(row);
        return SECTION_MARKER.matcher(line).find()
                || PAGE_FOOTER.matcher(line).find()
                || STATEMENT_CLOSING_MARKER.matcher(line).find();
    }

    /** True when no cell in {@code row} reads as a date or a number -- i.e. the row states names,
     *  not values. Uses the same parsers the rest of the pipeline judges values by, so "is this a
     *  value" cannot mean one thing here and another downstream. */
    private boolean carriesNoDataValue(List<PositionedText> row) {
        for (PositionedText t : row) {
            String cell = t.text().trim();
            if (cell.isEmpty()) continue;
            if (CsvParser.parseDate(cell) != null || CsvParser.parseNumeric(cell) != null) return false;
        }
        return true;
    }

    /**
     * Folds a run of header lines into one row of cells, one per column.
     *
     * <p>The first line seeds the columns; every later line's cells join the one whose anchor is
     * NEAREST, within {@link #HEADER_WRAP_MAX_COLUMN_JOIN}. Left edges only -- the same rule
     * {@link #nearestColumn} already places data runs by.
     *
     * <p>This deliberately does NOT consider whether the spans overlap, and that is the second
     * thing this method got wrong. Span overlap is the more accurate question in principle, and it
     * was tried first on the reasoning that it is the only rule that can place a continuation under
     * a wide left-aligned label. Measured against the real statement, it is the rule that broke it.
     * A run's measured width is its ADVANCE, not the extent of its glyphs: that statement's second
     * heading tier prints "Maturity Available" as a single run at x=261.46 whose width PDFBox
     * reports as 214.80 -- roughly three times its visible text, because the wide gap between the
     * two words is inside the run. Its span therefore reaches x=476.26 and swallows
     * "Withdrawable***" at [428.02, 489.36], 48 points of overlap between two labels that are not
     * remotely in the same column. The tier merged, and the fixed-deposit table anchored on its two
     * columns instead of the eight-column heading above it.
     *
     * <p>Left edges cannot be inflated that way. Dropping overlap also makes a width-less trace and
     * a real PDF take the same path through this method, which is worth more than the accuracy it
     * gives up: the committed trace could not have caught this bug precisely because overlap was
     * unreachable there, so the fixture that was supposed to represent the document diverged from it
     * exactly where the document was hardest.
     *
     * <p>Returns null -- refusing the merge outright -- if any cell joins NO column. That is the
     * rule that makes "these two lines are one header" mean something structural rather than just
     * "these two lines are close together": a wrapped label's lower line lives inside the columns
     * the upper line established. It stops a caption printed above a table being glued onto its
     * heading (the synthetic fixtures print their rows 10pt apart, close enough that proximity
     * alone accepted several), and it refuses the fixed-deposit schedule's lower header TIER --
     * a tier for the second line of each record, whose "Withdrawable***" (x=428.02) sits 166pt
     * past any column above it.
     *
     * <p>Known limitation, measured rather than assumed: this also refuses a heading whose upper
     * line simply has fewer labels than the table has columns. The recurring-deposit installment
     * schedule in the same statement is one -- six columns, four labels above them, with
     * "Instalment Amt Due" (x=181.53) and "Closing balance**" (x=470.53) named only on the lower
     * line. Its heading is therefore read from that lower line alone, which extracts every
     * installment correctly but names the columns "Number" and "Due" rather than "Instalment
     * Number" and "Instalment Amt Due". Admitting a new column was tried and does not reach it:
     * bounding new columns to the span the upper line covers leaves 470.53 outside it, and
     * removing the bound entirely lets the fixed-deposit tier back in, which splits that table and
     * re-anchors it on three columns. Telling the two apart needs a signal this class does not
     * have at the point it decides -- the data rows below the heading -- so the half-named
     * heading is the deliberate outcome, not an oversight.
     */
    private List<PositionedText> mergeHeaderLines(List<List<PositionedText>> block) {
        // Blank runs are dropped rather than folded in. PDFBox emits them, and a blank joined into
        // a cell puts a DOUBLE space in that column's name -- which reads identically and is not:
        // "Txn  Date" normalizes to "txn  date", and every whole-cell lookup in the pipeline
        // (CsvParser.firstNonBlank, which is how TransactionNormalizer finds the date and amount
        // columns) compares against "txn date" and misses. The column would bucket its values
        // perfectly and then be invisible to the stage that reads them.
        List<List<PositionedText>> columns = new ArrayList<>();
        for (PositionedText t : block.get(0)) {
            if (t.text().isBlank()) continue;
            List<PositionedText> column = new ArrayList<>();
            column.add(t);
            columns.add(column);
        }
        if (columns.isEmpty()) return null;

        for (int line = 1; line < block.size(); line++) {
            for (PositionedText t : block.get(line)) {
                if (t.text().isBlank()) continue;
                int target = columnFor(t, columns);
                if (target < 0) {
                    explainWrap(block.get(0), () -> "NO_MERGE: lower cell \"" + t.text().trim() + "\" at x="
                            + t.x() + " joins no column above it (nearest is more than "
                            + HEADER_WRAP_MAX_COLUMN_JOIN + "pt away), so these lines are not one"
                            + " heading -- a caption, or a second heading tier");
                    return null;
                }
                columns.get(target).add(t);
            }
            // Re-sorted after every line because joining a cell can move a column's anchor left
            // (these labels are centered), and a new column can land anywhere -- and the whole
            // pipeline downstream of here reads header cells in left-to-right order.
            columns.sort((a, b) -> Float.compare(anchorOf(a), anchorOf(b)));
        }

        List<PositionedText> headerRow = new ArrayList<>();
        for (List<PositionedText> column : columns) headerRow.add(asOneCell(column, lastLineY(block)));
        return headerRow;
    }

    private int columnFor(PositionedText cell, List<List<PositionedText>> columns) {
        // Strictly-less, so equidistant columns resolve to the LEFTMOST -- the same tie-breaking
        // nearestColumn uses a few methods down. The two were written to answer the same question
        // ("which column is this x nearest") and disagreeing on ties is how siblings in this class
        // have drifted apart before.
        int nearest = -1;
        float nearestDistance = Float.MAX_VALUE;
        for (int c = 0; c < columns.size(); c++) {
            float distance = Math.abs(cell.x() - anchorOf(columns.get(c)));
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = c;
            }
        }
        return nearestDistance <= HEADER_WRAP_MAX_COLUMN_JOIN ? nearest : -1;
    }

    /** One column's lines joined top-to-bottom into the single label a reader sees. Width is the
     *  span from the column's leftmost edge to its rightmost, but only when something in it was
     *  actually measured: a column built entirely from zero-width runs stays zero-width, so a
     *  trace that carries no widths cannot acquire a fabricated one here and start reaching
     *  RIGHT_ALIGNED_AMOUNTS' right-edge correction on evidence it does not have. */
    private PositionedText asOneCell(List<PositionedText> column, float y) {
        PositionedText first = column.get(0);
        if (column.size() == 1) return new PositionedText(first.text(), first.x(), y, first.pageIndex(), first.width());

        StringBuilder text = new StringBuilder();
        boolean anyMeasured = false;
        for (PositionedText t : column) {
            if (!text.isEmpty()) text.append(' ');
            text.append(t.text().trim());
            anyMeasured |= t.width() > 0;
        }
        float left = anchorOf(column);
        return new PositionedText(text.toString(), left, y, first.pageIndex(),
                anyMeasured ? endOf(column) - left : 0f);
    }

    private float anchorOf(List<PositionedText> column) {
        float left = Float.MAX_VALUE;
        for (PositionedText t : column) left = Math.min(left, t.x());
        return left;
    }

    private float endOf(List<PositionedText> column) {
        float right = -Float.MAX_VALUE;
        for (PositionedText t : column) right = Math.max(right, t.endX());
        return right;
    }

    /** The y of the block's LAST line, given to every merged cell. The header physically ends
     *  there, and the row spacing measured off it (blockSeparation, and the page-boundary checks)
     *  is measured from where the header ends, not from where it began. */
    private float lastLineY(List<List<PositionedText>> block) {
        List<PositionedText> last = block.get(block.size() - 1);
        return last.get(0).y();
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
    // Raised from 8 to 16 once the density check in looksLikeHeaderRow took over the real work of
    // rejecting prose: 8 was low enough to reject a genuine 7-column header that PDFBox split into
    // 11 runs (a real HDFC statement). Kept as a cheap absolute backstop against pathological rows,
    // not as the primary discriminator it used to be.
    private static final int MAX_HEADER_ROW_CELLS = 16;

    /**
     * Bug fix, verified against two real HDFC Bank statements that each extracted ZERO
     * transactions while reporting a successful import. Two independent over-strictnesses here,
     * both of which had to be wrong for a real, perfectly ordinary statement to be invisible:
     *
     * 1. The date check accepted only an exact "date"/"date & time" cell -- so a header reading
     *    "Txn Date Narration Withdrawals Deposits Closing Balance" was rejected outright, despite
     *    this same class's own {@link #DATE_HINTS} already listing "txn date" (and "transaction
     *    date"/"value date") as date-column names for its continuation-merge logic. The class
     *    contradicted itself: one notion of "the date column" for merging, a stricter one for
     *    detection. Now both use DATE_HINTS.
     *
     * 2. Hint matching was exact string equality against the whole normalized cell, so the very
     *    common real-world column names "Closing Balance", "Withdrawal Amt.", "Deposit Amt." and
     *    "Narration" all failed to match "balance"/"withdrawals"/"deposits" -- a header reading
     *    "Date Narration Chq./Ref.No. Value Dt Withdrawal Amt. Deposit Amt. Closing Balance"
     *    scored exactly 1 (only "Date") against a >= 2 requirement and was rejected. Matching is
     *    now token-aware: a cell matches a hint if any of its own words matches, so a qualifier
     *    ("closing", "amt.") no longer hides the column name it qualifies.
     *
     * Token matching is deliberately NOT the unbounded substring matching that caused a separate
     * bank-misdetection bug (see BankRegistry.matchAlias's own comment): it is scoped to a single
     * already-tokenized header cell in a row of at most MAX_HEADER_ROW_CELLS cells, and compares
     * whole words, so it cannot fabricate a match out of two unrelated words running together.
     */
    private boolean looksLikeHeaderRow(List<PositionedText> row) {
        if (row.size() > MAX_HEADER_ROW_CELLS) return false;
        int matches = 0;
        for (PositionedText t : row) {
            if (matchesAnyHint(t.text(), HEADER_HINTS)) matches++;
        }
        // A date column + at least one other recognized column name -- same two-signal requirement
        // CsvParser.findHeaderRowIndex uses for CSV, adapted to this row's token set instead of
        // a whole line's raw text.
        boolean hasDate = row.stream().anyMatch(t -> matchesAnyHint(t.text(), DATE_HINTS));
        // Third bug fix from the same real HDFC statement (see this method's own doc comment):
        // a genuine 7-column header "Date | Narration | Chq./Ref.No. | Value Dt | Withdrawal Amt. |
        // Deposit Amt. | Closing Balance" extracts as ELEVEN text runs, because PDFBox splits
        // multi-word cells ("Withdrawal" and "Amt." arrive separately). The old flat cap of 8 cells
        // therefore rejected it outright before any hint could be scored. The cap exists to stop
        // prose being misread as a header (see MAX_HEADER_ROW_CELLS' own comment) -- but cell COUNT
        // was never the property that distinguishes the two. Density is: a real header is mostly
        // column names, while a prose sentence that happens to contain "date" and "amount" is
        // mostly ordinary words. Requiring a third of the cells to be recognized column names
        // rejects that 13-cell/2-match sentence exactly as before, while accepting this 11-cell/
        // 5-match header, so the original protection is kept rather than traded away.
        boolean denseEnoughToBeAHeader = matches * 3 >= row.size();
        return hasDate && matches >= 2 && denseEnoughToBeAHeader;
    }

    /** True when {@code cell} names one of {@code hints} -- either as the whole normalized cell
     *  ("transaction details"), or as one of its own whitespace-separated words ("Closing Balance"
     *  -> "balance"). Multi-word hints are only ever compared against the whole cell, since a
     *  single word can't match one. */
    private boolean matchesAnyHint(String cell, List<String> hints) {
        String normalized = CsvParser.normalizeHeaderCell(cell);
        if (normalized.isBlank()) return false;
        if (hints.contains(normalized)) return true;
        // Edge punctuation is stripped per word because normalizeHeaderCell only removes a
        // trailing parenthetical -- a real header cell "Withdrawal Amt." tokenizes to
        // ["withdrawal", "amt."], and the trailing period must not hide the match.
        String[] words = normalized.split("\\s+");
        for (String hint : hints) {
            if (hint.contains(" ")) continue; // multi-word hint: whole-cell comparison above only
            for (String word : words) {
                if (word.replaceAll("^[^a-z0-9]+|[^a-z0-9]+$", "").equals(hint)) return true;
            }
        }
        return false;
    }

    /** The account-number-shaped digit run a {@link #SECTION_MARKER} banner names, or null when the
     *  line names none -- the identity signal used to tell a repeated per-page banner for the SAME
     *  account (must not split) from a banner introducing a genuinely different account (must
     *  split). Takes the LONGEST digit run on the line: a banner commonly also carries shorter
     *  incidental numbers (a branch code, a page number), and the account number is reliably the
     *  longest of them. Returns null rather than guessing when nothing is long enough to be an
     *  account number, which makes the caller fall back to the pre-existing always-split behavior
     *  -- an unrecognizable banner is not evidence that two sections are the same account. */
    private String accountIdentityIn(String markerLine) {
        Matcher digits = ACCOUNT_NUMBER_IN_MARKER.matcher(markerLine);
        String longest = null;
        while (digits.find()) {
            String candidate = digits.group();
            if (longest == null || candidate.length() > longest.length()) longest = candidate;
        }
        return longest;
    }

    /** Normalized set of this header row's own column names -- used to tell "the same table's
     *  header, repeated on a later page" (identical signature) from "a genuinely different
     *  section's header" (a different signature), once a marker-line banner isn't present. */
    private Set<String> headerSignature(List<PositionedText> row) {
        Set<String> signature = new LinkedHashSet<>();
        for (PositionedText t : row) signature.add(CsvParser.normalizeHeaderCell(t.text()));
        return signature;
    }

    private Map<String, String> bucketRow(List<PositionedText> row, List<String> headerNames, List<Float> headerAnchors,
                                           List<Float> headerEnds, DocumentContext ctx) {
        Map<String, String> result = new LinkedHashMap<>();
        for (PositionedText t : row) {
            int nearest = nearestColumn(t.x(), headerAnchors);
            // RIGHT_ALIGNED_AMOUNTS. Every rule below places a run by its LEFT edge, which is the
            // right question for left-aligned text and the wrong one for a number. Financial
            // documents right-align amount columns, so within one column the right edge is fixed
            // and the left edge slides with the value's length -- meaning a SHORT number sits
            // further right than a long one in the same column, and can cross the midpoint into
            // the next column purely because it has fewer digits.
            //
            // That is not hypothetical. On a real HDFC statement the withdrawals column's values
            // all end at x=357.89, but their left edges run 333.43 ("436.00"), 337.87 ("20.00"),
            // 342.32 ("0.00") -- and the midpoint to the deposits anchor is 340.88. The three
            // longer values bucketed correctly and "0.00" alone landed in Deposits, merging that
            // row into "0.00 25,000.00" with no Withdrawals value at all. Downstream that made a
            // 25,000 deposit an expense, which in turn made the opening balance 50,000 instead of
            // 0.00, because opening balance is derived by backing the first row's amount out of
            // its running balance. One point-and-a-half of text width, three wrong numbers.
            //
            // Measured by right edge instead, that value is 14.94 from Withdrawals and 61.81 from
            // Deposits -- the margin goes from a 1.44-point miss to a 4x win, and every other
            // amount in the document still lands where it did.
            //
            // Deliberately only ever moves a number INTO an amount column: a run whose right edge
            // points at a description or reference column is left where the left edge put it,
            // since those are left-aligned and the right edge means nothing there. Requires a real
            // measured width, so hand-built fixtures and traces recorded before widths existed
            // (width 0, endX == x) keep exactly their previous behaviour.
            if (t.width() > 0 && headerEnds != null && CsvParser.parseNumeric(t.text().trim()) != null) {
                int byRightEdge = nearestColumn(t.endX(), headerEnds);
                if (byRightEdge != nearest && isAmountColumn(headerNames.get(byRightEdge))) {
                    nearest = byRightEdge;
                    if (ctx != null) ctx.record("RIGHT_ALIGNED_AMOUNTS");
                }
            }
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
                if (ctx != null) ctx.record("OFFSET_COLUMN_ANCHORS");
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
                    if (ctx != null) ctx.record("OFFSET_COLUMN_ANCHORS");
                }
            }
            // Two text runs landing in the same column on the same row (e.g. a multi-word
            // description PDFBox split into separate runs) get joined with a space rather than
            // the second one silently overwriting the first.
            result.put(columnName, existing == null ? t.text() : existing + " " + t.text());
        }
        splitTrailingAmountIfMissing(result, headerNames, ctx);
        splitLeadingAmountFromBalanceIfMissing(result, headerNames, ctx);
        return result;
    }

    // Recovers a transaction whose amount was never given its own column value at all -- see
    // LEADING_AMOUNT_IN_BALANCE's own doc comment for the real statement that motivated this.
    // Only acts when every deposit/withdrawal/credit/debit-hint column is genuinely empty for this
    // row (never overwrites a real value), and only ever pulls the leading number off a Balance
    // cell that is exactly "amount balance" and nothing else. Defaults the recovered amount to
    // whichever credit/deposit-hint column exists (this shape has only been seen on a
    // balance-increasing row so far -- a cashback/reward credit); if none exists, falls back to a
    // debit/withdrawal-hint column so the row is still recovered rather than silently dropped, on
    // the principle that a possibly-wrong direction is still strictly better than losing the row
    // entirely -- the review screen is where the user corrects it if this guess is wrong.
    private static final List<String> CREDIT_HINTS = List.of("deposit", "deposits", "credit", "cr amount", "credit amount");
    private static final List<String> DEBIT_HINTS = List.of("withdrawal", "withdrawals", "debit", "dr amount", "debit amount");

    private void splitLeadingAmountFromBalanceIfMissing(Map<String, String> result, List<String> headerNames, DocumentContext ctx) {
        String balanceColumn = headerNames.stream()
                .filter(h -> CsvParser.normalizeHeaderCell(h).equals("balance"))
                .findFirst().orElse(null);
        if (balanceColumn == null || !result.containsKey(balanceColumn)) return;

        boolean anyDirectionColumnAlreadyHasAValue = headerNames.stream()
                .anyMatch(h -> isAmountColumn(h) && !h.equals(balanceColumn) && result.containsKey(h));
        if (anyDirectionColumnAlreadyHasAValue) return;

        Matcher m = LEADING_AMOUNT_IN_BALANCE.matcher(result.get(balanceColumn));
        if (!m.matches()) return;

        // matchesAnyHint, not an exact contains(), for the same reason isAmountColumn now uses it:
        // a real "Deposit Amt." / "Withdrawal Amt." column never matched the exact list, so this
        // recovery silently found no target column and returned, dropping the row's amount on
        // exactly the layouts it was written to rescue.
        String targetColumn = headerNames.stream()
                .filter(h -> matchesAnyHint(h, CREDIT_HINTS))
                .findFirst()
                .or(() -> headerNames.stream().filter(h -> matchesAnyHint(h, DEBIT_HINTS)).findFirst())
                .orElse(null);
        if (targetColumn == null) return;

        result.put(balanceColumn, m.group(2));
        result.put(targetColumn, m.group(1));
        if (ctx != null) ctx.record("OFFSET_COLUMN_ANCHORS");
    }

    // Handles the case the two redirects above can't: some rows in a real statement render a
    // fee/charge line's label and its amount as ONE combined PDFBox text run to begin with (e.g.
    // "FUEL SURCHARGE                                  10.00 Dr" as a single run, internal spacing
    // baked in to visually right-align the number) rather than the usual two separate runs -- so
    // there's no separate run for the per-run redirects to catch. Only acts when this row's single
    // "amount" column (the DR_CR_SUFFIX capability's shape specifically -- see AMOUNT_COLUMN_HINTS'
    // broader definition, deliberately not reused here) came back with no value at all, and only
    // ever pulls off a trailing amount, never touches a column that already has one.
    private void splitTrailingAmountIfMissing(Map<String, String> result, List<String> headerNames, DocumentContext ctx) {
        String amountColumn = headerNames.stream()
                .filter(h -> CsvParser.normalizeHeaderCell(h).equals("amount"))
                .findFirst().orElse(null);
        if (amountColumn == null || result.containsKey(amountColumn)) return;
        for (String column : List.copyOf(result.keySet())) {
            Matcher m = TRAILING_AMOUNT.matcher(result.get(column));
            if (m.matches()) {
                result.put(column, m.group(1));
                result.put(amountColumn, m.group(2));
                if (ctx != null) ctx.record("OFFSET_COLUMN_ANCHORS");
                return;
            }
        }
    }

    /** Same over-strictness fixed in {@link #looksLikeHeaderRow} applied here: a "Txn Date"/
     *  "Value Date" column is just as much the date column as a bare "Date" one, and this is
     *  consulted by bucketRow's duplicate-date guard, which silently did nothing on such a
     *  document. Now shares DATE_HINTS with every other date-column decision in this class. */
    private boolean isDateColumn(String columnName) {
        return matchesAnyHint(columnName, DATE_HINTS);
    }

    private static final List<String> AMOUNT_COLUMN_HINTS =
            List.of("amount", "debit", "credit", "deposit", "withdrawal", "deposits", "withdrawals", "balance");

    /** Bug fix: this exact-matched where its sibling {@link #isDateColumn} matches per word, so
     *  the two disagreed about the same header on the same document. "Withdrawal Amt." -- a column
     *  name this class's own HEADER_HINTS comment cites from two real HDFC statements -- normalizes
     *  to "withdrawal amt.", which is not in the list above, so isAmountColumn returned false for a
     *  column that is unambiguously an amount column.
     *
     *  <p>The visible consequence is in mergeInto: with isAmountColumn false, the
     *  {@code wouldBreakValidAmount} conjunction is false and a wrapped narration fragment gets
     *  appended to the amount cell -- producing precisely what that guard's comment says it
     *  prevents, "'10.00 Dr' into '10.00 Dr levied', dropping a transaction that had parsed
     *  perfectly well."
     *
     *  <p>Note the parenthesized forms already worked: normalizeHeaderCell strips a trailing
     *  parenthetical, so "Withdrawal (Dr.)" became exactly "withdrawal" and matched. That is why
     *  {@code SingularDepositWithdrawalColumnsPdfPreviewGeneratorTest} passes and this went
     *  unnoticed -- the fixture used the one spelling the exact match happens to handle.
     *
     *  <p>Sharing matchesAnyHint does widen this: "Closing Balance" now counts as an amount column
     *  where before only a bare "Balance" did. That is correct at every call site -- narration
     *  must not be appended onto a valid closing balance either, and a bare "balance" was already
     *  a hint, so this only adds the qualified spellings of a column that already qualified. */
    private boolean isAmountColumn(String columnName) {
        return matchesAnyHint(columnName, AMOUNT_COLUMN_HINTS);
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
