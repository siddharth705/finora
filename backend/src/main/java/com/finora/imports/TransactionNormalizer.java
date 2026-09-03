package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.CategoryRule;
import com.finora.service.CategorizationService;
import com.finora.service.MerchantNormalizationEngine;
import com.finora.service.RuleEngineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Turns one header-keyed row map (from {@link CsvParser}) into a normalized {@link StagedRow}:
 * resolves which columns are date/amount/type/description, auto-suggests a category, and flags
 * likely duplicates. This is the single place row-level business meaning gets attached to a raw
 * CSV row — everything upstream of this is mechanical parsing, everything downstream is either
 * user review (staging) or a straight write (confirm).
 */
@Component
public class TransactionNormalizer {

    private final CategorizationService categorizationService;
    private final DuplicateDetector duplicateDetector;
    private final RuleEngineService ruleEngineService;
    private final MerchantNormalizationEngine merchantNormalizationEngine;

    // Single source of truth for every column name this class recognizes -- shared by normalize(),
    // explainFailure(), and recognizedColumnNames() so the three can never drift out of sync (they
    // used to be three separate literal lists; a real regression came from exactly that kind of
    // drift once already, see StagedRow.description's own history). "balance" is last-resort on
    // purpose: a PDF statement's OPENING BALANCE/CLOSING BALANCE rows (see
    // PdfPreviewGenerator/PdfTableLocator) carry no debit or credit value at all -- only a Balance
    // column -- so without this fallback those two rows have a date but no recognizable amount and
    // get silently dropped, before PdfPreviewGenerator's own balancePoints logic ever sees them.
    // "due date" last, and last on purpose. Verified against a real HDFC combined statement whose
    // recurring-deposit schedule prints an installment table headed "Installment Number / Install.
    // Due Date / Amount Paid / Installment Paid Due / Installment Paid Status". PdfTableLocator
    // reconstructs that table correctly -- all six installments staged with their dates, amounts,
    // dues, statuses and running balances intact -- and then every one of them was dropped here
    // with "No column recognized as a date", because firstNonBlank matches a column name by EXACT
    // equality and "due date" was in no list. Six real transactions lost with the extraction
    // already perfect, on the one document in the corpus that fails its ground-truth gate.
    //
    // Ordered last so a table carrying BOTH a transaction date and a due date still resolves to the
    // transaction date: firstNonBlank returns the first hint that matches any column, so every
    // existing name keeps precedence and no document that parses today can change.
    //
    // Exact equality is also why this cannot catch a credit card's "Payment Due Date" metadata
    // field, which normalizes to "payment due date" and is a different string. Checked across the
    // 29-document real corpus: exactly one document has a column named "Due Date", and it is this
    // one.
    private static final String[] DATE_HINTS =
            {"date", "transaction_date", "txn date", "transaction date", "value date", "date & time",
             "due date"};
    // Bug fix: verified against a real Kotak Mahindra Bank statement -- its columns are literally
    // named "Deposit (Cr.)" / "Withdrawal (Dr.)", which CsvParser.normalizeHeaderCell reduces to
    // the SINGULAR "deposit"/"withdrawal" (the parenthesized "(Cr.)"/"(Dr.)" suffix strips the
    // same way a currency suffix like "Amount (Rs.)" does) -- neither of which matched anything
    // in this list before (only the plural "deposits"/"withdrawals" did). Every row on that
    // statement silently fell all the way through to the "balance" fallback instead: every
    // transaction staged with its AMOUNT showing as the account's running BALANCE, and every
    // transaction -- including genuine UPI credits -- staged as an EXPENSE, since "deposit"/
    // "withdrawal" weren't recognized as amount OR credit signals at all. Not a dropped-row bug
    // (which would have been obvious) -- a silently-wrong-data bug, worse in kind.
    // Split out of AMOUNT_HINTS specifically so RowKind classification can ask "did any REAL
    // debit/credit/amount column have a value" without touching how amountRaw itself is resolved
    // (AMOUNT_HINTS below is still exactly this list followed by BALANCE_HINTS, same order, same
    // behavior). This is the structural signal RowKind.BALANCE_MARKER is built on: a statement's
    // OPENING BALANCE/CLOSING BALANCE row (or "Beginning Balance"/"Balance Forward"/whatever a
    // given bank calls it) never has a value in any of these columns -- only in a Balance-style
    // column, which is exactly why AMOUNT_HINTS needed the fallback in the first place. See
    // RowKind's own doc comment: the classification reads which COLUMN produced the amount, never
    // the row's description text, so it does not depend on -- or need to enumerate -- how any
    // particular bank phrases its balance rows.
    private static final String[] TRANSACTION_AMOUNT_HINTS = {"amount", "debit", "credit",
            "dr amount", "cr amount", "debit amount", "credit amount",
            "withdrawal amt", "withdrawal amount", "deposit amt", "deposit amount",
            "deposit", "withdrawal", "deposits", "withdrawals"};
    // "amount paid" from the same real HDFC recurring-deposit schedule as DATE_HINTS' "due date"
    // above. That table's amount column is headed "Amount Paid", which normalizes to "amount paid"
    // and, under firstNonBlank's exact-equality match, equalled nothing -- so once the date was
    // recognized the same six rows were dropped one step later with "No column recognized as an
    // amount or balance". Placed after the transaction-amount names and before the balance
    // fallbacks, so a table carrying a real debit/credit column still resolves to that column and
    // only a table with no other amount name reaches this one.
    //
    // Deliberately NOT paired with a fix for that table's "Running balance**" column, whose
    // trailing footnote asterisks normalizeHeaderCell does not strip (it strips [.,;:] only). That
    // is a separate, wider change -- it would alter how every header ending in an asterisk is read
    // across the corpus -- and this table does not need it: "amount paid" is its real transaction
    // amount, and an installment schedule is not a running-balance ledger.
    private static final String[] AMOUNT_HINTS = {"amount", "debit", "credit",
            "dr amount", "cr amount", "debit amount", "credit amount",
            "withdrawal amt", "withdrawal amount", "deposit amt", "deposit amount",
            "deposit", "withdrawal", "deposits", "withdrawals",
            "amount paid",
            "balance", "running balance", "closing balance"};
    private static final String[] CREDIT_HINTS =
            {"credit", "cr amount", "credit amount", "deposit amt", "deposit amount", "deposit", "deposits"};
    // Bug fix, verified against a real Bandhan Bank savings statement: its direction column is
    // headed literally "Dr / Cr" -- the marker itself as the column name, with no "Type" anywhere
    // -- so the exact "type" hint never matched and typeRaw was null on every row. The existing
    // Dr/Cr support did not help either: that reads a marker SUFFIXED onto the amount cell
    // ("37.94 Dr"), and here the amount cell is a clean "INR15,000.00" with the marker in its own
    // column. With no Type column recognised and no Credit column in this layout at all, the
    // direction check below fell through to its final default and staged all three transactions --
    // including two genuine UPI credits of INR 15,000 and INR 10,000 -- as EXPENSE.
    //
    // That is the silently-wrong-data failure, not the visible dropped-row one: the amounts, dates
    // and balances would all have looked right in the review screen while the sign was inverted on
    // two thirds of the statement.
    //
    // Both spacings are listed because normalizeHeaderCell only strips a trailing parenthetical
    // and trailing punctuation -- it leaves interior spacing alone, so "Dr / Cr" and "Dr/Cr" reach
    // this list as genuinely different strings. (The already-covered "Type (DR/CR)" spelling still
    // matches "type": there the parenthetical is what gets stripped.)
    private static final String[] TYPE_HINTS = {"type", "dr / cr", "dr/cr", "cr / dr", "cr/dr"};
    // Bug fix, verified against a real SBI credit-card statement. PDFBox's extraction renders that
    // document's "Amount (₹)" sub-column literally as "( ` )" -- the same font-substitution quirk
    // CsvParser.parseNumeric's own "Rupee-as-C" comment describes, here landing on a column HEADER
    // instead of a value -- and every transaction row's Credit/Debit marker (a bare "C" or "D", per
    // the statement's own printed legend "C=Credit ; D=Debit") lands in that exact column. Cannot
    // simply be added to TYPE_HINTS above: normalizeHeaderCell strips a trailing parenthetical
    // unconditionally, so "( ` )" collapses to the EMPTY string -- the same key every genuinely
    // blank/spacer column in the whole corpus also produces, so a blank-header hint there would
    // match all of them, not just this one. Matched instead against the RAW, un-normalized header
    // key (see its one use site below), scoped to this single literal artifact string.
    private static final String RUPEE_ARTIFACT_TYPE_COLUMN = "( ` )";
    // "transaction id" is deliberately LAST -- lowest priority, only used when none of the real
    // description columns above have a value at all. Bug fix, verified against a real Union Bank
    // of India statement: its header row detects a "Remarks" column, but every actual data row's
    // narration text ends up bucketed under "Transaction Id" instead (a real column-anchor
    // artifact in this specific document -- the header token and the data values don't share a
    // column anchor), leaving "Remarks" permanently blank and every transaction staging with an
    // empty description -- which then also broke categorization (nothing to match against) and
    // silently pushed every row to "low confidence" in the review UI. "Transaction Id" isn't
    // treated as a description source in general (a genuine short reference-only "Transaction Id"
    // column on a different document shouldn't be surfaced as if it were the transaction's own
    // narration) -- it only fires here as a last resort, once "description"/"narration"/
    // "remarks"/etc. have all already had their chance and come up empty.
    // "transaction remarks" added: verified against a real ICICI savings e-statement, whose column
    // is headed exactly that -- two words, neither of which alone is "remarks", so the existing
    // bare "remarks" hint above never matched it (this class only ever compares a FULL normalized
    // header name, never a substring -- see firstNonZeroAmount/firstParseableAmount's own
    // equalsIgnoreCase checks). PdfTableLocator.recoverMissingDescriptionColumn recognizes the same
    // literal phrase when recovering the column in the first place; the two lists are independent
    // on purpose (one decides whether a column exists at all, this one decides what it means once
    // it does), but they need to agree on this document's exact wording or the recovered column
    // would still stage every transaction with an empty description.
    private static final String[] DESCRIPTION_HINTS =
            {"description", "narration", "remarks", "particulars", "transaction remarks",
                    "transaction description", "transaction details", "transaction id"};
    private static final String[] CATEGORY_HINTS = {"category"};
    // Phase 1 "capture facts" (docs/engineering/financial-document-intelligence-principles.md):
    // evidenced by a real Canara Bank statement's "Reference / Cheque No." column, silently
    // discarded until now even on rows that otherwise parsed successfully -- CsvParser.zipRow and
    // PdfTableLocator.bucketRow already capture every column into the row map, this was simply
    // never read back out. "instrument id" was already a PdfTableLocator.HEADER_HINTS entry used
    // only to detect a header row, never to capture a value -- same underlying gap, same fix.
    //
    // Bug fix: verified against a real Kotak Mahindra Bank statement, whose column is literally
    // "Chq/Ref. No." -- periods after BOTH "Ref" and "No", unlike the "chq/ref no" (no periods)
    // variant already covered.
    //
    // The entry below reads "chq/ref. no" -- interior period kept, trailing period gone -- because
    // normalizeHeaderCell now strips trailing punctuation but deliberately still leaves interior
    // punctuation alone. It previously stripped neither, so this entry had to be written as
    // "chq/ref. no." to match. That was a workaround at the call site for a gap in the normalizer,
    // and it stopped matching the moment the normalizer was fixed (which is how it was caught:
    // this list's own regression test went red). Worth noting as a pattern -- a hint spelled to
    // match a normalizer's quirks rather than the real-world string is coupled to those quirks.
    private static final String[] REFERENCE_HINTS =
            {"reference number", "ref no", "reference no", "cheque no", "chq no", "chq/ref no",
                    "chq/ref. no", "instrument id", "reference", "reference / cheque no"};
    // Deliberately separate from AMOUNT_HINTS, even though the literal column names overlap:
    // AMOUNT_HINTS' "balance"/"running balance"/"closing balance" entries exist as a last-resort
    // fallback AMOUNT for a summary row with no debit/credit column at all (see AMOUNT_HINTS' own
    // comment). This is a second, independent read of the same column for a different purpose --
    // an ordinary transaction row's running balance AFTER that transaction, not its amount.
    private static final String[] BALANCE_HINTS = {"balance", "running balance", "closing balance"};

