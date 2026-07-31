package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.service.CategorizationService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
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

    public TransactionNormalizer(CategorizationService categorizationService, DuplicateDetector duplicateDetector) {
        this.categorizationService = categorizationService;
        this.duplicateDetector = duplicateDetector;
    }

    /** Returns null when the row doesn't have enough signal to be a transaction (no recognizable
     *  date or amount column value) — callers should skip such rows rather than fail the import. */
    public StagedRow normalize(UUID userId, Map<String, String> row) {
        String dateRaw = CsvParser.firstNonBlank(row, "date", "transaction_date", "txn date", "transaction date", "value date");
        // "balance" is last-resort on purpose: a PDF statement's OPENING BALANCE/CLOSING BALANCE
        // rows (see PdfPreviewGenerator/PdfTableLocator) carry no debit or credit value at all --
        // only a Balance column -- so without this fallback those two rows have a date but no
        // recognizable amount and get silently dropped here, before PdfPreviewGenerator's own
        // balancePoints logic ever sees them. (This fallback previously existed and was lost in a
        // later edit to this same method -- restored here, and now covered by
        // TransactionNormalizerTest so a regression like this fails loudly next time instead of
        // only surfacing as a silent row-count mismatch in an unrelated PDF test.)
        String amountRaw = CsvParser.firstNonBlank(row, "amount", "debit", "credit",
                "dr amount", "cr amount", "debit amount", "credit amount",
                "withdrawal amt", "withdrawal amount", "deposit amt", "deposit amount",
                "balance", "running balance", "closing balance");
        if (dateRaw == null || amountRaw == null) return null;

        LocalDate date = CsvParser.parseDate(dateRaw.trim());
        BigDecimal parsedAmount = CsvParser.parseNumeric(amountRaw);
        if (parsedAmount == null || date == null) return null;
        BigDecimal amount = parsedAmount.abs();

        String typeRaw = CsvParser.firstNonBlank(row, "type");
        // A Debit/Credit-style statement has BOTH column headers present on every row (the map
        // built by CsvParser always has an entry per header regardless of whether that row's
        // value is blank), so what actually indicates income is a *non-blank* value in the
        // credit column, not just the column's presence.
        String creditRaw = CsvParser.firstNonBlank(row, "credit", "cr amount", "credit amount", "deposit amt", "deposit amount");
        // Bug fix: a unified Amount + Type column layout (one amount column, a separate Type
        // column holding literally "DR"/"CR" -- e.g. PNB ONE's PDF/CSV exports) has neither a
        // "credit" column nor a Type value containing the literal word "income", so every row
        // silently fell through to the `creditRaw != null` check, which is always null for this
        // layout -- every credit transaction on a real PNB-style statement was misclassified as
        // an EXPENSE (with the sign flattened to positive by the .abs() above, so it displayed as
        // a debit in the ledger). Checked ahead of the credit-column-presence fallback since it's
        // the more specific, more authoritative signal when a Type column exists at all.
        boolean isIncome;
        String typeNormalized = typeRaw == null ? null : typeRaw.trim().toLowerCase();
        if ("cr".equals(typeNormalized) || "credit".equals(typeNormalized)) {
            isIncome = true;
        } else if ("dr".equals(typeNormalized) || "debit".equals(typeNormalized)) {
            isIncome = false;
        } else {
            isIncome = (typeRaw != null && typeRaw.toLowerCase().contains("income")) || creditRaw != null;
        }
        String type = isIncome ? "INCOME" : "EXPENSE";

        // "remarks"/"particulars" are the transaction-detail column name on several real Indian
        // bank exports (PNB ONE among them -- see CsvParser's own doc comment) that don't use
        // "description" or "narration" at all. Without these, every row on such a statement
        // staged with an empty description, which CategorizationService.suggest() has nothing to
        // work with, and CategoryRules.extractMerchant("") falls back to the literal string
        // "unknown" -- which is what actually showed up in the ledger's Description column
        // (Ledger.tsx falls back to `t.merchant` when `t.description` is empty).
        String description = Optional.ofNullable(CsvParser.firstNonBlank(row, "description", "narration", "remarks", "particulars")).orElse("");
        String fileCategory = CsvParser.firstNonBlank(row, "category");

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

        return new StagedRow(date, description, amount, type, suggestedCategory, source, ruleId, likelyDuplicate);
    }
}
