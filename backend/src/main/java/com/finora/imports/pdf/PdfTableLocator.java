package com.finora.imports.pdf;

import com.finora.imports.CsvParser;
import com.finora.imports.DocumentContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
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

    // P-001 Fix B. The two bounds that admit a wrap merge onto a line that ALREADY scores as a
    // header on its own -- a case the merge used to be forbidden from touching at all. See
    // refinesRatherThanRedefines for the whole admission rule and wrappedHeaderAt's doc comment for
    // the real statement that needed it.
    //
    // STRICT_COLUMN_JOIN -- how far a lower cell may sit from an UPPER-LINE anchor and still be
    // that column's second line. Deliberately NOT the 40pt MAX_COLUMN_JOIN above: that bound exists
    // to let a CENTER-aligned continuation label sit off its column's left edge, and at 40pt it is
    // far too loose to be a discriminator when the alternative reading ("this is the table's first
    // data row") is already a working one. Measured across every trace in the corpus that has a
    // scoring header with a dateless line within 12pt below it: the genuine wraps sit at 3.89pt
    // (Central Bank of India) and 4.51pt (ICICI credit card), and the next-closest non-wrap is a
    // BoB narration line at 36.92pt. 5.0 sits inside a 32pt empty band, so it is a separating
    // value rather than a fitted one.
    //
    // STRICT_MIN_LOWER_CELLS -- 2. One stray token below a header is not a wrapped heading; it is a
    // footnote, a unit annotation or a narration fragment, and the corpus is full of them
    // (HSBC's "(DR=Debit)", AU's "amount due.", HDFC credit card's "(Xxxxxxxxx Xxxx)"). A second
    // line has to carry at least two column names before "these two lines are one header" is a
    // claim about the table's structure rather than about one label.
    private static final float HEADER_WRAP_STRICT_COLUMN_JOIN = 5.0f;
    private static final int HEADER_WRAP_STRICT_MIN_LOWER_CELLS = 2;


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

    // ILLUSTRATIVE_BLOCK_SUPPRESSED. A real AU Small Finance Bank credit-card statement carries a
    // fee/interest-calculation appendix -- "Illustration for calculating Interest & Late Payment
    // Charges" -- containing THREE fictional worked-example tables, each introduced by "The
    // following illustration will indicate the method of calculating...". Each one is a
    // perfectly well-formed header by every existing rule (a date-hint cell, >=2 HEADER_HINTS
    // matches, passes the density check) because it IS a real table -- just one describing
    // invented example transactions, not the statement's own. With nothing distinguishing
    // "real" from "illustrative," each of the three opened its own section via the
    // header-signature-difference fallback below, producing three garbage sections with headers
    // like "Date, Transaction/ Details, Amount, Balance, Transaction Type, Remarks" -- and because
    // those sections were non-empty, the REAL transactions (a completely different, non-tabular
    // shape -- see INFERRED_TWO_LINE_DATE_BLOCK) never got a chance: the zero-section fallback
    // gate at the end of locateAll never fired.
    //
    // Matched loosely against the observed phrasing (two clauses, both directly evidenced on the
    // real document, which uses the "following illustration will indicate" wording for two of its
    // three fake tables) rather than broadened with unevidenced synonyms ("specimen", "illustrative
    // example") -- see "Evidence before capability" in the engineering principles doc. Verified via
    // direct PositionedText geometry dump that the sentence renders as one un-wrapped run, so a
    // single-row match is sufficient; it does not need the two-row lookahead WRAPPED_HEADER needs
    // for a heading that spans physical lines.
    private static final Pattern ILLUSTRATIVE_EXAMPLE_MARKER = Pattern.compile(
            "(?i)\\bfollowing\\s+illustration\\s+will\\s+indicate\\b"
                    + "|(?i)\\billustration\\s+for\\s+calculating\\b");

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
        // ILLUSTRATIVE_BLOCK_SUPPRESSED. One-way: once an illustrative-example marker is seen,
        // every row for the REST OF THE DOCUMENT is treated the same as today's dateless
        // no-header-found rows -- folded into pendingAuxiliary, never a header, never a new
        // section. Not a resume-on-next-marker state machine: on the one real document this
        // exists for, real content never resumes after the fee/interest-illustration appendix
        // begins (it runs to the end of the statement), and a one-way gate is meaningfully
        // simpler to reason about than tracking where illustrative content ends. If a future real
        // document needs resumption, that is new evidence to design against, not something to
        // guess at now.
        boolean illustrativeBlockActive = false;

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

            if (illustrativeBlockActive) {
                pendingAuxiliary.add(rowLine);
                continue;
            }
            if (ILLUSTRATIVE_EXAMPLE_MARKER.matcher(rowLine).find()) {
                illustrativeBlockActive = true;
                // Closes whatever REAL section is open exactly the same way the header-signature
                // fallback below does (flush pendingLeading, stage the section) -- a document with
                // a genuine header-based table followed by this appendix must keep that real
                // section, not lose it along with the boilerplate that follows.
                if (currentRows != null) {
                    flushPendingLeading(currentRows, pendingLeading);
                    sections.add(new LocatedSection(pendingAuxiliary, currentRows));
                    pendingAuxiliary = new ArrayList<>();
                    currentRows = null;
                }
                if (ctx != null) ctx.record("ILLUSTRATIVE_BLOCK_SUPPRESSED");
                pendingAuxiliary.add(rowLine);
                continue;
            }

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
            // Asked on BOTH sides of "does this line already score as a header on its own" -- see
            // wrappedHeaderAt. On a line that does not score, the merge is the only way a table is
            // found at all and runs under the original 40pt admission rule. On a line that already
            // scores, the merge can only RENAME columns that were going to exist anyway, so it runs
            // under a much stricter one (P-001 Fix B, measured on a real Central Bank of India
            // statement whose header's second band was otherwise consumed as a data row).
            WrappedHeader wrapped = wrappedHeaderAt(rows, rowIndex, looksLikeHeaderRow(row));
            if (wrapped != null) {
                headerRow = wrapped.row();
                wrappedHeaderLines = wrapped.extraLines();
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
                // Sorted by x, matching the invariant mergeHeaderLines already establishes and
                // documents for the wrapped-header path ("the whole pipeline downstream of here
                // reads header cells in left-to-right order"). This single-line path never had that
                // guarantee: coalesceHeaderRuns preserves row's own order, which is PDFBox's text-
                // extraction order, not necessarily left-to-right. Verified on a real SBI credit-card
                // statement whose header extracted as [Transaction Details, Date, Amount, ( ` )] --
                // Transaction Details BEFORE Date despite sitting well to its right (x=179 vs x=35).
                // bucketRow's date-collision redirect ("Date already has a value, so this run
                // belongs to nearest+1") and OFFSET_COLUMN_ANCHORS's forward amount search both
                // assume index order IS x order; on that unsorted list, "the column after Date"
                // resolved to Amount, skipping over Transaction Details entirely, and the whole
                // description merged into the amount cell -- silently defeating every real amount on
                // the statement.
                List<PositionedText> coalesced = new ArrayList<>(coalesceHeaderRuns(stripEmbeddedDateRange(row)));
                coalesced.sort(Comparator.comparing(PositionedText::x));
                for (PositionedText t : coalesced) {
                    headerNames.add(t.text().trim());
                    headerAnchors.add(t.x());
                    headerEnds.add(t.endX());
                }
                resolveDuplicateColumnNames(headerNames, headerAnchors, rows, rowIndex, ctx);
                resolveBlankColumnNames(headerNames, headerAnchors, rows, rowIndex, ctx);
                recoverMissingDescriptionColumn(headerNames, headerAnchors, headerEnds, rows, rowIndex, ctx);
                recoverMissingSerialNumberColumn(headerNames, headerAnchors, headerEnds, rows, rowIndex, ctx);
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
        // INFERRED_HEADERLESS_LAYOUT. Only ever attempted once the loop above has already found
        // nothing -- see this capability's own doc comment on inferHeaderlessSection for why a
        // header-vocabulary miss on the whole document is a different problem from every other
        // capability in this class, which all assume a header was found and refine what happens
        // around it. Gated on sections.isEmpty() specifically so this can only ever turn today's
        // failure into a result; it is unreachable on every document that already parses.
        if (sections.isEmpty()) {
            LocatedSection inferred = inferHeaderlessSection(rows, ctx);
            if (inferred == null) inferred = inferTwoLineDateBlockSection(rows, ctx);
            if (inferred != null) sections.add(inferred);
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
            // Bug fix: this used to only redirect when the MERGED text failed to re-parse, on the
            // theory that a merge which still parses cleanly must be safe. It isn't -- a bare
            // numeric fragment (a stray reference-number digit run, a fee subtotal) landing in an
            // already-populated amount column merges into a DIFFERENT, still-perfectly-valid
            // number ("45" + " " + "6" -> "456" once CsvParser.parseNumeric strips the space),
            // silently corrupting a real transaction's amount with no error, no flag, and no
            // diagnostic. A real amount is always printed once, on one line, in every layout this
            // file has ever seen documented -- unlike a description, there is no legitimate case
            // where a continuation row's numeric fragment is meant to extend an amount cell's
            // value. So this is now unconditional, exactly like the date guard immediately above
            // it: any already-valid amount is authoritative and is never merged into, full stop,
            // regardless of whether the merged text would still happen to parse.
            boolean wouldBreakValidDate = isDateColumn(e.getKey()) && existing != null
                    && CsvParser.parseDate(existing.trim()) != null;
            boolean wouldBreakValidAmount = isAmountColumn(e.getKey()) && existing != null
                    && CsvParser.parseNumeric(existing.trim()) != null;
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
        // Bug fix: same gap as mergeInto's wouldBreakValidAmount -- re-parsing the prepended text
        // only catches a merge that becomes unparseable, not one that silently becomes a
        // DIFFERENT valid number (e.g. a stray digit fragment prepended to "45" becoming "645").
        // An already-valid amount is authoritative and is never merged into, unconditionally, the
        // same as an already-valid date immediately above.
        if (isAmountColumn(column) && CsvParser.parseNumeric(existing.trim()) != null) {
            return true;
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
     * <p>Was only ever called on a line that is NOT already a header by itself. That restriction
     * was load-bearing, not caution: it meant no document whose header is recognized today could
     * have its header changed, so this could only turn "no table found" into "table found".
     *
     * <p><b>P-001 Fix B</b> lifts it, under a strictly narrower admission rule -- see
     * {@link #refinesRatherThanRedefines}. It had to be lifted, and no threshold change reaches
     * the document that needed it. The real Central Bank of India savings statement
     * ({@code central-bank-savings-ledger-validation}) has a genuine two-band header whose bands
     * are 11.64pt apart -- already INSIDE {@link #HEADER_WRAP_MAX_GAP}. The merge was refused only
     * because band 1 alone scores: token-aware matching sees {@code date} inside "Post Date", plus
     * Debit/Credit/Balance. Band 2 -- "Date | Code | Number" -- therefore fell through and was
     * consumed as the table's first data row ({@code {Value=Date, Branch=Code, Cheque=Number}}),
     * and the date column stayed named "Value" rather than "Value Date".
     *
     * <p>That is not a cosmetic loss. {@code TransactionNormalizer} resolves its date column by
     * WHOLE-CELL comparison against {@code DATE_HINTS}, and neither "value" nor "post date"
     * is in it. Measured on that trace: of 224 located rows, <b>0</b> carried a column the
     * normalizer could read as a date, so all 222 of its transactions were rejected downstream --
     * while the locator recorded a successful single-section parse. It is the only 100% row loss
     * in the committed corpus, and it is silent.
     *
     * <p>The general safety property above is preserved in a different form: on an
     * already-scoring line the merge cannot change WHETHER a table is found, cannot change how
     * many columns it has, and is admitted only when it demonstrably improves how many of them
     * the normalizer can name.
     *
     * <p>Correction (P-001 investigation): this comment used to cite the real Canara Bank
     * statement's LEADING_NARRATION_CONTINUATION layout as the layout this guard protects. It is
     * not. Measured on {@code canara-savings-ledger-validation}, the line below that header sits
     * <b>24pt</b> down -- outside {@link #HEADER_WRAP_MAX_GAP} (12.0) -- and carries a parseable
     * number ("1,15,238.60"), which {@link #carriesNoDataValue} refuses on its own. Canara is
     * doubly protected by the gap bound and the numeric check, and would be safe with this guard
     * removed. The guard's real value is the general one stated above; no known corpus document
     * depends on it alone.
     *
     * <p>Merging is also the SAFE direction for the prose false-positive that
     * {@link #MAX_HEADER_ROW_CELLS} and the density check exist to reject: joining lines adds
     * cells faster than it adds recognized column names, so a paragraph merged with its
     * neighbour scores strictly LESS dense than either line did, not more.
     */
    private WrappedHeader wrappedHeaderAt(List<List<PositionedText>> rows, int index, boolean alreadyScores) {
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
                // The strict admission rule applies ONLY when the upper line already scores on its
                // own. Kept as a `continue` rather than a `break` so a refused two-line span can
                // still be re-offered as a three-line one -- the extra line can only add lower
                // cells and column names, which is the direction that makes gates 1 and 4 easier
                // while gate 2 stays exactly as strict.
                if (alreadyScores && !refinesRatherThanRedefines(block, candidate)) continue;
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
     * P-001 Fix B's admission rule: whether merging these lines REFINES the header the upper line
     * already states, rather than REDEFINING it into a different table.
     *
     * <p>Asked only when the upper line already scores as a header on its own -- the case
     * {@link #wrappedHeaderAt} used to refuse outright. When it does not score, the merge is the
     * only way a table is found at all and none of this applies: nothing can regress, because
     * today there is nothing there.
     *
     * <p>When it DOES score, the alternative reading -- "the upper line is the header and the lower
     * line is the table's first data row" -- is already a working one on most documents, so the
     * merge has to clear a much higher bar than "these two lines are close together". Four gates,
     * every one of them measured against the whole committed trace corpus:
     *
     * <ol>
     *   <li><b>At least {@link #HEADER_WRAP_STRICT_MIN_LOWER_CELLS} lower cells.</b> A single token
     *       under a header is a footnote, not a second heading band.</li>
     *   <li><b>Every lower cell within {@link #HEADER_WRAP_STRICT_COLUMN_JOIN} of an UPPER-LINE
     *       anchor.</b> Measured against the upper line's own left edges, not against the merged
     *       columns' anchors, which {@code mergeHeaderLines} moves as it joins. This is the gate
     *       that separates a printed second band from a line that merely sits nearby.</li>
     *   <li><b>No new columns.</b> The merged row must have exactly as many cells as the upper line
     *       has non-blank runs. A genuine wrapped band renames the columns above it; it never
     *       introduces one. {@code mergeHeaderLines} already refuses a cell that joins no column,
     *       so today this is an invariant rather than a filter -- stated here anyway, because it is
     *       the property that makes "one header over two lines" mean something, and the half-named
     *       heading documented on {@code mergeHeaderLines} shows the pressure to relax it.</li>
     *   <li><b>Strictly more WHOLE-CELL hint matches than the upper line alone.</b> The safety
     *       valve. {@code TransactionNormalizer} resolves its date and amount columns by whole-cell
     *       comparison, not by the token-aware matching {@link #looksLikeHeaderRow} scores with, so
     *       this counts what the normalizer can actually name. Requiring a strict increase means a
     *       merge is admitted only where it demonstrably improves that count -- a merge that merely
     *       shuffles names, or makes them worse, is refused and the document keeps exactly today's
     *       behaviour. On Central Bank of India this goes 4 -> 5 ("Value" + "Date" -> "Value Date",
     *       which {@link #DATE_HINTS} lists and "value" alone is not).</li>
     * </ol>
     *
     * <p>Gate 4's other half -- that the merged row still scores as a header at all -- is the
     * caller's {@code looksLikeHeaderRow(candidate)} check, which is why it is not repeated here.
     *
     * <p>Taken together these give back the safety property the old {@code !looksLikeHeaderRow}
     * guard provided, in a different form: on an already-scoring line the merge cannot change
     * WHETHER a table is found, cannot change how many columns it has, and is admitted only when it
     * demonstrably improves how many of them the normalizer can name.
     */
    private boolean refinesRatherThanRedefines(List<List<PositionedText>> block, List<PositionedText> merged) {
        List<PositionedText> upper = block.get(0);

        // Gate 1 -- the lower band has to contribute more than one stray token.
        int lowerCells = 0;
        for (int line = 1; line < block.size(); line++) {
            for (PositionedText t : block.get(line)) {
                if (!t.text().isBlank()) lowerCells++;
            }
        }
        if (lowerCells < HEADER_WRAP_STRICT_MIN_LOWER_CELLS) {
            int counted = lowerCells;
            explainWrap(upper, () -> "NO_MERGE (strict): the upper line already scores as a header on"
                    + " its own, and the lower line(s) contribute only " + counted + " cell(s) -- fewer"
                    + " than " + HEADER_WRAP_STRICT_MIN_LOWER_CELLS + ", so this is a footnote, not a"
                    + " second heading band");
            return false;
        }

        // Gate 2 -- every lower cell sits under a column the upper line actually established.
        for (int line = 1; line < block.size(); line++) {
            for (PositionedText t : block.get(line)) {
                if (t.text().isBlank()) continue;
                float nearest = Float.MAX_VALUE;
                for (PositionedText anchor : upper) {
                    if (anchor.text().isBlank()) continue;
                    nearest = Math.min(nearest, Math.abs(t.x() - anchor.x()));
                }
                if (nearest > HEADER_WRAP_STRICT_COLUMN_JOIN) {
                    float distance = nearest;
                    explainWrap(upper, () -> "NO_MERGE (strict): the upper line already scores as a"
                            + " header on its own, and lower cell \"" + t.text().trim() + "\" at x="
                            + t.x() + " is " + distance + "pt from the nearest column above it (limit "
                            + HEADER_WRAP_STRICT_COLUMN_JOIN + "pt) -- not a printed second band");
                    return false;
                }
            }
        }

        // Gate 3 -- refinement, not redefinition: the merge renames columns, never adds one.
        int upperColumns = 0;
        for (PositionedText t : upper) {
            if (!t.text().isBlank()) upperColumns++;
        }
        if (merged.size() != upperColumns) {
            int defined = upperColumns;
            explainWrap(upper, () -> "NO_MERGE (strict): merging would leave " + merged.size()
                    + " columns where the upper line alone defines " + defined
                    + " -- a wrapped band renames columns, it does not introduce them");
            return false;
        }

        // Gate 4 -- and it has to be an improvement the normalizer can actually see.
        int before = wholeCellHintMatches(upper);
        int after = wholeCellHintMatches(merged);
        if (after <= before) {
            explainWrap(upper, () -> "NO_MERGE (strict): merging does not increase the number of"
                    + " columns nameable by whole-cell comparison (" + before + " -> " + after
                    + "), so it is a rename rather than an improvement -- the unmerged reading stands");
            return false;
        }
        return true;
    }

    /**
     * How many of this row's cells name a known column by WHOLE-CELL comparison -- the way
     * {@code TransactionNormalizer} resolves its columns, and deliberately NOT the way
     * {@link #matchesAnyHint} scores a header row.
     *
     * <p>The difference is the entire point of gate 4. Token-aware matching sees {@code date}
     * inside "Post Date" and calls the column found; the normalizer compares "post date" against
     * its hint list and finds nothing. Counting the token-aware way would make Central Bank of
     * India's merge look like no improvement at all -- both readings score a date column -- when in
     * fact the merge is the difference between 0 and 222 importable transactions.
     */
    private int wholeCellHintMatches(List<PositionedText> row) {
        int matches = 0;
        for (PositionedText t : row) {
            String normalized = CsvParser.normalizeHeaderCell(t.text());
            if (normalized.isBlank()) continue;
            if (DATE_HINTS.contains(normalized) || HEADER_HINTS.contains(normalized)) matches++;
        }
        return matches;
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
     * Detects header cells that normalize to the SAME column name -- two cells both literally
     * "Amount (INR)" is the real case this exists for, on a statement whose heading prints in
     * three stacked tiers and whose accepted header line is only the bottom tier, because
     * {@link #mergeHeaderLines} correctly refuses to fold the tier above it in (a "Cheque Number"
     * label sits past {@link #HEADER_WRAP_MAX_COLUMN_JOIN} from anything in the bottom tier, and
     * that refusal is deliberate -- see mergeHeaderLines's own doc comment). The bottom tier alone
     * names its debit and credit columns identically, and {@link #bucketRow} has no way to tell
     * them apart once that happens: every value lands under whichever of the two the row-bucketing
     * search reaches first, silently discarding the other.
     *
     * <p>When a collision is found, this tries to recover the missing distinction from the tier
     * that {@code mergeHeaderLines} refused to fold in wholesale, by looking at just the ONE label
     * near each duplicate's own x position rather than requiring the whole line to join. This is
     * narrower than a full merge and does not reopen the refusal above: it never runs unless two
     * columns already collapsed to one name, so it cannot re-admit an unrelated extra column the
     * way folding the whole tier in would.
     *
     * <p>The DUPLICATE_COLUMN_NAMES signal is recorded whenever a collision is found, whether or
     * not a qualifying label turns up -- an unresolved collision is still worth knowing about, since
     * it is exactly the shape of bug this method exists to catch.
     */
    private void resolveDuplicateColumnNames(List<String> headerNames, List<Float> headerAnchors,
                                              List<List<PositionedText>> rows, int rowIndex, DocumentContext ctx) {
        Map<String, List<Integer>> byNormalizedName = new LinkedHashMap<>();
        for (int i = 0; i < headerNames.size(); i++) {
            String normalized = CsvParser.normalizeHeaderCell(headerNames.get(i));
            if (normalized.isEmpty()) continue;
            byNormalizedName.computeIfAbsent(normalized, k -> new ArrayList<>()).add(i);
        }
        boolean anyDuplicate = false;
        for (List<Integer> indices : byNormalizedName.values()) {
            if (indices.size() < 2) continue;
            anyDuplicate = true;
            for (int index : indices) {
                String qualifier = findQualifyingLabel(rows, rowIndex, headerAnchors.get(index));
                if (qualifier != null) {
                    headerNames.set(index, qualifier + " " + headerNames.get(index));
                }
            }
        }
        if (anyDuplicate && ctx != null) ctx.record("DUPLICATE_COLUMN_NAMES");
    }

    /**
     * A header cell whose printed text is real but whose NORMALIZED form is blank -- a bare
     * currency unit like "(INR)" is the real case this exists for (see
     * {@link CsvParser#normalizeHeaderCell}: a trailing parenthetical is stripped as noise, and
     * here the parenthetical IS the entire cell, verified on a real ICICI savings e-statement
     * whose Balance column heading prints as "Balance" one tier up and bare "(INR)" on the
     * accepted line). Every downstream recognizer ({@code TransactionNormalizer.recognizedColumnNames})
     * matches only a normalized name, so a column like this is invisible everywhere below this
     * class even though its header text looks present -- the same silent-loss shape
     * {@link #resolveDuplicateColumnNames} exists for, just triggered by an empty name instead of
     * a repeated one. Kept as a separate method (not folded into that one) because the trigger and
     * the capability it reports are genuinely different signals -- a document can have one without
     * the other -- and conflating them would make DUPLICATE_COLUMN_NAMES mean two different things.
     * Recovered the identical way: {@link #findQualifyingLabel} searches the tier(s)
     * {@link #mergeHeaderLines} already refused to fold in wholesale for a single label near this
     * column's own anchor.
     */
    private void resolveBlankColumnNames(List<String> headerNames, List<Float> headerAnchors,
                                          List<List<PositionedText>> rows, int rowIndex, DocumentContext ctx) {
        boolean anyBlank = false;
        for (int i = 0; i < headerNames.size(); i++) {
            if (!CsvParser.normalizeHeaderCell(headerNames.get(i)).isEmpty()) continue;
            anyBlank = true;
            String qualifier = findQualifyingLabel(rows, rowIndex, headerAnchors.get(i));
            if (qualifier != null) headerNames.set(i, qualifier + " " + headerNames.get(i));
        }
        if (anyBlank && ctx != null) ctx.record("BLANK_COLUMN_NAME_QUALIFIED");
    }

    /**
     * A narration/remarks column that has NO representation at all on the accepted header line --
     * not a misnamed or blank cell (the two cases above already cover those), a column that
     * genuinely does not exist there. Verified on the same real ICICI statement
     * {@link #resolveBlankColumnNames} is: its heading prints in three stacked tiers, and
     * "Transaction Remarks" lives ONLY on the middle one -- the tier {@link #mergeHeaderLines}
     * correctly and deliberately refuses to fold in wholesale (its "Cheque Number" cell sits past
     * {@link #HEADER_WRAP_MAX_COLUMN_JOIN} from anything on the accepted bottom tier -- see that
     * method's own doc comment for why that refusal is right). Losing the whole tier for that
     * reason also loses the one cell on it every transaction row actually needs.
     *
     * <p>Deliberately narrower than "admit any cell the tier refused" -- the fix
     * {@code mergeHeaderLines}'s own doc comment records as tried and reverted, because it
     * re-admitted an unrelated table's own heading elsewhere in the same document. This only ever
     * admits ONE cell, and only when its normalized text is already a recognized label from a
     * small, curated vocabulary -- a content gate, not a position-only one -- and only when the
     * accepted header has no such column at all yet. A line offering more than one such label is
     * ambiguous and refused rather than guessed at.
     *
     * <p>Also what recovers a completely unnamed "S No." column on the same real ICICI statement
     * -- not for its own sake (nothing downstream reads a serial number), but because leaving it
     * unnamed is actively harmful: {@link #bucketRow}'s {@link #nearestColumn} has no maximum-
     * distance cap, so S No.'s own digit values (the leftmost thing on every row) are nearer to
     * the Date column's anchor than to anything else and land there instead, corrupting every
     * row's date with a prepended serial number ("1 28.07.2026") until it no longer parses at
     * all. Giving the column its own anchor, exactly the way Transaction Remarks is recovered
     * below, removes the collision at its source rather than teaching bucketRow to special-case
     * it.
     */
    private static final Set<String> DESCRIPTION_COLUMN_LABELS = Set.of(
            "description", "narration", "remarks", "particulars", "transaction remarks",
            "transaction details", "transaction description");
    private static final Set<String> SERIAL_NUMBER_COLUMN_LABELS = Set.of(
            "s no", "sno", "sr no", "srno", "serial no", "serial number");

    private void recoverMissingDescriptionColumn(List<String> headerNames, List<Float> headerAnchors,
            List<Float> headerEnds, List<List<PositionedText>> rows, int rowIndex, DocumentContext ctx) {
        if (recoverMissingColumn(headerNames, headerAnchors, headerEnds, rows, rowIndex, DESCRIPTION_COLUMN_LABELS)
                && ctx != null) {
            ctx.record("RECOVERED_MISSING_DESCRIPTION_COLUMN");
        }
    }

    private void recoverMissingSerialNumberColumn(List<String> headerNames, List<Float> headerAnchors,
            List<Float> headerEnds, List<List<PositionedText>> rows, int rowIndex, DocumentContext ctx) {
        if (recoverMissingColumn(headerNames, headerAnchors, headerEnds, rows, rowIndex, SERIAL_NUMBER_COLUMN_LABELS)
                && ctx != null) {
            ctx.record("RECOVERED_MISSING_SERIAL_NUMBER_COLUMN");
        }
    }

    /**
     * Deliberately restricted to the SINGLE line immediately above the accepted header ({@code
     * rowIndex - 1}), unlike {@link #findQualifyingLabel}'s {@link #HEADER_WRAP_MAX_LINES}-deep
     * search -- that method also requires proximity to a SPECIFIC existing column's anchor
     * ({@link #HEADER_WRAP_MAX_COLUMN_JOIN}), which this method has no equivalent of (it has no
     * existing column to be near; that is the whole reason it exists). Without a positional gate
     * of some kind, scanning multiple lines back is unsafe: real regression, found on a real SBI
     * credit-card statement's composite five-section layout -- a "Transaction Details" label that
     * genuinely belongs to a DIFFERENT nearby section two lines back got attached to a section
     * that never had one, changing where an already-tolerated rejected-prose fragment landed.
     * Restricting to the immediately-adjacent line is what {@link #resolveDuplicateColumnNames}'s
     * and {@link #resolveBlankColumnNames}'s real cases both actually need too -- ICICI's "S No.",
     * "Transaction Remarks", and "Balance" qualifiers all live on the single tier directly above
     * the accepted header -- so this is not a narrower capability, only a narrower search.
     */
    private boolean recoverMissingColumn(List<String> headerNames, List<Float> headerAnchors,
            List<Float> headerEnds, List<List<PositionedText>> rows, int rowIndex, Set<String> recognizedLabels) {
        for (String name : headerNames) {
            if (recognizedLabels.contains(CsvParser.normalizeHeaderCell(name))) return false;
        }
        if (rowIndex - 1 < 0) return false;
        List<PositionedText> candidate = rows.get(rowIndex - 1);
        if (candidate.isEmpty() || !carriesNoDataValue(candidate) || hasProseLengthCell(candidate)) return false;
        // Requires at least 2 non-blank cells -- a genuine header TIER (ICICI's real case: three
        // cells, "S No." / "Cheque Number" / "Transaction Remarks", sharing one line) has more than
        // one, where a lone caption label does not. Real regression, found on the same SBI
        // statement this method's own doc comment describes: a rejected block's own caption prints
        // "Transaction Details" as a single, isolated cell on its own line, immediately above where
        // that same rejected content's "for Statement Period: ..." text lands as an orphan row --
        // lexically identical to a real narration-column label, but structurally a caption for
        // unrelated content, not a second tier of THIS table's header.
        if (candidate.stream().filter(t -> !t.text().isBlank()).count() < 2) return false;
        PositionedText found = null;
        for (PositionedText t : candidate) {
            String text = t.text().trim();
            if (text.isEmpty() || !recognizedLabels.contains(CsvParser.normalizeHeaderCell(text))) continue;
            if (found != null) return false; // more than one candidate -- ambiguous, refuse
            found = t;
        }
        if (found == null) return false;
        int insertAt = 0;
        while (insertAt < headerAnchors.size() && headerAnchors.get(insertAt) < found.x()) insertAt++;
        headerNames.add(insertAt, found.text().trim());
        headerAnchors.add(insertAt, found.x());
        headerEnds.add(insertAt, found.endX());
        return true;
    }

    /**
     * Searches up to {@link #HEADER_WRAP_MAX_LINES} lines immediately above the accepted header
     * row for a single label near {@code anchorX}, using the same left-edge tolerance
     * ({@link #HEADER_WRAP_MAX_COLUMN_JOIN}) {@link #mergeHeaderLines} uses to join a whole line --
     * applied here to one column instead of requiring every cell in the candidate line to join
     * one. A candidate line is skipped unless it independently reads as label text: no date or
     * number ({@link #carriesNoDataValue}) and no prose-length cell ({@link #hasProseLengthCell}),
     * which is what keeps this from picking up an unrelated caption, disclaimer, or -- worse -- an
     * actual data row sitting above a table that never had a header line at all.
     */
    private String findQualifyingLabel(List<List<PositionedText>> rows, int rowIndex, float anchorX) {
        for (int back = 1; back <= HEADER_WRAP_MAX_LINES && rowIndex - back >= 0; back++) {
            List<PositionedText> candidate = rows.get(rowIndex - back);
            if (candidate.isEmpty() || !carriesNoDataValue(candidate) || hasProseLengthCell(candidate)) continue;
            PositionedText nearest = null;
            float nearestDistance = HEADER_WRAP_MAX_COLUMN_JOIN;
            for (PositionedText t : candidate) {
                String text = t.text().trim();
                if (text.isEmpty()) continue;
                float distance = Math.abs(t.x() - anchorX);
                if (distance <= nearestDistance) {
                    nearest = t;
                    nearestDistance = distance;
                }
            }
            if (nearest != null) return nearest.text().trim();
        }
        return null;
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

    // How much horizontal space may sit between two runs of the SAME header cell. Bug fix (P-001),
    // measured on three real HDFC savings statements: PDFBox emits a genuine 7-column header
    // "Date | Narration | Chq./Ref.No. | Value Dt | Withdrawal Amt. | Deposit Amt. | Closing Balance"
    // as ELEVEN runs, splitting every multi-word cell at its space ("Withdrawal" and "Amt." arrive
    // separately). looksLikeHeaderRow already accepts that row (its own density check exists for
    // exactly this document) -- what broke is what the columns are CALLED: one column per run gave
    // TWO columns literally named "Amt.", which collide on the same key in bucketRow's map, and
    // "amt" matches nothing in TransactionNormalizer's hint lists, so every amount fell through to
    // its last-resort "balance" entry. Measured on the three traces: 230 / 343 / 7 rows staged with
    // the running BALANCE as their amount, and every deposit staged as an EXPENSE -- the same
    // silently-wrong-data failure already documented for Kotak's "Deposit (Cr.)" in
    // TransactionNormalizer, arriving through a different door.
    //
    // 6pt is a midpoint with margin on both sides, not a fitted value. On the HDFC header the
    // intra-cell gaps are 2.00 / 2.00 / 2.00 / 2.01 pt (one space at that font size); the smallest
    // genuine INTER-column gap anywhere in the committed corpus is 7.99pt ("Txn Date" -> "Type" on
    // the Axis credit-card fine-print line) and the smallest on any accepted header row is 13.38pt
    // ("Dt" -> "Withdrawal", same HDFC header). Nothing in the corpus sits between 2.01 and 7.99.
    private static final float HEADER_RUN_JOIN_MAX_GAP = 6.0f;

    /**
     * An accepted header row with each multi-word cell's runs put back together -- see
     * {@link #HEADER_RUN_JOIN_MAX_GAP} for the real statements this was measured on.
     *
     * <p>Called at exactly one place: where {@code headerNames}/{@code headerAnchors}/
     * {@code headerEnds} are built in {@code locateAll}, which is strictly AFTER
     * {@link #looksLikeHeaderRow} has already accepted the row and AFTER any
     * {@link #wrappedHeaderAt} merge. Both halves of that placement are load-bearing and were
     * measured, not assumed:
     *
     * <ul>
     *   <li>Joining runs SHRINKS {@code row.size()} while leaving the hint count unchanged or
     *       higher, so it makes {@code looksLikeHeaderRow}'s density test
     *       ({@code matches * 3 >= row.size()}) strictly easier to pass -- the opposite direction
     *       from the vertical merge in {@link #wrappedHeaderAt}, which is safe precisely because it
     *       adds cells faster than names. Applied to every line rather than only to an
     *       already-accepted header, it invented a bogus section out of an Axis credit-card
     *       statement's fine print, which is the exact false-positive class
     *       {@link #MAX_HEADER_ROW_CELLS} and the density check exist to stop. Running it only on
     *       a row that ALREADY scored as a header means this can never change WHETHER a row is a
     *       header -- only what its columns are named.</li>
     *   <li>{@code mergeHeaderLines} seeds its columns from the first line's RUNS and joins later
     *       lines by nearest anchor, so coalescing before it changes which columns exist and
     *       therefore which joins are made -- in simulation that shifted section boundaries on the
     *       SBI credit-card statement, a WRAPPED_HEADER document. After the merge, nothing it
     *       decided can be revisited.</li>
     * </ul>
     *
     * <p>Both runs must carry a MEASURED width. With {@code width == 0}, {@code endX() == x} and
     * the "gap" degenerates into the raw x-delta between two left edges, which says nothing about
     * whether they touch -- it could join two genuinely separate columns. Older v1/v2 traces and
     * some redacted runs are exactly that shape, and they keep today's behaviour, the same
     * precedent RIGHT_ALIGNED_AMOUNTS and {@link #asOneCell} already set.
     *
     * <p>Neither run may parse as a date or a number. Header cells are words; a pair of adjacent
     * VALUES that happen to sit close together (two amounts in narrow neighbouring columns) must
     * never be glued into one fabricated column name.
     */
    /**
     * Drops an embedded "from &lt;date&gt; to &lt;date&gt;" statement-period span from a header row
     * before its cells become column names.
     *
     * <p>Verified on a real Kotak credit-card statement, whose header prints its own statement
     * period inline between two real column labels: {@code "Date Transaction details from
     * 16-Feb-2026 to 15-Mar-2026 Spends Area Amount (Rs.)R"}. Left in place, those four runs
     * become FOUR phantom columns -- {@code "Transaction details from"}, {@code "16-Feb-2026"},
     * {@code "to"}, {@code "15-Mar-2026"} -- and every real row's narration or date partly
     * buckets into one of them instead of its real column. This is not the density fix above
     * making the row scoreable; it is what has to happen next so the row's OWN content is
     * correct once it is scored.
     *
     * <p>Narrow on purpose: matches only literal {@code "from"} immediately followed by a
     * parseable date, then literal {@code "to"} immediately followed by a parseable date, in the
     * row's own run order (not by x, since this runs before any reordering). A genuine "From"/
     * "To" pair of COLUMN NAMES is vanishingly unlikely to sit adjacent to two date VALUES in
     * exactly this shape, and this never touches a data row -- only a row already headed for the
     * accepted-header branch.
     */
    private List<PositionedText> stripEmbeddedDateRange(List<PositionedText> row) {
        for (int i = 0; i + 3 < row.size(); i++) {
            if (isWord(row.get(i), "from") && CsvParser.parseDate(row.get(i + 1).text().trim()) != null
                    && isWord(row.get(i + 2), "to") && CsvParser.parseDate(row.get(i + 3).text().trim()) != null) {
                List<PositionedText> stripped = new ArrayList<>(row);
                stripped.subList(i, i + 4).clear();
                return stripped;
            }
        }
        return row;
    }

    private boolean isWord(PositionedText t, String word) {
        return t.text().trim().equalsIgnoreCase(word);
    }

    private List<PositionedText> coalesceHeaderRuns(List<PositionedText> row) {
        List<PositionedText> cells = new ArrayList<>();
        for (PositionedText run : row) {
            PositionedText previous = cells.isEmpty() ? null : cells.get(cells.size() - 1);
            if (previous != null && joinsOntoHeaderCell(previous, run)) {
                // Left run's x stays the anchor and the right run's endX becomes the end -- the same
                // convention asOneCell uses for a vertically merged cell.
                cells.set(cells.size() - 1, new PositionedText(
                        previous.text().trim() + " " + run.text().trim(),
                        previous.x(), previous.y(), previous.pageIndex(),
                        run.endX() - previous.x()));
                continue;
            }
            cells.add(run);
        }
        return cells;
    }

    /** Whether {@code right} is the continuation of the same header cell {@code left} begins. */
    private boolean joinsOntoHeaderCell(PositionedText left, PositionedText right) {
        if (left.pageIndex() != right.pageIndex()) return false;
        if (left.width() <= 0 || right.width() <= 0) return false;
        float gap = right.x() - left.endX();
        // Negative means the runs overlap, which is not the one-space adjacency this looks for.
        if (gap < 0 || gap > HEADER_RUN_JOIN_MAX_GAP) return false;
        return !carriesAValue(left) && !carriesAValue(right);
    }

    private boolean carriesAValue(PositionedText run) {
        String text = run.text().trim();
        return CsvParser.parseDate(text) != null || CsvParser.parseNumeric(text) != null;
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

    // P-002 Fix 2 (root cause of section over-segmentation). MAX_HEADER_ROW_CELLS and the density
    // check above were meant to keep prose paragraphs out of looksLikeHeaderRow, but both fail
    // open on real MITC/fee-schedule/T&C text: matchesAnyHint tokenizes a WHOLE CELL into words and
    // matches any one of them against HEADER_HINTS, so a paragraph containing the ordinary English
    // words "date" and "amount" scores hasDate=true, matches>=2 -- and the density guard, computed
    // in PDFBox RUNS rather than words, measures a 600-character paragraph that PDFBox happened to
    // emit as two or three long runs as maximally "dense" (matches*3 >= row.size() is trivial when
    // row.size() is 2 or 3). Measured against the 20-trace corpus: every genuine header cell across
    // every genuine table in the corpus is <= 7 words; every spurious prose header cell is >= 19
    // words. 12 sits in the middle of that gap with no corpus member inside it, so it is not a
    // tuned/fragile fit to any one document.
    //
    // MUST be measured per CELL of coalesceHeaderRuns(row) output, not on the raw pre-coalesce runs
    // and not as a total word count across the row. PDFBox splits a genuine multi-word header cell
    // ("Withdrawal Amt.") into several short runs, so an uncoalesced word count is a different,
    // meaningless quantity -- coalesceHeaderRuns (P-001, commit 2bcb21e) is what turns those runs
    // back into the real column names, and a genuine 7-column HDFC header coalesces to cells of
    // <= 3 words each. Summing words across the whole row would also be wrong: a genuine 7-column
    // header can carry ~15-20 words in total while every individual cell stays a short column name.
    private static final int MAX_HEADER_CELL_WORDS = 12;

    /** Whether any cell of {@code row}, after {@link #coalesceHeaderRuns}, is long enough to be
     *  prose rather than a column name -- see {@link #MAX_HEADER_CELL_WORDS}. */
    private boolean hasProseLengthCell(List<PositionedText> row) {
        for (PositionedText cell : coalesceHeaderRuns(row)) {
            String text = cell.text().trim();
            if (text.isEmpty()) continue;
            if (text.split("\\s+").length > MAX_HEADER_CELL_WORDS) return true;
        }
        return false;
    }

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
        // P-002 Fix 2: a coalesced cell longer than MAX_HEADER_CELL_WORDS is a paragraph, not a
        // column name -- see that constant's own comment. Checked before the hint/density scoring
        // below so a prose paragraph that happens to contain "date" and "amount" never reaches it.
        if (hasProseLengthCell(row)) return false;
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
        //
        // The denominator excludes cells that carry a VALUE (parse as a date or number), not just
        // recognized names -- verified on a real Kotak credit-card statement whose header embeds
        // its own dynamic statement period inline: "Date Transaction details from 16-Feb-2026 to
        // 15-Mar-2026 Spends Area Amount (Rs.)R". PDFBox splits "Transaction"/"details" apart, so
        // this scores matches=3 ("date", "details", "amount" each individually recognized) against
        // 11 raw cells -- past the >=2 floor, but 3*3=9 < 11 fails density by exactly the two date
        // VALUES the range contributes. A date value sitting in a header row is not prose the way
        // "the" or "balance" would be if this were a paragraph -- it is not a column name and it is
        // not ordinary text either, so it should not count against the row's "mostly column names"
        // measure any more than the column names themselves do. Only affects the denominator:
        // matchesAnyHint already never matches a bare value (HEADER_HINTS names columns, not dates
        // or amounts), so no value cell was ever contributing to `matches` either way.
        int valueCells = (int) row.stream().filter(this::carriesAValue).count();
        boolean denseEnoughToBeAHeader = matches * 3 >= (row.size() - valueCells);
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
            // Same shape again, this time for a Balance/Amount column that already holds a clean
            // number receiving genuine NARRATION text afterward. Verified against a real PNB ONE
            // statement: the Remarks column is wide, left-aligned text whose actual data doesn't
            // start at a fixed x -- a UPI reference string's digit count varies row to row, so the
            // narration's left edge sometimes falls on the Balance side of the Balance/Remarks
            // midpoint purely because that particular reference happened to be short. nearestColumn
            // then buckets the WHOLE narration into Balance, joined onto the real value with a
            // space (a real balance figure, then "UPI/DR/<reference>/<bank>/<upi handle>" glued
            // straight onto it) -- a string that fails parseNumeric outright, so the row's running
            // balance is lost entirely rather than merely wrong. ~38% of rows on that statement
            // lost their balance this way. Excludes a trailing Dr/Cr marker ("Dr", "(Cr)")
            // deliberately: that is a real, common continuation of the SAME balance value printed
            // as a separate run, not narration overshoot, and must stay attached rather than being
            // redirected away.
            if (existing != null && isAmountColumn(columnName) && CsvParser.parseNumeric(existing.trim()) != null
                    && CsvParser.parseNumeric(t.text().trim()) == null && CsvParser.parseDate(t.text().trim()) == null
                    && !CsvParser.hasTrailingDrCrMarker(t.text().trim())) {
                int laterTextColumn = nextNonNumericColumn(headerNames, nearest);
                if (laterTextColumn >= 0) {
                    nearest = laterTextColumn;
                    columnName = headerNames.get(nearest);
                    existing = result.get(columnName);
                    if (ctx != null) ctx.record("OFFSET_COLUMN_ANCHORS");
                }
            }
            // Same shape as the date redirect above, for the opposite end of the row: an amount
            // (a plain number, optionally Dr/Cr-suffixed) that would otherwise be appended onto an
            // already-non-blank description or merchant-category cell almost certainly overshot
            // its own, later, amount-shaped column instead -- e.g. a short amount like "500.00 Dr"
            // sitting nearer to a short merchant-category word like "MEDICAL" than to the amount
            // column's own header anchor. Redirects forward to the nearest LATER amount-shaped
            // column, never backward, and never into an otherwise-empty cell (a genuinely blank
            // merchant-category column with just a number in it is left alone).
            //
            // Excludes a reference/cheque-number column, unlike "MEDICAL" above. Verified against a
            // real HDFC statement: an unusually long Narration ("...CONNECT AND HEAL") pushed its
            // last word past the Narration/Chq.Ref.No. midpoint (nearestColumn, by left edge), so
            // Chq./Ref.No. was already non-blank by the time this rule saw the row's ACTUAL
            // Chq./Ref.No. value -- itself a plain digit run, a bank reference/UTR number, not an
            // amount. This rule then read "non-blank, non-amount column, incoming run parses as a
            // number" and forwarded that reference number into Withdrawal Amt., turning a ₹454
            // deposit into what looked like a >₹500,000,000 withdrawal. The distinction this rule
            // cannot make on its own: a merchant-category cell like "MEDICAL" never legitimately
            // holds a number, but a reference/cheque-number cell always does -- so a stray number
            // landing there is far more likely to belong there than to have overshot from
            // elsewhere. Deliberately checked on the CURRENT columnName only (the cell this run is
            // about to be redirected AWAY from), not on the destination -- this is about trusting
            // what the reference column already holds, not about which column looks correct to
            // receive it.
            // Also requires a decimal point in the run's own text. Verified against a real Kotak
            // credit-card statement: several merchant lines print a bare 3-digit card-ending
            // suffix right after the merchant name ("AMAZON 356", the card's last 3 digits, no
            // relation to the transaction amount) with no decimal point at all -- unlike every
            // real amount on the same statement, which is always printed with two decimal places.
            // Without this, "356" reads as "non-blank, non-amount column, incoming run parses as
            // a number" exactly like a genuinely overshot "500.00" would, and gets forwarded onto
            // the real amount, concatenating into "356304.00" for what is actually a ₹304.00
            // purchase. A bare integer is far more likely to be an identifier -- a suffix, a
            // count, a reference fragment -- than a standalone currency amount in a column that
            // isn't itself amount-shaped; a decimal amount overshooting its own column is the
            // documented motivating case and still has one.
            if (existing != null && !isAmountColumn(columnName) && !isReferenceColumn(columnName)
                    && t.text().contains(".") && CsvParser.parseNumeric(t.text().trim()) != null) {
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

    // Word-boundary regex, not matchesAnyHint's per-word exact match and not a plain substring
    // check: a real header cell like "Chq./Ref.No." is one punctuation-joined token with no
    // whitespace, so matchesAnyHint's word-splitting (which only strips LEADING/TRAILING
    // punctuation per word, see its own doc comment) would tokenize it to a single word
    // "chq./ref.no" that equals neither "chq" nor "ref" outright -- a plain substring check was
    // tried first and is wrong the other way: "ref" as a bare substring also matches inside
    // "Refund" and "Preference", neither of which is a reference-number column. \b sees the same
    // transition (word character <-> non-word character) on punctuation as it does on whitespace,
    // so it isolates "ref" as its own token in "chq./ref.no." (bounded by "/" and ".") while
    // correctly refusing to match it inside "refund" (no boundary between "ref" and the "u" that
    // continues the same word).
    private static final Pattern REFERENCE_COLUMN_PATTERN =
            Pattern.compile("\\b(ref|cheque|chq|utr|instrument no)\\b");

    /** True for a reference/cheque-number column -- see the OFFSET_COLUMN_ANCHORS guard in
     *  {@link #bucketRow} that this exists for: unlike a merchant-category or description column,
     *  this kind of column legitimately holds nothing but digits. */
    private boolean isReferenceColumn(String columnName) {
        return REFERENCE_COLUMN_PATTERN.matcher(CsvParser.normalizeHeaderCell(columnName)).find();
    }

    private int nextAmountColumn(List<String> headerNames, int afterIndex) {
        for (int i = afterIndex + 1; i < headerNames.size(); i++) {
            if (isAmountColumn(headerNames.get(i))) return i;
        }
        return -1;
    }

    /** Mirror of {@link #nextAmountColumn} for the opposite redirect: the nearest LATER column
     *  that is neither amount- nor date-shaped, for narration text that overshot backward into a
     *  numeric column. */
    private int nextNonNumericColumn(List<String> headerNames, int afterIndex) {
        for (int i = afterIndex + 1; i < headerNames.size(); i++) {
            if (!isAmountColumn(headerNames.get(i)) && !isDateColumn(headerNames.get(i))) return i;
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

    // ===== INFERRED_HEADERLESS_LAYOUT =====
    //
    // Every capability above this point assumes looksLikeHeaderRow found SOMETHING -- they refine
    // where a header is, what it is folded with, or how a row is bucketed once one already exists.
    // This one exists for a real SBI savings statement where none of that ever gets a chance to
    // run: the column vocabulary (Date/Narration/Debit/Credit/Balance) never appears as text
    // anywhere in the document, so looksLikeHeaderRow never scores true, currentRows stays null for
    // the whole of locateAll's main loop, and every line -- transaction data included -- falls into
    // pendingAuxiliary as unstructured text. The document returns zero sections despite its
    // transaction table being geometrically as regular as any header-based one: a stable 7-anchor
    // column structure (Date, Value Date, Narration, a reference-ish column, Debit, Credit,
    // Balance), confirmed directly against the real file's PositionedText geometry.
    //
    // The approach: infer column ROLES from what the data itself looks like, rather than from
    // vocabulary that was never printed. A physical row is trusted as transaction-shaped only if it
    // carries both a date and a decimal amount (isTransactionShapedRow); columns are found by
    // clustering those rows' cell positions (clusterIntoColumns); each cluster's role -- Date,
    // Description, or a numeric candidate -- is decided from what its own values look like across
    // every transaction-shaped row, not from a label. The one genuinely ambiguous decision --
    // which numeric column is Debit and which is Credit -- is resolved by trying the small, bounded
    // set of plausible assignments and keeping whichever one's running-balance arithmetic actually
    // holds up (resolveDebitCreditByBalanceChain), the same "verify against real data before
    // committing" discipline every other fix in this file's history already follows.
    //
    // Deliberately conservative at every stage: each step returns null/bails to today's
    // zero-section outcome rather than emit a labeling nothing here can stand behind. This is not
    // the real financial verification -- BalanceChainValidator, downstream via ImportVerifier,
    // still runs unchanged on whatever this produces, exactly as it does for any other document
    // (PdfPreviewGenerator only ever asks "is doc.sections() non-empty", never how it got that
    // way) -- it is a selection heuristic for choosing between a handful of candidate column
    // labelings, not a replacement for verification.
    //
    // Named "headerless LAYOUT" rather than after SBI specifically: nothing below keys on this
    // bank, this account, or any vocabulary unique to this document -- it is architected to fire on
    // any statement with this same shape (a geometrically regular, date-anchored transaction table
    // printed with no column headings at all), not hardcoded to the one real document that
    // motivated it.

    // Unmeasured against a corpus of real headerless statements -- there is only one in hand, and
    // real financial documents are never committed (Synthetic Fixture Policy). Tight enough to keep
    // two real columns separate (the closest real gap measured on the motivating document is ~49pt,
    // a blank column's right edge to the Debit column's anchor) and loose enough to absorb ordinary
    // rendering jitter at one fixed edge. Revisit once a second real headerless statement is seen.
    private static final float HEADERLESS_COLUMN_CLUSTER_TOLERANCE = 15.0f;
    // Mirrors BalanceChainValidator.MIN_PAIRS_FOR_A_VERDICT's spirit (a score from too few rows is
    // a coin flip, not evidence), one row higher: this also has to survive its own row-
    // classification heuristic being imperfect, not just ordinary small-sample noise.
    private static final int HEADERLESS_MIN_TRANSACTION_ROWS = 3;
    // Bounds resolveDebitCreditByBalanceChain's search to at most 4x3=12 trials -- small and
    // bounded by construction, so it can never become the combinatorial search it deliberately
    // isn't. A document whose numeric-candidate pool is larger than this bails out rather than
    // guesses; that shape hasn't been seen on a real statement yet.
    private static final int HEADERLESS_MAX_NUMERIC_CANDIDATES = 4;
    private static final float HEADERLESS_DATE_FRACTION_THRESHOLD = 0.8f;
    private static final float HEADERLESS_MIN_COLUMN_PRESENCE = 0.2f;
    private static final float HEADERLESS_NUMERIC_PURITY_THRESHOLD = 0.8f;
    // Balance is the one numeric column that must be populated on essentially every transaction
    // row (there is always a resulting balance); Debit and Credit are each populated on a subset.
    // This is the signal that tells them apart -- deliberately NOT applied as a presence gate to
    // the Debit/Credit candidate pool itself: a statement with five debits and one credit (the
    // motivating document has exactly this shape) would fail any presence bar high enough to be
    // meaningful for Balance, so numeric-candidate admission below is gated on purity alone, which
    // a genuinely unused column already fails (zero non-blank cells scores zero purity by
    // definition -- see ColumnStats.numericPurity()).
    private static final float HEADERLESS_BALANCE_COLUMN_MIN_PRESENCE = 0.9f;
    // Mirrors BalanceChainValidator.FAILED_THRESHOLD (private, a different architectural layer --
    // cited here by value, not by reference) and the same reasoning: half is what distinguishes "a
    // whole column is mislabeled" from "a few rows are quirky", and a labeling that cannot clear
    // even that bar is not worth guessing.
    private static final double HEADERLESS_CHAIN_ACCEPT_THRESHOLD = 0.5;

    /** True when text is empty or is nothing but a placeholder dash -- the literal character a real
     *  SBI statement prints in an amount column that does not apply to a given row. Treated as "no
     *  value" everywhere in this capability, the same way {@link CsvParser#parseNumeric} already
     *  treats it (a bare dash is not a parseable number): counting it as "present but non-numeric"
     *  would understate every amount column's purity for no reason, since the column is not
     *  actually holding anything on that row. */
    private boolean isBlankCell(String text) {
        String t = text.trim();
        return t.isEmpty() || t.matches("-+");
    }

    /** True for a physical row this document prints one transaction on. Requires a date-parseable
     *  cell AND a decimal-amount cell on the SAME row -- verified directly against the real
     *  document's account-summary block (every field there prints as its own line; date-bearing
     *  lines and amount-bearing lines never coincide) that this combination cleanly isolates the
     *  transaction table from the metadata around it, with no bank-specific vocabulary at all.
     *
     *  <p>The amount half requires a decimal point, not just {@link CsvParser#parseNumeric}
     *  returning non-null, for the same reason {@link #bucketRow}'s own OFFSET_COLUMN_ANCHORS
     *  redirect already requires it: a bare digit run -- an account number, a CIF number, a MICR
     *  code, all of which sit in the same metadata block as real dates -- parses as a valid
     *  BigDecimal but is not a currency amount. Without this guard, a metadata line naming both an
     *  account-opening date and an account number would misclassify as a transaction row. */
    private boolean isTransactionShapedRow(List<PositionedText> row) {
        boolean hasDate = false;
        boolean hasAmount = false;
        for (PositionedText cell : row) {
            String text = cell.text().trim();
            if (!hasDate && CsvParser.parseDate(text) != null) hasDate = true;
            if (!hasAmount && text.contains(".") && CsvParser.parseNumeric(text) != null) hasAmount = true;
        }
        return hasDate && hasAmount;
    }

    /** Drops the second of any two ADJACENT transaction-shaped rows whose full cell text is
     *  identical. Exists for a real artifact on the motivating document: it reprints its last
     *  transaction row again at the top of the following page, right before the statement-summary
     *  block -- same date, narration, amounts, and balance. Left in, that duplicate implies a
     *  zero-delta transaction that fits no real debit or credit, which would corrupt
     *  {@link #resolveDebitCreditByBalanceChain}'s scoring into rejecting an otherwise-correct
     *  column labeling. Scoped to ADJACENT rows deliberately: two coincidentally-identical but
     *  genuinely distinct transactions would still have moved the balance between them, so their
     *  balance cell -- part of the full-line equality check -- would differ, and this never fires
     *  on them. */
    private List<List<PositionedText>> dedupeAdjacentIdenticalRows(List<List<PositionedText>> rows) {
        List<List<PositionedText>> result = new ArrayList<>();
        String previousLine = null;
        for (List<PositionedText> row : rows) {
            String line = lineOf(row);
            if (!line.equals(previousLine)) result.add(row);
            previousLine = line;
        }
        return result;
    }

    /** The position clusterIntoColumns groups a cell by: a numeric cell's RIGHT edge, everything
     *  else's LEFT edge. The same left/right split {@link #bucketRow}'s own RIGHT_ALIGNED_AMOUNTS
     *  handling already makes necessary elsewhere in this file -- a right-aligned amount's left
     *  edge shifts with the value's digit count while its right edge stays fixed -- applied here
     *  one step earlier, at column-discovery time instead of at bucketing time. Clustering by raw
     *  left edge alone risks splitting one logical amount column into two, or merging a short value
     *  into its neighbour, exactly as RIGHT_ALIGNED_AMOUNTS's own doc comment documents for a real
     *  HDFC statement. */
    private float clusterKey(PositionedText cell) {
        return CsvParser.parseNumeric(cell.text().trim()) != null ? cell.endX() : cell.x();
    }

    /** One column this capability discovered from data alone: its representative left edge (the
     *  minimum x seen among its cells -- used as this column's headerAnchors entry, matching how
     *  {@link #bucketRow}'s primary placement always compares by left edge) and representative
     *  right edge (the maximum endX seen among its NUMERIC cells, or the same as the left edge if
     *  it has none -- used as this column's headerEnds entry, feeding the RIGHT_ALIGNED_AMOUNTS
     *  override exactly as a real header's own endX would), plus the content-shape counts every
     *  role decision below is made from.
     *
     *  <p>{@code amountLikeCount} is deliberately narrower than "parses as a number": it requires a
     *  decimal point too, the same guard {@link #isTransactionShapedRow} and {@link #bucketRow}'s
     *  own OFFSET_COLUMN_ANCHORS redirect already apply, for the same reason -- a bare digit run (a
     *  cheque number, a reference number) parses as a valid BigDecimal but is not a currency amount.
     *  {@link #numericPurity()} is built from this, not from every numeric parse, so a
     *  reference-number column with real (but non-decimal) values can never look enough like an
     *  amount column to enter the Debit/Credit candidate pool. */
    private record ColumnStats(float repLeft, float repRight, int nonBlankCount, int dateCount,
                                int amountLikeCount, long wordSum) {
        float presence(int totalRows) {
            return totalRows == 0 ? 0f : (float) nonBlankCount / totalRows;
        }

        float dateFraction() {
            return nonBlankCount == 0 ? 0f : (float) dateCount / nonBlankCount;
        }

        float numericPurity() {
            return nonBlankCount == 0 ? 0f : (float) amountLikeCount / nonBlankCount;
        }

        float avgWordCount() {
            return nonBlankCount == 0 ? 0f : (float) wordSum / nonBlankCount;
        }
    }

    /** Clusters every non-blank cell across {@code transactionRows} by {@link #clusterKey} into
     *  columns, returned in ascending clusterKey order. Each returned {@link ColumnStats} is built
     *  entirely from the cells that landed in its own cluster -- there is no separate re-matching
     *  step, so a cell can never be counted against a different column here than the one that
     *  defined its own cluster. */
    private List<ColumnStats> clusterIntoColumns(List<List<PositionedText>> transactionRows) {
        List<PositionedText> informative = new ArrayList<>();
        for (List<PositionedText> row : transactionRows) {
            for (PositionedText cell : row) {
                if (!isBlankCell(cell.text())) informative.add(cell);
            }
        }
        informative.sort(Comparator.comparing(this::clusterKey));

        List<List<PositionedText>> groups = new ArrayList<>();
        List<PositionedText> current = new ArrayList<>();
        Float lastKey = null;
        for (PositionedText cell : informative) {
            float key = clusterKey(cell);
            if (lastKey != null && key - lastKey > HEADERLESS_COLUMN_CLUSTER_TOLERANCE) {
                groups.add(current);
                current = new ArrayList<>();
            }
            current.add(cell);
            lastKey = key;
        }
        if (!current.isEmpty()) groups.add(current);

        List<ColumnStats> stats = new ArrayList<>();
        for (List<PositionedText> group : groups) {
            float repLeft = Float.MAX_VALUE;
            float repRight = -Float.MAX_VALUE;
            boolean anyNumeric = false;
            int dateCount = 0;
            int amountLikeCount = 0;
            long wordSum = 0;
            for (PositionedText cell : group) {
                String text = cell.text().trim();
                repLeft = Math.min(repLeft, cell.x());
                if (CsvParser.parseNumeric(text) != null) {
                    anyNumeric = true;
                    repRight = Math.max(repRight, cell.endX());
                    if (text.contains(".")) amountLikeCount++;
                }
                if (CsvParser.parseDate(text) != null) dateCount++;
                wordSum += text.split("\\s+").length;
            }
            if (!anyNumeric) repRight = repLeft;
            stats.add(new ColumnStats(repLeft, repRight, group.size(), dateCount, amountLikeCount, wordSum));
        }
        return stats;
    }

    /** The candidate labeling {@link #resolveDebitCreditByBalanceChain} settled on: which numeric
     *  column (by index into the {@code List<ColumnStats>} it was chosen from) is Debit, which is
     *  Credit, and the chain-consistency score that made it the winner. */
    private record DebitCreditAssignment(int debitIndex, int creditIndex, double score) {}

    /** The value {@code row} holds for {@code column}, or null if nothing in the row lands near it.
     *  Matches a cell by the SMALLER of its left- or right-edge distance to the column's own
     *  representative edges -- unlike {@link #clusterKey}, which commits to one edge per cell type
     *  at discovery time, this only needs to find "the one real value in this row for this column"
     *  for scoring purposes, not to define the column itself.
     *
     *  <p>Bug fix: this used to accept whichever non-blank cell was CLOSEST with no cutoff -- on a
     *  row where this column is genuinely blank (a debit row's Credit cell, printed as a dash and
     *  therefore already excluded by {@link #isBlankCell}), the globally-nearest surviving cell was
     *  often the OTHER amount column's real value, tens of points away but still nearer than
     *  anything else on the row. That silently copied one row's debit into its own credit slot (and
     *  the reverse), making every {@link #scoreChain} trial fail identically regardless of which
     *  candidate pairing was actually correct -- measured directly against the real motivating
     *  document, where every permutation scored exactly 0.0. Bounded to the column's own measured
     *  jitter (its repRight-repLeft span) plus {@link #HEADERLESS_COLUMN_CLUSTER_TOLERANCE}: a
     *  genuine same-column value's right edge sits within a few points of repRight regardless of
     *  digit count, comfortably inside that bound, while the real document's own Debit/Credit gap
     *  (measured ~78pt between their nearest real values) sits well outside it. */
    private BigDecimal nearestCellValue(List<PositionedText> row, ColumnStats column) {
        float maxAcceptableDistance = HEADERLESS_COLUMN_CLUSTER_TOLERANCE + (column.repRight() - column.repLeft());
        PositionedText best = null;
        float bestDistance = Float.MAX_VALUE;
        for (PositionedText cell : row) {
            String text = cell.text().trim();
            if (isBlankCell(text)) continue;
            float distance = Math.min(Math.abs(cell.x() - column.repLeft()), Math.abs(cell.endX() - column.repRight()));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = cell;
            }
        }
        if (best == null || bestDistance > maxAcceptableDistance) return null;
        return CsvParser.parseNumeric(best.text().trim());
    }

    /** Fraction of chain-consecutive {@code transactionRows} where {@code balance[i] ==
     *  balance[i-1] - debit[i] + credit[i]} (BigDecimal-exact, one-paisa tolerance for rounding),
     *  trying {@code debitIdx}/{@code creditIdx} as the candidate labeling. A row with no value in
     *  the debit or credit column counts as zero for that side -- the same treatment a real
     *  Debit/Credit-shaped statement already gets downstream (a blank cell is not a missing
     *  transaction, it is the side that did not move). Pairs where either balance is missing are
     *  skipped rather than counted as a miss, since this is scoring a LABELING, not flagging a
     *  discrepancy the way BalanceChainValidator's real report does. */
    private double scoreChain(List<List<PositionedText>> transactionRows, ColumnStats balanceColumn,
                               ColumnStats debitColumn, ColumnStats creditColumn) {
        List<BigDecimal> balances = new ArrayList<>();
        List<BigDecimal> debits = new ArrayList<>();
        List<BigDecimal> credits = new ArrayList<>();
        for (List<PositionedText> row : transactionRows) {
            balances.add(nearestCellValue(row, balanceColumn));
            debits.add(nearestCellValue(row, debitColumn));
            credits.add(nearestCellValue(row, creditColumn));
        }
        int checked = 0;
        int matched = 0;
        for (int i = 1; i < balances.size(); i++) {
            BigDecimal previousBalance = balances.get(i - 1);
            BigDecimal thisBalance = balances.get(i);
            if (previousBalance == null || thisBalance == null) continue;
            BigDecimal debit = debits.get(i) == null ? BigDecimal.ZERO : debits.get(i);
            BigDecimal credit = credits.get(i) == null ? BigDecimal.ZERO : credits.get(i);
            BigDecimal expected = previousBalance.subtract(debit).add(credit);
            checked++;
            if (expected.subtract(thisBalance).abs().compareTo(new BigDecimal("0.01")) <= 0) matched++;
        }
        return checked == 0 ? 0.0 : (double) matched / checked;
    }

    /** Tries every plausible (candidate -> Debit)/(candidate -> Credit) assignment from
     *  {@code numericPool} and keeps whichever scores highest via {@link #scoreChain}, or null if
     *  the best score doesn't clear {@link #HEADERLESS_CHAIN_ACCEPT_THRESHOLD}. A pool of size N
     *  tries every ordered pair (at most 4x3=12 trials at {@link #HEADERLESS_MAX_NUMERIC_CANDIDATES}) --
     *  small and bounded by construction, never the combinatorial search this deliberately isn't.
     *
     *  <p>Deliberately NOT built on {@code BalanceChainValidator}/{@code StagedRow}: this chooses
     *  between candidate column labelings over raw bucketed rows, not the verification
     *  BalanceChainValidator performs on already-normalized, committed rows -- building a throwaway
     *  StagedRow for every one of up to 12 trials just to discard 11 is the wrong shape for what is
     *  a selection heuristic, not a second verification layer. The real verification is unaffected
     *  and still runs, unchanged, once a labeling is chosen -- see this capability's own top-level
     *  doc comment. */
    private DebitCreditAssignment resolveDebitCreditByBalanceChain(List<List<PositionedText>> transactionRows,
            List<ColumnStats> columns, int balanceColumn, List<Integer> numericPool) {
        DebitCreditAssignment best = null;
        for (int debitIdx : numericPool) {
            for (int creditIdx : numericPool) {
                if (debitIdx == creditIdx) continue;
                double score = scoreChain(transactionRows, columns.get(balanceColumn), columns.get(debitIdx), columns.get(creditIdx));
                if (best == null || score > best.score()) {
                    best = new DebitCreditAssignment(debitIdx, creditIdx, score);
                }
            }
        }
        if (best == null || best.score() < HEADERLESS_CHAIN_ACCEPT_THRESHOLD) return null;
        return best;
    }

    /** Rows and non-transaction text collected in the same pass -- see {@link #inferHeaderlessSection}
     *  for why the latter matters as much as the former (it feeds product/identity classification
     *  downstream, exactly as the header-based path's own pendingAuxiliary does). */
    private record HeaderlessBucketResult(List<Map<String, String>> rows, List<String> auxiliaryText) {}

    /** Buckets every row of {@code allRows} (not just the transaction-shaped subset used for role
     *  inference and scoring) against the inferred header, merging each non-transaction-shaped row
     *  into the preceding transaction row's Description via the existing {@link #mergeInto} --
     *  reused rather than reimplemented, since it already carries the hardening "never corrupt an
     *  already-valid date or amount" needs (see mergeInto's own doc comment). This is what recovers
     *  a narration that wraps across several following physical lines with no date or amount of its
     *  own, exactly as the header-based path's own WRAPPED_DESCRIPTION handling does.
     *
     *  <p>Skips {@link #PAGE_FOOTER} lines and stops entirely at the first
     *  {@link #STATEMENT_CLOSING_MARKER} match, mirroring the header-based path. Known, bounded
     *  limitation: a statement whose closing summary (totals, counts) is not marked by either
     *  pattern -- the motivating document's own "Statement Summary" block is not -- gets folded
     *  into the last transaction's Description instead of being dropped. Capped at
     *  {@link #MAX_BLOCK_CONTINUATION_ROWS} consecutive merges per anchor, so this can never
     *  corrupt more than a bounded amount of trailing text, and it never touches a date, amount, or
     *  balance cell regardless -- mergeInto's own protection covers that.
     *
     *  <p>Also drops a transaction-shaped row whose full text exactly repeats the immediately
     *  preceding transaction-shaped row -- the same page-boundary reprint {@link
     *  #dedupeAdjacentIdenticalRows} exists for, applied again here so the duplicate is absent from
     *  the final staged rows too, not just from the candidates {@link #resolveDebitCreditByBalanceChain}
     *  scored. Compares only against the last TRANSACTION-shaped row, so intervening continuation
     *  lines between the original and its reprint don't defeat the comparison. */
    private HeaderlessBucketResult bucketHeaderlessRowsWithContinuation(List<List<PositionedText>> allRows,
            List<String> headerNames, List<Float> headerAnchors, List<Float> headerEnds, DocumentContext ctx) {
        List<Map<String, String>> result = new ArrayList<>();
        List<String> auxiliaryText = new ArrayList<>();
        Map<String, String> currentAnchor = null;
        int continuationCount = 0;
        String previousTransactionLine = null;
        for (List<PositionedText> row : allRows) {
            String rowLine = lineOf(row);
            if (PAGE_FOOTER.matcher(rowLine).find()) continue;
            if (STATEMENT_CLOSING_MARKER.matcher(rowLine).find()) break;
            if (isTransactionShapedRow(row)) {
                if (rowLine.equals(previousTransactionLine)) continue;
                Map<String, String> bucketed = bucketRow(row, headerNames, headerAnchors, headerEnds, ctx);
                if (bucketed.isEmpty()) continue;
                result.add(bucketed);
                currentAnchor = bucketed;
                continuationCount = 0;
                previousTransactionLine = rowLine;
            } else if (currentAnchor != null && continuationCount < MAX_BLOCK_CONTINUATION_ROWS) {
                Map<String, String> bucketed = bucketRow(row, headerNames, headerAnchors, headerEnds, ctx);
                if (bucketed.isEmpty()) continue;
                mergeInto(currentAnchor, bucketed, headerNames);
                continuationCount++;
            } else if (!rowLine.isBlank()) {
                // Pre-first-transaction page furniture (the exact case this capability was blind to:
                // a credit-card payment-summary block above the ledger) and post-cap continuation
                // overflow both land here, never in a transaction row.
                auxiliaryText.add(rowLine);
            }
        }
        return new HeaderlessBucketResult(result, auxiliaryText);
    }

    /** Entry point for the whole INFERRED_HEADERLESS_LAYOUT capability -- see its top-level doc
     *  comment above {@link #HEADERLESS_COLUMN_CLUSTER_TOLERANCE}. Returns null, never partially,
     *  when the document doesn't fit this shape well enough to trust; the caller's contract on null
     *  is "leave sections exactly as they were" -- today's zero-section outcome. */
    private LocatedSection inferHeaderlessSection(List<List<PositionedText>> rows, DocumentContext ctx) {
        List<List<PositionedText>> candidates = new ArrayList<>();
        for (List<PositionedText> row : rows) {
            if (isTransactionShapedRow(row)) candidates.add(row);
        }
        candidates = dedupeAdjacentIdenticalRows(candidates);
        if (candidates.size() < HEADERLESS_MIN_TRANSACTION_ROWS) {
            if (ctx != null) ctx.recordDiagnostic("HEADERLESS_TOO_FEW_TRANSACTION_ROWS");
            return null;
        }

        List<ColumnStats> columns = clusterIntoColumns(candidates);
        int totalRows = candidates.size();

        // Date / Value Date: the leftmost 1-2 columns whose non-blank cells are mostly
        // date-parseable. isTransactionShapedRow already required every candidate row to carry a
        // date somewhere, so the true date column's presence should be near-universal by
        // construction -- the presence gate here is a sanity floor, not the deciding signal.
        List<Integer> dateColumns = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            ColumnStats c = columns.get(i);
            if (c.dateFraction() >= HEADERLESS_DATE_FRACTION_THRESHOLD && c.presence(totalRows) >= HEADERLESS_MIN_COLUMN_PRESENCE) {
                dateColumns.add(i);
            }
        }
        if (dateColumns.isEmpty()) {
            if (ctx != null) ctx.recordDiagnostic("HEADERLESS_NO_DATE_COLUMN");
            return null;
        }
        Set<Integer> claimed = new LinkedHashSet<>();
        int dateColumn = dateColumns.get(0);
        claimed.add(dateColumn);
        Integer valueDateColumn = dateColumns.size() > 1 ? dateColumns.get(1) : null;
        if (valueDateColumn != null) claimed.add(valueDateColumn);

        // Description: among unclaimed columns, the one whose non-blank cells average the most
        // words -- a narration column is prose, every other column is a short date or a number.
        int descriptionColumn = -1;
        float bestWordAverage = -1f;
        for (int i = 0; i < columns.size(); i++) {
            if (claimed.contains(i)) continue;
            ColumnStats c = columns.get(i);
            if (c.presence(totalRows) < HEADERLESS_MIN_COLUMN_PRESENCE) continue;
            if (c.avgWordCount() > bestWordAverage) {
                bestWordAverage = c.avgWordCount();
                descriptionColumn = i;
            }
        }
        if (descriptionColumn < 0) {
            if (ctx != null) ctx.recordDiagnostic("HEADERLESS_NO_DESCRIPTION_COLUMN");
            return null;
        }
        claimed.add(descriptionColumn);

        // Numeric candidates: among the still-unclaimed columns, keep only ones whose non-blank
        // cells are mostly numeric. No presence gate here deliberately -- see
        // HEADERLESS_BALANCE_COLUMN_MIN_PRESENCE's own doc comment for why a low-presence column
        // (a statement with far more debits than credits, or vice versa) must not be excluded here.
        // A column with no real values at all already scores zero purity by definition and is
        // excluded on that basis instead.
        List<Integer> numericCandidates = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if (claimed.contains(i)) continue;
            if (columns.get(i).numericPurity() >= HEADERLESS_NUMERIC_PURITY_THRESHOLD) numericCandidates.add(i);
        }
        if (numericCandidates.isEmpty()) {
            if (ctx != null) ctx.recordDiagnostic("HEADERLESS_NO_NUMERIC_CANDIDATES");
            return null;
        }

        // Balance: the numeric candidate with the highest presence, gated on being near-universal
        // -- the one signal that actually distinguishes it from Debit/Credit, each of which is
        // legitimately populated on only a subset of rows.
        int balanceColumn = numericCandidates.get(0);
        for (int i : numericCandidates) {
            if (columns.get(i).presence(totalRows) > columns.get(balanceColumn).presence(totalRows)) balanceColumn = i;
        }
        if (columns.get(balanceColumn).presence(totalRows) < HEADERLESS_BALANCE_COLUMN_MIN_PRESENCE) {
            if (ctx != null) ctx.recordDiagnostic("HEADERLESS_NO_BALANCE_COLUMN");
            return null;
        }
        numericCandidates.remove(Integer.valueOf(balanceColumn));
        if (numericCandidates.isEmpty() || numericCandidates.size() > HEADERLESS_MAX_NUMERIC_CANDIDATES) {
            if (ctx != null) ctx.recordDiagnostic("HEADERLESS_NUMERIC_POOL_UNUSABLE");
            return null;
        }

        DebitCreditAssignment assignment = resolveDebitCreditByBalanceChain(candidates, columns, balanceColumn, numericCandidates);
        if (assignment == null) {
            if (ctx != null) ctx.recordDiagnostic("HEADERLESS_CHAIN_SCORE_TOO_LOW");
            return null;
        }

        // Literal, recognized vocabulary only (TransactionNormalizer.recognizedColumnNames()) --
        // this capability assigns ROLES onto a fixed set of names, it never invents new ones, so
        // nothing downstream of PdfTableLocator needs to change to recognize an inferred column.
        Map<Integer, String> roleByIndex = new LinkedHashMap<>();
        roleByIndex.put(dateColumn, "Date");
        if (valueDateColumn != null) roleByIndex.put(valueDateColumn, "Value Date");
        roleByIndex.put(descriptionColumn, "Description");
        roleByIndex.put(balanceColumn, "Balance");
        roleByIndex.put(assignment.debitIndex(), "Debit");
        roleByIndex.put(assignment.creditIndex(), "Credit");

        // Sorted by left edge -- bucketRow's own OFFSET_COLUMN_ANCHORS redirects (nextAmountColumn,
        // nextNonNumericColumn) search FORWARD from an index assuming headerNames is already in
        // left-to-right order, the same invariant the header-based path documents and depends on.
        List<Integer> namedIndices = new ArrayList<>(roleByIndex.keySet());
        namedIndices.sort(Comparator.comparing(i -> columns.get(i).repLeft()));
        List<String> headerNames = new ArrayList<>();
        List<Float> headerAnchors = new ArrayList<>();
        List<Float> headerEnds = new ArrayList<>();
        for (int i : namedIndices) {
            headerNames.add(roleByIndex.get(i));
            headerAnchors.add(columns.get(i).repLeft());
            headerEnds.add(columns.get(i).repRight());
        }

        HeaderlessBucketResult bucketResult = bucketHeaderlessRowsWithContinuation(rows, headerNames, headerAnchors, headerEnds, ctx);
        if (bucketResult.rows().isEmpty()) {
            if (ctx != null) ctx.recordDiagnostic("HEADERLESS_FINAL_BUCKETING_EMPTY");
            return null;
        }
        if (ctx != null) ctx.record("INFERRED_HEADERLESS_LAYOUT");
        return new LocatedSection(bucketResult.auxiliaryText(), bucketResult.rows());
    }

    // ===== INFERRED_TWO_LINE_DATE_BLOCK =====
    //
    // A real AU Small Finance Bank credit-card statement's "Your Transactions" section isn't a
    // table at all -- each transaction is a small visual CARD printed across two physical lines:
    // day-of-month, merchant narration, and a currency-prefixed amount on the upper line; the
    // month+year and a bare "Cr"/"Dr" direction marker on the line below it. No column headings,
    // no shared left-edge alignment a table's columns would have -- INFERRED_HEADERLESS_LAYOUT's
    // own isTransactionShapedRow (a date AND an amount on the SAME row) never matches this shape,
    // since the date is split across two lines and the amount sits on a different line than the
    // direction marker that disambiguates it.
    //
    // Simpler than INFERRED_HEADERLESS_LAYOUT in one important way: there is no Debit-vs-Credit
    // ambiguity to resolve by trying candidate assignments against a balance chain, because each
    // block already carries its own explicit, unambiguous direction (the literal Cr/Dr token).
    // TransactionNormalizer already fully supports a single Amount column paired with a Type
    // column holding "Cr"/"Dr" (the same shape a real PNB statement uses -- see its own TYPE_HINTS
    // handling), so this stages {Date, Description, Amount, Type} rather than inventing a new
    // column shape or a hypothesis-and-validate search that has nothing left to choose between.
    //
    // No heading requirement (no hardcoded "Your Transactions" check): matching
    // INFERRED_HEADERLESS_LAYOUT's own precedent of relying on content shape, not bank-specific
    // vocabulary. The compound structural signal below -- a day-of-month cell paired with a
    // currency-prefixed amount, confirmed by a month/year token paired with a bare direction
    // marker within TWO_LINE_BLOCK_MAX_GAP, repeated at least TWO_LINE_BLOCK_MIN_TRANSACTIONS
    // times -- is already narrow enough that a heading would only guard against a threat this
    // pairing already rules out.

    // Between the ~16pt within-block line pitch and the ~32-35pt between-block gap measured on the
    // real document -- wide enough to tolerate ordinary rendering jitter, narrow enough that an
    // unrelated pair of lines two transactions apart can never satisfy it.
    private static final float TWO_LINE_BLOCK_MAX_GAP = 24.0f;
    // Mirrors HEADERLESS_MIN_TRANSACTION_ROWS's reasoning: a document with fewer than this many
    // paired blocks is a coin flip, not evidence -- bail to today's behaviour rather than guess.
    private static final int TWO_LINE_BLOCK_MIN_TRANSACTIONS = 3;
    // Allows an optional leading zero -- the real document's day cells are "07", "14", not "7"/"14".
    private static final Pattern DAY_OF_MONTH_CELL = Pattern.compile("^(0?[1-9]|[12]\\d|3[01])$");
    private static final Pattern BARE_CR_DR_CELL = Pattern.compile("(?i)^(cr|dr)$");
    // Not AU-specific: CsvParser.parseNumeric already treats Rs./INR the same as the Rupee sign
    // everywhere else in this pipeline, so this stays a general "Indian-rupee statement" signal.
    private static final Pattern CURRENCY_PREFIXED_AMOUNT = Pattern.compile(
            "(?i)^[+-]?\\s*(₹|rs\\.?|inr)\\s*[\\d,]+\\.\\d{2}$");

    /** One matched transaction: the reconstructed date text (day + month/year, not yet parsed --
     *  the caller feeds it back through {@link CsvParser#parseDate} exactly as any other staged
     *  date cell would be), the narration, the raw amount text, the Cr/Dr direction token, and how
     *  many {@code rows} entries (starting from the anchor row) the block consumed. */
    private record TwoLineBlock(String dateText, String description, String amountRaw, String direction,
                                 int rowsConsumed) {}

    /** True for a month/year token by asking the SAME question the eventual staged date will be
     *  asked -- whether {@code "01 " + text} parses -- rather than a separate hand-written regex
     *  that could silently drift from what {@link CsvParser#parseDate} actually accepts. */
    private boolean looksLikeMonthYearToken(String text) {
        return CsvParser.parseDate("01 " + text.trim()) != null;
    }

    /** Tries to match a transaction block anchored at {@code rows.get(rowIndex)}: a day-of-month
     *  cell, confirmed by a currency-prefixed amount cell, a month/year token, and a bare Cr/Dr
     *  marker all appearing somewhere within {@link #TWO_LINE_BLOCK_MAX_GAP} of the day cell's own
     *  y (same page). Returns null on any mismatch -- a day-shaped number with nothing else
     *  qualifying nearby is not a transaction, not an error.
     *
     *  <p>Deliberately does NOT assume the day, narration, and amount share one {@code rows} entry
     *  even though they read as one visual line: measured directly against the real document, the
     *  amount cell's baseline sits far enough below the narration/day baseline (about 1.3pt from
     *  the day cell, versus about 4.2pt from the narration cell that starts the row) that
     *  {@code groupIntoRows}' own {@code ROW_Y_TOLERANCE} (3.0pt) splits what looks like one line
     *  into two separate {@code rows} entries -- narration+day in one, amount alone in the next --
     *  with the month/year+direction line as a third. Anchoring on the day cell's own y and
     *  pooling every cell within the gap bound, rather than assuming a fixed row count, is what
     *  makes this robust to that split without having to loosen {@code ROW_Y_TOLERANCE} itself
     *  (a global change with unknown effect on every other document already relying on it).
     *
     *  <p>Cross-checks the amount's sign against the direction marker, but only ever refuses the
     *  ONE contradiction actually reachable on the real document -- a "+"-prefixed amount paired
     *  with a "Dr" marker. It does not also refuse an unsigned amount paired with "Cr" (the real
     *  document's debit rows print with no sign at all, so a stricter symmetric rule would reject
     *  real, correct data for a combination that was never actually observed as wrong -- the same
     *  mistake {@code firstNonZeroAmount}'s own bug-fix history in TransactionNormalizer warns
     *  against: inventing strictness beyond what evidence supports). */
    private TwoLineBlock twoLineBlockAt(List<List<PositionedText>> rows, int rowIndex) {
        List<PositionedText> anchorRow = rows.get(rowIndex);
        if (anchorRow.isEmpty()) return null;
        PositionedText dayCell = null;
        for (PositionedText cell : anchorRow) {
            if (DAY_OF_MONTH_CELL.matcher(cell.text().trim()).matches()) {
                dayCell = cell;
                break;
            }
        }
        if (dayCell == null) return null;

        // Reference point is the ANCHOR ROW's own y (its smallest member, since groupIntoRows
        // sorts by y before grouping), not the day cell's own y specifically -- the day cell can
        // itself sit a couple of points below the row's other members (measured: the narration
        // cell that starts this row is ~2.9pt above the day cell within the SAME groupIntoRows
        // group). Measuring from dayCell.y() made the anchor row's own gap negative and broke the
        // pool before it ever included the row the day cell came from.
        float windowStartY = anchorRow.get(0).y();
        int page = dayCell.pageIndex();
        List<PositionedText> pool = new ArrayList<>();
        int lastRowInWindow = rowIndex;
        for (int i = rowIndex; i < rows.size(); i++) {
            List<PositionedText> row = rows.get(i);
            if (row.isEmpty() || row.get(0).pageIndex() != page) break;
            float gap = row.get(0).y() - windowStartY;
            if (gap < 0 || gap > TWO_LINE_BLOCK_MAX_GAP) break;
            pool.addAll(row);
            lastRowInWindow = i;
        }

        PositionedText amountCell = null;
        PositionedText monthYearCell = null;
        PositionedText directionCell = null;
        for (PositionedText cell : pool) {
            if (cell == dayCell) continue;
            String text = cell.text().trim();
            if (amountCell == null && CURRENCY_PREFIXED_AMOUNT.matcher(text).matches()) {
                amountCell = cell;
                continue;
            }
            if (monthYearCell == null && looksLikeMonthYearToken(text)) {
                monthYearCell = cell;
                continue;
            }
            if (directionCell == null && BARE_CR_DR_CELL.matcher(text).matches()) directionCell = cell;
        }
        if (amountCell == null || monthYearCell == null || directionCell == null) return null;

        String dateText = dayCell.text().trim() + " " + monthYearCell.text().trim();
        if (CsvParser.parseDate(dateText) == null) return null;

        String direction = directionCell.text().trim();
        boolean amountSignIsCredit = amountCell.text().trim().startsWith("+");
        boolean markerIsCredit = "cr".equalsIgnoreCase(direction);
        if (amountSignIsCredit && !markerIsCredit) return null;

        List<PositionedText> narrationCells = new ArrayList<>(pool);
        narrationCells.remove(dayCell);
        narrationCells.remove(amountCell);
        narrationCells.remove(monthYearCell);
        narrationCells.remove(directionCell);
        return new TwoLineBlock(dateText, lineOf(narrationCells), amountCell.text().trim(), direction,
                lastRowInWindow - rowIndex + 1);
    }

    /** Entry point for the whole INFERRED_TWO_LINE_DATE_BLOCK capability -- see its top-level doc
     *  comment above {@link #TWO_LINE_BLOCK_MAX_GAP}. Walks {@code rows} looking for matched
     *  blocks, skipping every row a match consumed (a block's own lines can never themselves start
     *  a different block). Returns null, the same "leave sections exactly as they were" contract
     *  {@link #inferHeaderlessSection} follows, when fewer than
     *  {@link #TWO_LINE_BLOCK_MIN_TRANSACTIONS} blocks are found. */
    private LocatedSection inferTwoLineDateBlockSection(List<List<PositionedText>> rows, DocumentContext ctx) {
        List<Map<String, String>> resultRows = new ArrayList<>();
        // Every row a block match doesn't consume -- most importantly, the payment-summary /
        // account-identity block that precedes the ledger on a real credit-card statement -- is kept
        // here instead of discarded, so downstream product/identity classification (which reads
        // LocatedSection.auxiliaryText(), not rows()) has something to work with. This capability
        // used to return List.of() here, which is exactly why a document using it could extract its
        // transactions correctly while still being misclassified as SAVINGS with no account number.
        List<String> auxiliaryText = new ArrayList<>();
        int rowIndex = 0;
        while (rowIndex < rows.size()) {
            String rowLine = lineOf(rows.get(rowIndex));
            if (PAGE_FOOTER.matcher(rowLine).find() || STATEMENT_CLOSING_MARKER.matcher(rowLine).find()) {
                rowIndex++;
                continue;
            }
            TwoLineBlock block = twoLineBlockAt(rows, rowIndex);
            if (block != null) {
                Map<String, String> staged = new LinkedHashMap<>();
                staged.put("Date", block.dateText());
                staged.put("Description", block.description());
                staged.put("Amount", block.amountRaw());
                staged.put("Type", block.direction());
                resultRows.add(staged);
                rowIndex += block.rowsConsumed();
                continue;
            }
            if (!rowLine.isBlank()) auxiliaryText.add(rowLine);
            rowIndex++;
        }
        if (resultRows.size() < TWO_LINE_BLOCK_MIN_TRANSACTIONS) {
            if (ctx != null) ctx.recordDiagnostic("TWO_LINE_BLOCK_TOO_FEW_TRANSACTIONS");
            return null;
        }
        if (ctx != null) ctx.record("INFERRED_TWO_LINE_DATE_BLOCK");
        return new LocatedSection(auxiliaryText, resultRows);
    }
}