    @Autowired
    public TransactionNormalizer(CategorizationService categorizationService, DuplicateDetector duplicateDetector,
                                  RuleEngineService ruleEngineService,
                                  MerchantNormalizationEngine merchantNormalizationEngine) {
        this.categorizationService = categorizationService;
        this.duplicateDetector = duplicateDetector;
        this.ruleEngineService = ruleEngineService;
        this.merchantNormalizationEngine = merchantNormalizationEngine;
    }

    /**
     * Pre-Phase-A shape, kept so the ~40 existing tests constructing this directly don't need to
     * change. Merchant resolution is additive: a normalizer built this way simply never populates
     * StagedRow.merchant/merchantConfidence, which is correct for every caller that doesn't pass one.
     */
    public TransactionNormalizer(CategorizationService categorizationService, DuplicateDetector duplicateDetector,
                                  RuleEngineService ruleEngineService) {
        this(categorizationService, duplicateDetector, ruleEngineService, null);
    }

    /**
     * Every column name this class treats as meaningful, across date/amount/type/description/
     * category recognition -- used by {@code PdfPipelineDiagnostic} to report which columns in a
     * real document weren't recognized by any capability yet (see "Never lose information" in
     * docs/engineering/financial-document-intelligence-principles.md: an unrecognized column today
     * may be exactly what motivates tomorrow's capability, and that's invisible unless something
     * reports it). Not used by normalize() itself, which still checks columns individually so it
     * can report *which* hint matched, not just that one did.
     */
    public static Set<String> recognizedColumnNames() {
        Set<String> names = new LinkedHashSet<>();
        for (String[] hints : new String[][]{DATE_HINTS, AMOUNT_HINTS, CREDIT_HINTS, TYPE_HINTS,
                DESCRIPTION_HINTS, CATEGORY_HINTS, REFERENCE_HINTS, BALANCE_HINTS}) {
            for (String hint : hints) names.add(hint);
        }
        return names;
    }

