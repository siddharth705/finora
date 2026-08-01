package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.service.CategorizationService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
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

    // Single source of truth for every column name this class recognizes -- shared by normalize(),
    // explainFailure(), and recognizedColumnNames() so the three can never drift out of sync (they
    // used to be three separate literal lists; a real regression came from exactly that kind of
    // drift once already, see StagedRow.description's own history). "balance" is last-resort on
    // purpose: a PDF statement's OPENING BALANCE/CLOSING BALANCE rows (see
    // PdfPreviewGenerator/PdfTableLocator) carry no debit or credit value at all -- only a Balance
    // column -- so without this fallback those two rows have a date but no recognizable amount and
    // get silently dropped, before PdfPreviewGenerator's own balancePoints logic ever sees them.
    private static final String[] DATE_HINTS =
            {"date", "transaction_date", "txn date", "transaction date", "value date", "date & time"};
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
    private static final String[] AMOUNT_HINTS = {"amount", "debit", "credit",
            "dr amount", "cr amount", "debit amount", "credit amount",
            "withdrawal amt", "withdrawal amount", "deposit amt", "deposit amount",
            "deposit", "withdrawal", "deposits", "withdrawals",
            "balance", "running balance", "closing balance"};
    private static final String[] CREDIT_HINTS =
            {"credit", "cr amount", "credit amount", "deposit amt", "deposit amount", "deposit", "deposits"};
    private static final String[] TYPE_HINTS = {"type"};
    private static final String[] DESCRIPTION_HINTS =
            {"description", "narration", "remarks", "particulars", "transaction description", "transaction details"};
    private static final String[] CATEGORY_HINTS = {"category"};
    // Phase 1 "capture facts" (docs/engineering/financial-document-intelligence-principles.md):
    // evidenced by a real Canara Bank statement's "Reference / Cheque No." column, silently
    // discarded until now even on rows that otherwise parsed successfully -- CsvParser.zipRow and
    // PdfTableLocator.bucketRow already capture every column into the row map, this was simply
    // never read back out. "instrument id" was already a PdfTableLocator.HEADER_HINTS entry used
    // only to detect a header row, never to capture a value -- same underlying gap, same fix.
    private static final String[] REFERENCE_HINTS =
            {"reference number", "ref no", "reference no", "cheque no", "chq no", "chq/ref no",
                    "instrument id", "reference", "reference / cheque no.", "reference / cheque no"};
    // Deliberately separate from AMOUNT_HINTS, even though the literal column names overlap:
    // AMOUNT_HINTS' "balance"/"running balance"/"closing balance" entries exist as a last-resort
    // fallback AMOUNT for a summary row with no debit/credit column at all (see AMOUNT_HINTS' own
    // comment). This is a second, independent read of the same column for a different purpose --
    // an ordinary transaction row's running balance AFTER that transaction, not its amount.
    private static final String[] BALANCE_HINTS = {"balance", "running balance", "closing balance"};

    public TransactionNormalizer(CategorizationService categorizationService, DuplicateDetector duplicateDetector) {
        this.categorizationService = categorizationService;
        this.duplicateDetector = duplicateDetector;
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

        String amountRaw = firstParseableAmount(row, AMOUNT_HINTS);
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
        String dateRaw = CsvParser.firstNonBlank(row, DATE_HINTS);
        String amountRaw = firstParseableAmount(row, AMOUNT_HINTS);
        if (dateRaw == null || amountRaw == null) return null;

        if (ctx != null && CsvParser.hasDateTimeComponent(dateRaw)) ctx.record("DATE_TIME_COLUMN");
        if (ctx != null && CsvParser.hasTrailingDrCrMarker(amountRaw)) ctx.record("DR_CR_SUFFIX");

        LocalDate date = CsvParser.parseDate(dateRaw.trim());
        BigDecimal parsedAmount = CsvParser.parseNumeric(amountRaw);
        if (parsedAmount == null || date == null) return null;
        BigDecimal amount = parsedAmount.abs();

        String typeRaw = CsvParser.firstNonBlank(row, TYPE_HINTS);
        // A Debit/Credit-style statement has BOTH column headers present on every row (the map
        // built by CsvParser always has an entry per header regardless of whether that row's
        // value is blank), so what actually indicates income is a *non-blank* value in the
        // credit column, not just the column's presence.
        // Same non-numeric-match skip as firstParseableAmount above, for the same reason: a
        // summary row's "Closing Balance"/"Opening Balance" label sitting in the Deposits column
        // must not be read as "this row has a credit," any more than it should be read as this
        // row's actual amount.
        String creditRaw = firstParseableAmount(row, CREDIT_HINTS);
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
        if ("cr".equals(typeNormalized) || "credit".equals(typeNormalized)) {
            isIncome = true;
        } else if ("dr".equals(typeNormalized) || "debit".equals(typeNormalized)) {
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
        if (fileCategory != null) {
            suggestedCategory = fileCategory;
            source = "file";
        } else {
            // amount is passed as rule-evaluation context (e.g. an AMOUNT-field category_rules
            // row) — accountType isn't known yet at staging time (the account is chosen/created
            // at confirm time), so that context stays null here.
            var suggestion = categorizationService.suggest(userId, description, amount, null);
            suggestedCategory = suggestion.category();
            source = suggestion.source();
            ruleId = suggestion.ruleId();
        }

        boolean likelyDuplicate = duplicateDetector.isLikelyDuplicate(userId, date, amount, description);

        String referenceNumber = CsvParser.firstNonBlank(row, REFERENCE_HINTS);
        String balanceRaw = firstParseableAmount(row, BALANCE_HINTS);
        BigDecimal balanceAfter = CsvParser.parseNumeric(balanceRaw);
        if (ctx != null && balanceAfter != null) {
            ctx.record("RUNNING_BALANCE");
            if (CsvParser.hasTrailingDrCrMarker(balanceRaw)) ctx.record("DR_CR_SUFFIX");
        }

        return new StagedRow(date, description, amount, type, suggestedCategory, source, ruleId,
                likelyDuplicate, referenceNumber, balanceAfter);
    }
}
