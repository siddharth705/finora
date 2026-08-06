package com.finora.imports;

import com.finora.dto.ImportDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reports rows whose raw columns admitted more than one reading, so a guess is visible as a guess.
 *
 * <p><b>This is the only rule that looks at the document rather than the result.</b> The other
 * three compare numbers that have already been decided: by the time a balance chain or a total can
 * object, an ambiguous cell has long since been resolved one way, and the parser's choice has
 * become the thing being verified. That is precisely the failure this exists for. A real statement
 * produced a cell reading {@code "0.00 25,000.00"} in its deposits column with nothing in
 * withdrawals; every downstream number was then internally consistent with the wrong answer.
 *
 * <p><b>It reports ambiguity, not error.</b> A guess flagged here may well be right — most are.
 * The claim is only that the document did not say, and that a person reviewing the import should
 * look at this row rather than assume it was determined. So the outcome is a warning even when
 * many rows are affected: escalating on volume would imply a confidence about which reading is
 * correct that this rule, by construction, does not have.
 *
 * <p><b>Deliberately does not re-run the parser's direction cascade.</b> {@link
 * TransactionNormalizer} decides direction from a Type column, then a credit column, then a
 * trailing Dr/Cr marker, then a leading plus, and finally defaults to an expense when nothing said
 * otherwise. That last default is also a guess, and a fair thing to want reported — but detecting
 * it here would mean duplicating the whole cascade, and a copy that drifts would report ambiguity
 * on rows the parser read confidently, or stay silent on rows it did not. What this checks instead
 * is decidable from the row alone: a cell that holds two amounts, and two direction columns that
 * both claim the money. Reporting the silent default belongs with the code that performs it.
 */
@Component
public class ColumnAmbiguityValidator {

    /** Stable machine identifier — clients group and explain by it, so it must not track wording. */
    public static final String RULE = "COLUMN_AMBIGUITY";

    /** A number as a statement prints one, with or without grouping and decimals. */
    private static final Pattern AMOUNT = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    private static final List<String> CREDIT_HINTS =
            List.of("deposit", "deposits", "credit", "credits", "deposit amount", "credit amount",
                    "cr amount", "deposit amt", "credit amt");
    private static final List<String> DEBIT_HINTS =
            List.of("withdrawal", "withdrawals", "debit", "debits", "withdrawal amount", "debit amount",
                    "dr amount", "withdrawal amt", "debit amt");

    /** One row that could be read more than one way, and how. */
    private record Ambiguity(int rowIndex, String kind, String column, String value) {
        Map<String, Object> toDetails() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rowIndex", rowIndex);
            m.put("kind", kind);
            m.put("column", column);
            m.put("value", value);
            return m;
        }
    }

    public ImportDto.VerificationFinding check(List<Map<String, String>> rawRows) {
        Map<String, Object> details = new LinkedHashMap<>();

        if (rawRows == null || rawRows.isEmpty()) {
            details.put("reason", "The rows were not available in their original column form, so "
                    + "there was nothing to check for ambiguity.");
            return new ImportDto.VerificationFinding(RULE, "NOT_APPLICABLE", details);
        }

        List<Ambiguity> found = new ArrayList<>();
        for (int i = 0; i < rawRows.size(); i++) {
            Map<String, String> row = rawRows.get(i);
            if (row == null) continue;

            String creditColumn = null, debitColumn = null;
            BigDecimal credit = null, debit = null;

            for (Map.Entry<String, String> e : row.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                String column = CsvParser.normalizeHeaderCell(e.getKey()).toLowerCase(Locale.ROOT);
                String value = e.getValue().trim();
                boolean isCredit = CREDIT_HINTS.contains(column);
                boolean isDebit = DEBIT_HINTS.contains(column);
                if (!isCredit && !isDebit) continue;

                // Two amounts in one cell. Whichever the parser picked, the other had an equal
                // claim -- this is the shape that made a 25,000 deposit read as an expense.
                if (countAmounts(value) > 1) {
                    found.add(new Ambiguity(i, "MULTIPLE_AMOUNTS_IN_ONE_COLUMN", e.getKey(), value));
                    continue;
                }

                BigDecimal parsed = CsvParser.parseNumeric(value);
                if (parsed == null || parsed.signum() == 0) continue;
                if (isCredit) { creditColumn = e.getKey(); credit = parsed; }
                else { debitColumn = e.getKey(); debit = parsed; }
            }

            // Both sides claiming money on one row. A separate-columns layout prints a zero in the
            // side that did not move, so two non-zero values are a contradiction rather than a
            // layout quirk -- and the row can be read as either a credit or a debit.
            if (credit != null && debit != null) {
                found.add(new Ambiguity(i, "BOTH_DIRECTIONS_HAVE_A_VALUE",
                        creditColumn + " / " + debitColumn, credit + " / " + debit));
            }
        }

        details.put("rowsChecked", rawRows.size());
        details.put("ambiguousRows", found.size());

        if (found.isEmpty()) {
            return new ImportDto.VerificationFinding(RULE, "VERIFIED", details);
        }

        details.put("ambiguities", found.stream().map(Ambiguity::toDetails).toList());
        details.put("explanation", found.size() == 1
                ? "One row could be read more than one way. The import used its best reading, but the "
                  + "statement did not settle it — worth checking that row."
                : found.size() + " rows could be read more than one way. The import used its best "
                  + "reading of each, but the statement did not settle them — worth checking them.");
        return new ImportDto.VerificationFinding(RULE, "WARNING", details);
    }

    private static int countAmounts(String value) {
        Matcher m = AMOUNT.matcher(value);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