    // Same as CsvParser.firstNonBlank, but skips a hint match whose value doesn't parse as a
    // number at all, continuing to a lower-priority hint instead of committing to a value already
    // known to be unusable. Bug fix, verified against a real Canara Bank statement: its closing-
    // balance summary row puts the literal label text "Closing Balance" in what's otherwise the
    // Deposits column (the same column real deposit amounts use on every other row) -- without
    // this, that label matched the "deposits" hint first and the row's real amount (a genuine
    // last transaction's Withdrawals value, sharing this row with the closing-balance label) was
    // never reached; the row failed to parse a nonsense "amount" instead.
    /**
     * The first hint whose value parses to a NON-ZERO number.
     *
     * <p>Bug fix, verified against a real HDFC savings statement. A separate-columns layout does
     * not leave the unused side blank -- it prints {@code 0.00} there. On a withdrawal row that
     * gives {@code Withdrawals=436.00, Deposits=0.00}, and {@code 0.00} is neither blank nor
     * unparseable, so {@link #firstParseableAmount} committed to it the moment "deposits" was
     * checked (which AMOUNT_HINTS does before "withdrawals") and never reached the real figure.
     * Three withdrawals imported as amount 0 while the one genuine deposit on the same statement
     * imported correctly -- the shape that makes this hard to notice, since the file plainly
     * "worked".
     *
     * <p>It was wrong twice over: CREDIT_HINTS matched that same {@code 0.00} deposit cell, and
     * the direction check below only asks whether a credit column had a value at all -- so every
     * one of those withdrawals would have been classified INCOME once the amount was right. A zero
     * in the credit column is the layout's way of saying "not this side", which is the opposite of
     * what a non-null credit value is taken to mean.
     *
     * <p>Zero is still a legitimate amount in principle, so this does not reject it outright --
     * callers fall back to {@link #firstParseableAmount}, and a row whose only parseable value is
     * zero still normalizes to zero exactly as before.
     */
    private static String firstNonZeroAmount(Map<String, String> row, String[] hints) {
        for (String hint : hints) {
            for (Map.Entry<String, String> e : row.entrySet()) {
                if (e.getKey() != null && CsvParser.normalizeHeaderCell(e.getKey()).equalsIgnoreCase(hint)) {
                    String v = e.getValue();
                    if (v == null || v.isBlank()) continue;
                    var parsed = CsvParser.parseNumeric(v);
                    if (parsed != null && parsed.signum() != 0) return v;
                }
            }
        }
        return null;
    }

