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
        // balancePoints logic ever sees them.
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
        boolean isIncome = (typeRaw != null && typeRaw.toLowerCase().contains("income")) || creditRaw != null;
        String type = isIncome ? "INCOME" : "EXPENSE";

        String description = Optional.ofNullable(CsvParser.firstNonBlank(row, "description", "narration")).orElse("");
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
