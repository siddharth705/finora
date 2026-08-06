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
    // "SAVINGS ACCOUNT-RES  120-070727-006", which introduces a second account partway through a
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
    // numbers are printed with (HSBC's "120-070727-006"). See accountIdentityIn.
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
    private static final Pattern PAGE_FOOTER = Pattern.compile("(?i)\\bpage\\b.*\\bof\\b");

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
    private static final int MAX_TRAILING_CONTINUATION_ROWS = 2;

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
        int trailingCountSinceLastAnchor = 0;
        // LEADING_NARRATION_CONTINUATION: dateless rows that arrive once trailingCountSinceLastAnchor
        // has hit its cap -- narration for a transaction whose OWN date row hasn't been seen yet
        // (a real Canara Bank statement's layout; see MAX_TRAILING_CONTINUATION_ROWS's own doc
        // comment). Buffered here, in encounter order, until the next date-bearing row arrives and
        // claims it as its leading part -- see mergeLeadingInto's own doc comment for why that's a
        // prepend, not the ordinary append mergeInto does for trailing continuations.
        Map<String, String> pendingLeading = null;

        for (List<PositionedText> row : rows) {
            String rowLine = lineOf(row);

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
                trailingCountSinceLastAnchor = 0;
                pendingLeading = null;
                pendingAuxiliary.add(rowLine);
                continue;
            }

            if (looksLikeHeaderRow(row)) {
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
                trailingCountSinceLastAnchor = 0;
                pendingLeading = null;
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
                        if (!mergeLeadingInto(bucketed, pendingLeading)) {
                            // Refused: this buffer is a standalone noise line, not this
                            // transaction's leading narration -- see mergeLeadingInto. Kept as its
                            // own row so it still surfaces as unparseable rather than vanishing.
                            currentRows.add(pendingLeading);
                        }
                        pendingLeading = null;
                    }
                    currentRows.add(bucketed);
                    lastRowPage = row.get(0).pageIndex();
                    trailingCountSinceLastAnchor = 0;
                } else if (currentRows.isEmpty()) {
                    // Nothing to attach to at all yet (e.g. an "Opening Balance" summary line
                    // before any real transaction) -- stands on its own, same as before. Closed to
                    // trailing continuation immediately: a summary row isn't a transaction, and
                    // narration that follows it belongs to the FIRST real transaction as leading
                    // content, not to this row as trailing content.
                    currentRows.add(bucketed);
                    lastRowPage = row.isEmpty() ? lastRowPage : row.get(0).pageIndex();
                    trailingCountSinceLastAnchor = MAX_TRAILING_CONTINUATION_ROWS;
                } else if (samePage && trailingCountSinceLastAnchor < MAX_TRAILING_CONTINUATION_ROWS) {
                    mergeInto(currentRows.get(currentRows.size() - 1), bucketed);
                    if (ctx != null) ctx.record("WRAPPED_DESCRIPTION");
                    trailingCountSinceLastAnchor++;
                    lastRowPage = row.get(0).pageIndex();
                } else {
                    // Past the trailing cap (or on a new page with nothing to trail into) -- this
                    // is leading narration for a transaction whose date row hasn't appeared yet.
                    // Not gated on samePage the way the trailing branch above is: unlike a page
                    // footer or repeated title banner (which must never cross a page boundary into
                    // the wrong row), genuine leading narration legitimately can span a page break
                    // -- verified against the real Canara statement this capability is modeled on.
                    if (pendingLeading == null) pendingLeading = new LinkedHashMap<>();
                    mergeInto(pendingLeading, bucketed);
                    if (ctx != null) ctx.record("LEADING_NARRATION_CONTINUATION");
                    lastRowPage = row.isEmpty() ? lastRowPage : row.get(0).pageIndex();
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
     * e.g. "UPI/124008948334/02:44:32/UPI/paytm.s25j48". Under the old check that row looked like a
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
     */
    private boolean hasDateValue(Map<String, String> bucketed) {
        String dateRaw = CsvParser.firstNonBlank(bucketed, DATE_HINTS.toArray(new String[0]));
        return dateRaw != null && CsvParser.parseDate(dateRaw.trim()) != null;
    }

    /** Merges a continuation row's non-blank column values into the transaction row above it --
     *  per column, appending with a space when both already have a value (same join convention
     *  {@link #bucketRow} itself uses for two text runs landing in the same column), or simply
     *  filling it in when the target's own value for that column is blank/absent. */
    private void mergeInto(Map<String, String> target, Map<String, String> continuation) {
        for (Map.Entry<String, String> e : continuation.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            String existing = target.get(e.getKey());

            // Bug fix: a continuation row's wrapped narration very often mis-buckets into the DATE
            // column (that column's anchor is leftmost, and a wrapped line starts at the left
            // margin) -- e.g. "UPI/124008948334/02:44:32/UPI/paytm.s25j48". Appending that onto the
            // anchor row's own valid date produced "02/05/25 UPI/1240089..." which no longer parses
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
                String descriptionColumn = descriptionColumnIn(target);
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
                    String fallback = firstUnstructuredColumn(target);
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

    /** The description-ish column actually present in this row, or null when the layout has none --
     *  used to rehome narration text that mis-bucketed into the date column (see mergeInto). */
    private String descriptionColumnIn(Map<String, String> row) {
        for (String column : row.keySet()) {
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
    private String firstUnstructuredColumn(Map<String, String> row) {
        for (String column : row.keySet()) {
            if (!isDateColumn(column) && !isAmountColumn(column)) return column;
        }
        return null;
    }

    /** Same column-merge semantics as {@link #mergeInto}, but PREPENDS instead of appending --
     *  used only for {@code pendingLeading} (see {@link #locateAll}): a leading narration buffer's
     *  text chronologically precedes whatever the new anchor row's own bucketed values already
     *  hold, so it has to read before them, not after. */
    private boolean mergeLeadingInto(Map<String, String> target, Map<String, String> leading) {
        // Bug fix, exposed by tightening hasDateValue to require a PARSEABLE date: a per-page title
        // banner ("Savings Account" at the top of page 2) has no date of its own, so it is no
        // longer mistaken for a transaction anchor -- correct -- but it was then buffered as
        // LEADING narration and prepended into the next real transaction. Its text sits in the date
        // column, so the prepend produced "Savings Account 02-05-2026", which no longer parses, and
        // the genuine transaction it was prepended to was dropped entirely. A buffer that would
        // destroy the anchor's own valid date is not that transaction's narration; it is a
        // standalone noise line. Refusing the merge here lets the caller keep it as its own row, so
        // it still surfaces as an unparseable row ("never lose information") instead of either
        // vanishing or corrupting a real transaction.
        for (Map.Entry<String, String> e : leading.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            if (isDateColumn(e.getKey())) {
                String existing = target.get(e.getKey());
                if (existing != null && CsvParser.parseDate(existing.trim()) != null
                        && CsvParser.parseDate((e.getValue() + " " + existing).trim()) == null) {
                    return false;
                }
            }
        }
        for (Map.Entry<String, String> e : leading.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            String existing = target.get(e.getKey());
            target.put(e.getKey(), (existing == null || existing.isBlank()) ? e.getValue() : e.getValue() + " " + existing);
        }
        return true;
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