    private static String firstParseableAmount(Map<String, String> row, String[] hints) {
        for (String hint : hints) {
            for (Map.Entry<String, String> e : row.entrySet()) {
                if (e.getKey() != null && CsvParser.normalizeHeaderCell(e.getKey()).equalsIgnoreCase(hint)) {
                    String v = e.getValue();
                    if (v != null && !v.isBlank() && CsvParser.parseNumeric(v) != null) return v;
                }
            }
        }
        return null;
    }

    /**
     * True when this row carries a non-blank value under a column name none of this class's hint
     * lists (date/amount/credit/type/description/category/reference/balance --
     * {@link #recognizedColumnNames()}) recognize at all.
     *
     * <p>Used only to gate confidence in a {@link RowKind#BALANCE_MARKER} classification. A
     * genuine balance-marker row's entire non-blank content is accounted for by recognized
     * columns (e.g. a real {@code OPENING BALANCE} row: a description column and a Balance
     * column, with its debit/credit columns present but blank) -- that is a confident marker. A
     * row that instead has a non-blank value under some column name this class simply does not
     * know yet (e.g. a bank whose transactional-amount column is headed "Txn Amount", which
     * TRANSACTION_AMOUNT_HINTS does not list) is a different situation entirely: the
     * BALANCE_MARKER classification only happened because that column went unrecognized, not
     * because the row genuinely lacks transactional data. Callers must not silently drop such a
     * row from the review screen -- see {@code PdfPreviewGenerator}/{@code PreviewGenerator}'s
     * staging loops, which route it to the unparseable-row diagnostic instead.
     */
    public boolean hasUnrecognizedNonBlankColumn(Map<String, String> row) {
        Set<String> recognized = recognizedColumnNames();
        for (Map.Entry<String, String> e : row.entrySet()) {
            String v = e.getValue();
            if (v == null || v.isBlank()) continue;
            String key = e.getKey() == null ? "" : CsvParser.normalizeHeaderCell(e.getKey());
            if (!recognized.contains(key)) return true;
        }
        return false;
    }

