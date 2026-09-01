package com.finora.imports.pdf;

import com.finora.imports.CsvParser;
import com.finora.imports.DocumentContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

    // A plain "Account Number: <digits>" line -- the identity signal a composite statement gives
    // when it has NO SECTION_MARKER-shaped product banner at all (no "SAVINGS ACCOUNT -", just an
    // ordinary account-details field, the same shape PdfMetadataExtractor.ACCOUNT_NUMBER already
    // recognizes downstream for metadata purposes). Deliberately a SEPARATE pattern, not a shared
    // import from that class: PdfTableLocator must not depend on PdfMetadataExtractor at all (see
    // LocatedSection's own doc comment -- this class does not decide what a section MEANS
    // financially, and identity/product interpretation belongs strictly downstream).
    //
    // Anchored at the START of the line specifically so an ordinary transaction narration that
    // merely MENTIONS an account number mid-sentence -- a NEFT/UPI reference like "...Account No
    // 1234567890 credited" -- can never match. A genuine identity line states the label first;   // synthetic-ok: 1-2-3-4-5-6-7-8-9-0, invented, not corpus-derived
    // narration never does.
    private static final Pattern ACCOUNT_IDENTITY_LINE = Pattern.compile(
            "(?i)^\\s*(?:Account\\s*No\\.?|Account\\s*Number|A/C\\s*No\\.?)\\s*:?\\s*.*\\d{4,}");

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

    // PAGE_LEGEND_BLOCK_SUPPRESSED's own opening line -- see pageLegendBlockActive's doc comment
    // for the mechanism this seeds. Matched against this real SBI credit-card statement's exact
    // observed phrasing, the same evidence-before-capability discipline TRAILING_CONTENT_TRIGGERS'
    // own patterns above follow -- narrow to the one real sentence rather than broadened to a
    // generic "legend" or "disclaimer" heading a genuine transaction narration could plausibly echo.
    private static final Pattern PAGE_LEGEND_BLOCK_START = Pattern.compile(
            "(?i)transactions\\s+highlighted\\s+in\\s+grey\\s+color"
                    // A real Kotak Mahindra Bank credit-card statement opens a per-page
                    // payment-methods legend with this sentence, printed at the bottom of page 1
                    // and set beside a second, side-by-side "What you must know!" box. A
                    // page-boundary block, not the document's true end (real transactions resume
                    // on page 2), so this belongs here rather than among TRAILING_CONTENT_TRIGGERS'
                    // permanently-closing markers. Without it, the whole block -- fragmented across
                    // several narration-only physical rows by the two-column layout -- glued onto
                    // whichever real transaction row on either side of the page break sat closest,
                    // confirmed via CorpusProbe against the real document.
                    + "|pay\\s+your\\s+credit\\s+card\\s+bills\\s+using\\s+the\\s+following"
                    // Three real HDFC savings-account statements (independently uploaded) open a
                    // per-page footer with this exact sentence -- "*Closing balance includes funds
                    // earmarked for hold and uncleared funds", followed by an address-correctness
                    // disclaimer and the bank's GSTIN/registered-office boilerplate, at the bottom
                    // of every page. Same page-boundary shape as the two triggers above: without
                    // it, this block glued onto the last real transaction above each page break --
                    // confirmed via CorpusGarbageSweep against all three real documents, which share
                    // the identical layout (same bank, same export format).
                    + "|closing\\s+balance\\s+includes\\s+funds\\s+earmarked");

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

    // TRANSACTION_TABLE_TOTAL_CLOSED. A real Kotak Mahindra Bank credit-card statement prints a
    // column-total row -- "Total Purchase & Other Charges  5,178.69" -- directly beneath the last
    // real transaction, before the MITC/fees-and-charges legal schedule begins. Same failure shape
    // Phase 2A's investigation found on Axis (see
    // docs/architecture/system-design/transaction-boundary-phase2a-investigation.md): without this,
    // the trailing MITC content still gets bucketed as candidate rows, surviving only because
    // row-continuation merging happens to fuse it into a blob whose date cell fails
    // TransactionNormalizer's Stage-4 parse. Confirmed single-occurrence on this document (`grep`),
    // not present anywhere else in the real corpus -- narrow to this exact phrasing rather than
    // broadened to a generic "Total ... Charges" shape, which real column labels elsewhere in the
    // corpus (e.g. "Other Debit&Charges") would false-positive against.
    private static final Pattern TRANSACTION_TABLE_TOTAL_MARKER = Pattern.compile(
            "(?i)total\\s+purchase\\s*&\\s*other\\s+charges");

    // TRANSACTION_CATEGORY_HEADER_SUPPRESSED. A real Kotak Mahindra Bank credit-card statement
    // groups its own ledger into sub-categories mid-table -- "Payments and Other Credits", then a
    // card-identity aside ("Primary Card Transactions- <masked card>"), then "Retail Purchases and
    // Cash Transactions" -- each a bare heading line with no date and no amount of its own, printed
    // between two real transaction rows (never before the header, and never at the document's true
    // end, so neither PAGE_LEGEND_BLOCK_START's per-page reset nor TRAILING_CONTENT_TRIGGERS'
    // permanent one is the right shape). Without recognizing these, the ordinary leading/trailing
    // narration merge -- which has no notion of "this dateless line is a category divider, not
    // prose" -- swept one onto the transaction printed before it and the other onto the transaction
    // printed after it, by nothing more than which row it happened to sit closer to. Confirmed via
    // CorpusProbe against the real document: one category header ended up prepended to the first
    // purchase row's own narration, and another appended to an unrelated payment row's.
    //
    // Anchored to the whole line (never a mid-sentence match) and narrow to these three real
    // observed phrases, same "evidence before capability" discipline as every other trigger in this
    // class -- a genuine transaction narration never consists of nothing but one of these phrases.
    private static final Pattern CREDIT_CARD_CATEGORY_HEADER = Pattern.compile(
            "(?i)^\\s*(?:payments\\s+and\\s+other\\s+credits"
                    + "|primary\\s+card\\s+transactions\\b.*"
                    + "|retail\\s+purchases\\s+and\\s+cash\\s+transactions)\\s*$");

    // MITC_SECTION_CLOSED. A real ICICI Bank credit-card statement prints "MOST IMPORTANT TERMS AND
    // CONDITIONS (MITC)" as an all-caps section heading immediately after the last real transaction
    // and its rewards summary, opening a multi-page legal/T&C appendix. Same failure shape as
    // TRANSACTION_TABLE_TOTAL_MARKER and STATEMENT_CLOSING_MARKER above -- see the Phase 2A
    // investigation doc.
    //
    // Deliberately CASE-SENSITIVE, matching only the exact all-caps heading form. Two real
    // documents in the corpus (AU, SBI) mention the same concept in ordinary mixed-case prose
    // *before* their own real transactions end -- AU's "Most Important Terms and conditions" (a
    // footer link, page 1) and SBI's "Most Important Terms & Conditions" / "Most Important Terms
    // and Conditions (MITC)" (an insurance disclosure aside and a payment-terms mention, both mid-
    // document, both using "&" or lowercase "and" rather than this pattern's spelled-out "AND").
    // A case-insensitive match would have closed both of those documents' sections dozens of pages
    // early. The case requirement is not a stylistic choice -- it is the one thing that lets this
    // pattern fire on ICICI's own genuine section-opening heading without also firing on either
    // false positive, verified against the full real corpus before this was written this way.
    private static final Pattern MITC_SECTION_MARKER = Pattern.compile(
            "MOST IMPORTANT TERMS AND CONDITIONS");

    // ACCOUNT_DISCREPANCY_DISCLAIMER_CLOSED. Two real, independently-uploaded savings-account
    // statements (a Central Bank of India export and a PNB ONE export) each open their document's
    // true closing disclaimer block with a regulatory-boilerplate sentence about notifying the bank
    // of a discrepancy -- CBI's "Unless a constituent notifies the Bank immediately of any
    // discrepancy...", PNB's "Unless constituent notifies the bank immediately of any
    // discrepancy...". Same failure shape as every other TRAILING_CONTENT_TRIGGERS entry: this
    // sentence sits BEFORE either document's own true end-of-statement marker (CBI's literal "END
    // OF STATEMENT", PNB's own bulleted disclaimer list has no such marker at all), so it had
    // already been swept into the last real transaction's trailing narration by the time any
    // existing trigger got a chance to fire. Matched on the invariant core across both real
    // documents' minor wording differences ("a constituent"/"constituent", "Bank"/"bank") rather
    // than either one verbatim -- both are the same regulatory-mandated disclosure, not two
    // coincidentally similar sentences.
    private static final Pattern ACCOUNT_DISCREPANCY_DISCLAIMER_MARKER = Pattern.compile(
            "(?i)constituent\\s+notifies\\s+the\\s+bank\\s+immediately\\s+of\\s+any\\s+discrepancy");

    // STATEMENT_SUMMARY_BLOCK_CLOSED. Three real, independently-uploaded savings-account statements
    // (an HDFC single-page export, an SBI export, and a much longer 38-page HDFC export) each close
    // with a "Statement Summary :" block -- opening/closing balance, debit/credit counts and
    // totals, then a security disclaimer -- printed directly after the last real transaction, with
    // no other recognized closing marker anywhere in any of the three. Same failure shape as every
    // other TRAILING_CONTENT_TRIGGERS entry: without it, the block's own header row ("Opening
    // Balance Dr Count Cr Count Debits Credits Closing Bal") and the security disclaimer that
    // follows it both got swept into the last real transaction's trailing narration -- and on the
    // 38-page document specifically, the summary grid's own VALUE row (its aggregate debit/credit
    // totals, e.g. "368,759.09"/"374,644.91") was severe enough to also form an entire PHANTOM
    // transaction of its own: a fabricated row with no real date, carrying those aggregate totals
    // as if they were one more genuine debit and credit. See
    // SplitHeaderRunsPdfTableLocatorTest's own updated counts for the exact before/after row this
    // eliminated.
    //
    // Bug fix: bare "statement summary" (no trailing colon) collided with a real ICICI credit-card
    // statement's own PRE-table payment-summary panel heading ("STATEMENT SUMMARY", immediately
    // followed by "Total Amount due"/"Closing Balance" fields, verified alone on its own physical
    // row near the top of the document) -- an entirely different, legitimate use of the same two
    // words, and matching it discarded the ICICI document's whole transaction table as if the
    // panel heading were the document's true end. Both real evidencing documents happen to punctuate
    // their own heading with a colon right after "Summary" ("STATEMENT SUMMARY :-", "Statement
    // Summary : 01-07-2026 To..."); ICICI's does not. Requiring it is what tells apart "this IS the
    // closing recap" from "this MENTIONS a summary," the same discipline MITC_SECTION_MARKER's own
    // case-sensitivity requirement already applies for an analogous real collision.
    private static final Pattern STATEMENT_SUMMARY_BLOCK_MARKER = Pattern.compile(
            "(?i)statement\\s+summary\\s*:");

    // CHEQUE_PAYABLE_FOOTER_CLOSED. A real Axis Bank credit-card statement's own true end opens
    // with "Your cheque should be payable to Axis Bank Card No.<masked>...", immediately followed
    // by a "Dear Customer, pay your Axis Bank Credit Card bill..." ECS-registration sentence and an
    // "IMPORTANT MESSAGE" legal/GST disclaimer block -- confirmed single-occurrence (`grep`), never
    // repeated per page, so this is the document's true end, not a page legend. Without it, the
    // whole block was swept into the last real transaction's trailing narration.
    private static final Pattern CHEQUE_PAYABLE_FOOTER_MARKER = Pattern.compile(
            "(?i)cheque\\s+should\\s+be\\s+payable\\s+to");

    // NEUCOINS_FOOTNOTE_CLOSED. A real HDFC "Tata Neu Plus" credit-card statement's own transaction
    // table ends with a "Note:" footnote explaining how its "Base NeuCoins" rewards column is
    // calculated -- confirmed single-occurrence (`grep`), directly beneath the last real
    // transaction, before a page break and the MITC/fees appendix. Without it, the whole footnote
    // was swept into the last real transaction's trailing narration.
    private static final Pattern NEUCOINS_FOOTNOTE_MARKER = Pattern.compile(
            "(?i)base\\s+neucoins.{0,20}are\\s+calculated\\s+as");

    // SAVINGS_AND_BENEFITS_SECTION_CLOSED. A real SBI credit-card statement (the same document
    // HEADER_RECONSTRUCTED/PAGE_LEGEND_BLOCK_SUPPRESSED are evidenced from) closes its supplementary
    // cardholder section's transaction table with a "SAVINGS AND BENEFITS SECTION" heading,
    // introducing a Cash Back / Petrol Surcharge Waiver / Reward Points recap grid -- confirmed
    // single-occurrence in this section (`grep`), directly beneath the last real transaction.
    // Without it, the grid's own header/value rows were swept into that transaction's trailing
    // narration.
    private static final Pattern SAVINGS_AND_BENEFITS_SECTION_MARKER = Pattern.compile(
            "(?i)savings\\s+and\\s+benefits\\s+section");

    /** One row-shaped trigger the trailing-content suppression gate checks for, paired with the
     *  capability name to record when it fires -- see {@link #trailingContentTriggerCapability}. */
    private record TrailingContentTrigger(Pattern pattern, String capability) {}

    // Checked in this order, first match wins -- order has no behavioral significance among these
    // entries (each is evidenced from a different real document and none has been found to overlap
    // with another's territory), but a stable order keeps a diff of this list reviewable.
    private static final List<TrailingContentTrigger> TRAILING_CONTENT_TRIGGERS = List.of(
            new TrailingContentTrigger(ILLUSTRATIVE_EXAMPLE_MARKER, "ILLUSTRATIVE_BLOCK_SUPPRESSED"),
            new TrailingContentTrigger(STATEMENT_CLOSING_MARKER, "TRANSACTION_TABLE_CLOSED"),
            new TrailingContentTrigger(TRANSACTION_TABLE_TOTAL_MARKER, "TRANSACTION_TABLE_TOTAL_CLOSED"),
            new TrailingContentTrigger(MITC_SECTION_MARKER, "MITC_SECTION_CLOSED"),
            new TrailingContentTrigger(ACCOUNT_DISCREPANCY_DISCLAIMER_MARKER,
                    "ACCOUNT_DISCREPANCY_DISCLAIMER_CLOSED"),
            new TrailingContentTrigger(STATEMENT_SUMMARY_BLOCK_MARKER, "STATEMENT_SUMMARY_BLOCK_CLOSED"),
            new TrailingContentTrigger(NEUCOINS_FOOTNOTE_MARKER, "NEUCOINS_FOOTNOTE_CLOSED"),
            new TrailingContentTrigger(SAVINGS_AND_BENEFITS_SECTION_MARKER,
                    "SAVINGS_AND_BENEFITS_SECTION_CLOSED"));

    /** The capability name the first matching trigger should record for {@code rowLine}, or null
     *  if none match. {@code pageIndex}/{@code lastPageIndex} exist only for
     *  CHEQUE_PAYABLE_FOOTER_CLOSED -- see its own check below for why. */
    private static String trailingContentTriggerCapability(String rowLine, int pageIndex, int lastPageIndex) {
        // CHEQUE_PAYABLE_FOOTER_CLOSED needs one more check than every other entry below: unlike
        // those (each confirmed single-occurrence AND genuinely at their evidencing document's true
        // end), this exact sentence was found on a SECOND real Axis Bank credit-card statement,
        // printed on page 1 of 3 as part of an ordinary payment-instructions panel next to the
        // summary -- not a closing block. "Single occurrence" alone does not distinguish an early
        // informational panel from a genuine document-closing footer; the two real Axis documents
        // this pattern has now been evidenced against disagree on where it prints. Requiring it to
        // sit on the document's own actual last page is the one thing a true closing block and this
        // false-positive panel cannot both satisfy at once, and it needs no new vocabulary -- the
        // page position is already known to the caller.
        //
        // Known limitation, unevidenced against the real corpus so deliberately not solved
        // speculatively: {@code lastPageIndex} is the WHOLE document's last page, not the
        // currently-open section's. A composite multi-account statement whose first section's own
        // true-end footer sits on that section's own last page (not the document's) would be
        // refused here too. This is not a new risk this fix introduces, though -- pre-fix, the same
        // false-positive-on-page-1 shape this fix closes would have permanently suppressed every
        // row for the REST of the document (trailingContentSuppressed never resets), composite
        // sections included; refusing an early/wrong-page match and letting the normal
        // SECTION_MARKER/header machinery close the section later is strictly safer than that. No
        // real document in this corpus evidences CHEQUE_PAYABLE_FOOTER_MARKER on a composite
        // statement (only single-account Axis credit-card exports) -- revisit if one ever does.
        if (CHEQUE_PAYABLE_FOOTER_MARKER.matcher(rowLine).find()) {
            return pageIndex == lastPageIndex ? "CHEQUE_PAYABLE_FOOTER_CLOSED" : null;
        }
        for (TrailingContentTrigger trigger : TRAILING_CONTENT_TRIGGERS) {
            if (trigger.pattern().matcher(rowLine).find()) return trigger.capability();
        }
        return null;
    }

    /** Records one of {@link #TRAILING_CONTENT_TRIGGERS}' capabilities against {@code ctx}.
     *
     *  <p>Deliberately an explicit switch with the capability name spelled out as a quoted string
     *  constant in each branch, not a passthrough that hands {@code capability} straight to {@code
     *  DocumentContext.record}. {@code CapabilityCorpusCoverageTest} proves every capability the
     *  engine can record by scanning this source tree for a quoted-string argument at each real
     *  recording call site; a passthrough is invisible to that scan, since the argument there is a
     *  variable, not a quoted constant -- all four Phase 2C capabilities silently reporting as
     *  uncovered is what surfaced this. The {@code default} branch throws rather than silently doing
     *  nothing, so a fifth trigger added to {@link #TRAILING_CONTENT_TRIGGERS} without a matching
     *  branch here fails loudly the first time it fires, not silently forever. */
    private static void recordTrailingContentTrigger(DocumentContext ctx, String capability) {
        if (ctx == null) return;
        switch (capability) {
            case "ILLUSTRATIVE_BLOCK_SUPPRESSED" -> ctx.record("ILLUSTRATIVE_BLOCK_SUPPRESSED");
            case "TRANSACTION_TABLE_CLOSED" -> ctx.record("TRANSACTION_TABLE_CLOSED");
            case "TRANSACTION_TABLE_TOTAL_CLOSED" -> ctx.record("TRANSACTION_TABLE_TOTAL_CLOSED");
            case "MITC_SECTION_CLOSED" -> ctx.record("MITC_SECTION_CLOSED");
            case "ACCOUNT_DISCREPANCY_DISCLAIMER_CLOSED" -> ctx.record("ACCOUNT_DISCREPANCY_DISCLAIMER_CLOSED");
            case "STATEMENT_SUMMARY_BLOCK_CLOSED" -> ctx.record("STATEMENT_SUMMARY_BLOCK_CLOSED");
            case "CHEQUE_PAYABLE_FOOTER_CLOSED" -> ctx.record("CHEQUE_PAYABLE_FOOTER_CLOSED");
            case "NEUCOINS_FOOTNOTE_CLOSED" -> ctx.record("NEUCOINS_FOOTNOTE_CLOSED");
            case "SAVINGS_AND_BENEFITS_SECTION_CLOSED" -> ctx.record("SAVINGS_AND_BENEFITS_SECTION_CLOSED");
            default -> throw new IllegalStateException(
                    "Unknown trailing-content trigger capability: " + capability);
        }
    }

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

    /** A physical row that carried a date-shaped cell AND a decimal-amount cell on the same line
     *  (see {@link #isTransactionShapedRow}) but did not become a member of a section's own
     *  {@code rows()} -- a structural SHAPE fact only, never a claim that the line actually was a
     *  missing transaction ("candidate," not "missing" -- the validator downstream must never
     *  overclaim this into "N transactions went missing"). That judgment (whether this is worth
     *  surfacing to a user) belongs downstream, in whatever reads this list -- mirrors the same
     *  layering the class-level doc comment on {@link LocatedSection} already establishes for
     *  every other field here.
     *
     *  <p>Deliberately carries no raw text and no customer data -- only {@code reason} (a stable
     *  machine code, e.g. {@code "BUCKET_EMPTY"}) and {@code signals} (which structural properties
     *  were present, e.g. {@code DATE_PRESENT}/{@code AMOUNT_PRESENT}). A PDF row can carry an
     *  account number, a merchant name, a balance -- exactly the class of data a prior real
     *  incident already leaked into a code comment on this same statement's own last-4 digits (see
     *  the AU auxiliaryText propagation fix). Evidence about a row's SHAPE never needs its VALUE. */
    public record DroppedCandidateRow(String reason, java.util.Set<String> signals) {}

    /**
     * A section whose own accepted header may be an incomplete fallback, not the document's real
     * transaction header -- built from a real corpus study, not a hypothesis. Deliberately narrow:
     * fires only when ALL THREE hold together, because each alone was measured and rejected --
     * see {@link #LOW_CONFIDENCE_TRANSACTION_HEADER_COLUMN_COUNT}'s own doc comment for exactly
     * what the corpus study found. Never claims the section IS wrong, only that reconstruction
     * left uncertainty a reviewer should see -- the same "candidate, not a claim" posture
     * {@link DroppedCandidateRow} already takes. {@code vocabularySignals} carries which {@link
     * #AMOUNT_COLUMN_HINTS} words were present on the row that failed to merge (never the row's
     * own text -- see {@code DroppedCandidateRow}'s own doc comment on why raw content never
     * belongs in this evidence).
     *
     * <p>{@code sectionIndex} exists because a document is not one verdict -- a real SBI credit-
     * card statement's first section (a primary cardholder's own transactions) reconstructs
     * correctly while its second (a supplementary cardholder's transactions, introduced by a
     * banner mid-document) does not; a document-level signal alone would have reported that
     * document as healthy, exactly the class of blind spot a per-section total would create.
     * Deliberately does NOT carry an unparseable-row count: that fact belongs to normalization
     * (see {@code TransactionNormalizer}), a stage this class does not reach -- correlate this
     * finding's {@code sectionIndex} against that section's own {@code StagedAccountSection}
     * (built later, by {@code PdfPreviewGenerator}) rather than have this record guess at, or
     * be silently stale about, a fact it cannot itself observe.
     */
    public record HeaderReconstructionFinding(String reason, int sectionIndex,
                                                java.util.Set<String> vocabularySignals,
                                                int acceptedHeaderColumnCount) {}

    /** Everything {@link PdfTableLocator} can say about a section's OWN extraction -- structural,
     *  never financial-interpretation -- beyond its rows and auxiliary text. A dedicated object
     *  rather than more fields directly on {@link LocatedSection}, so this stays the one place new
     *  structural evidence (dropped-candidate rows, header-reconstruction findings today; page
     *  coverage or further row-fate detail later) accumulates, instead of {@code LocatedSection}
     *  itself slowly becoming a dumping ground for every future signal this class learns to
     *  compute. */
    public record ExtractionEvidence(List<DroppedCandidateRow> droppedTransactionCandidates,
                                      List<HeaderReconstructionFinding> headerReconstructionFindings) {
        static final ExtractionEvidence NONE = new ExtractionEvidence(List.of(), List.of());
    }

    /** One detected account/table within a document -- {@code auxiliaryText} is the free-standing
     *  text (account holder/number/branch/IFSC lines, a credit-card payment-summary block, etc.)
     *  that appeared before this section's own header row, for {@link PdfMetadataExtractor} and
     *  credit-card-signal detection to scan. {@code evidence} is the row-accounting trail (see
     *  {@link ExtractionEvidence}) -- currently populated only at the four drop points with the
     *  strongest evidence: three in the header-based path (an unrecognized-bank document risk this
     *  class's own header-diff/marker/footer logic already accepted, not speculative) plus the
     *  headerless-inference path's own adjacent-duplicate drop (see
     *  {@link #bucketHeaderlessRowsWithContinuation}); the rest of this class's many other drop
     *  points are a documented, deliberate gap, not silently assumed complete. */
    public record LocatedSection(List<String> auxiliaryText, List<Map<String, String>> rows,
                                  ExtractionEvidence evidence) {}

    /**
     * Structural facts about how {@link #groupIntoRows} turned this document's raw text runs into
     * physical rows -- computed once, for the WHOLE document, before any section or header logic
     * runs. Deliberately document-level, not per-section: {@code groupIntoRows} groups the entire
     * document's text in one pass, before sections are even determined, so there is no per-section
     * moment to attribute this to.
     *
     * <p><b>Why this exists, and why it is scoped this narrowly.</b> A real ICICI CC statement's
     * header formed incorrectly not because header detection chose wrong, but because
     * {@code groupIntoRows} had already fused one unrelated summary-panel heading into the real
     * header's own row before header logic ever ran -- two text runs 2.3pt apart in y, inside
     * {@link #ROW_Y_TOLERANCE} (3.0pt), treated as one physical line. That is the earliest
     * irreversible decision in the whole pipeline for that document; every stage downstream
     * (`looksLikeHeaderRow`, `wrappedHeaderAt`) then behaved correctly given already-corrupted
     * input. This is Phase 1 of Input Fate Accounting's next layer -- Physical Row Formation
     * Evidence -- deliberately starting BEFORE header/table detection, not at it.
     *
     * <p><b>Measurements, not a verdict -- deliberately named to say so.</b> {@code
     * maxPhysicalRowVerticalExtent} is named for what it measures, not for what it might mean: real
     * corpus evidence gathered while building this shows a large value does NOT reliably mean
     * something is wrong -- a real, working AU statement reaches 2.9pt, almost indistinguishable
     * from the real, confirmed-broken ICICI CC statement's 3.0pt. A name like "spread" or
     * "anomaly" would have implied a verdict this single number cannot support. The same caution
     * applies to {@code maxCellsInRow}: on its own a maximum can describe two very different
     * distributions equally (one unusually large row among many normal ones, vs. every row running
     * large) -- {@code totalPhysicalCells} and {@code averageCellsPerRow} exist so a future reader
     * is not left guessing which shape produced the maximum. No validator reads any of this yet, on
     * purpose -- see "Evidence before capability": a specific mechanism for what counts as anomalous
     * needs more real documents establishing it, the same gate every other capability in this file
     * was built under.
     *
     * @param textRuns                      {@code positionedText.size()} -- the raw input {@code
     *                                      groupIntoRows} started from.
     * @param physicalRowsCreated           how many physical rows {@code groupIntoRows} produced.
     * @param totalPhysicalCells            sum of every row's own member count, across the whole
     *                                      document -- together with {@code physicalRowsCreated},
     *                                      this is what {@code averageCellsPerRow} is derived from.
     * @param averageCellsPerRow            {@code totalPhysicalCells / physicalRowsCreated}, zero
     *                                      when there are no rows -- the context {@code
     *                                      maxCellsInRow} alone cannot provide: a document whose
     *                                      rows are mostly small with one large outlier looks
     *                                      identical to a maximum-only reading of a document whose
     *                                      rows are uniformly large, and those are different shapes.
     * @param maxCellsInRow                 the largest single row's member count, across the whole
     *                                      document -- an oversized row is the shape an over-merge
     *                                      (of either kind: many genuinely-adjacent cells, or
     *                                      several unrelated elements) takes.
     * @param maxPhysicalRowVerticalExtent  the largest y-difference between any two members of the
     *                                      SAME formed row, across the whole document -- zero when
     *                                      every row's members share one y (the common case,
     *                                      confirmed on a real document), positive whenever {@code
     *                                      groupIntoRows} joined members that were not printed on
     *                                      the exact same baseline.
     * @param cellCountDistribution         row size (cell count) mapped to how many rows had that
     *                                      size, across the whole document -- the strongest signal
     *                                      found while building this, and the reason it is kept in
     *                                      the evidence itself rather than left for a caller to
     *                                      recompute from raw rows. A real, measured example of what
     *                                      it can show that {@code maxCellsInRow} alone cannot: on a
     *                                      real ICICI CC statement, size 7 appears in exactly ONE
     *                                      row -- {@code {..., 5=4, 7=1}}, nothing at size 6 at all
     *                                      -- while on real AU and BOB statements, each document's
     *                                      own largest row size recurs 3-4 times ({@code {..., 5=7,
     *                                      6=4}}, {@code {..., 5=1, 6=3}}). Reported as an observed
     *                                      difference, not a rule: one broken document and two
     *                                      working ones is not enough evidence to define what
     *                                      "recurs" or "singleton" means in general, only enough to
     *                                      say this document's own distribution looked different
     *                                      from those two documents' own distributions.
     */
    public record PhysicalRowFormationEvidence(int textRuns, int physicalRowsCreated, int totalPhysicalCells,
                                                 double averageCellsPerRow, int maxCellsInRow,
                                                 float maxPhysicalRowVerticalExtent,
                                                 Map<Integer, Integer> cellCountDistribution) {}

    public record LocatedDocument(List<LocatedSection> sections,
                                   PhysicalRowFormationEvidence physicalRowFormationEvidence) {}

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
        int lastPageIndex = -1;
        for (PositionedText t : positionedText) lastPageIndex = Math.max(lastPageIndex, t.pageIndex());
        if (ctx != null) ctx.recordPages(lastPageIndex + 1);
        List<List<PositionedText>> rows = groupIntoRows(positionedText);
        PhysicalRowFormationEvidence physicalRowFormationEvidence =
                measurePhysicalRowFormation(positionedText.size(), rows);
        // Computed once, up front, for hasDateValue's yearless-date fallback (see that method's
        // own doc comment) -- every call site below reads this rather than recomputing it per row.
        Map<Integer, Set<Integer>> yearsByPage = yearsByPage(rows);

        List<LocatedSection> sections = new ArrayList<>();
        List<String> pendingAuxiliary = new ArrayList<>();
        // Row-accounting evidence: physical rows that had transaction shape (a date-shaped cell
        // and a decimal-amount cell on the same line -- see isTransactionShapedRow) but were about
        // to be dropped with no trace at all. Threaded and reset exactly like pendingAuxiliary --
        // see closeCurrentSection.
        List<DroppedCandidateRow> pendingDroppedCandidates = new ArrayList<>();
        // Header-reconstruction evidence: rows that failed a multi-line header merge while already
        // carrying strong transaction-ledger vocabulary (see recordIfHeaderReconstructionCandidate).
        // Threaded and reset exactly like pendingDroppedCandidates -- see closeCurrentSection, which
        // is the only place this turns into a HeaderReconstructionFinding, once the section's own
        // accepted column count is known.
        List<java.util.Set<String>> pendingHeaderReconstructionVocab = new ArrayList<>();
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
        // Set by the ACCOUNT_IDENTITY_LINE block below when an identity line appears mid-section
        // and does not confirm as a repeat of currentSectionAccountId -- deliberately does NOT
        // close the section right there (that line might just be trailing restatement text before
        // a differently-shaped table, not a new account starting). Consulted only at the next
        // header event, which is where sections are actually created -- see the header-diff block.
        boolean pendingIdentityMismatch = false;
        String pendingAccountIdCandidate = null;
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
        // TRAILING_CONTENT_SUPPRESSED (ILLUSTRATIVE_BLOCK_SUPPRESSED / TRANSACTION_TABLE_CLOSED).
        // One-way: once either trigger below is seen, every row for the REST OF THE DOCUMENT is
        // treated the same as today's dateless no-header-found rows -- folded into
        // pendingAuxiliary, never a header, never a new section. Not a resume-on-next-marker
        // state machine: on every real document either trigger exists for, real content never
        // resumes once it fires (it runs to the end of the statement), and a one-way gate is
        // meaningfully simpler to reason about than tracking where the suppressed content ends.
        // If a future real document needs resumption, that is new evidence to design against, not
        // something to guess at now.
        //
        // Four independent triggers share this one flag rather than each getting its own, because
        // they mean the same thing structurally ("nothing genuine follows this line") even though
        // they're evidenced from different real documents and get their own capability names (see
        // TRAILING_CONTENT_TRIGGERS below) so it stays visible which one fired. See
        // docs/architecture/system-design/transaction-boundary-phase2a-investigation.md for the
        // shared Phase 2A/2C investigation this whole family of triggers comes from.
        boolean trailingContentSuppressed = false;

        // PAGE_LEGEND_BLOCK_SUPPRESSED. The resumption case trailingContentSuppressed's own comment
        // above anticipated: a real SBI credit-card statement prints a legal/legend block ("Transactions
        // highlighted in grey color...", the "C=Credit ; D=Debit..." abbreviation key, an "Important
        // Messages" heading, then open-ended late-payment-charges prose) at the BOTTOM OF EVERY PAGE,
        // not once at the true end of the table -- unlike TRAILING_CONTENT_TRIGGERS' own markers
        // (MITC, "End of Statement", etc.), which really do mean nothing genuine follows for the rest
        // of the document. Left unrecognized, each of this block's lines has no date and no
        // structural meaning of its own, so the ordinary trailing-continuation merge glues the whole
        // block onto the last real transaction above the page break -- confirmed on the real document:
        // "UPI-VMPL DEL 24 390.00" gained the entire legend paragraph as if it were a wrapped
        // description. Unlike trailingContentSuppressed, this resets the moment a genuine header is
        // recognized again (repeated or new) -- see every reset site below -- so the real transactions
        // that resume on the NEXT page are not silently discarded along with the boilerplate between
        // them and the last one.
        boolean pageLegendBlockActive = false;

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

            if (trailingContentSuppressed) {
                pendingAuxiliary.add(rowLine);
                continue;
            }
            int rowPageIndex = row.isEmpty() ? -1 : row.get(0).pageIndex();
            String trailingContentTrigger = trailingContentTriggerCapability(rowLine, rowPageIndex, lastPageIndex);
            if (trailingContentTrigger != null) {
                trailingContentSuppressed = true;
                // Closes whatever REAL section is open exactly the same way the header-signature
                // fallback below does (flush pendingLeading, stage the section) -- a document with
                // a genuine header-based table followed by this appendix/closing marker must keep
                // that real section, not lose it along with the boilerplate that follows.
                if (currentRows != null) {
                    PendingState closed = closeCurrentSection(currentRows, pendingLeading, headerNames,
                            pendingAuxiliary, pendingDroppedCandidates, pendingHeaderReconstructionVocab, sections, ctx);
                    pendingAuxiliary = closed.auxiliary();
                    pendingDroppedCandidates = closed.droppedCandidates();
                    pendingHeaderReconstructionVocab = closed.headerReconstructionVocab();
                    currentRows = null;
                }
                // This explicit boundary supersedes any still-unresolved ACCOUNT_IDENTITY_LINE
                // mismatch -- see the header-diff block's own identityContradicts check. Left set,
                // it would still be consulted by the next real header this document finds, forcing
                // a split that has nothing to do with the identity line that set it.
                pendingIdentityMismatch = false;
                pendingAccountIdCandidate = null;
                recordTrailingContentTrigger(ctx, trailingContentTrigger);
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
                // A marker banner is a stronger, explicit identity signal than a plain
                // ACCOUNT_IDENTITY_LINE -- it settles the question either way (same account
                // confirmed, or a new one named outright), so any earlier still-unresolved
                // mismatch this document hasn't reached a header for yet is now moot. Left set, it
                // would still be consulted by the next real header, forcing a split that has
                // nothing to do with the identity line that originally set it.
                pendingIdentityMismatch = false;
                pendingAccountIdCandidate = null;
                if (sameAccountBannerRepeated) {
                    if (ctx != null) ctx.record("REPEATED_ACCOUNT_BANNER");
                    // Row-accounting evidence: this line is about to be discarded with NO other
                    // trace at all (unlike the "different account" path below, whose banner line
                    // survives into the new section's own auxiliary text) -- the one case in this
                    // block that's actually silent.
                    recordIfTransactionShaped(row, "REPEATED_ACCOUNT_BANNER", pendingDroppedCandidates);
                    continue; // repeated per-page banner for the account already in progress
                }
                if (currentRows != null) {
                    PendingState closed = closeCurrentSection(currentRows, pendingLeading, headerNames,
                            pendingAuxiliary, pendingDroppedCandidates, pendingHeaderReconstructionVocab, sections, ctx);
                    pendingAuxiliary = closed.auxiliary();
                    pendingDroppedCandidates = closed.droppedCandidates();
                    pendingHeaderReconstructionVocab = closed.headerReconstructionVocab();
                    if (ctx != null) ctx.record("COMPOSITE_STATEMENT");
                } else {
                    // Bug fix, 2026-08-29: pendingAuxiliary used to be reset to empty here, discarding
                    // whatever front matter (page-1 letterhead: branch name, portfolio-summary figures,
                    // etc.) had accumulated before the document's FIRST section marker. A marker that
                    // closes a PRIOR section (the currentRows != null branch above) already carries its
                    // pendingAuxiliary forward via closeCurrentSection -- the first marker has no prior
                    // section to attribute front matter to, but that is a reason to hand it to the
                    // section this marker is about to OPEN, not to discard it outright. Found via a
                    // real HSBC composite statement whose own "Branch Name"/"Total Deposits and
                    // Investments" letterhead fields were silently lost this way once the
                    // PdfTableLocator.lineOf X-ordering fix (docs/superpowers/specs/
                    // 2026-08-29-lineof-x-ordering-fix-design.md) let this document's SECTION_MARKER
                    // banner correctly match for the first time -- previously it never matched at all,
                    // so this discard path was never reached for this document.
                    pendingDroppedCandidates = new ArrayList<>();
                    pendingHeaderReconstructionVocab = new ArrayList<>();
                }
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
                pageLegendBlockActive = false;
                pendingAuxiliary.add(rowLine);
                continue;
            }

            // A plain identity line with no SECTION_MARKER-shaped banner around it -- the composite-
            // statement case that banner path cannot see at all. Real bug, found by adversarial
            // review: two different accounts sharing the same column layout, each identified only by
            // an ordinary "Account Number: <digits>" line, previously fell through to the
            // header-signature-equality fallback below with NO identity check whatsoever -- the
            // second account's transactions were silently appended into the first account's section.
            // Mirrors the SECTION_MARKER block above exactly, reusing accountIdentityIn unchanged;
            // deliberately placed right after it and before WRAPPED_HEADER for the same reason that
            // block already documents -- a structurally meaningful line must be classified before
            // anything asks whether it looks like half a header.
            Matcher accountIdentityLine = ACCOUNT_IDENTITY_LINE.matcher(rowLine);
            if (accountIdentityLine.find()) {
                String plainAccountId = accountIdentityIn(rowLine);
                boolean sameAccountIdentityRepeated = currentRows != null
                        && plainAccountId != null
                        && plainAccountId.equals(currentSectionAccountId);
                if (sameAccountIdentityRepeated) {
                    if (ctx != null) ctx.record("REPEATED_ACCOUNT_BANNER");
                    // Reconfirming the section's own id clears any stale mismatch an EARLIER,
                    // different-looking identity line left pending (e.g. a stray misread digit
                    // run) -- found by adversarial review. Left set, a same-shaped header right
                    // after this line would still be forced to split on account of the
                    // already-superseded earlier mismatch, not this line's own confirmation.
                    pendingIdentityMismatch = false;
                    pendingAccountIdCandidate = null;
                    pendingAuxiliary.add(rowLine);
                    continue;
                }
                if (currentRows == null) {
                    // No section open yet -- nothing to protect against merging into. Remember
                    // this as the identity for whatever section opens next, same as before.
                    currentSectionAccountId = plainAccountId;
                    pendingAuxiliary.add(rowLine);
                    continue;
                }
                // A section IS open and this line did not confirm as a repeat of its account --
                // but do NOT close here. Found by testing against a real composite statement
                // (hdfc-composite-deposit-schedules): an "Account Number:" line restating the
                // SAME account routinely appears as trailing text within an already-open section
                // (a deposit-schedule table's own summary block) before a genuinely different,
                // differently-shaped table follows -- closing immediately here split that
                // trailing text onto the wrong side of the real boundary, losing it from the
                // section it actually belonged to. This line is not proof a new account starts
                // here; it is only proof that the account is no longer CONFIRMED as unchanged.
                // Defer to the next header event -- see the header-diff block below, which is
                // where sections are actually created and where currentRows genuinely closes.
                pendingIdentityMismatch = true;
                pendingAccountIdCandidate = plainAccountId;
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
            boolean rowAlreadyScoresAlone = looksLikeHeaderRow(row);
            WrappedHeader wrapped = wrappedHeaderAt(rows, rowIndex, rowAlreadyScoresAlone);
            if (wrapped != null) {
                headerRow = wrapped.row();
                wrappedHeaderLines = wrapped.extraLines();
            } else if (!rowAlreadyScoresAlone && carriesNoDataValue(row)) {
                // Header-reconstruction evidence: this row did not score as a header alone AND a
                // multi-line merge with what follows it failed. carriesNoDataValue is required here
                // too, not just inside wrappedHeaderAt itself -- without it, this branch is also
                // reached by every ORDINARY transaction data row in the document (wrappedHeaderAt
                // returns null immediately for any row carrying a date or number, which is every
                // real transaction), and a narration that happens to mention two amount words
                // ("UPI CREDIT TRANSFER BALANCE ADJUSTMENT") would wrongly record evidence against
                // a row that was never a header candidate at all. Recorded here, not decided here --
                // see recordIfHeaderReconstructionCandidate's own doc comment for the vocabulary
                // gate, and closeCurrentSection for the only place this can turn into a real finding
                // (once this section's own accepted column count is known).
                recordIfHeaderReconstructionCandidate(row, pendingHeaderReconstructionVocab);
            }
            if (looksLikeHeaderRow(headerRow)) {
                row = headerRow;
                if (wrappedHeaderLines > 0) {
                    rowIndex += wrappedHeaderLines;
                    if (ctx != null) ctx.record("WRAPPED_HEADER");
                    if (wrapped.admittedInteriorTierColumns() && ctx != null) {
                        ctx.record("WRAPPED_HEADER_INTERIOR_TIER_COLUMNS");
                    }
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
                // Bug fix, verified against a real SBI credit-card statement: a "for Statement
                // Period: ..." caption prints on THIS page's repeat of the header's own physical row
                // (a per-page rendering variance -- the same caption sits comfortably far from the
                // header on the document's first page instead). Left in row, it corrupted two
                // downstream steps that both need "only genuine column names" here, not just the one
                // buildHeaderColumns already guards (see its own containsEmbeddedDateRange comment):
                // headerSignature (just below) hashes the RAW cells, so this document's own repeated
                // header stopped matching itself and opened a spurious new section
                // (COMPOSITE_STATEMENT) on a single-account statement; and reconstructHeader's own
                // conflict gate (HeaderReconstructionEngineTest's
                // ...evenWithAnOrphanedCaptionOnTheAcceptedRow) treats every cell of headerRow AS
                // GIVEN as an "established anchor" a fill-empty fragment must stay clear of -- with
                // the caption still in there, the real "Transaction Details" fragment on the line
                // above sat within HEADER_WRAP_MAX_COLUMN_JOIN of it and was wrongly declined as if
                // it would rename the caption, losing the Description column (and with it every real
                // row's narration) on all 29 of that section's transactions. Stripped here, once,
                // before either consumer -- not inside buildHeaderColumns, which runs AFTER
                // headerSignature and is called a second time on reconstructHeader's own candidate
                // (seeing this row unfiltered there would also make buildHeaderColumns' own
                // orphanedHeaderRowText double-count this same caption).
                List<String> orphanedCaptions = new ArrayList<>();
                row = stripStandaloneEmbeddedDateRangeCaptions(row, orphanedCaptions);
                pendingAuxiliary.addAll(orphanedCaptions);
                Set<String> signature = headerSignature(row);
                // An unresolved ACCOUNT_IDENTITY_LINE mismatch overrides "same shape, keep
                // appending" -- this is the actual B1 danger zone: two different accounts sharing
                // one column layout. Without this check, a same-shaped header following a
                // contradicting identity line would silently fall through to REPEATED_HEADER and
                // its rows would be appended straight into the still-open (wrong) section.
                boolean identityContradicts = currentRows != null && pendingIdentityMismatch;
                if (currentRows != null && signature.equals(currentHeaderSignature) && !identityContradicts) {
                    if (ctx != null) ctx.record("REPEATED_HEADER");
                    pageLegendBlockActive = false;
                    continue; // repeated header of the table already in progress -- not a data row
                }
                if (currentRows != null) {
                    // A different header shape, or a same-shaped header with a contradicting
                    // identity line since this section opened -- fallback signal for a new section
                    // in a document without a banner line.
                    PendingState closed = closeCurrentSection(currentRows, pendingLeading, headerNames,
                            pendingAuxiliary, pendingDroppedCandidates, pendingHeaderReconstructionVocab, sections, ctx);
                    pendingAuxiliary = closed.auxiliary();
                    pendingDroppedCandidates = closed.droppedCandidates();
                    pendingHeaderReconstructionVocab = closed.headerReconstructionVocab();
                    if (ctx != null) ctx.record("COMPOSITE_STATEMENT");
                    // Required companion to the ACCOUNT_IDENTITY_LINE block above: THIS section
                    // (the one just closed) is being replaced. If a contradicting identity line
                    // caused it, carry that identity forward into the section now opening -- it's
                    // the whole reason this split happened. Otherwise (a plain shape change with no
                    // identity signal seen) the new section's identity is genuinely unknown -- reset
                    // rather than let it inherit the closed section's id. Deliberately scoped to
                    // only this branch, not the fallthrough below: when currentRows is ALREADY null
                    // here (the very start of the document, or a header following a confirmed-open
                    // identity line), currentSectionAccountId may have just been set moments ago and
                    // must survive into this header so a later repeat check has something correct to
                    // compare against -- resetting unconditionally here was a real bug, found in
                    // self-testing: it wiped out an identity set one row earlier before it was ever
                    // compared, turning a genuine same-account repeat into a false split.
                    currentSectionAccountId = identityContradicts ? pendingAccountIdCandidate : null;
                }
                pendingIdentityMismatch = false;
                pendingAccountIdCandidate = null;
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
                // orphanedHeaderRowText is captured into a LOCAL list here, not pendingAuxiliary
                // directly -- buildHeaderColumns can run a second time just below, on
                // reconstructHeader's candidate, which always starts from every non-blank fragment
                // of THIS SAME row verbatim (see reconstructHeader's own doc comment). Without a
                // local list, an orphaned caption sitting on the header's own physical row would be
                // scanned by containsEmbeddedDateRange twice and appended to auxiliaryText twice --
                // a real duplicate-text bug found in self-review, not yet backed by a real corpus
                // trace (none in the current committed corpus combines an embedded-date-range
                // caption with a header that also independently triggers reconstruction), but
                // reproduced directly via HeaderReconstructionEngineTest.
                // orphanedCaptionOnAReconstructedHeadersOwnRow_isNotDuplicatedIntoAuxiliaryText.
                List<String> orphanedHeaderRowText = new ArrayList<>();
                buildHeaderColumns(row, headerNames, headerAnchors, headerEnds, rows, rowIndex, ctx, orphanedHeaderRowText);
                // Header Quality Gate + Reconstruction Engine (Phase 2E.2 prototype) -- runs AFTER
                // the recovery pipeline just above, evaluating what it actually produced, never
                // before it. recoverMissingDescriptionColumn already tries to solve exactly this
                // class of problem (a fragment one physical line above the header, naming a column
                // the accepted line is missing) and correctly declines a single-cell candidate as
                // too ambiguous on its own evidence alone -- see that method's own doc comment and
                // HeaderColumnRecoveryTest.loneSingleCellCaptionLine_isNotRecoveredAsAColumn, a real
                // regression this exact interaction found on the same real SBI statement. This
                // engine is reached only when that narrower, evidence-light mechanism has already
                // had its chance and the header is STILL weak -- its own extra evidence (row
                // compatibility against the section's real upcoming data, design doc §4.9) is what
                // makes admitting a single-cell fragment safe in exactly the cases the older
                // mechanism has to decline for lack of any such evidence. Also why this cannot merge
                // two sections that should stay separate: the REPEATED_HEADER/closeCurrentSection
                // decision above has already run, against the ORIGINAL row's signature -- by the
                // time this reconstructs anything, which section is open or new is already decided.
                if (headerQualityWeak(headerNames, headerAnchors, headerEnds, rows, rowIndex, wrappedHeaderLines)) {
                    List<PositionedText> reconstructed = reconstructHeader(row, rows, rowIndex, wrappedHeaderLines);
                    if (reconstructed != null) {
                        headerNames = new ArrayList<>();
                        headerAnchors = new ArrayList<>();
                        headerEnds = new ArrayList<>();
                        orphanedHeaderRowText = new ArrayList<>();
                        buildHeaderColumns(reconstructed, headerNames, headerAnchors, headerEnds, rows, rowIndex, ctx, orphanedHeaderRowText);
                        if (ctx != null) ctx.record("HEADER_RECONSTRUCTED");
                    }
                }
                pendingAuxiliary.addAll(orphanedHeaderRowText);
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
                pageLegendBlockActive = false;
                continue;
            }

            // Bug fix, 2026-08-31: pageLegendBlockActive's only resume signal used to be a newly
            // recognized header row (see the pageLegendBlockActive = false call sites above). That
            // assumption silently broke on any real document whose per-page legend/footer sits
            // under a table that does NOT reprint its header on every page -- a real HDFC savings
            // statement (24 pages) prints its header exactly once, on page 1, and never again; once
            // its own per-page footer ("Closing balance includes funds earmarked...", part of
            // PAGE_LEGEND_BLOCK_START above) set this flag on page 1, there was no "next header"
            // ever again to reset it, and every one of the document's remaining 23 pages -- 231 of
            // 243 real transactions -- was silently suppressed for the rest of the document. A
            // second, independent resume signal: a row that is transaction-shaped
            // (isTransactionShapedRow -- some cell parses as a date AND some cell parses as a
            // decimal amount, not necessarily the same cell; the same admission test the headerless
            // path already trusts) is treated as proof the block has ended, with or without a header
            // row in between.
            //
            // Known limitation, unevidenced against the real corpus so deliberately not solved
            // speculatively: none of the three real PAGE_LEGEND_BLOCK_START sentences (SBI, Kotak,
            // HDFC -- see that pattern's own doc comment) are documented as being followed by a
            // date-and-amount-bearing row within their own legend/disclaimer block, but nothing
            // structurally prevents one -- a summary panel with an effective-date field and a
            // nearby currency figure on the same physical line would satisfy isTransactionShapedRow
            // without being a real transaction, resuming suppression a row early and admitting that
            // row as a spurious transaction. Revisit if a real document ever shows this shape.
            if (pageLegendBlockActive && isTransactionShapedRow(row)) {
                pageLegendBlockActive = false;
            }

            if (currentRows == null) {
                // Row-accounting evidence: no section has opened yet, so this row is about to be
                // folded into pendingAuxiliary with no trace at all -- the same silent-loss shape
                // BUCKET_EMPTY and PAGE_FOOTER_OR_CLOSING_MARKER below already guard against, just
                // before the document's first header is ever found. Real motivating case: a real
                // HSBC credit-card statement whose only transaction sits on a page whose column
                // header renders as part of a background image with no extractable text at all
                // (confirmed by direct inspection, not row-count inference), while a later,
                // unrelated page's table header IS extractable and becomes the section that wins.
                //
                // Uses looksLikeFinancialActivityCandidate, NOT isTransactionShapedRow -- measured
                // against that real statement, isTransactionShapedRow returns false here, because
                // its date prints without a year ("30JUN") and CsvParser.parseDate requires one.
                // isTransactionShapedRow is also what inferHeaderlessSection uses to decide what to
                // STAGE, so loosening its date check to catch this would loosen real import
                // behaviour, not just evidence -- a materially different risk. See
                // looksLikeFinancialActivityCandidate's own doc comment for the evidence-only/
                // staging split this exists to preserve, and PRE_HEADER_ACTIVITY_CANDIDATE
                // specifically for why this needs a THIRD signal (description-like text) that
                // BUCKET_EMPTY/PAGE_FOOTER_OR_CLOSING_MARKER/REPEATED_ACCOUNT_BANNER do not.
                recordIfFinancialActivityCandidate(row, "PRE_HEADER_ACTIVITY_CANDIDATE", pendingDroppedCandidates);
                pendingAuxiliary.add(rowLine);
            } else if (pageLegendBlockActive) {
                // Still inside the legend/disclaimer block -- see pageLegendBlockActive's own doc
                // comment. Every line here is boilerplate until the next header resets the flag.
                // Deliberately NOT added to pendingAuxiliary -- same choice PAGE_FOOTER makes just
                // below, and for the same reason found here specifically: this exact legend line
                // spells out "Monthly Installments"/"Total Amount Due" as abbreviation-key LABELS,
                // not genuine statement fields, and ProductDiscovery reads auxiliaryText as if it
                // were the latter -- surfaced by testing this fix against the real document, where
                // routing the block there flipped the primary cardholder's own section from a
                // confident CREDIT_CARD detection to UNKNOWN (INSTALLMENT_FIELD/EMI_FIELD/
                // MINIMUM_DUE_FIELD all reading as CONTRADICTORY evidence against every candidate
                // product, credit card included). recordIfTransactionShaped below is this content's
                // own "never lose information" trace, same as PAGE_FOOTER_OR_CLOSING_MARKER's.
                continue;
            } else if (PAGE_LEGEND_BLOCK_START.matcher(rowLine).find()) {
                pageLegendBlockActive = true;
                if (ctx != null) ctx.record("PAGE_LEGEND_BLOCK_SUPPRESSED");
                recordIfTransactionShaped(row, "PAGE_LEGEND_BLOCK_SUPPRESSED", pendingDroppedCandidates);
                continue;
            } else if (PAGE_FOOTER.matcher(rowLine).find() || STATEMENT_CLOSING_MARKER.matcher(rowLine).find()) {
                if (ctx != null) ctx.record("PAGE_BOUNDARY_ISOLATION");
                // Row-accounting evidence: acknowledged, real risk (see this pattern's own doc
                // comment) -- a genuine transaction description that happens to also match this
                // loose page-footer shape would otherwise vanish with zero trace at all.
                recordIfTransactionShaped(row, "PAGE_FOOTER_OR_CLOSING_MARKER", pendingDroppedCandidates);
                continue; // a page-number line or closing marker is never a transaction or a continuation of one
            } else if (CREDIT_CARD_CATEGORY_HEADER.matcher(rowLine).find()) {
                // See CREDIT_CARD_CATEGORY_HEADER's own doc comment. Dropped outright, not merged
                // either direction and not buffered as leading/trailing narration -- a category
                // divider describes the table, not one transaction in it, so no single row is the
                // right place for it to land.
                if (ctx != null) ctx.record("TRANSACTION_CATEGORY_HEADER_SUPPRESSED");
                recordIfTransactionShaped(row, "TRANSACTION_CATEGORY_HEADER_SUPPRESSED", pendingDroppedCandidates);
                continue;
            } else {
                Set<Integer> rowCandidateYears = yearsByPage.getOrDefault(rowPageIndex, Set.of());
                // Substituted before bucketRow for the identical reason inferHeaderlessSection's
                // own call does (see substituteYearlessDates' own doc comment): without it, the
                // RAW yearless text ("May 01") reaches bucketRow's stored value, and
                // TransactionNormalizer downstream rejects it through the same CsvParser.parseDate
                // call that has no yearless awareness at all -- hasDateValue recognizing it a few
                // lines up only gets the row treated as an anchor, it does not fix what ends up
                // stored in that anchor's own date cell.
                List<PositionedText> resolvedRow = substituteYearlessDates(row, rowCandidateYears);
                Map<String, String> bucketed = bucketRow(resolvedRow, headerNames, headerAnchors, headerEnds, ctx,
                        rowCandidateYears);
                if (bucketed.isEmpty()) {
                    // Row-accounting evidence: the row survived every structural gate up to
                    // bucketing and still produced literally nothing -- the strongest "we don't
                    // know what happened to this line" signal this loop has.
                    recordIfTransactionShaped(row, "BUCKET_EMPTY", pendingDroppedCandidates);
                    continue;
                }

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

                if (hasDateValue(bucketed, yearsByPage.getOrDefault(rowPageIndex, Set.of()))) {
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
                } else if (currentRows.isEmpty() && (!isNarrationOnly(bucketed)
                        // A row that populates MORE THAN ONE column, even carrying no date/number
                        // value of its own, reads as a genuine structured summary/identity row (a
                        // real ICICI Credit Card statement's own masked cardholder-identity line,
                        // two columns wide) rather than free-text transaction narration -- which
                        // never spans more than the one column it was typed into. Found regression-
                        // testing the lookahead below against the full real corpus: without this,
                        // a genuine identity row got deferred and merged into an unrelated later
                        // transaction merely because one happened to exist within the lookahead
                        // window, corrupting that transaction's own row.
                        || bucketed.size() > 1
                        // Content alone still cannot tell "defer -- a real transaction is coming"
                        // apart from "stand alone -- nothing ever claims this," verified against
                        // real SBI Card (a header missing its narration column squishes a real
                        // transaction's date and merchant name into one bucketed cell that fails
                        // the same numeric check a genuine caption does) and real AU Credit Card
                        // (a perfectly well-formed header can still open a section with no
                        // transaction in it at all -- an EMI-disclosure panel, pure narration). The
                        // only signal that told these apart from the one real ICICI savings
                        // document this whole gate exists for: whether a genuine hasDateValue row
                        // actually follows, within a bounded lookahead.
                        || !anchorFollowsWithinSection(rows, rowIndex + 1, headerNames, headerAnchors, headerEnds,
                                yearsByPage)
                        // A real anchor existing somewhere ahead still is not enough on its own --
                        // found on a real HDFC Credit Card statement: a genuine two-line merchant
                        // caption, printed LOOSELY spaced above its own tightly-spaced pair with the
                        // transaction below, is single-column and does have an anchor within the
                        // lookahead window, yet baseline stands its first line alone and this
                        // document's own regression-guard test locks that in. What told it apart
                        // from the one real ICICI savings document this gate exists for: that
                        // document's narration sits at the SAME line pitch as the anchor's own
                        // wrapped continuation (5.1pt vs 5.0pt) -- a single, consistently-set block
                        // -- while HDFC's first line's gap to its second (11pt) is nothing like the
                        // second line's gap to the real anchor (4.3pt), a genuine pitch break. Only
                        // checked when something sits between this row and the anchor -- a row
                        // immediately adjacent to the anchor has no prior chain pitch to break.
                        || !firstHopIsPitchConsistentOrDirect(rows, rowIndex, headerNames, headerAnchors, headerEnds,
                                yearsByPage))) {
                    // Nothing to attach to at all yet (e.g. an "Opening Balance" summary line
                    // before any real transaction) -- stands on its own, same as before. Closed to
                    // trailing continuation immediately: a summary row isn't a transaction, and
                    // narration that follows it belongs to the FIRST real transaction as leading
                    // content, not to this row as trailing content.
                    //
                    // Gated on carrying a real value, spanning multiple columns, or nothing real
                    // ever following (see above), found on a real ICICI savings statement whose
                    // table has no such summary row at all: the very first bucketed content is the
                    // first transaction's OWN leading narration, with nothing yet in currentRows.
                    // Before this gate, it fell into this branch and stood alone as a bare, dateless
                    // row -- the same shape this class's leading-narration buffer already exists to
                    // solve everywhere else in a section, just never reached because
                    // currentRows.isEmpty() is checked first. samePage is false here (lastRowPage is
                    // still null, reset when this section opened), so a narration-only row now
                    // safely falls through this branch and the trailing-continuation branch below it
                    // (whose own samePage check is also false) into the leading-narration branch at
                    // the bottom, unchanged.
                    currentRows.add(bucketed);
                    lastRowPage = row.isEmpty() ? lastRowPage : row.get(0).pageIndex();
                    lastRowY = row.isEmpty() ? lastRowY : row.get(0).y();
                    blockPitch = null;
                    blockSeparation = null;
                    trailingCountSinceLastAnchor = MAX_TRAILING_CONTINUATION_ROWS;
                } else if (!currentRows.isEmpty() && samePage
                        // Explicit currentRows.isEmpty() guard, found via a real corpus crash: with
                        // TWO+ consecutive narration-only lines before the first anchor, the first
                        // one's own leading-narration bookkeeping already sets lastRowPage/lastRowY
                        // (the same fields every branch here updates), which makes samePage true for
                        // the second one even though currentRows is still genuinely empty --
                        // crashing this branch's own currentRows.get(currentRows.size() - 1) on an
                        // empty list. "Trailing continuation" only means anything once something
                        // real exists to trail from.
                        && (continuesTheBlock(row, lastRowY, blockPitch, blockSeparation,
                                    trailingCountSinceLastAnchor)
                            || isChequeReferenceTrailer(rowLine)
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
                    if (ctx != null) {
                        ctx.record("WRAPPED_DESCRIPTION");
                        if (isChequeReferenceTrailer(rowLine)) ctx.record("CHEQUE_REFERENCE_TRAILER_RECOVERED");
                    }
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
            PendingState closed = closeCurrentSection(currentRows, pendingLeading, headerNames,
                    pendingAuxiliary, pendingDroppedCandidates, pendingHeaderReconstructionVocab, sections, ctx);
            // closeCurrentSection's auxiliary is non-empty only when it just suppressed a
            // payment-summary panel instead of staging a section (the ordinary path always
            // returns a fresh, empty list for whatever section comes next). This is the end of the
            // document, so there IS no next section to fold that demoted text into -- without
            // this, a document whose FINAL section is a payment-summary panel would silently lose
            // it, the exact class of bug e65af76 fixed elsewhere in auxiliaryText handling. Only
            // added when a real section already exists: a document whose ONLY content was a
            // suppressed panel must stay sections.isEmpty() so the headerless/two-line fallbacks
            // below still get their chance. Out of Phase 1 scope: this leftover's own dropped-
            // candidate evidence (closed.droppedCandidates()) has nowhere left to fold into either,
            // and this end-of-document edge case is rare enough not to warrant one -- documented,
            // not silently assumed complete, same as this class's other deferred drop points.
            if (!closed.auxiliary().isEmpty() && !sections.isEmpty()) {
                sections.add(new LocatedSection(closed.auxiliary(), List.of(), ExtractionEvidence.NONE));
            }
        } else if (trailingContentSuppressed && !pendingAuxiliary.isEmpty() && !sections.isEmpty()) {
            // Related to the payment-summary-panel fold just above (same e65af76 motivation: don't
            // silently drop trailing auxiliary text) but NOT the same fix -- appending as its own
            // new section, the way that case does, was tried first and rejected: verified via
            // CorpusProbe against the real Axis document TRANSACTION_TABLE_CLOSED is evidenced from,
            // it produces a second, zero-row section with no account identity of its own, which
            // FinancialProductClassifier cannot confidently classify (UNKNOWN, productNeedsReview)
            // -- a phantom "second account" the review screen would ask the user to name, for
            // content that was never a second account, just this same one's own trailing
            // boilerplate. Merged into the LAST real section's own auxiliary text instead, so
            // whatever identity/product evidence that section already carries stays attached to the
            // same section rather than orphaned on a new one with none. currentRows is already null
            // here because the illustrative/closing-marker trigger above already closed the real
            // section and started accumulating everything from that point into pendingAuxiliary
            // directly, never through currentRows at all.
            LocatedSection last = sections.get(sections.size() - 1);
            List<String> mergedAuxiliary = new ArrayList<>(last.auxiliaryText());
            mergedAuxiliary.addAll(pendingAuxiliary);
            sections.set(sections.size() - 1,
                    new LocatedSection(mergedAuxiliary, last.rows(), last.evidence()));
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
        return new LocatedDocument(sections, physicalRowFormationEvidence);
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

    // CHEQUE_REFERENCE_TRAILER_RECOVERED. A real Canara Bank statement (Manas Chaturvedi's own
    // upload -- a different real document from the one MAX_TRAILING_CONTINUATION_ROWS was tuned
    // against) wraps EVERY transaction's own narration across 4 dateless lines before its date
    // row, then closes with up to THREE dateless lines after it: a reference-number continuation,
    // a time-of-day line, and finally a bare "Chq: <the same reference>" line -- one more than
    // MAX_TRAILING_CONTINUATION_ROWS admits. That third line was falling through to the
    // leading-narration branch and attaching to the NEXT transaction instead: confirmed via
    // CorpusGarbageSweep, every "Chq: <number>" trailer in this real document ended up prepended to
    // the wrong transaction's description, its own reference number never matching what followed it.
    //
    // Raising MAX_TRAILING_CONTINUATION_ROWS itself was rejected for the same reason that constant's
    // own doc comment already gives for Bandhan Bank: a DIFFERENT real Canara document needs the
    // boundary at exactly 2, where its own third dateless row genuinely IS the next transaction's
    // leading narration -- the two real documents are irreconcilable by count alone. This is a
    // content-shape exception instead, not a wider cap: a bare "Chq: <digits>" line can only ever be
    // a reference trailer for the transaction printed immediately above it -- a cheque or reference
    // number describes a completed instrument, never introduces the next one -- so it is always safe
    // to admit as one more trailing continuation regardless of the count already reached. Unlike
    // continuesTheBlock's pitch-based extension, this needs no evidence the document "separates its
    // blocks": the shape alone is the evidence.
    private static final Pattern CHEQUE_REFERENCE_TRAILER = Pattern.compile("(?i)^\\s*chq[:.]?\\s*\\d+\\s*$");

    private boolean isChequeReferenceTrailer(String rowLine) {
        return CHEQUE_REFERENCE_TRAILER.matcher(rowLine).matches();
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

    /** Below this many rows, a candidate is even considered for {@link
     *  #looksLikePaymentSummaryPanel} at all -- both real motivating documents produce exactly 2.
     *  A pure safety net, not the deciding signal: {@link #PAYMENT_SUMMARY_FIELD_PHRASES} is. An
     *  earlier version of this gate relied on row count plus "no description column" alone and
     *  broke real, already-supported recurring/fixed-deposit installment schedules -- those are
     *  ALSO short and ALSO lack a narration column (an installment record has no story to tell the
     *  way a transaction does), so that pair of signals could not tell a summary panel from a
     *  genuine small ledger. Found by the full test suite, not a real document: 24 failures and 6
     *  errors across CompositeMultiProductClassificationTest, TraceFixtureRegressionTest, and
     *  others the moment it ran, reverted before any of it reached a commit. */
    private static final int PAYMENT_SUMMARY_PANEL_MAX_ROWS = 2;

    /** How many distinct {@link #PAYMENT_SUMMARY_FIELD_PHRASES} must appear across a candidate's
     *  header before it counts -- the same two-signal floor {@code MIN_CREDIT_CARD_TEXT_SIGNALS}
     *  in {@code PdfPreviewGenerator} already uses for the analogous "is this really a credit-card
     *  payment summary" question elsewhere in the pipeline (a different layer, so not shared code,
     *  but the same reasoning: one phrase could be a coincidence, two together are a real match). */
    private static final int MIN_PAYMENT_SUMMARY_FIELD_MATCHES = 2;

    /**
     * A credit card's own account-level snapshot fields -- due dates, credit/cash limits, the
     * amount currently owed -- restated once near the top of the statement, never as a repeating
     * per-row record. Deliberately phrase-level (not single words like "due" or "limit" alone,
     * which a genuine deposit-schedule column like "Instalment Amt Due" or "Closing Balance" could
     * otherwise share) and deliberately narrower than the full vocabulary {@code
     * CREDIT_CARD_TEXT_SIGNALS} in {@code PdfPreviewGenerator} uses for product classification --
     * this only needs the phrases that actually appeared as HEADER-LIKE labels on the two real
     * documents that motivate it, not everything that can appear in a payment-summary paragraph.
     */
    private static final List<String> PAYMENT_SUMMARY_FIELD_PHRASES = List.of(
            "total payment due", "minimum payment due", "payment due date", "total amount due",
            "minimum amount due", "minimum due", "due date", "statement generation date",
            "statement period", "credit limit", "cash limit");

    /**
     * A credit card's own payment-summary panel satisfies {@link #looksLikeHeaderRow}'s
     * date-plus-vocabulary check exactly like a real transaction table does -- it prints several
     * dates and financial-figure labels together -- so it gets read as a section of its own, one
     * row deep, immediately superseded by the real ledger's header a few lines later. Verified on
     * two real credit-card statements with otherwise unrelated layouts (a real Axis statement with
     * an explicit "PAYMENT SUMMARY" banner above one summary row, and a real HDFC statement with
     * no such banner at all, just a multi-column credit-limit/dues grid) -- the shared signal used
     * here is deliberately the phrase vocabulary both share, not the banner text HDFC lacks.
     *
     * <p>Requires ALL THREE: a small row count, no recognized narration column ({@link
     * #DESCRIPTION_COLUMN_LABELS}), AND at least {@link #MIN_PAYMENT_SUMMARY_FIELD_MATCHES}
     * distinct payment-summary phrases. The first two alone are NOT enough -- see {@link
     * #PAYMENT_SUMMARY_PANEL_MAX_ROWS}'s own doc comment for the real regression that taught this;
     * a genuine recurring/fixed-deposit installment schedule is also short and also lacks a
     * narration column, and only the phrase vocabulary tells the two apart.
     */
    private boolean looksLikePaymentSummaryPanel(List<String> headerNames, List<Map<String, String>> currentRows) {
        if (headerNames == null || currentRows.size() > PAYMENT_SUMMARY_PANEL_MAX_ROWS) return false;
        Set<String> matchedPhrases = new LinkedHashSet<>();
        for (String name : headerNames) {
            String normalized = CsvParser.normalizeHeaderCell(name);
            if (DESCRIPTION_COLUMN_LABELS.contains(normalized)) return false;
            for (String phrase : PAYMENT_SUMMARY_FIELD_PHRASES) {
                if (normalized.contains(phrase)) matchedPhrases.add(phrase);
            }
        }
        return matchedPhrases.size() >= MIN_PAYMENT_SUMMARY_FIELD_MATCHES;
    }

    /** Paired return for {@link #closeCurrentSection} -- {@code pendingAuxiliary}/
     *  {@code pendingDroppedCandidates}/{@code pendingHeaderReconstructionVocab} always travel
     *  together (all carry forward unchanged on suppression, all reset to fresh empty lists once a
     *  real section is staged), so returning them as one record keeps that pairing from drifting
     *  apart at a call site the way three separately-returned values could. */
    private record PendingState(List<String> auxiliary, List<DroppedCandidateRow> droppedCandidates,
                                 List<java.util.Set<String>> headerReconstructionVocab) {}

    /**
     * Column count below which an accepted header is treated as a suspiciously small fallback,
     * not just a genuinely small real table. Evidence threshold derived from a real corpus study
     * (24 real documents), not a universal rule about PDF statements -- a document with a real
     * "Date | Amount | Description" 3-column ledger is completely normal and must not be flagged
     * on column count alone (measured directly: several correct documents in the study corpus
     * stage against 4-column headers). This constant is read ONLY alongside {@link
     * #recordIfHeaderReconstructionCandidate}'s own vocabulary signal in {@link
     * #closeCurrentSection} -- see {@link HeaderReconstructionFinding}'s own doc comment for why
     * neither condition alone was trustworthy in the corpus measurement (a low column count with
     * no vocabulary evidence, or vocabulary evidence with a normal column count, both occur on
     * real, correctly-extracted documents). Six months from now, do not read a document clearing
     * this threshold as proof its header is wrong -- it is one of two required signals, not a
     * verdict by itself.
     */
    private static final int LOW_CONFIDENCE_TRANSACTION_HEADER_COLUMN_COUNT = 3;

    /** Closes whatever section is currently open: flushes any pending leading narration, then
     *  either stages it as a real {@link LocatedSection} or -- when {@link
     *  #looksLikePaymentSummaryPanel} says it looks like a misdetected payment-summary panel
     *  instead of a genuine transaction table -- folds its rows back into auxiliary text instead,
     *  the same "never lose information, just stop calling it a table" treatment {@link
     *  #inferTwoLineDateBlockSection}'s own unmatched rows already get.
     *
     *  <p>Also where {@link HeaderReconstructionFinding} is actually built, and the only place it
     *  can be: the section's own accepted column count (the union of every staged row's keys,
     *  matching {@code detectedColumns}'s already-established approach elsewhere in this package)
     *  is not known until every row belonging to it has been collected. Fires only when the
     *  column count clears {@link #LOW_CONFIDENCE_TRANSACTION_HEADER_COLUMN_COUNT} AND at least
     *  one qualifying row was recorded during the scan that just closed -- see {@link
     *  #recordIfHeaderReconstructionCandidate} for what qualifies.
     *
     *  @return the {@link PendingState} the caller should keep accumulating into for whatever
     *  comes next -- fresh empty lists after a real section is staged (its own evidence now
     *  belongs to that section), or the SAME lists, with the demoted rows appended to
     *  {@code auxiliary}, when suppressed -- carrying it forward is what lets a demoted panel's
     *  text still end up as auxiliary text on the NEXT (real) section once that one closes, rather
     *  than being silently dropped the moment the caller's own "start fresh" reset ran. */
    private PendingState closeCurrentSection(List<Map<String, String>> currentRows, Map<String, String> pendingLeading,
            List<String> headerNames, List<String> pendingAuxiliary,
            List<DroppedCandidateRow> pendingDroppedCandidates,
            List<java.util.Set<String>> pendingHeaderReconstructionVocab,
            List<LocatedSection> sections, DocumentContext ctx) {
        flushPendingLeading(currentRows, pendingLeading);
        if (looksLikePaymentSummaryPanel(headerNames, currentRows)) {
            for (Map<String, String> row : currentRows) {
                pendingAuxiliary.add(String.join(" ", row.values()));
            }
            if (ctx != null) ctx.record("PAYMENT_SUMMARY_PANEL_SUPPRESSED");
            return new PendingState(pendingAuxiliary, pendingDroppedCandidates, pendingHeaderReconstructionVocab);
        }
        java.util.Set<String> acceptedColumns = new java.util.LinkedHashSet<>();
        for (Map<String, String> row : currentRows) acceptedColumns.addAll(row.keySet());
        List<HeaderReconstructionFinding> headerFindings = new ArrayList<>();
        if (!pendingHeaderReconstructionVocab.isEmpty()
                && acceptedColumns.size() <= LOW_CONFIDENCE_TRANSACTION_HEADER_COLUMN_COUNT) {
            java.util.Set<String> combinedVocab = new java.util.LinkedHashSet<>();
            for (java.util.Set<String> vocab : pendingHeaderReconstructionVocab) combinedVocab.addAll(vocab);
            headerFindings.add(new HeaderReconstructionFinding(
                    "TRANSACTION_HEADER_RECONSTRUCTION_UNCERTAIN", sections.size(), combinedVocab, acceptedColumns.size()));
        }
        sections.add(new LocatedSection(pendingAuxiliary, currentRows,
                new ExtractionEvidence(pendingDroppedCandidates, headerFindings)));
        return new PendingState(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
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
     *
     * <p>Third bug fix, same shape again: {@link CsvParser#parseDate} alone also misses a
     * genuinely yearless date column -- a real Standard Chartered savings statement prints every
     * transaction's date as a bare "May 01", relying on the account-summary block's own statement
     * date range for the year. {@link #resolveYearlessDate} already exists to resolve exactly this
     * (see its own doc comment), but until now every caller of it was a narrower, evidence-gated
     * capability (INFERRED_HEADERLESS_LAYOUT) rather than this method, the central anchor gate --
     * so a row whose ONLY date value was yearless never registered as a transaction anchor at all,
     * and the whole table collapsed into trailing continuations of whichever row happened to also
     * carry a full, year-bearing date. {@code candidateYears} is threaded in from {@link
     * #yearsByPage}, scoped to the row's OWN page for the identical reason that scoping already
     * exists there.
     */
    private boolean hasDateValue(Map<String, String> bucketed, Set<Integer> candidateYears) {
        for (Map.Entry<String, String> e : bucketed.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isBlank()) continue;
            if (!isDateColumn(e.getKey())) continue;
            String value = e.getValue().trim();
            if (CsvParser.parseDate(value) != null || resolveYearlessDate(value, candidateYears) != null) {
                return true;
            }
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


        // Chain-based clustering: currentRowY tracks the MOST RECENTLY ADDED member's y, not the
        // row's first member -- confirmed necessary against real HSBC DB.pdf (OCR) evidence, see
        // header-reconstruction-design.md §9.4. A fixed first-member anchor has no way to accumulate
        // a chain of small, individually-tolerable gaps: OCR's per-word y-jitter across HSBC's one
        // printed header line totals more than ROW_Y_TOLERANCE end to end even though every
        // individual consecutive gap stays under it, so an anchor-based comparison split one visual
        // line into two physical rows before header logic ever ran. Comparing each run to the
        // previous one instead tolerates that same accumulated drift, since no single step ever
        // exceeds the tolerance.
        List<List<PositionedText>> rows = new ArrayList<>();
        List<PositionedText> current = new ArrayList<>();
        Float currentRowY = null;
        int currentPage = -1;
        for (PositionedText t : sorted) {
            boolean sameRow = currentRowY != null && t.pageIndex() == currentPage
                    && Math.abs(t.y() - currentRowY) <= ROW_Y_TOLERANCE;
            if (!sameRow) {
                if (!current.isEmpty()) rows.add(inReadingOrder(current));
                current = new ArrayList<>();
                currentPage = t.pageIndex();
            }
            current.add(t);
            currentRowY = t.y();
        }
        if (!current.isEmpty()) rows.add(inReadingOrder(current));
        return rows;
    }

    /**
     * A finished row's members, re-sorted left-to-right -- the order every downstream consumer of
     * a row already assumes it's in (this class's own comments say so explicitly: {@code
     * mergeHeaderLines}'s doc comment calls left-to-right "the invariant... the whole pipeline
     * downstream of here reads header cells in", and the single-line header path carries its own
     * ad-hoc x-sort for the identical reason -- see the real SBI credit-card statement cited where
     * skipping it sent "Transaction Details" before "Date" and merged a whole column into another).
     * Both of those fixes sorted AFTER the fact, at their own call site, because the row itself
     * wasn't guaranteed to arrive in that order. This is the gap that left open: {@code current}'s
     * members are collected in the CLUSTERING sort's order (page, then y ascending, then x -- see
     * this method's own header comment), which groups the right runs into one row but says nothing
     * about their order once grouped, and two runs on the same visual line routinely differ by a
     * fraction of a point in y (sub-pixel rendering jitter, not a real vertical offset) -- enough
     * to sort one before the other by Y ONLY, regardless of which one a reader would actually
     * encounter first, left to right.
     *
     * <p>Confirmed on a real Standard Chartered savings statement: a transaction's narration run
     * prints at y=274.10, its own date-column value at y=274.75 -- 0.65pt apart, both comfortably
     * one visual line, but the narration sorts first. {@link #bucketRow}'s OFFSET_COLUMN_ANCHORS
     * redirect (search this class for that name) exists exactly to catch a run that overshoots a
     * column already holding a value and send it to the next one over -- but it only ever sees
     * "already holding a value" if the real value arrived FIRST. With the narration processed
     * first, it fills the date column outright (nothing else claims it yet), and the row's own
     * genuine date arrives afterward to find that column already non-blank -- concatenated onto
     * the narration instead of recognized as this row's anchor. Most of that statement's rows
     * carry no date value ANYWHERE else, so almost every transaction silently became a trailing
     * continuation of whichever one happened to keep its date, and 15+ real transactions collapsed
     * into 2 raw rows.
     *
     * <p>Sorting by x here, once, fixes it at the source for every consumer at once rather than
     * adding a third ad-hoc sort at a third call site -- and only ever reorders text that {@code
     * ROW_Y_TOLERANCE} already decided belongs to the same visual line, so it cannot merge or split
     * a row, only correct the order its members are visited in.
     */
    private List<PositionedText> inReadingOrder(List<PositionedText> row) {
        List<PositionedText> ordered = new ArrayList<>(row);
        ordered.sort(Comparator.comparing(PositionedText::x));
        return ordered;
    }

    /** Measures {@link #groupIntoRows}' own output after the fact -- reads the rows it already
     *  produced, changes nothing about how they were formed. {@code maxPhysicalRowVerticalExtent}
     *  is computed as max(y) - min(y) across each row's own members, not against {@code
     *  groupIntoRows}' internal anchor (its first member) -- an equivalent measure of the same
     *  fact, computable from the return value alone, so this needed no change to {@code
     *  groupIntoRows} itself and carries the same "did not alter extraction" guarantee every other
     *  evidence-only addition in this class has. See {@link PhysicalRowFormationEvidence}'s own doc
     *  comment for what this is and is not used for.
     *
     *  <p>{@code cellCountDistribution} is captured here, in the evidence itself, rather than
     *  recomputed wherever it is needed -- the earlier version of this method left it out and made
     *  {@code PdfPipelineDiagnostic} (a test-only diagnostic, hence {@code @code} rather than
     *  {@code @link}: it is not resolvable from this module's own main sources) call {@code
     *  groupIntoRows} directly to reconstruct it, which needed widening that method's visibility
     *  purely to serve a diagnostic. Capturing it here instead means {@code groupIntoRows} stays
     *  {@code private} -- an implementation detail again, not a visibility compromise made for one
     *  caller's convenience. */
    private PhysicalRowFormationEvidence measurePhysicalRowFormation(int textRuns, List<List<PositionedText>> rows) {
        int totalPhysicalCells = 0;
        int maxCellsInRow = 0;
        float maxVerticalExtent = 0f;
        Map<Integer, Integer> cellCountDistribution = new TreeMap<>();
        for (List<PositionedText> row : rows) {
            totalPhysicalCells += row.size();
            maxCellsInRow = Math.max(maxCellsInRow, row.size());
            cellCountDistribution.merge(row.size(), 1, Integer::sum);
            if (row.size() < 2) continue;
            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (PositionedText t : row) {
                minY = Math.min(minY, t.y());
                maxY = Math.max(maxY, t.y());
            }
            maxVerticalExtent = Math.max(maxVerticalExtent, maxY - minY);
        }
        double averageCellsPerRow = rows.isEmpty() ? 0.0 : (double) totalPhysicalCells / rows.size();
        return new PhysicalRowFormationEvidence(textRuns, rows.size(), totalPhysicalCells,
                averageCellsPerRow, maxCellsInRow, maxVerticalExtent, cellCountDistribution);
    }

    /** A header reconstructed from several visual lines, and how many lines past the first the
     *  caller must skip. */
    private record WrappedHeader(List<PositionedText> row, int extraLines, boolean admittedInteriorTierColumns) {}

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
            // INTERIOR_TIER_COLUMNS. Only reachable when the upper line does not already score --
            // see mergeHeaderLinesAdmittingInteriorTierColumns' own doc comment for why that
            // restriction is what keeps this from reopening the FD/RD cross-contamination risk
            // mergeHeaderLines' own doc comment records as tried and reverted.
            List<PositionedText> candidate = alreadyScores
                    ? mergeHeaderLines(block)
                    : mergeHeaderLinesAdmittingInteriorTierColumns(block);
            if (candidate == null) break; // the merge helper has already explained which cell refused
            if (looksLikeHeaderRow(candidate)) {
                // The strict admission rule applies ONLY when the upper line already scores on its
                // own. Kept as a `continue` rather than a `break` so a refused two-line span can
                // still be re-offered as a three-line one -- the extra line can only add lower
                // cells and column names, which is the direction that makes gates 1 and 4 easier
                // while gate 2 stays exactly as strict.
                if (alreadyScores && !refinesRatherThanRedefines(block, candidate)) continue;
                boolean admittedInteriorTierColumns = !alreadyScores && candidate.size() > nonBlankCount(first);
                // GROUNDED_SEED_COLUMNS. Checked here, against the CURRENT span's own candidate --
                // not inside the merge helper itself -- specifically so an ungrounded span doesn't
                // abort the whole search (`continue`, not `break`): the seed cell this rejects
                // might still get renamed by a LINE THIS SPAN HASN'T REACHED YET, exactly
                // Statement.pdf's own case (see that method's own doc comment). Only asked when a
                // new column may have been admitted in the first place -- the strict path
                // (mergeHeaderLines) never introduces an unjoined seed cell for this to catch, and
                // asking it there would be pure overhead.
                if (admittedInteriorTierColumns && !seedColumnsAreGrounded(first, candidate)) continue;
                found = new WrappedHeader(candidate, span, admittedInteriorTierColumns);
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
     * GROUNDED_SEED_COLUMNS. True unless {@code candidate} (the merged header row {@link
     * #mergeHeaderLinesAdmittingInteriorTierColumns} produced from {@code seedLine} as its block's
     * first line) contains a seed cell that stayed completely alone -- nothing from a later line
     * ever joined or renamed it -- AND is not itself recognized column-name vocabulary ({@link
     * #isRecognizedHeaderWord}). A lone, unrecognized seed cell is exactly what a caption sitting
     * near the real table looks like once it happens to satisfy {@link #wrappedHeaderAt}'s own
     * "carries no data value, not structural" admission on its own merits, same as any genuine
     * header line does; nothing about being the SEED specifically vouches for it.
     *
     * <p>Confirmed on a real Axis Bank credit-card statement: "Account Summary" -- an unrelated
     * section caption -- became a seed this way, and the real header line below it (DATE /
     * TRANSACTION DETAILS / MERCHANT CATEGORY / AMOUNT (Rs.)) is entirely recognized vocabulary,
     * so {@link #isPureVocabularyTier} correctly admitted its four cells as new interior-tier
     * columns -- but nothing in that line was ever within {@link #HEADER_WRAP_MAX_COLUMN_JOIN} of
     * "Account Summary" itself, so it stayed an isolated, unrenamed, unrecognized column of its
     * own, inserted between two genuine ones. {@link #looksLikeHeaderRow}'s own density check
     * cannot see this: one unrecognized cell among four recognized ones is easily dense enough to
     * pass.
     *
     * <p>Distinguishes this from Statement.pdf's own real fix, where none of the seed line's own
     * three cells ("Date(Value", "Ref No.", "Transaction") are individually recognized either --
     * but all three are later renamed by a THIRD line ("Date)", "/Cheque No", "Type"), which is
     * exactly the corroborating evidence "Account Summary" never receives.
     *
     * <p>Checked by STRING identity against {@code candidate}'s own cell text, not by re-walking
     * {@code mergeHeaderLinesAdmittingInteriorTierColumns}'s internal column-building state --
     * {@link #asOneCell} only ever changes a column's text by joining it to something else with a
     * space, so a seed cell's trimmed text surviving into the candidate UNCHANGED is exactly the
     * fact "nothing joined this column" reduces to once the merge has finished and the internal
     * per-column bookkeeping is gone.
     */
    private boolean seedColumnsAreGrounded(List<PositionedText> seedLine, List<PositionedText> candidate) {
        Set<String> candidateTexts = new HashSet<>();
        for (PositionedText t : candidate) candidateTexts.add(t.text().trim());
        for (PositionedText t : seedLine) {
            if (t.text().isBlank()) continue;
            String seedText = t.text().trim();
            if (!candidateTexts.contains(seedText)) continue; // joined into something else -- fine
            if (!isRecognizedHeaderWord(seedText)) return false;
        }
        return true;
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

        // Gate 1 -- the lower band has to contribute more than one stray token, UNLESS that lone
        // token is itself exact recognized column-name vocabulary. Confirmed on a real Standard
        // Chartered savings statement: its header's only wrap is a single cell, "Date", printed
        // one line below "Value" -- a genuine "Value Date" column split across two lines, not a
        // footnote (a footnote is prose or a unit annotation; a lone cell whose ENTIRE text
        // exactly equals a recognized column-name word is neither). Left unrecovered, the column
        // stays named bare "Value", which isDateColumn does not recognize the way it recognizes
        // "Value Date" (a literal entry in DATE_HINTS) -- and most of this statement's own
        // transaction rows print their date in exactly that column and nowhere else, so without
        // the rename most rows never register as carrying a date at all and collapse into
        // trailing continuations of whichever row DID have one. Still gated by 2 (within
        // STRICT_COLUMN_JOIN of an upper anchor) and 4 (a net improvement in whole-cell hint
        // matches) below, so this is narrower than "any one recognized word", not a general
        // loosening of the footnote guard.
        int lowerCells = 0;
        PositionedText onlyLowerCell = null;
        for (int line = 1; line < block.size(); line++) {
            for (PositionedText t : block.get(line)) {
                if (t.text().isBlank()) continue;
                lowerCells++;
                onlyLowerCell = t;
            }
        }
        boolean singleRecognizedWordWrap = lowerCells == 1 && isRecognizedHeaderWord(onlyLowerCell.text());
        if (lowerCells < HEADER_WRAP_STRICT_MIN_LOWER_CELLS && !singleRecognizedWordWrap) {
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

    // How many of a section's own upcoming rows the header-quality gate and the reconstruction
    // engine sample before judging fit -- see headerQualityWeak and reconstructHeader. Not
    // calibrated against the corpus (no numeric weight is; see the design doc's stance on that);
    // just large enough that a single unusual row cannot swing the verdict on its own, small
    // enough that this never scans meaningfully into a document.
    private static final int HEADER_QUALITY_SAMPLE_SIZE = 10;

    // Never judge quality from fewer than this many sampled rows -- a section with only one or two
    // real rows visible this close to its header is exactly the case where guessing is riskiest and
    // evidence is thinnest. Mirrors this class's existing discipline of doing nothing rather than
    // acting on too little (e.g. accountIdentityIn returning null rather than guessing an account
    // number). Below this, headerQualityWeak reports "not weak" -- not because the header is known
    // good, but because there is not enough here to say otherwise.
    private static final int HEADER_QUALITY_MIN_SAMPLE = 2;

    /**
     * Phase 2E.2 prototype of the Header Quality Gate
     * (docs/architecture/system-design/header-reconstruction-design.md §4.1-4.2). Judges
     * {@code headerColumns} -- the FINAL column count this section's header actually has, after
     * {@link #buildHeaderColumns}'s whole existing recovery pipeline has already run (duplicate
     * resolution, blank-name qualification, missing description/serial-number recovery) -- by
     * whether the section's own real upcoming rows fit under that many columns, never by how
     * header-like the original line looked in isolation.
     *
     * <p>Deliberately evaluated AFTER recovery, not before: {@link #recoverMissingDescriptionColumn}
     * already attempts to solve exactly this class of problem (a fragment one line above the header
     * naming a column the accepted line is missing) and correctly declines when its own candidate is
     * a single, ambiguous cell -- see that method's own doc comment. Judging quality on the PRE-
     * recovery column count would make this gate fire on documents that recovery was always going to
     * fix on its own (a real regression, found against a real ICICI three-tier header while building
     * this gate -- see {@code HeaderColumnRecoveryTest}), triggering the heavier reconstruction
     * engine below where the older, narrower, already-correct mechanism was already going to succeed.
     *
     * <p>Also deliberately NOT keyed on whether a wrap-merge attempt failed: traced against a real
     * SBI credit-card statement, the row that ends up accepted as this document's Section 1 header
     * ("Date | Amount ( ` )") was never itself the subject of a failed merge -- the merge that failed
     * ran on a DIFFERENT row, one physical line above, which never scored as a header on its own and
     * so never reached this gate at all. See the design doc §4.1 for the full trace.
     *
     * <p>Row fit is measured the same way {@link #reconstructHeader} measures it: by actually
     * bucketing a sample of the section's own real rows against {@code headerNames}/
     * {@code headerAnchors}/{@code headerEnds} (the real {@link #bucketRow}, not an approximation of
     * it) via {@link #rowsCleanlyExplainedBy} -- both the date column and, when one is recognized,
     * the amount column must hold a cleanly parseable value. A header is weak when the MAJORITY of
     * the sample does not clear that bar. Deliberately NOT a raw cell-count comparison ("does the
     * header have enough columns") and deliberately NOT date-only either -- both were tried and both
     * were found blind to a real failure mode while writing this gate's own tests. Cell-count alone
     * passed its own tests while silently producing a header whose Date anchor didn't line up with
     * where dates are actually printed, collapsing every real row into one unparseable blob at the
     * SAME cell-count that looked fully explained. Date-only was blind to the opposite failure: a
     * header missing its description column still buckets a perfectly parseable date every time (the
     * date run is always first in raw order and claims the date column before anything else
     * competes), while the missing description silently corrupts the amount column instead --
     * {@link #rowsCleanlyExplainedBy}'s own doc comment has the full trace. Checking what
     * {@code bucketRow} actually PLACES, in both of the columns that matter, is what catches both.
     *
     * <p>Returns {@code false} -- "not weak" -- whenever there is not enough evidence to say
     * otherwise (fewer than {@link #HEADER_QUALITY_MIN_SAMPLE} real rows found nearby). This is the
     * gate's own version of the reconstruction engine's no-forced-guess rule (design doc §4.3): a
     * judgment made without evidence is not a safer default, it is a guess wearing a confident
     * label.
     */
    private boolean headerQualityWeak(List<String> headerNames, List<Float> headerAnchors,
                                       List<Float> headerEnds, List<List<PositionedText>> rows,
                                       int headerIndex, int wrappedHeaderLines) {
        if (headerNames.isEmpty()) return false; // nothing to judge -- an empty header is someone else's problem
        List<List<PositionedText>> sample = sampleRealDataRows(
                rows, headerIndex + wrappedHeaderLines + 1, HEADER_QUALITY_SAMPLE_SIZE);
        if (sample.size() < HEADER_QUALITY_MIN_SAMPLE) return false;
        int explained = rowsCleanlyExplainedBy(new HeaderColumns(headerNames, headerAnchors, headerEnds), sample);
        return explained * 2 < sample.size();
    }

    /** Turns an accepted header {@code row} into {@code headerNames}/{@code headerAnchors}/
     *  {@code headerEnds} -- coalesce, x-sort, then the existing four-step recovery pipeline
     *  (duplicate names, blank names, missing description column, missing serial-number column).
     *  Extracted so the Header Quality Gate below can run this SAME pipeline a second time, on a
     *  reconstructed row, without duplicating it -- every one of the four recovery calls still runs
     *  exactly once per row it is actually given, so nothing about their own behavior changes. */
    private void buildHeaderColumns(List<PositionedText> row, List<String> headerNames, List<Float> headerAnchors,
                                     List<Float> headerEnds, List<List<PositionedText>> rows, int rowIndex,
                                     DocumentContext ctx, List<String> orphanedHeaderRowText) {
        List<PositionedText> coalesced = new ArrayList<>(coalesceHeaderRuns(stripEmbeddedDateRange(row)));
        coalesced.sort(Comparator.comparing(PositionedText::x));
        for (PositionedText t : coalesced) {
            String text = t.text().trim();
            // Phase 2E.5's HSBC row-formation fix (groupIntoRows' chain-based clustering, see
            // header-reconstruction-design.md §9.4) can now correctly fold a nearby caption line
            // onto an accepted header's own physical row -- confirmed live on a real SBI credit
            // card statement, where "for Statement Period: 99 Xxx 99 to 99 Xxx 99" sits 2.36pt
            // below the header's own "( ` )" sub-label, well within ROW_Y_TOLERANCE measured
            // chain-wise, even though it is a genuinely different printed line, not a real column
            // name. Every cell on the header's physical row otherwise becomes a column name
            // unconditionally, so without this check that caption became a phantom column no real
            // transaction data was ever near enough to populate -- and since headerNames are never
            // exposed in LocatedSection's own returned data, its real text vanished from the
            // document entirely: not a row, not auxiliary text, nothing.
            //
            // Deliberately narrow: containsEmbeddedDateRange, not a blanket vocabulary check.
            // Tried filtering on "matches no HEADER_HINTS/DATE_HINTS word at all" first -- it also
            // excluded Axis's genuine, merely-uncatalogued "XXXXXXXX XXXXXXXX" column and (worse)
            // SBI's/ICICI's own blank-shaped currency-unit cell ("(Rs.)") that
            // resolveBlankColumnNames exists specifically to qualify into a real column further
            // down this same pipeline -- filtering before that recovery step got its chance broke
            // Axis down to 25 of its normal 108 staged rows. A cell embedding a literal "<date> to
            // <date>" range, by contrast, is never a real column name in this corpus (verified
            // against two independent real documents: this SBI caption and the already-established
            // Kotak case stripEmbeddedDateRange above exists for) and never something
            // resolveBlankColumnNames-style qualification would want to keep.
            if (containsEmbeddedDateRange(text)) {
                orphanedHeaderRowText.add(text);
                continue;
            }
            headerNames.add(text);
            headerAnchors.add(t.x());
            headerEnds.add(t.endX());
        }
        resolveDuplicateColumnNames(headerNames, headerAnchors, rows, rowIndex, ctx);
        resolveBlankColumnNames(headerNames, headerAnchors, rows, rowIndex, ctx);
        recoverMissingDescriptionColumn(headerNames, headerAnchors, headerEnds, rows, rowIndex, ctx);
        recoverMissingSerialNumberColumn(headerNames, headerAnchors, headerEnds, rows, rowIndex, ctx);
    }

    /**
     * Header Reconstruction Engine (design doc §4.6-4.9, generalized per §9.3's correction in
     * Phase 2E.5): composes a header from fragments that live on physical lines ABOVE the row that
     * gets accepted, not below it -- {@link #wrappedHeaderAt} only ever looks forward, so it never
     * finds this shape. Originally a Phase 2E.2 prototype narrowed to exactly one orphaned
     * single-cell fragment one line above {@code headerRow} (SBI's Section 1); generalized here to a
     * genuine multi-tier backward walk so a real multi-cell second tier -- e.g. IOB's forward
     * composition shape and ICICI savings' genuine three-cell tier, both of which the single-fragment
     * prototype declined outright -- can be composed too, bounded by {@link #HEADER_WRAP_MAX_LINES}
     * and gated line-by-line against renaming an already-established column (see the walk's own
     * inline comment for the fill-empty-vs-qualify distinction and the real ICICI regression that
     * distinction exists to prevent).
     *
     * <p>Steps, matching the design doc's numbering:
     * <ol>
     *   <li><b>Collect fragments (§4.6).</b> Walk backward from {@code headerRow} one physical line
     *       at a time, each line admitted only if it is itself a plausible orphaned header fragment
     *       (no date/number of its own, not a structural line, within the same physical-adjacency
     *       window {@link #wrapsOnto} already uses for the forward case) and every one of its
     *       fragments sits nowhere near a column this composition already has an anchor for. The
     *       walk stops -- declining that line and everything further back -- the instant a line
     *       conflicts, or reaches {@code HEADER_WRAP_MAX_LINES}, or nothing was ever safely
     *       composable.</li>
     *   <li><b>Classify vocabulary (§4.7).</b> Reuses {@link #looksLikeHeaderRow} directly: the
     *       composed candidate must clear the exact same bar every other accepted header in this
     *       document does, not a looser one built just for reconstructed headers.</li>
     *   <li><b>Build the candidate (§4.8).</b> Every non-blank fragment from every admitted line as
     *       its own column, x-sorted -- the "no line refines another, every fragment is independent"
     *       reading the design doc's §2 describes as what {@code mergeHeaderLines} has no model for
     *       at all.</li>
     *   <li><b>Validate against real rows (§4.9).</b> Accepted only if it explains STRICTLY MORE of
     *       the sampled sample than {@code headerRow} did -- never a tie, never "looks more
     *       complete." A composition that does not demonstrably improve row fit is not an
     *       improvement, the same standard {@link #refinesRatherThanRedefines}'s gate 4 already
     *       holds the forward-wrap case to.</li>
     * </ol>
     *
     * <p>Returns {@code null} -- no reconstruction, {@code HeaderReconstructionFinding} stands
     * exactly as it does today -- when any step fails to clear its bar. Never returns a candidate
     * merely because it is the only one tried; §4.9's validation is not optional just because there
     * was nothing to compare it against but the original.
     */
    private List<PositionedText> reconstructHeader(List<PositionedText> headerRow,
                                                     List<List<PositionedText>> rows, int headerIndex,
                                                     int wrappedHeaderLines) {
        if (headerIndex <= 0) return null;

        // Walk backward one physical line at a time, collecting fragments -- generalizes the
        // original single-line, single-fragment prototype (Phase 2E.2) to a genuine multi-tier
        // region, per design doc §4.6-4.9 and §9.3's correction. Each candidate fragment must be a
        // FILL-EMPTY addition -- sitting nowhere near a column this composition already has an
        // anchor for -- never a QUALIFY/rename of one, which this engine still does not attempt
        // (that is resolveDuplicateColumnNames's job, in buildHeaderColumns, run separately before
        // this is ever reached). The walk stops the instant a line has ANY fragment within
        // HEADER_WRAP_MAX_COLUMN_JOIN of an already-established anchor -- not just skipping that one
        // fragment, but declining the whole line and everything further back, since a line this
        // close to being a rename is exactly the shape a naive fill-empty read gets wrong (see the
        // real ICICI regression this design's correction documents: composing a qualify-shaped tier
        // as brand-new columns recovers some rows while corrupting the statement's own reconciliation
        // check). Anchored at headerRow -- the row the ordinary per-row loop already accepted -- so,
        // unlike a forward walk seeded at an early, not-yet-accepted row, this can never settle for a
        // worse composition just because an earlier starting point happened to look valid in
        // isolation.
        List<PositionedText> candidate = new ArrayList<>();
        for (PositionedText t : headerRow) if (!t.text().isBlank()) candidate.add(t);
        List<Float> establishedAnchors = new ArrayList<>();
        for (PositionedText t : candidate) establishedAnchors.add(t.x());

        List<PositionedText> mostRecentlyIncluded = headerRow;
        int added = 0;
        for (int back = 1; back < HEADER_WRAP_MAX_LINES && headerIndex - back >= 0; back++) {
            List<PositionedText> line = rows.get(headerIndex - back);
            if (line.isEmpty() || !carriesNoDataValue(line) || carriesStructuralMeaning(line)) break;
            if (!wrapsOnto(line, mostRecentlyIncluded)) break;

            List<PositionedText> fragments = new ArrayList<>();
            for (PositionedText t : line) if (!t.text().isBlank()) fragments.add(t);
            if (fragments.isEmpty()) break;

            boolean conflict = false;
            for (PositionedText fragment : fragments) {
                for (float anchor : establishedAnchors) {
                    if (Math.abs(fragment.x() - anchor) < HEADER_WRAP_MAX_COLUMN_JOIN) {
                        conflict = true;
                        break;
                    }
                }
                if (conflict) break;
            }
            if (conflict) break; // this line -- and anything further back -- would rename, not fill

            candidate.addAll(fragments);
            for (PositionedText t : fragments) establishedAnchors.add(t.x());
            mostRecentlyIncluded = line;
            added++;
        }
        if (added == 0) return null; // nothing safely composable -- decline, same as today

        candidate.sort(Comparator.comparing(PositionedText::x));

        if (!looksLikeHeaderRow(candidate)) return null;

        List<List<PositionedText>> sample = sampleRealDataRows(
                rows, headerIndex + wrappedHeaderLines + 1, HEADER_QUALITY_SAMPLE_SIZE);
        if (sample.size() < HEADER_QUALITY_MIN_SAMPLE) return null;

        // Validated by actually bucketing the sample against each header's real anchors, not by
        // comparing cell counts -- see headerQualityWeak's doc comment for why a count-only version
        // of this check passed its own tests while producing a header whose Date anchor didn't
        // actually line up with the data, silently collapsing every real row into one blob.
        int before = rowsCleanlyExplainedBy(coalescedNamesAnchorsEnds(headerRow), sample);
        int after = rowsCleanlyExplainedBy(coalescedNamesAnchorsEnds(candidate), sample);
        if (after <= before) return null;

        return candidate;
    }

    /** {@code (names, anchors, ends)} for a header row, coalesced and x-sorted exactly as
     *  {@link #buildHeaderColumns} builds them for real -- but WITHOUT that method's four recovery
     *  calls, which record capabilities as a side effect and must fire at most once per row this
     *  class actually commits to. Used only to VALIDATE a candidate before commitment; the row that
     *  is ultimately accepted is built for real, exactly once, via {@link #buildHeaderColumns}. */
    private HeaderColumns coalescedNamesAnchorsEnds(List<PositionedText> row) {
        List<PositionedText> coalesced = new ArrayList<>(coalesceHeaderRuns(stripEmbeddedDateRange(row)));
        coalesced.sort(Comparator.comparing(PositionedText::x));
        List<String> names = new ArrayList<>();
        List<Float> anchors = new ArrayList<>();
        List<Float> ends = new ArrayList<>();
        for (PositionedText t : coalesced) {
            names.add(t.text().trim());
            anchors.add(t.x());
            ends.add(t.endX());
        }
        return new HeaderColumns(names, anchors, ends);
    }

    private record HeaderColumns(List<String> names, List<Float> anchors, List<Float> ends) {}

    /**
     * True when a genuine {@link #hasDateValue} anchor exists later in this section, within a
     * bounded lookahead -- the only signal that reliably tells "defer this narration-only row, a
     * real transaction is coming to claim it" apart from "stand it alone now, nothing ever claims
     * it," verified by tracing three real documents. {@link #isNarrationOnly} alone cannot make
     * this distinction: a header missing its narration column squishes a real transaction's date
     * and merchant name into one bucketed cell that fails the same numeric check a genuine caption
     * does (a real SBI Card section), and a perfectly well-formed header can still open a section
     * with no transaction in it at all (a real AU Credit Card EMI-disclosure panel). Bucketed
     * against the CURRENT header's own columns, with a null {@code ctx} -- this is a speculative
     * peek, not the real bucketing pass, and must not record capability signals for rows it may
     * never actually process this way. Bounded by {@link #MAX_LEADING_CONTINUATION_ROWS}, the same
     * cap the leading-narration buffer itself gives up at: if an anchor would not turn up within
     * that many rows, the buffer would not successfully claim one either, so there is no reason to
     * look further.
     */
    private boolean anchorFollowsWithinSection(List<List<PositionedText>> rows, int fromIndex,
            List<String> headerNames, List<Float> headerAnchors, List<Float> headerEnds,
            Map<Integer, Set<Integer>> yearsByPage) {
        int scanLimit = Math.min(rows.size(), fromIndex + MAX_LEADING_CONTINUATION_ROWS);
        for (int i = Math.max(fromIndex, 0); i < scanLimit; i++) {
            List<PositionedText> row = rows.get(i);
            if (row.isEmpty()) continue;
            if (carriesStructuralMeaning(row)) return false;
            Set<Integer> candidateYears = yearsByPage.getOrDefault(row.get(0).pageIndex(), Set.of());
            if (hasDateValue(bucketRow(row, headerNames, headerAnchors, headerEnds, null, candidateYears),
                    candidateYears)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when either nothing sits between {@code rows.get(rowIndex)} and whatever claims it next
     * (nothing to contradict), or the gap from it to the very next row matches the gap from THAT
     * row to the one after -- a single, consistently-set block rather than a genuinely separate,
     * loosely-spaced caption sitting above a tighter pair. The only signal, found by comparing real
     * gaps across two real documents, that distinguishes a real ICICI savings narration line (whose
     * gap to its anchor matches the anchor's own established line pitch almost exactly) from a real
     * HDFC Credit Card merchant caption (whose gap to the next line is nothing like that next
     * line's own gap to the real anchor below it) -- both single-column, both with a real anchor
     * somewhere ahead, yet only one of them the anchor's own leading narration.
     */
    private boolean firstHopIsPitchConsistentOrDirect(List<List<PositionedText>> rows, int rowIndex,
            List<String> headerNames, List<Float> headerAnchors, List<Float> headerEnds,
            Map<Integer, Set<Integer>> yearsByPage) {
        List<PositionedText> current = rows.get(rowIndex);
        int nextIndex = rowIndex + 1;
        while (nextIndex < rows.size() && rows.get(nextIndex).isEmpty()) nextIndex++;
        if (nextIndex >= rows.size()) return true; // nothing follows to contradict
        List<PositionedText> next = rows.get(nextIndex);
        // The very next row IS the anchor -- directly adjacent, exactly the one real ICICI
        // savings document's own shape, with no intervening line to measure a pitch against at
        // all. A DIFFERENT transaction's anchor two rows down (as in this class's own simplest
        // synthetic fixture, whose second real transaction sits at its own, unrelated pitch) must
        // never be used as a reference here -- only a genuine chain of narration awaiting the SAME
        // anchor gives a meaningful pitch to compare against.
        Set<Integer> nextCandidateYears = yearsByPage.getOrDefault(next.get(0).pageIndex(), Set.of());
        if (hasDateValue(bucketRow(next, headerNames, headerAnchors, headerEnds, null, nextCandidateYears),
                nextCandidateYears)) {
            return true;
        }
        int nextNextIndex = nextIndex + 1;
        while (nextNextIndex < rows.size() && rows.get(nextNextIndex).isEmpty()) nextNextIndex++;
        if (nextNextIndex >= rows.size()) return true; // no reference pitch available
        List<PositionedText> nextNext = rows.get(nextNextIndex);
        if (current.get(0).pageIndex() != next.get(0).pageIndex()
                || next.get(0).pageIndex() != nextNext.get(0).pageIndex()) {
            return true; // a page break is not evidence of a pitch break
        }
        float firstHop = next.get(0).y() - current.get(0).y();
        float secondHop = nextNext.get(0).y() - next.get(0).y();
        return Math.abs(firstHop - secondHop) <= BLOCK_PITCH_TOLERANCE;
    }

    /** Up to {@code maxCount} of this section's own upcoming rows that plausibly carry real
     *  transaction data -- skips rows that carry no date/number of their own (repeated headers,
     *  captions, blank spacer lines), stops at the first structurally-meaningful line (a section
     *  banner, page footer, or closing marker means there is nothing left of THIS section's data to
     *  sample), and never scans further than {@code maxCount * 3} rows looking for them, so an
     *  unusual document cannot make this silently walk deep into a section it has no evidence about
     *  yet. Shared by {@link #headerQualityWeak} and {@link #reconstructHeader} so both judge a
     *  header against the exact same evidence. */
    private List<List<PositionedText>> sampleRealDataRows(List<List<PositionedText>> rows, int fromIndex,
                                                            int maxCount) {
        List<List<PositionedText>> sample = new ArrayList<>();
        int scanLimit = Math.min(rows.size(), fromIndex + maxCount * 3);
        for (int i = Math.max(fromIndex, 0); i < scanLimit && sample.size() < maxCount; i++) {
            List<PositionedText> row = rows.get(i);
            if (row.isEmpty()) continue;
            if (carriesStructuralMeaning(row)) break;
            if (carriesNoDataValue(row)) continue;
            sample.add(row);
        }
        return sample;
    }

    /**
     * How many of {@code sample}'s rows both (a) bucket with NO COLLISION -- every raw non-blank
     * cell lands in its own column, none sharing with another -- and (b) end up with a cleanly
     * parseable date in whichever column {@link #isDateColumn} recognizes. {@code header} is
     * bucketed for real via {@link #bucketRow}, never approximated.
     *
     * <p>Collision is measured as {@code bucketed.size() < } the row's own raw non-blank cell count:
     * {@code bucketRow} has no maximum join distance and always folds an unaccounted value into
     * whichever column is nearest rather than leave it out, so two raw cells sharing one column
     * is the only way the bucketed map ends up with fewer entries than the row had values. This is
     * deliberately vocabulary-free -- it does not need to recognize a column as "the amount column"
     * to notice that two real values collapsed into one. An earlier version of this method checked
     * only whether a NAMED amount column parsed cleanly, and that was blind whenever the missing
     * column's neighbor wasn't itself amount-shaped: a two-column [Date, Narration] header missing
     * a Balance column still merges the balance value into Narration, corrupting it, but "Narration"
     * matches no amount vocabulary, so nothing was ever checked there. Checking for the collision
     * directly, rather than inferring it from whichever specific column happened to absorb it,
     * catches every shape of it. Date-only validation (checked even earlier) was blind for a
     * related reason: the date run is always first in raw row order and claims the date column
     * before anything else competes for it, so a missing column can corrupt everything ELSE while
     * the date keeps parsing perfectly -- collision detection catches that failure directly too,
     * without needing the date check to somehow notice a problem in a column it isn't looking at.
     *
     * <p>Passing {@code null} for {@code ctx} to {@code bucketRow} is deliberate -- this runs
     * speculatively, on candidates that may never be adopted, and {@code bucketRow}'s own capability
     * recordings (RIGHT_ALIGNED_AMOUNTS, OFFSET_COLUMN_ANCHORS) must fire only for rows actually
     * bucketed for real, inside {@link #buildHeaderColumns}'s real, single, committed run.
     */
    private int rowsCleanlyExplainedBy(HeaderColumns header, List<List<PositionedText>> sample) {
        int dateColumn = -1;
        for (int i = 0; i < header.names().size(); i++) {
            if (isDateColumn(header.names().get(i))) { dateColumn = i; break; }
        }
        if (dateColumn < 0) return 0;
        String dateColumnName = header.names().get(dateColumn);
        int explained = 0;
        for (List<PositionedText> row : sample) {
            int rawCells = nonBlankCount(row);
            Map<String, String> bucketed = bucketRow(row, header.names(), header.anchors(), header.ends(), null,
                    Set.of());
            if (bucketed.size() < rawCells) continue; // collision -- two raw values shared one column
            String dateValue = bucketed.get(dateColumnName);
            if (dateValue == null || CsvParser.parseDate(dateValue.trim()) == null) continue;
            explained++;
        }
        return explained;
    }

    private int nonBlankCount(List<PositionedText> row) {
        int count = 0;
        for (PositionedText t : row) if (!t.text().isBlank()) count++;
        return count;
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

    /**
     * INTERIOR_TIER_COLUMNS. {@link #mergeHeaderLines}'s sibling for the one case that method
     * cannot represent at all: a header block whose column GROUPS wrap to unequal depths, so a
     * whole tier's worth of columns exists on a middle line and nowhere else in the block.
     *
     * <p>Confirmed on two real savings-account statements (a third-party-generated SBI one and a
     * real Standard Chartered one). Both print a three-line header where the outer columns
     * ("Date"/"Ref No."/"Type" on the SBI one) wrap across the TOP and BOTTOM lines, skipping the
     * middle one entirely, while a second column group ("Particulars"/"Debit(Rs)"/"Credit(Rs)"/
     * "Balance(Rs)") prints ONLY on that middle line and nowhere else. {@code mergeHeaderLines}
     * seeds its columns from the block's first line and requires every later line's cell to join
     * one of them, refusing the whole merge otherwise (see that method's own doc comment for why
     * that refusal is right in general) -- so the middle line's four cells, none of which sit
     * within {@link #HEADER_WRAP_MAX_COLUMN_JOIN} of the top line's three, aborted the merge
     * outright. Both documents located a table with 2-3 garbled columns instead of 6-7 and staged
     * zero transaction rows.
     *
     * <p>Only ever called on a line that does NOT already score as a header alone -- the exact
     * restriction {@link #wrappedHeaderAt}'s own doc comment describes as load-bearing for {@code
     * mergeHeaderLines}, kept here for the same reason: a document whose header is recognized
     * today never reaches this method, so nothing already working can regress through it.
     *
     * <p><b>Why this does not reopen the FD/RD cross-contamination this class already tried and
     * reverted once</b> (see {@code mergeHeaderLines}'s own doc comment: admitting an unmatched
     * cell as a new column, unconditionally, let an unrelated fixed-deposit schedule's own second
     * tier bleed into a recurring-deposit table's header on the same real HDFC statement). That
     * experiment had no content gate at all -- any unmatched cell became a column. This one admits
     * a whole LINE's unmatched cells only when {@link #isPureVocabularyTier} accepts the line as a
     * whole: every one of its non-blank cells, individually, normalizes to a word this class
     * already recognizes as a column name ({@link #HEADER_HINTS} or {@link #DATE_HINTS}) -- not
     * "looks table-shaped" the way {@link #carriesNoDataValue} does, but "is column-name
     * vocabulary, cell for cell". "Particulars"/"Debit(Rs)"/"Credit(Rs)"/"Balance(Rs)" all pass
     * that individually; the FD schedule's real intruder, "Withdrawable***", does not (it is not
     * "withdrawal" or "withdrawals" under {@link #matchesAnyHint}'s exact-word matching) and so a
     * line containing it still refuses the merge outright, exactly as today.
     */
    private List<PositionedText> mergeHeaderLinesAdmittingInteriorTierColumns(List<List<PositionedText>> block) {
        List<List<PositionedText>> columns = new ArrayList<>();
        for (PositionedText t : block.get(0)) {
            if (t.text().isBlank()) continue;
            List<PositionedText> column = new ArrayList<>();
            column.add(t);
            columns.add(column);
        }
        if (columns.isEmpty()) return null;

        for (int line = 1; line < block.size(); line++) {
            List<PositionedText> currentLine = block.get(line);
            // Joined against the column set as it stood BEFORE this line, and any cell this line
            // itself cannot join is collected rather than resolved immediately -- so two cells
            // printed side by side on the SAME physical line can never both land in a column this
            // line itself just created (which would erase the very distinction a wrapped-header
            // block exists to preserve: one column per printed label).
            List<PositionedText> unresolved = new ArrayList<>();
            for (PositionedText t : currentLine) {
                if (t.text().isBlank()) continue;
                int target = columnFor(t, columns);
                if (target < 0) {
                    unresolved.add(t);
                    continue;
                }
                columns.get(target).add(t);
            }
            if (!unresolved.isEmpty()) {
                if (!isPureVocabularyTier(currentLine)) {
                    PositionedText first = unresolved.get(0);
                    explainWrap(block.get(0), () -> "NO_MERGE: lower cell \"" + first.text().trim() + "\" at x="
                            + first.x() + " joins no column above it (nearest is more than "
                            + HEADER_WRAP_MAX_COLUMN_JOIN + "pt away), and its own line is not"
                            + " entirely recognized column-name vocabulary, so it is not admitted as"
                            + " a new interior-tier column");
                    return null;
                }
                for (PositionedText t : unresolved) {
                    List<PositionedText> column = new ArrayList<>();
                    column.add(t);
                    columns.add(column);
                }
            }
            columns.sort((a, b) -> Float.compare(anchorOf(a), anchorOf(b)));
        }

        List<PositionedText> headerRow = new ArrayList<>();
        for (List<PositionedText> column : columns) headerRow.add(asOneCell(column, lastLineY(block)));
        return headerRow;
    }

    /**
     * True when every non-blank cell in {@code line}, INDIVIDUALLY, normalizes to a word {@link
     * #HEADER_HINTS} or {@link #DATE_HINTS} already recognizes -- the content gate {@link
     * #mergeHeaderLinesAdmittingInteriorTierColumns} uses to decide whether an unmatched line may
     * contribute brand new columns rather than refuse the merge. Deliberately stricter than {@link
     * #looksLikeHeaderRow}'s own density check (which only requires a THIRD of a row's cells to be
     * recognized, to tolerate a genuine column name sitting beside genuine prose): a line being
     * admitted here isn't being scored as a header on its own, it is being trusted to silently
     * widen another line's column count, so every cell on it has to earn that trust individually.
     *
     * <p>Requires at least {@link #HEADER_WRAP_STRICT_MIN_LOWER_CELLS} non-blank cells, the same
     * floor {@link #refinesRatherThanRedefines}'s own gate 1 uses and for the identical reason: one
     * recognized word sitting alone on a line is ordinarily a stray label, not a second heading
     * band -- gate 1's own doc comment records the one narrow exception to that (a lone cell that
     * RENAMES an existing column rather than trying to introduce a whole new one), which does not
     * apply here since this method exists specifically to admit new columns.
     */
    private boolean isPureVocabularyTier(List<PositionedText> line) {
        int nonBlank = 0;
        for (PositionedText t : line) {
            if (t.text().isBlank()) continue;
            nonBlank++;
            if (!isRecognizedHeaderWord(t.text())) return false;
        }
        return nonBlank >= HEADER_WRAP_STRICT_MIN_LOWER_CELLS;
    }

    /** True when {@code cellText}'s normalized form exactly names a recognized column -- either a
     *  {@link #HEADER_HINTS} word or a {@link #DATE_HINTS} one. The single-cell content gate
     *  shared by {@link #isPureVocabularyTier} and {@link #refinesRatherThanRedefines}'s gate 1. */
    private boolean isRecognizedHeaderWord(String cellText) {
        return matchesAnyHint(cellText, HEADER_HINTS) || matchesAnyHint(cellText, DATE_HINTS);
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

    /** True when {@code text} embeds a literal "&lt;date&gt; to &lt;date&gt;" range within a
     *  SINGLE fused text run -- the shape {@link #stripEmbeddedDateRange} already handles when the
     *  same range arrives as four SEPARATE runs (a real Kotak credit-card statement's "from
     *  16-Feb-2026 to 15-Mar-2026"), generalized here for when PDFBox emits the whole phrase as one
     *  continuous run instead (a real SBI credit-card statement's "for Statement Period: 99 Xxx 99
     *  to 99 Xxx 99"). Scans for the literal word "to", then tries windows of 1-3 tokens
     *  immediately before and after it for a parseable date -- 1-3 tokens because a real date can
     *  print as one hyphenated token ("16-Feb-2026") or as three separate ones ("99 Xxx 99").
     *  Deliberately narrow, matching only this one real shape found so far: a genuine column name
     *  is never itself a literal date range, so this never touches a real header cell -- see
     *  buildHeaderColumns' own comment on this call site for why a broader "matches no known
     *  vocabulary" version was tried and rejected (it excluded Axis's genuine, merely-uncatalogued
     *  column and SBI/ICICI's own blank currency-unit cell that a later recovery step needs intact). */
    private boolean containsEmbeddedDateRange(String text) {
        String[] tokens = text.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            if (!tokens[i].equalsIgnoreCase("to")) continue;
            if (dateEndingAt(tokens, i - 1) && dateStartingAt(tokens, i + 1)) return true;
        }
        return false;
    }

    private boolean dateEndingAt(String[] tokens, int end) {
        for (int width = 1; width <= 3; width++) {
            int start = end - width + 1;
            if (start < 0) break;
            if (CsvParser.parseDate(String.join(" ", Arrays.asList(tokens).subList(start, end + 1))) != null) {
                return true;
            }
        }
        return false;
    }

    private boolean dateStartingAt(String[] tokens, int start) {
        for (int width = 1; width <= 3; width++) {
            int end = start + width; // exclusive
            if (end > tokens.length) break;
            if (CsvParser.parseDate(String.join(" ", Arrays.asList(tokens).subList(start, end))) != null) {
                return true;
            }
        }
        return false;
    }

    private boolean isWord(PositionedText t, String word) {
        return t.text().trim().equalsIgnoreCase(word);
    }

    /** Removes any run in {@code row} whose OWN text alone already matches {@link
     *  #containsEmbeddedDateRange} -- the removed text is appended to {@code orphanedCaptions}, in
     *  row order. Called once, at header acceptance, before anything downstream treats {@code row}'s
     *  cells as real column identity: see the call site's own comment for the two consumers
     *  (headerSignature, reconstructHeader) this exists to protect, neither of which is the
     *  buildHeaderColumns' own containsEmbeddedDateRange check further down this class -- that one
     *  stays as a safety net for a caption that only becomes a fused single cell AFTER coalescing,
     *  which this single-run check cannot see (this runs before coalescing). */
    private List<PositionedText> stripStandaloneEmbeddedDateRangeCaptions(
            List<PositionedText> row, List<String> orphanedCaptions) {
        List<PositionedText> kept = new ArrayList<>();
        for (PositionedText t : row) {
            String text = t.text().trim();
            if (containsEmbeddedDateRange(text)) {
                orphanedCaptions.add(text);
                continue;
            }
            kept.add(t);
        }
        return kept;
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

    // Bug fix, verified against a real SBI credit-card statement: this exact literal string is
    // PDFBox's font-substitution rendering of a currency-symbol sub-label ("Amount (₹)" split into
    // "Amount" + this cell) -- the same class of quirk as parseNumeric's own "Rupee-as-C" comment,
    // here on a header cell rather than a value. On MOST layouts (and on this SAME document's own
    // first page, purely by extraction-order coincidence) it never reaches joinsOntoHeaderCell at
    // all. But it sits close enough to "Amount" that, whenever it does, coalescing would ordinarily
    // fold it in -- correct and desired for an ordinary decorative currency suffix like "(Rs.)" or
    // "(INR)", which carries no data of its own (see TransactionNormalizer.RUPEE_ARTIFACT_TYPE_COLUMN's
    // own doc comment for the flip side of this same evidence). This one is different: every real
    // transaction row on that document carries a bare Credit/Debit marker ("C"/"D") in this exact
    // column, per the statement's own printed legend "C=Credit ; D=Debit". Coalesced away, that
    // marker has nowhere of its own to bucket into and gets glued onto the amount value instead
    // ("25.00 D"), which fails CsvParser.parseNumeric outright -- every one of that document's
    // second-cardholder transactions silently vanished this way. Scoped to the single literal
    // artifact string, not a general "any currency-symbol cell" rule -- see
    // HeaderReconstructionEngineTest.sbiShapedPartitionedHeader_keepsTheRupeeArtifactColumnSeparateFromAmount
    // for the other (legitimate-merge) shape this must not disturb.
    private static final String RUPEE_ARTIFACT_MARKER_COLUMN = "( ` )";

    /** Whether {@code right} is the continuation of the same header cell {@code left} begins. */
    private boolean joinsOntoHeaderCell(PositionedText left, PositionedText right) {
        if (left.pageIndex() != right.pageIndex()) return false;
        if (left.width() <= 0 || right.width() <= 0) return false;
        float gap = right.x() - left.endX();
        // Negative means the runs overlap, which is not the one-space adjacency this looks for.
        if (gap < 0 || gap > HEADER_RUN_JOIN_MAX_GAP) return false;
        if (RUPEE_ARTIFACT_MARKER_COLUMN.equals(right.text().trim())) return false;
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
                                           List<Float> headerEnds, DocumentContext ctx, Set<Integer> candidateYears) {
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
            //
            // The existing-value check also accepts a YEARLESS date (resolveYearlessDate against
            // this row's own candidateYears) -- without it, a real Standard Chartered savings
            // statement whose date column holds a bare "May 01" never triggered this redirect at
            // all: CsvParser.parseDate("May 01") is null (no year), so the column read as "not yet
            // holding a real date" even after hasDateValue (a few lines up the call chain) had
            // already recognized it as one, and the narration that follows kept overwriting it
            // instead of advancing to Description.
            if (existing != null && isDateColumn(columnName)
                    && (CsvParser.parseDate(existing.trim()) != null
                            || resolveYearlessDate(existing.trim(), candidateYears) != null)
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

    /** Joins a row's members left-to-right by x -- NOT in whatever order {@link #groupIntoRows}'
     *  Y-primary sort happened to leave them in. Row membership (which runs share a physical line)
     *  is decided entirely by groupIntoRows and is correct; this method only decides read order
     *  within an already-correct row. Sorts a defensive copy (stable, so genuinely tied x -- e.g.
     *  overlapping/stacked glyphs -- keeps its prior relative order rather than being reshuffled)
     *  so callers holding the original row list see no side effect.
     *
     *  <p>Found via a real SBI branch-code field whose colon ran a hair below its label's y --
     *  common sub-point baseline jitter between punctuation and letters/digits on the same printed
     *  line -- which Y-primary sort placed before the label despite x making the true order
     *  unambiguous. Corpus-wide measurement (2026-08-29, real 27-doc corpus) found this affects
     *  22/27 documents and 7.9% of all physical rows, including transaction-shaped rows in at
     *  least one document previously believed clean -- not a rare edge case. */
    private String lineOf(List<PositionedText> row) {
        List<PositionedText> ordered = new ArrayList<>(row);
        ordered.sort(Comparator.comparing(PositionedText::x));
        StringBuilder line = new StringBuilder();
        for (PositionedText t : ordered) {
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
        return isTransactionShapedRow(row, Set.of());
    }

    /** Same contract as {@link #isTransactionShapedRow(List)}, plus {@code candidateYears} -- see
     *  {@link #resolveYearlessDate}. Kept as a genuine overload rather than changing the
     *  single-argument signature everywhere: this file has several call sites for the plain form
     *  (the pageLegendBlockActive resume check and the dropped-candidate evidence recorder among
     *  them), and only the INFERRED_HEADERLESS_LAYOUT call sites have year context worth passing.
     *  An empty candidate set makes {@link #resolveYearlessDate} always return null, so the plain
     *  overload's behaviour for every untouched call site is unchanged. */
    private boolean isTransactionShapedRow(List<PositionedText> row, Set<Integer> candidateYears) {
        boolean hasDate = false;
        boolean hasAmount = false;
        for (PositionedText cell : row) {
            String text = cell.text().trim();
            if (!hasDate && (CsvParser.parseDate(text) != null
                    || resolveYearlessDate(text, candidateYears) != null)) hasDate = true;
            if (!hasAmount && text.contains(".") && CsvParser.parseNumeric(text) != null) hasAmount = true;
        }
        return hasDate && hasAmount;
    }

    /** Test-only accessor -- see {@link #isTransactionShapedRow(List, Set)}. */
    boolean isTransactionShapedRowForTest(List<PositionedText> row, Set<Integer> candidateYears) {
        return isTransactionShapedRow(row, candidateYears);
    }

    // The only two structural facts isTransactionShapedRow's own gate can attest to -- fixed
    // rather than computed per-call, since by construction both are true whenever that gate
    // passes. Carried as signals, never the row's own text (see DroppedCandidateRow's own doc
    // comment on why raw content never belongs in this evidence).
    private static final java.util.Set<String> TRANSACTION_SHAPE_SIGNALS =
            java.util.Set.of("DATE_PRESENT", "AMOUNT_PRESENT");

    /** Row-accounting evidence: called at a drop point right before a physical row is discarded
     *  with no other trace. Appends nothing when the row doesn't have transaction shape --
     *  ordinary boilerplate (a page number, an address line, a disclaimer sentence) is not
     *  evidence of anything and must not manufacture a false review signal the way flagging every
     *  discarded line would. See {@link ExtractionEvidence}'s own doc comment for why this lives
     *  on {@link LocatedSection} rather than being reported some other way. */
    private void recordIfTransactionShaped(List<PositionedText> row, String reason,
                                            List<DroppedCandidateRow> pendingDroppedCandidates) {
        if (isTransactionShapedRow(row)) {
            pendingDroppedCandidates.add(new DroppedCandidateRow(reason, TRANSACTION_SHAPE_SIGNALS));
        }
    }

    // A bare day+month token with no year -- "30JUN"/"30 JUN"/"30-JUN" -- the shape a real HSBC
    // credit-card statement prints its transaction dates in, relying on the statement period
    // printed once elsewhere for the year. Matched against real month abbreviations specifically
    // (not just "digits then letters") to stay narrow: CsvParser.DATE_FORMATS has no yearless
    // pattern, and never will -- see looksLikeFinancialActivityCandidate's own doc comment for why
    // this shape stays out of general date parsing. resolveYearlessDate is the one place this DOES
    // get resolved to a real LocalDate, evidence-gated on a candidateYears set every caller has to
    // supply explicitly -- never by loosening CsvParser itself. Two capturing groups (day, month
    // abbreviation) so resolveYearlessDate can pull both back out without re-parsing the string a
    // second way.
    private static final Pattern WEAK_DAY_MONTH = Pattern.compile(
            "(?i)^(\\d{1,2})[\\s-]?(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)$");

    // The same shape, month first -- "May 01"/"MAY-01"/"MAY01" -- a real Standard Chartered
    // savings statement's own table rows, relying on the account-summary block's own "STATEMENT
    // DATE : 01 May 2026 To 31 May 2026" line (a full, year-bearing date CsvParser.parseDate
    // already resolves) for the year, exactly the way WEAK_DAY_MONTH's HSBC statement relies on
    // its header. A separate pattern rather than a reordered capture on WEAK_DAY_MONTH -- both are
    // narrow, evidence-shaped patterns matched against a real document's own printed order, and
    // reordering one into "try either group order" would accept a shape neither real document
    // actually prints, which is exactly the fabrication resolveYearlessDate's own doc comment says
    // this capability refuses to do.
    private static final Pattern WEAK_MONTH_DAY = Pattern.compile(
            "(?i)^(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[\\s-]?(\\d{1,2})$");

    // Small, explicit map rather than Month.valueOf(String) -- java.time's Month enum only parses
    // full English names ("JUNE"), not the three-letter abbreviations WEAK_DAY_MONTH matches
    // ("JUN"), and a TextStyle-based lookup pulls in a Locale-dependent parser this file has
    // deliberately avoided everywhere else (see DATE_FORMAT's own Locale.ENGLISH pin in
    // StatementTitleDateRangeExtractor for why: locale-implicit parsing is exactly the kind of bug
    // class this project keeps re-discovering).
    private static final Map<String, Integer> MONTH_ABBREVIATIONS = Map.ofEntries(
            Map.entry("JAN", 1), Map.entry("FEB", 2), Map.entry("MAR", 3), Map.entry("APR", 4),
            Map.entry("MAY", 5), Map.entry("JUN", 6), Map.entry("JUL", 7), Map.entry("AUG", 8),
            Map.entry("SEP", 9), Map.entry("OCT", 10), Map.entry("NOV", 11), Map.entry("DEC", 12));

    /**
     * Page index to the set of calendar years appearing among that page's own full, year-bearing
     * dates ({@link CsvParser#parseDate} already succeeds on these -- this does not introduce a
     * new date shape, only collects the year off ones already recognized). Scoped per page, not
     * per document: a statement whose transaction table spans a year boundary must not let one
     * page's year silently leak onto another page's yearless dates.
     *
     * <p>Computed once per document, from every physical row {@link #inferHeaderlessSection}
     * already has in hand -- not just the transaction-shaped candidates, since the full dates that
     * supply year context (a statement period, a payment due date) live in the surrounding
     * account-summary rows this capability's candidate filter deliberately excludes.
     */
    private Map<Integer, Set<Integer>> yearsByPage(List<List<PositionedText>> rows) {
        Map<Integer, Set<Integer>> result = new HashMap<>();
        for (List<PositionedText> row : rows) {
            for (PositionedText cell : row) {
                LocalDate parsed = CsvParser.parseDate(cell.text().trim());
                if (parsed != null) {
                    result.computeIfAbsent(cell.pageIndex(), k -> new HashSet<>()).add(parsed.getYear());
                }
            }
        }
        return result;
    }

    /**
     * Resolves a {@link #WEAK_DAY_MONTH}- or {@link #WEAK_MONTH_DAY}-shaped string ("30JUN" or
     * "May 01", no year) to the one calendar date it unambiguously names, using {@code
     * candidateYears} -- typically the years already seen among other full dates on the same page
     * (see {@link #yearsByPage}). Motivated by two real documents printing their transaction dates
     * exactly these two shapes, each relying on the statement period printed elsewhere for the
     * year -- see each pattern's own doc comment for which.
     *
     * <p>Never guesses: returns {@code null} when zero candidate years produce a valid calendar
     * date (e.g. "29FEB" against a non-leap year) or when two or more candidate years each
     * produce a DIFFERENT valid date (genuinely ambiguous -- a statement spanning a year boundary
     * could see both "31DEC" and "01JAN" become resolvable against either year without this
     * check). Matches this file's "fail-safe over fabrication" discipline established throughout
     * the headerless-layout capability: see this capability's own top-level doc comment above
     * {@link #HEADERLESS_COLUMN_CLUSTER_TOLERANCE}.
     *
     * <p>Deliberately separate from {@link CsvParser#parseDate}, not an addition to it -- see
     * {@link #WEAK_DAY_MONTH}'s own doc comment for why a yearless pattern stays local to this
     * evidence-gated capability rather than becoming a general date shape every caller of {@code
     * CsvParser} would then also accept.
     */
    private LocalDate resolveYearlessDate(String text, Set<Integer> candidateYears) {
        String trimmed = text.trim();
        int day;
        Integer month;
        Matcher dayFirst = WEAK_DAY_MONTH.matcher(trimmed);
        if (dayFirst.matches()) {
            day = Integer.parseInt(dayFirst.group(1));
            month = MONTH_ABBREVIATIONS.get(dayFirst.group(2).toUpperCase(Locale.ENGLISH));
        } else {
            Matcher monthFirst = WEAK_MONTH_DAY.matcher(trimmed);
            if (!monthFirst.matches()) return null;
            month = MONTH_ABBREVIATIONS.get(monthFirst.group(1).toUpperCase(Locale.ENGLISH));
            day = Integer.parseInt(monthFirst.group(2));
        }
        if (month == null) return null;

        Set<LocalDate> resolved = new HashSet<>();
        for (int year : candidateYears) {
            try {
                resolved.add(LocalDate.of(year, month, day));
            } catch (DateTimeException notARealCalendarDate) {
                // e.g. 29 Feb against a non-leap year -- excluded, not coerced.
            }
        }
        return resolved.size() == 1 ? resolved.iterator().next() : null;
    }

    /** Test-only accessor -- see {@link #resolveYearlessDate}. */
    LocalDate resolveYearlessDateForTest(String text, Set<Integer> candidateYears) {
        return resolveYearlessDate(text, candidateYears);
    }

    /** Test-only accessor -- see {@link #yearsByPage}. */
    Map<Integer, Set<Integer>> yearsByPageForTest(List<List<PositionedText>> rows) {
        return yearsByPage(rows);
    }

    /**
     * A deliberately SEPARATE, more permissive question from {@link #isTransactionShapedRow}:
     * "does this row look like it might be financial activity worth a human's attention" is not
     * "should this row be imported as a transaction", and the two must never share one
     * implementation. {@code isTransactionShapedRow} is also the gate {@link
     * #inferHeaderlessSection} uses to decide what to actually STAGE -- loosening ITS date check
     * to accept a yearless "30JUN" would loosen what that path is willing to import too, an
     * entirely different risk with an entirely different cost of being wrong. This method is read
     * only by evidence recording (see {@link #recordIfFinancialActivityCandidate}); it must never
     * be called from any staging or normalization path.
     *
     * <p>Requires THREE signals, not {@code isTransactionShapedRow}'s two: a date (the same
     * {@link CsvParser#parseDate} check, OR the weaker {@link #WEAK_DAY_MONTH} shape), an amount,
     * AND a description-like cell distinct from both. The third signal exists because two is not
     * enough here specifically: a real Loan Summary/EMI-schedule table row (e.g. a loan's own
     * booking date and principal amount) has a genuine date and a genuine amount and would
     * otherwise misfire this detector on every such table -- confirmed against a real HSBC
     * credit-card statement's own Loan Summary table, whose rows fail {@code
     * TransactionNormalizer} for the unrelated reason that "Loan Booking Date" is not a recognized
     * transaction-date column. A description-like cell (multiple words, or one long alphanumeric
     * token -- see {@link #looksLikeDescriptionText}) does not fully close this gap: a loan row's
     * own merchant-name cell can look identical in shape to real narration. What actually keeps
     * this narrow is where it's called from -- only rows scanned BEFORE the document's first
     * accepted header, where a table's own data rows essentially never appear (a header prints
     * before its rows, by construction), so the residual risk is a loan-shaped row accidentally
     * appearing pre-header, not every loan table in the corpus.
     *
     * <p>On top of the three-signal gate, ALSO refuses outright when any cell names a
     * non-transaction financial product ({@link #NON_TRANSACTION_PRODUCT_HINTS} -- loan, EMI/
     * instalment, fixed/recurring deposit, maturity, premium/policy, mutual-fund folio/NAV). Those
     * products belong to their own future domains (Loans/Liabilities, Investments/Deposits), not
     * the transaction ledger this evidence exists to protect, and each one carries its own genuine
     * date and amount by construction -- exactly the shape a plain three-signal gate cannot tell
     * apart from a real transaction.
     *
     * <p>Deliberately excludes two common words precisely BECAUSE they are common: "interest" and
     * "deposit" are both legitimate real-transaction vocabulary on an ordinary savings account
     * ("INTEREST CREDITED", "SB INT", "CASH DEPOSIT" -- the latter is even one of {@code
     * HEADER_HINTS}' own real column names). This list can only ever trade one risk for the other
     * -- a keyword that's too broad silently suppresses evidence of a REAL missing transaction,
     * which this whole rule exists to catch in the first place, the exact "false success" class
     * judged worse than a false warning. So the list stays narrow and product-specific: single
     * words unlikely to appear in ordinary transaction narration ("loan", "principal", "emi",
     * "tenure", "maturity", ...), plus a few multi-word phrases ("fixed deposit", "interest rate")
     * that are safe specifically because they require the WHOLE cell to match, not a substring --
     * see {@link #matchesAnyHint}'s own two-tier behaviour. Grow this list only against a real
     * document that needs it, the same discipline {@code HEADER_HINTS} has followed throughout.
     */
    private boolean looksLikeFinancialActivityCandidate(List<PositionedText> row) {
        boolean hasDate = false;
        boolean hasAmount = false;
        boolean hasDescription = false;
        for (PositionedText cell : row) {
            String text = cell.text().trim();
            if (text.isEmpty()) continue;
            if (matchesAnyHint(text, NON_TRANSACTION_PRODUCT_HINTS)) return false;
            boolean isDate = CsvParser.parseDate(text) != null || WEAK_DAY_MONTH.matcher(text).matches();
            boolean isAmount = text.contains(".") && CsvParser.parseNumeric(text) != null;
            if (isDate) hasDate = true;
            if (isAmount) hasAmount = true;
            if (!isDate && !isAmount && looksLikeDescriptionText(text)) hasDescription = true;
        }
        return hasDate && hasAmount && hasDescription;
    }

    // Named financial products this evidence must stay out of, not just the Loan Summary table
    // that originally motivated the three-signal gate above -- an FD/RD/EMI/insurance row has its
    // own genuine date and amount just as legitimately, and belongs to a future Loans/Liabilities
    // or Investments/Deposits domain, never the transaction ledger. Reuses matchesAnyHint (already
    // used for HEADER_HINTS) so single-word and multi-word entries are both matched consistently
    // with the rest of this class, per-cell rather than as a whole-row substring search.
    //
    // A RISK-CONTROL LAYER, NOT A CLASSIFICATION SYSTEM -- this list only prevents an obvious
    // loan/FD/RD/EMI-shaped row from being counted as evidence of a MISSING TRANSACTION. It does
    // not identify, classify, or route those rows anywhere; a document whose only content is a
    // Loan Summary table is not "handled" by this list, it is simply not miscounted as a lost
    // transaction. Real loan/FD/RD/EMI classification -- recognizing these as their own financial
    // products and feeding them to a future Loans/Liabilities or Investments/Deposits domain --
    // does not exist yet anywhere in this codebase. Don't read a hit against this list as "this
    // document's loan data was captured" a year from now; it wasn't, on purpose, out of scope here.
    private static final List<String> NON_TRANSACTION_PRODUCT_HINTS = List.of(
            "loan", "principal", "emi", "tenure", "instalment", "installment", "maturity",
            "premium", "policy", "folio", "nav",
            "loan booking", "fixed deposit", "recurring deposit",
            "interest rate", "interest calculation");

    /** Free-form narration, distinguished from a short structured label or value: either multiple
     *  whitespace-separated words (a bill-payment narration followed by its own long alphanumeric
     *  reference token), or one long token that mixes letters and digits on its own (a reference
     *  string a word-count check alone would miss). A bare short word ("CR", "Fee", "Dr") satisfies
     *  neither and is not description text. */
    private boolean looksLikeDescriptionText(String text) {
        String[] words = text.split("\\s+");
        if (words.length >= 2) return true;
        return text.length() >= 8 && text.chars().anyMatch(Character::isLetter)
                && text.chars().anyMatch(Character::isDigit);
    }

    // Three signals, not isTransactionShapedRow's two -- see looksLikeFinancialActivityCandidate's
    // own doc comment for why DESCRIPTION_PRESENT is required here specifically.
    private static final java.util.Set<String> FINANCIAL_ACTIVITY_CANDIDATE_SIGNALS =
            java.util.Set.of("DATE_PRESENT", "AMOUNT_PRESENT", "DESCRIPTION_PRESENT");

    /** Same shape as {@link #recordIfTransactionShaped}, backed by the more permissive/narrower-
     *  scoped {@link #looksLikeFinancialActivityCandidate} instead -- see that method's own doc
     *  comment for why the two gates are deliberately not the same implementation. */
    private void recordIfFinancialActivityCandidate(List<PositionedText> row, String reason,
                                                      List<DroppedCandidateRow> pendingDroppedCandidates) {
        if (looksLikeFinancialActivityCandidate(row)) {
            pendingDroppedCandidates.add(new DroppedCandidateRow(reason, FINANCIAL_ACTIVITY_CANDIDATE_SIGNALS));
        }
    }

    /**
     * Header-reconstruction evidence: records a row's transaction-ledger vocabulary when it failed
     * a multi-line header merge (see the caller's own comment for exactly when this is invoked).
     * Corpus-measured gate, not a guess: requires TWO OR MORE DISTINCT {@link #AMOUNT_COLUMN_HINTS}
     * words, not just an occurrence count -- a real document's own two-column-layout tariff/legal
     * pages repeat a single word ("credit" appearing in "Credit Card Number", "Credit Limit",
     * "Available Credit Limit") often enough that a plain count alone produced false positives in
     * the corpus study this evidence is built from. Distinct-word diversity alone was ALSO measured
     * and found insufficient by itself (a real, correctly-extracted document's own account-summary
     * grid can legitimately combine two or three of these words) -- see {@link
     * #LOW_CONFIDENCE_TRANSACTION_HEADER_COLUMN_COUNT}'s own doc comment for the second signal this
     * is only ever combined with, in {@link #closeCurrentSection}, never here alone.
     */
    private void recordIfHeaderReconstructionCandidate(List<PositionedText> row,
                                                         List<java.util.Set<String>> pendingHeaderReconstructionVocab) {
        java.util.Set<String> distinctWords = new java.util.LinkedHashSet<>();
        for (PositionedText cell : row) {
            String text = cell.text().trim();
            if (text.isEmpty()) continue;
            for (String word : AMOUNT_COLUMN_HINTS) {
                if (matchesAnyHint(text, List.of(word))) distinctWords.add(word);
            }
        }
        if (distinctWords.size() >= 2) {
            pendingHeaderReconstructionVocab.add(distinctWords);
        }
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
     *  defined its own cluster.
     *
     *  <p>{@code yearsByPage} lets a cell's date-ness be recognised even when it's {@link
     *  #WEAK_DAY_MONTH}-shaped rather than a full {@link CsvParser#parseDate}-able date -- without
     *  this, a real date column built entirely from yearless cells would score a
     *  {@link ColumnStats#dateFraction()} of zero and never be recognised as the date column at
     *  all, even after {@link #isTransactionShapedRow}'s own admission gate already resolved the
     *  same cells via {@link #resolveYearlessDate}. */
    private List<ColumnStats> clusterIntoColumns(List<List<PositionedText>> transactionRows,
            Map<Integer, Set<Integer>> yearsByPage) {
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
                Set<Integer> pageYears = yearsByPage.getOrDefault(cell.pageIndex(), Set.of());
                if (CsvParser.parseDate(text) != null || resolveYearlessDate(text, pageYears) != null) {
                    dateCount++;
                }
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

    /** Rows, non-transaction text, and row-accounting evidence collected in the same pass -- see
     *  {@link #inferHeaderlessSection} for why the auxiliary text matters as much as the rows (it
     *  feeds product/identity classification downstream, exactly as the header-based path's own
     *  pendingAuxiliary does). {@code droppedTransactionCandidates} is what makes the adjacent-
     *  duplicate drop below an accountable fate instead of a silent one -- see this method's own
     *  doc comment. */
    private record HeaderlessBucketResult(List<Map<String, String>> rows, List<String> auxiliaryText,
            List<DroppedCandidateRow> droppedTransactionCandidates) {}

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
     *  lines between the original and its reprint don't defeat the comparison.
     *
     *  <p><b>This drop, unlike {@link #dedupeAdjacentIdenticalRows}'s own, is recorded as row-
     *  accounting evidence ({@code REPEATED_PHYSICAL_ROW_REMOVED}) and as a capability activation
     *  ({@code PHYSICAL_ROW_DEDUP_EVIDENCE}, recorded only when a removal actually happens, not
     *  merely when this method runs).</b> The reason code names the EVENT ("a repeated row was
     *  removed"), not the detection mechanism that found it ("rows looked adjacent-identical") --
     *  a name like the latter reads, out of context, as ambiguous about whether the row survived.
     *  The two dedup passes look similar but are not the same fact: {@code dedupeAdjacentIdenticalRows}
     *  only cleans the candidate list fed into column-role scoring -- a row it drops can still reach
     *  this method's own independent scan of {@code allRows} and end up staged, so recording it as
     *  "dropped from output" there would be false evidence. This method's drop is different -- it
     *  removes the row from {@code result}, the list that becomes {@link LocatedSection#rows()} --
     *  so this is the one point that actually corresponds to "this row will not reach the user," and
     *  the one point where recording that fact is honest. */
    /**
     * Returns {@code row} unchanged unless one of its cells is {@link #WEAK_DAY_MONTH}-shaped and
     * resolves unambiguously via {@link #resolveYearlessDate} -- in which case that ONE cell is
     * replaced with an equivalent {@link PositionedText} (same x/y/page/width, so bucketing-by-
     * position is unaffected) whose text is the resolved date formatted "dd MMM yyyy", a shape
     * {@link CsvParser#parseDate} already accepts (see its own {@code DATE_FORMATS} list). Without
     * this substitution, {@link #isTransactionShapedRow}'s admission gate would accept the row but
     * the RAW yearless text would still reach {@code TransactionNormalizer} downstream via {@link
     * #bucketRow}'s stored value, which parses dates through the exact same {@link
     * CsvParser#parseDate} call and would reject it there instead -- moving the failure one layer
     * down rather than fixing it.
     */
    private List<PositionedText> substituteYearlessDates(List<PositionedText> row, Set<Integer> pageYears) {
        List<PositionedText> result = null;
        for (int i = 0; i < row.size(); i++) {
            PositionedText cell = row.get(i);
            LocalDate resolved = resolveYearlessDate(cell.text().trim(), pageYears);
            if (resolved != null) {
                if (result == null) result = new ArrayList<>(row);
                String formatted = resolved.format(
                        DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH));
                result.set(i, new PositionedText(formatted, cell.x(), cell.y(), cell.pageIndex(),
                        cell.width(), cell.height(), cell.confidence(), cell.source()));
            }
        }
        return result != null ? result : row;
    }

    private HeaderlessBucketResult bucketHeaderlessRowsWithContinuation(List<List<PositionedText>> allRows,
            List<String> headerNames, List<Float> headerAnchors, List<Float> headerEnds, DocumentContext ctx,
            Map<Integer, Set<Integer>> yearsByPage) {
        List<Map<String, String>> result = new ArrayList<>();
        List<String> auxiliaryText = new ArrayList<>();
        List<DroppedCandidateRow> droppedTransactionCandidates = new ArrayList<>();
        Map<String, String> currentAnchor = null;
        int continuationCount = 0;
        String previousTransactionLine = null;
        int lastPageIndex = -1;
        for (List<PositionedText> r : allRows) {
            if (!r.isEmpty()) lastPageIndex = Math.max(lastPageIndex, r.get(0).pageIndex());
        }
        for (List<PositionedText> row : allRows) {
            String rowLine = lineOf(row);
            if (PAGE_FOOTER.matcher(rowLine).find()) continue;
            // Every TRAILING_CONTENT_TRIGGERS marker, not just STATEMENT_CLOSING_MARKER alone --
            // this headerless path used to check only that one trigger, so a document that falls
            // back to headerless inference (no recognized column vocabulary at all) got none of the
            // other real-document-evidenced closing markers the header-based path already has.
            // Confirmed on a real SBI savings statement (Sanjay SBI.pdf): its own "Statement
            // Summary" balance-recap block, printed after the last real transaction, was swept into
            // that transaction's own trailing narration here even after STATEMENT_SUMMARY_BLOCK_MARKER
            // was added to TRAILING_CONTENT_TRIGGERS, because this loop never consulted that list.
            // A permanent break, same as every trigger's meaning in the header-based path -- none of
            // these markers is a per-page, resumable thing the way PAGE_FOOTER is.
            int rowPageIndex = row.isEmpty() ? -1 : row.get(0).pageIndex();
            String trailingTrigger = trailingContentTriggerCapability(rowLine, rowPageIndex, lastPageIndex);
            if (trailingTrigger != null) {
                recordTrailingContentTrigger(ctx, trailingTrigger);
                break;
            }
            Set<Integer> rowYears = row.isEmpty() ? Set.of()
                    : yearsByPage.getOrDefault(row.get(0).pageIndex(), Set.of());
            // Substituted before bucketRow specifically -- see substituteYearlessDates's own doc
            // comment for why the ADMISSION check (isTransactionShapedRow) and the STORED value
            // both need to see the resolved date, not just the former: isTransactionShapedRow
            // passing on the raw "30JUN" text is not enough, because bucketRow would otherwise
            // store that same raw text under the Date column, and TransactionNormalizer downstream
            // parses it through the exact same CsvParser.parseDate call that rejected it here.
            List<PositionedText> resolvedRow = substituteYearlessDates(row, rowYears);
            if (isTransactionShapedRow(resolvedRow, rowYears)) {
                if (rowLine.equals(previousTransactionLine)) {
                    recordIfTransactionShaped(row, "REPEATED_PHYSICAL_ROW_REMOVED", droppedTransactionCandidates);
                    if (ctx != null) ctx.record("PHYSICAL_ROW_DEDUP_EVIDENCE");
                    continue;
                }
                Map<String, String> bucketed = bucketRow(resolvedRow, headerNames, headerAnchors, headerEnds, ctx,
                        rowYears);
                if (bucketed.isEmpty()) continue;
                result.add(bucketed);
                currentAnchor = bucketed;
                continuationCount = 0;
                previousTransactionLine = rowLine;
            } else if (currentAnchor != null && continuationCount < MAX_BLOCK_CONTINUATION_ROWS) {
                Map<String, String> bucketed = bucketRow(resolvedRow, headerNames, headerAnchors, headerEnds, ctx,
                        rowYears);
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
        return new HeaderlessBucketResult(result, auxiliaryText, droppedTransactionCandidates);
    }

    /** Entry point for the whole INFERRED_HEADERLESS_LAYOUT capability -- see its top-level doc
     *  comment above {@link #HEADERLESS_COLUMN_CLUSTER_TOLERANCE}. Returns null, never partially,
     *  when the document doesn't fit this shape well enough to trust; the caller's contract on null
     *  is "leave sections exactly as they were" -- today's zero-section outcome. */
    private LocatedSection inferHeaderlessSection(List<List<PositionedText>> rows, DocumentContext ctx) {
        // Computed once, from every physical row (not just the transaction-shaped candidates
        // below) -- see yearsByPage's own doc comment for why the full dates that supply year
        // context live in the surrounding account-summary rows this candidate filter excludes.
        Map<Integer, Set<Integer>> yearsByPage = yearsByPage(rows);
        List<List<PositionedText>> candidates = new ArrayList<>();
        for (List<PositionedText> row : rows) {
            Set<Integer> rowYears = row.isEmpty() ? Set.of()
                    : yearsByPage.getOrDefault(row.get(0).pageIndex(), Set.of());
            if (isTransactionShapedRow(row, rowYears)) candidates.add(row);
        }
        candidates = dedupeAdjacentIdenticalRows(candidates);
        if (candidates.size() < HEADERLESS_MIN_TRANSACTION_ROWS) {
            if (ctx != null) ctx.recordDiagnostic("HEADERLESS_TOO_FEW_TRANSACTION_ROWS");
            return null;
        }

        List<ColumnStats> columns = clusterIntoColumns(candidates, yearsByPage);
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

        HeaderlessBucketResult bucketResult = bucketHeaderlessRowsWithContinuation(rows, headerNames, headerAnchors, headerEnds, ctx, yearsByPage);
        if (bucketResult.rows().isEmpty()) {
            if (ctx != null) ctx.recordDiagnostic("HEADERLESS_FINAL_BUCKETING_EMPTY");
            return null;
        }
        if (ctx != null) ctx.record("INFERRED_HEADERLESS_LAYOUT");
        // Header-reconstruction evidence is out of scope for this path: there is no header-merge
        // decision to have failed here at all, by construction -- this path only ever runs once
        // the header-based loop has already found nothing across the WHOLE document.
        return new LocatedSection(bucketResult.auxiliaryText(), bucketResult.rows(),
                new ExtractionEvidence(bucketResult.droppedTransactionCandidates(), List.of()));
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
        return new LocatedSection(auxiliaryText, resultRows, ExtractionEvidence.NONE);
    }
}
