package com.finora.imports.pdf;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts account-level fields from the text that appears ABOVE the transaction table --
 * account holder, account number, branch, IFSC, statement period. This is genuinely PDF-specific
 * (unlike table-row extraction, which reuses StatementValidator/TransactionNormalizer directly):
 * CSV's account-level signals come from table COLUMNS (StatementValidator.scanRow scans each
 * row for an "account holder"-ish column), but a real bank statement PDF's header fields appear
 * as free-standing "Label : Value" lines before the table, not as columns -- there's no existing
 * component that already does this.
 *
 * Every field is nullable, and genuinely null when the label wasn't found -- same discipline
 * ImportDto.DetectedAccountInfo's own doc comment establishes for CSV: nothing here is guessed
 * just to fill a field.
 */
@Component
public class PdfMetadataExtractor {

    // Case-insensitive, tolerant of a colon with surrounding whitespace, or just whitespace with
    // no colon (some statement generators use "Account Number    000123456789" with no colon at
    // all) -- (?:...)? makes the colon itself optional while still requiring some separation.
    private static final Pattern ACCOUNT_HOLDER = labelPattern("Account Holder(?: Name)?");
    private static final Pattern ACCOUNT_NUMBER = labelPattern("Account Number");
    private static final Pattern BRANCH = labelPattern("Branch(?: Name)?");
    private static final Pattern IFSC = labelPattern("IFSC(?: Code)?");
    private static final Pattern STATEMENT_PERIOD = labelPattern("Statement Period");
    private static final Pattern CREDIT_LIMIT = labelPattern("(?:Total )?Credit Limit(?: \\(Including Cash\\))?");
    private static final Pattern PAYMENT_DUE_DATE = labelPattern("(?:Payment )?Due Date");

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
    };

    private static Pattern labelPattern(String label) {
        return Pattern.compile("(?i)^\\s*" + label + "\\s*:?\\s*(.+)$");
    }

    public record ExtractedMetadata(
            String accountHolderName, String accountNumberMasked, String branchName,
            String ifscCode, LocalDate statementPeriodStart, LocalDate statementPeriodEnd,
            java.math.BigDecimal creditLimit, LocalDate paymentDueDate
    ) {}

    public ExtractedMetadata extract(List<String> preTableLines) {
        String accountHolderName = null;
        String accountNumberMasked = null;
        String branchName = null;
        String ifscCode = null;
        LocalDate periodStart = null;
        LocalDate periodEnd = null;
        java.math.BigDecimal creditLimit = null;
        LocalDate paymentDueDate = null;

        for (String line : preTableLines) {
            String holder = firstGroup(ACCOUNT_HOLDER, line);
            if (holder != null) { accountHolderName = holder; continue; }

            String acctNo = firstGroup(ACCOUNT_NUMBER, line);
            if (acctNo != null) { accountNumberMasked = com.finora.imports.CsvParser.maskAccountNumber(acctNo); continue; }

            String branch = firstGroup(BRANCH, line);
            if (branch != null) { branchName = branch; continue; }

            String ifsc = firstGroup(IFSC, line);
            if (ifsc != null) { ifscCode = ifsc.toUpperCase(); continue; }

            String period = firstGroup(STATEMENT_PERIOD, line);
            if (period != null) {
                LocalDate[] parsed = parsePeriod(period);
                periodStart = parsed[0];
                periodEnd = parsed[1];
                continue;
            }

            // Checked ahead of PAYMENT_DUE_DATE below since "Credit Limit" is the more specific
            // label of the two (a payment-summary block commonly lists both fields on separate
            // lines, and neither regex is a prefix of the other, but ordering follows the same
            // "most specific signal first" convention as the rest of this loop).
            String limit = firstGroup(CREDIT_LIMIT, line);
            if (limit != null) { creditLimit = com.finora.imports.CsvParser.parseNumeric(limit); continue; }

            String dueDate = firstGroup(PAYMENT_DUE_DATE, line);
            if (dueDate != null) paymentDueDate = parseDate(dueDate);
        }

        return new ExtractedMetadata(accountHolderName, accountNumberMasked, branchName, ifscCode,
                periodStart, periodEnd, creditLimit, paymentDueDate);
    }

    private String firstGroup(Pattern pattern, String line) {
        Matcher m = pattern.matcher(line);
        if (m.matches()) {
            String value = m.group(1).trim();
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    /** "01-07-2026 to 31-07-2026" -> [start, end]. Either element is null if that half didn't
     *  parse -- a genuinely malformed period string shouldn't throw and abort the whole import,
     *  just leave the field(s) it couldn't make sense of unset, same as everywhere else here. */
    private LocalDate[] parsePeriod(String text) {
        String[] parts = text.split("(?i)\\s+to\\s+");
        LocalDate start = parts.length > 0 ? parseDate(parts[0].trim()) : null;
        LocalDate end = parts.length > 1 ? parseDate(parts[1].trim()) : null;
        return new LocalDate[]{start, end};
    }

    private LocalDate parseDate(String raw) {
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try { return LocalDate.parse(raw, fmt); } catch (Exception ignored) {}
        }
        return null;
    }
}