    /**
     * True when this row carries a non-blank value under a RECOGNIZED transactional-amount column
     * ({@link #TRANSACTION_AMOUNT_HINTS} -- Amount/Debit/Credit/Withdrawals/Deposits/etc.) that
     * still fails to parse as a number at all.
     *
     * <p>Third fix pass on the marker-row-pollution bug: {@link #hasUnrecognizedNonBlankColumn}
     * only catches the case where the column NAME is unknown. It says nothing about a row whose
     * column name is perfectly recognized but whose VALUE {@link CsvParser#parseNumeric} chokes
     * on -- e.g. a stray {@code "1500/-"} in a Debit cell, or any of the real bank formats
     * {@code CsvParser.parseNumeric}'s own javadoc documents fixing (Union Bank's parenthesized
     * {@code "50000.00(Cr)"}, HDFC's rupee-glyph-as-"C" artifact, Axis's bare {@code "37.94 Dr"}
     * suffix) on a statement whose export happens to use a variant not yet covered. Such a row's
     * transactional column never resolves a value at all (parseNumeric returns null), so it falls
     * out of {@link #firstNonZeroAmount}/{@link #firstParseableAmount} exactly like a genuinely
     * blank cell would -- the row classifies {@link RowKind#BALANCE_MARKER} by the same structural
     * logic a real OPENING BALANCE row does, and {@code hasUnrecognizedNonBlankColumn} finds
     * nothing wrong because every column name IS recognized. Left unguarded, the row silently
     * vanishes: not staged (wrong kind), not reported unparseable (no unrecognized column). That
     * is worse than the pre-Track-B behavior of staging with a wrong-but-visible amount -- see
     * docs/architecture/system-design/marker-row-pollution-scope-investigation.md.
     *
     * <p>Used the same way as {@code hasUnrecognizedNonBlankColumn}: only to gate confidence in a
     * {@code BALANCE_MARKER} classification. Callers route a row where either check is true to the
     * unparseable diagnostic instead of silently excluding it.
     */
    public boolean hasUnparseableRecognizedAmount(Map<String, String> row) {
        for (String hint : TRANSACTION_AMOUNT_HINTS) {
            for (Map.Entry<String, String> e : row.entrySet()) {
                if (e.getKey() != null && CsvParser.normalizeHeaderCell(e.getKey()).equalsIgnoreCase(hint)) {
                    String v = e.getValue();
                    if (v != null && !v.isBlank() && CsvParser.parseNumeric(v) == null) return true;
                }
            }
        }
        return false;
    }

    /**
     * Human-readable reason {@link #normalize} returned null for this row -- callers that
     * implement "never lose information" (see
     * docs/engineering/financial-document-intelligence-principles.md) surface this to the user
     * instead of silently dropping the row. Only meaningful to call on a row {@code normalize}
     * has already rejected; this re-derives the same date/amount hint lookups {@code normalize}
     * itself uses (kept in exact sync deliberately, not duplicated independently, so this can
     * never drift into reporting a reason that doesn't match what actually gated the row).
     */
    public String explainFailure(Map<String, String> row) {
        String dateRaw = CsvParser.firstNonBlank(row, DATE_HINTS);
        if (dateRaw == null) return "No column recognized as a date";
        if (CsvParser.parseDate(dateRaw.trim()) == null) return "Date value \"" + dateRaw + "\" didn't match any known date format";

        String amountRaw = firstNonZeroAmount(row, AMOUNT_HINTS);
        // Falls back so a genuinely zero-amount row still normalizes exactly as before -- the
        // preference above only stops a 0.00 in the unused column from shadowing the real one.
        if (amountRaw == null) amountRaw = firstParseableAmount(row, AMOUNT_HINTS);
        if (amountRaw == null) {
            String anyAmountRaw = CsvParser.firstNonBlank(row, AMOUNT_HINTS);
            if (anyAmountRaw == null) return "No column recognized as an amount or balance";
            return "Amount value \"" + anyAmountRaw + "\" didn't match any known numeric format";
        }

        return "Date and amount both parsed but the row was still rejected";
    }

    /** Returns null when the row doesn't have enough signal to be a transaction (no recognizable
     *  date or amount column value) — callers should skip such rows rather than fail the import. */
    public StagedRow normalize(UUID userId, Map<String, String> row) {
        return normalize(userId, row, null);
    }

    /** Same as {@link #normalize(UUID, Map)}, plus records capability activations (Phase 1
     *  "capture facts" -- docs/engineering/financial-document-intelligence-principles.md) onto
     *  {@code ctx} as they fire. {@code ctx} is nullable -- callers that don't have a
     *  DocumentContext in scope (or don't care) get exactly the old behavior. */
    public StagedRow normalize(UUID userId, Map<String, String> row, DocumentContext ctx) {
        return normalize(userId, row, ctx, ruleEngineService.ruleSet(userId), null);
    }

    /**
     * Same as {@link #normalize(UUID, Map, DocumentContext)}, against a rule set the caller loaded
     * once for the whole statement.
     *
     * <p>Both staging loops (PreviewGenerator, PdfPreviewGenerator) call this once per row, and the
     * loading overload above re-queried {@code category_rules} twice per row -- measured at exactly
     * 2.00 queries/row, the largest single N+1 in the import pipeline. A user's rules cannot change
     * partway through parsing one statement, so loading them once per statement is equivalent by
     * construction, not merely close enough.
     *
     * <p>The rule set must come from {@code RuleEngineService.ruleSet(userId)}: it returns USER
     * rules before GLOBAL, and that order is what decides which rule wins.
     */
    public StagedRow normalize(UUID userId, Map<String, String> row, DocumentContext ctx,
                                List<CategoryRule> rules) {
        return normalize(userId, row, ctx, rules, null);
    }

    /**
     * A duplicate index for one staging pass.
     *
     * <p>Exposed here rather than having each staging loop inject {@code DuplicateDetector}
     * directly: this class already owns the detector, and the index is only ever useful as an
     * argument to {@link #normalize}, so the two belong behind one collaborator.
     */
    public DuplicateIndex duplicateIndexFor(UUID userId) {
        return duplicateDetector.indexFor(userId);
    }

    /**
     * A merchant index for one staging pass -- see {@link MerchantIndex}'s own doc comment for why
     * this is needed at all (staging has no transaction for {@code MerchantNormalizationEngine}'s
     * own per-transaction memo to live in). Null when this normalizer was built via the pre-Phase-A
     * 3-arg constructor, matching that constructor's "merchant resolution is simply skipped"
     * contract.
     */
    public MerchantIndex merchantIndexFor(UUID userId) {
        return merchantNormalizationEngine == null ? null : merchantNormalizationEngine.indexFor(userId);
    }

    /**
     * Same again, against a {@link DuplicateIndex} the caller built once for the whole statement.
     *
     * <p>The duplicate check was the last per-row query in this method after b7aab9d removed the
     * rule lookup -- 1.00 statements/row, recommendation 2 of the import pipeline profile. A null
     * index falls back to the per-row query, which keeps the three overloads above working
     * unchanged for tests and the PDF diagnostic.
     */
    public StagedRow normalize(UUID userId, Map<String, String> row, DocumentContext ctx,
                                List<CategoryRule> rules, DuplicateIndex duplicateIndex) {
        return normalize(userId, row, ctx, rules, duplicateIndex, null);
    }

    /**
     * Same again, against a {@link MerchantIndex} the caller built once for the whole statement via
     * {@link #merchantIndexFor} -- both staging loops (PreviewGenerator, PdfPreviewGenerator) call
     * this overload. A null {@code merchantIndex} falls back to a live, un-indexed
     * {@code MerchantNormalizationEngine.resolveReadOnly(userId, description)} call per row when a
     * {@code MerchantNormalizationEngine} is wired but no index was hoisted -- correct for a caller
     * invoking this a handful of times (e.g. a diagnostic), wrong for a real per-row staging loop,
     * which is why both staging loops always pass one. {@code merchantNormalizationEngine == null}
     * (the pre-Phase-A 3-arg constructor) skips merchant resolution entirely, as before.
     */
    public StagedRow normalize(UUID userId, Map<String, String> row, DocumentContext ctx,
                                List<CategoryRule> rules, DuplicateIndex duplicateIndex,
                                MerchantIndex merchantIndex) {
        String dateRaw = CsvParser.firstNonBlank(row, DATE_HINTS);
        String amountRaw = firstNonZeroAmount(row, AMOUNT_HINTS);
        // Falls back so a genuinely zero-amount row still normalizes exactly as before -- the
        // preference above only stops a 0.00 in the unused column from shadowing the real one.
        if (amountRaw == null) amountRaw = firstParseableAmount(row, AMOUNT_HINTS);
        if (dateRaw == null || amountRaw == null) return null;

        if (ctx != null && CsvParser.hasDateTimeComponent(dateRaw)) ctx.record("DATE_TIME_COLUMN");
        if (ctx != null && CsvParser.hasTrailingDrCrMarker(amountRaw)) ctx.record("DR_CR_SUFFIX");

        LocalDate date = CsvParser.parseDate(dateRaw.trim());
        BigDecimal parsedAmount = CsvParser.parseNumeric(amountRaw);
        if (parsedAmount == null || date == null) return null;
        BigDecimal amount = parsedAmount.abs();

        String typeRaw = CsvParser.firstNonBlank(row, TYPE_HINTS);
        // Raw-key fallback for RUPEE_ARTIFACT_TYPE_COLUMN -- see that constant's own doc comment
        // for why this can't go through the normalized-header-name path TYPE_HINTS above uses.
        if (typeRaw == null) {
            String artifactValue = row.get(RUPEE_ARTIFACT_TYPE_COLUMN);
            if (artifactValue != null && !artifactValue.isBlank()) typeRaw = artifactValue;
        }
        // A Debit/Credit-style statement has BOTH column headers present on every row (the map
        // built by CsvParser always has an entry per header regardless of whether that row's
        // value is blank), so what actually indicates income is a *non-blank* value in the
        // credit column, not just the column's presence.
        // Same non-numeric-match skip as firstParseableAmount above, for the same reason: a
        // summary row's "Closing Balance"/"Opening Balance" label sitting in the Deposits column
        // must not be read as "this row has a credit," any more than it should be read as this
        // row's actual amount.
        // Non-zero deliberately: the direction check below treats any credit value as proof of
        // income, and a separate-columns layout prints 0.00 in the side that did NOT move.
        String creditRaw = firstNonZeroAmount(row, CREDIT_HINTS);
        // Bug fix: a unified Amount + Type column layout (one amount column, a separate Type
        // column holding literally "DR"/"CR" -- e.g. PNB ONE's PDF/CSV exports) has neither a
        // "credit" column nor a Type value containing the literal word "income", so every row
        // silently fell through to the `creditRaw != null` check, which is always null for this
        // layout -- every credit transaction on a real PNB-style statement was misclassified as
        // an EXPENSE (with the sign flattened to positive by the .abs() above, so it displayed as
        // a debit in the ledger). Checked ahead of the credit-column-presence fallback since it's
        // the more specific, more authoritative signal when a Type column exists at all.
        //
        // Lowest-priority fallback: a single Amount column with no separate Type/Credit column at
        // all (Axis's "37.94 Dr" / "10,081.99 Cr", HDFC's leading "+" for a credit) -- neither of
        // the checks above ever fires for this shape, since there's no Type/Credit column to find,
        // so every row would otherwise default to EXPENSE regardless of its actual sign. Only
        // consulted once every more specific column-based signal has already come up empty.
        boolean isIncome;
        String typeNormalized = typeRaw == null ? null : typeRaw.trim().toLowerCase();
        // Bare "c"/"d" (RUPEE_ARTIFACT_TYPE_COLUMN's own shorthand, per the SBI statement's own
        // printed legend "C=Credit ; D=Debit") alongside the existing "cr"/"dr" abbreviations --
        // the same single-letter convention, one character shorter.
        if ("cr".equals(typeNormalized) || "credit".equals(typeNormalized) || "c".equals(typeNormalized)) {
            isIncome = true;
        } else if ("dr".equals(typeNormalized) || "debit".equals(typeNormalized) || "d".equals(typeNormalized)) {
            isIncome = false;
        } else if (typeRaw != null && typeRaw.toLowerCase().contains("income")) {
            isIncome = true;
        } else if (creditRaw != null) {
            isIncome = true;
        } else {
            Boolean signFromAmount = CsvParser.detectSignFromRawAmount(amountRaw);
            if (ctx != null && signFromAmount != null) {
                // Distinguishes the two real shapes detectSignFromRawAmount covers: a trailing
                // Dr/Cr marker already recorded DR_CR_SUFFIX above if present on this exact cell,
                // so a "+"-prefixed amount with no such marker is the OTHER capability.
                ctx.record(amountRaw.trim().startsWith("+") ? "LEADING_PLUS_CREDIT" : "DR_CR_SUFFIX");
            }
            isIncome = Boolean.TRUE.equals(signFromAmount);
        }
        String type = isIncome ? "INCOME" : "EXPENSE";

        // "remarks"/"particulars" are the transaction-detail column name on several real Indian
        // bank exports (PNB ONE among them -- see CsvParser's own doc comment) that don't use
        // "description" or "narration" at all. Without these, every row on such a statement
        // staged with an empty description, which CategorizationService.suggest() has nothing to
        // work with, and CategoryRules.extractMerchant("") falls back to the literal string
        // "unknown" -- which is what actually showed up in the ledger's Description column
        // (Ledger.tsx falls back to `t.merchant` when `t.description` is empty).
        String description = Optional.ofNullable(CsvParser.firstNonBlank(row, DESCRIPTION_HINTS)).orElse("");
        String fileCategory = CsvParser.firstNonBlank(row, CATEGORY_HINTS);

        // Bug fix: every credit/income row used to be hardcoded to "Salary" regardless of what it
        // actually was — a friend's UPI repayment, an interest credit, a refund, a cashback,
        // anything with a non-blank Credit column landed under Salary with no review flag at all,
        // since "default" source was reserved for the expense branch. Income rows now go through
        // the same suggestion engine as expense rows: a real salary credit still self-classifies
        // correctly (CategoryRules' Salary rule matches on "salary"/"payroll"/"stipend"/etc.), and
        // anything else — including learned merchant corrections from past manual fixes — gets a
        // real suggestion instead of a blind guess, falling to "Other" with a review flag when
        // nothing matches rather than a wrong-but-confident label.
        String suggestedCategory;
        String source;
        UUID ruleId = null;
        Integer categoryConfidence = null;
        if (fileCategory != null) {
            suggestedCategory = fileCategory;
            source = "file";
        } else {
            // amount is passed as rule-evaluation context (e.g. an AMOUNT-field category_rules
            // row) — accountType isn't known yet at staging time (the account is chosen/created
            // at confirm time), so that context stays null here.
            // suggestReadOnly, not suggest: normalize() runs ONLY at staging time (its two
            // callers are PreviewGenerator and PdfPreviewGenerator -- confirm has an entirely
            // separate path), and staging is a preview the user may abandon. suggest() would
            // create a merchant and an alias for every distinct description in a file that is
            // never imported, which is Bug 36. Same matching, same order, no writes.
            //
            // merchantIndex passed through so CategorizationService's own merchant resolution
            // (needed for rule-context matching and the learned-category lookup) also costs zero
            // queries -- without this, that call still did its own live, un-indexed
            // resolveReadOnly(userId, description) per row even after this class's OWN merchant
            // resolution below was indexed, which is why ImportQueryCountIT stayed at 2.00
            // queries/row rather than dropping toward zero.
            var suggestion = categorizationService.suggestReadOnly(rules, userId, description, amount, null,
                    merchantIndex);
            suggestedCategory = suggestion.category();
            source = suggestion.source();
            ruleId = suggestion.ruleId();
            categoryConfidence = suggestion.confidence();
        }

        // findMatch, not isLikelyDuplicate: one query either way, but it carries the evidence the
        // review screen needs to let the user decide rather than just flagging the row (WI5).
        var duplicateMatch = (duplicateIndex != null
                ? duplicateDetector.findMatch(duplicateIndex, date, amount, description)
                : duplicateDetector.findMatch(userId, date, amount, description)).orElse(null);
        boolean likelyDuplicate = duplicateMatch != null;

        String referenceNumber = CsvParser.firstNonBlank(row, REFERENCE_HINTS);
        String balanceRaw = firstParseableAmount(row, BALANCE_HINTS);
        BigDecimal balanceAfter = CsvParser.parseNumeric(balanceRaw);
        if (ctx != null && balanceAfter != null) {
            ctx.record("RUNNING_BALANCE");
            if (CsvParser.hasTrailingDrCrMarker(balanceRaw)) ctx.record("DR_CR_SUFFIX");
        }

        // RowKind classification (see RowKind's own doc comment for the full reasoning): does any
        // REAL transactional amount column (TRANSACTION_AMOUNT_HINTS) have a NON-ZERO parseable
        // value on this row at all? If so, this is an ordinary transaction, full stop -- it does
        // not matter whether the row's description ALSO happens to mention "balance" (e.g. a
        // genuine "Balance transfer to savings account" narration on a real debit).
        String transactionAmountRaw = firstNonZeroAmount(row, TRANSACTION_AMOUNT_HINTS);
        RowKind kind;
        if (transactionAmountRaw != null) {
            kind = RowKind.TRANSACTION;
        } else {
            // No transactional column had a non-zero value. Two structurally different shapes
            // land here, and they must not be classified the same way:
            //
            //  (a) No transactional column had ANY value at all (blank/absent on this row) --
            //      amountRaw above could only have come from BALANCE_HINTS' last-resort fallback.
            //      Unambiguous: this is a balance-marker row.
            //
            //  (b) A transactional column DID resolve, but to exactly zero -- e.g. a separate
            //      debit/credit-columns layout that prints "0.00" on BOTH sides of a balance-only
            //      row instead of leaving them blank (see firstNonZeroAmount's own doc comment,
            //      verified against a real HDFC statement for the single-zero-side case; a
            //      balance-marker row is the same layout convention applied to both sides at
            //      once). This is genuinely ambiguous by the zero value alone -- it is the same
            //      column shape a real zero-value transaction (a waived fee, a reversed/voided
            //      charge that nets to zero) would also produce. Zero itself is deliberately NOT
            //      the signal (a blanket "amount == 0 => not a transaction" rule would misclassify
            //      those legitimate rows).
            //
            //      The structural tiebreaker: is a Balance-style column PRESENT on this row at all
            //      -- i.e. did it resolve ANY value, whatever that value is -- making it the row's
            //      defining content? A row whose transactional side is explicitly, deliberately
            //      zeroed AND that also carries a Balance-style column (even a legitimately zero
            //      one, e.g. a closed/emptied account's CLOSING BALANCE row reading
            //      Withdrawals=0.00/Deposits=0.00/Balance=0.00) is structurally a balance-report
            //      row. A genuine zero-value transaction that has no accompanying balance figure
            //      at all (no Balance-style column on this row/layout -- balanceAfter is null
            //      because the column is absent or itself unparseable) has no such competing
            //      "real" numeric content, so it stays TRANSACTION.
            //
            //      Deliberately NOT "balance value is non-zero" (a prior version of this fix used
            //      balanceAfter.signum() != 0): that treats a genuinely zero account balance as if
            //      the Balance column weren't there at all, which misclassified an all-zero
            //      CLOSING BALANCE row (a closed/emptied account) as an ordinary zero-value
            //      TRANSACTION. Presence -- balanceAfter != null -- not magnitude, is the
            //      structural signal; this is still not the blanket "amount == 0 => marker" rule
            //      the earlier comment above warns against, since a zero transactional amount with
            //      NO balance column at all still stays TRANSACTION.
            String zeroTransactionRaw = firstParseableAmount(row, TRANSACTION_AMOUNT_HINTS);
            boolean transactionalColumnExplicitlyZero = zeroTransactionRaw != null;
            boolean balanceColumnPresent = balanceAfter != null;
            kind = (transactionalColumnExplicitlyZero && !balanceColumnPresent)
                    ? RowKind.TRANSACTION
                    : RowKind.BALANCE_MARKER;
        }

        String merchant = null;
        Double merchantConfidence = null;
        if (merchantNormalizationEngine != null) {
            var resolved = merchantIndex != null
                    ? merchantNormalizationEngine.resolveReadOnly(userId, description, merchantIndex)
                    : merchantNormalizationEngine.resolveReadOnly(userId, description);
            if (resolved.isPresent()) {
                merchant = resolved.get().getCanonicalName();
                merchantConfidence = 1.0;
            }
        }

        return new StagedRow(date, description, amount, type, suggestedCategory, source, ruleId,
                likelyDuplicate, referenceNumber, balanceAfter, duplicateMatch, kind, null, merchant,
                merchantConfidence, categoryConfidence);
    }
}
