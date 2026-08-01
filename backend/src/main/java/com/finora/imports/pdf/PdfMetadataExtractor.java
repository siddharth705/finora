package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
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
    // Bug fix: verified against a real Union Bank of India statement -- its "Branch Address" line
    // is a two-column SECTION HEADER ("Branch Address" | "Statement Details" side by side, same
    // pattern as an earlier "Your Details" | "Account Details" header higher up the page), not a
    // genuine "Branch: <name>" field -- without the negative lookahead, the bare "Branch" match
    // consumed "Address Statement Details" as if it were the branch name.
    private static final Pattern BRANCH = labelPattern("Branch(?: Name)?(?!\\s*Address)");
    private static final Pattern IFSC = labelPattern("IFSC(?: Code)?");
    private static final Pattern STATEMENT_PERIOD = labelPattern("Statement Period");
    private static final Pattern CREDIT_LIMIT = labelPattern("(?:Total )?Credit Limit(?: \\(Including Cash\\))?");
    private static final Pattern PAYMENT_DUE_DATE = labelPattern("(?:Payment )?Due Date");

    // GRID_METADATA_TRAILING_LABEL: a genuine second real-world grid-metadata shape, distinct from
    // GRID_DUE_DATE_LABEL's "label row, then a later value row" layout -- here the VALUE comes
    // BEFORE its label on the very same line ("317002010038811 Account Number", "UBIN0531707
    // IFSC"), the reverse of every "Label: Value" pattern above. Verified against the same real
    // Union Bank of India statement: its account-details panel renders as a two-column grid where
    // each row is "value label" rather than "label value", and ACCOUNT_HOLDER/ACCOUNT_NUMBER/IFSC
    // above never match this layout at all (none of their lines start with the label).
    //
    // Account holder name specifically: this statement's "Name" field wraps its own value across
    // several lines *before* the "Name" label itself appears (a still-harder shape not attempted
    // here -- see the engineering principles doc's LEADING_NARRATION_CONTINUATION entry for the
    // same underlying "value precedes its label across multiple lines" difficulty in a different
    // part of this pipeline). The grid's OTHER column has a cleaner "Account Name" field on one
    // line, which this uses instead -- a real, if less complete (no honorific), holder name is a
    // better outcome than none. Capped at 3 space-separated capitalized words specifically so a
    // preceding, unrelated address fragment ("...3,BEHIND  SHIVANI SURESH MOURYA Account Name")
    // doesn't get swept into the captured name -- verified this cap is what correctly excludes
    // "BEHIND" (itself capitalized) while still capturing the full 3-word name.
    private static final Pattern ACCOUNT_NAME_TRAILING_LABEL =
            Pattern.compile("(?i)([A-Z][A-Za-z]*(?:\\s+[A-Z][A-Za-z]*){0,2})\\s+Account\\s*Name\\s*$");
    private static final Pattern ACCOUNT_NUMBER_TRAILING_LABEL =
            Pattern.compile("(?i)^(\\d{6,20})\\s+Account\\s*Number\\s*$");
    private static final Pattern STATEMENT_PERIOD_TRAILING_LABEL =
            Pattern.compile("(?i)^(.+?)\\s+Statement\\s*Period\\s*$");
    // IFSC codes have a fixed, distinctive shape (4 letters, a literal 0, 6 more alphanumerics) --
    // reliable enough to find directly by content, independent of any label at all, which sidesteps
    // needing to handle this statement's IFSC line being merged with an unrelated Email field on
    // the same extracted line ("...@GMAIL.COM Email id UBIN0531707 IFSC").
    private static final Pattern IFSC_SHAPE = Pattern.compile("\\b[A-Z]{4}0[A-Z0-9]{6}\\b");

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            // "09 Aug, 2026" / "09 Aug 2026" -- real HDFC statements render Due Date this way
            // (see GRID_DUE_DATE_LABEL's own doc comment for why it isn't a plain "Label: Value" line).
            DateTimeFormatter.ofPattern("d MMM, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
    };

    // Some real statements (verified against an actual HDFC "Tata Neu Plus" credit card export)
    // lay the payment-summary block out as a genuine grid -- a row of labels ("... MINIMUM DUE
    // DUE DATE"), then a row of values a line or two later ("C200.00 09 Aug, 2026") -- rather
    // than "Label: Value" text PAYMENT_DUE_DATE above can match on a single line. This is a
    // best-effort fallback for that shape specifically: find a line whose LAST label is "Due
    // Date", then look at the next few lines for the first date-shaped substring, which by
    // construction (values render in the same left-to-right order as their labels) corresponds
    // to it. Bounded to a few lines so it can't wander into unrelated text further down the page.
    private static final Pattern GRID_DUE_DATE_LABEL = Pattern.compile("(?i).*\\bDUE\\s+DATE\\s*$");
    private static final Pattern DATE_LIKE = Pattern.compile(
            "\\b\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}\\b|\\b\\d{1,2}\\s+[A-Za-z]{3,9}\\.?,?\\s+\\d{4}\\b");
    private static final int GRID_VALUE_SEARCH_WINDOW = 3;

    private static Pattern labelPattern(String label) {
        return Pattern.compile("(?i)^\\s*" + label + "\\s*:?\\s*(.+)$");
    }

    public record ExtractedMetadata(
            String accountHolderName, String accountNumberMasked, String branchName,
            String ifscCode, LocalDate statementPeriodStart, LocalDate statementPeriodEnd,
            java.math.BigDecimal creditLimit, LocalDate paymentDueDate
    ) {}

    public ExtractedMetadata extract(List<String> preTableLines) {
        return extract(preTableLines, null);
    }

    /** Same as {@link #extract(List)}, plus records GRID_METADATA_FALLBACK/
     *  GRID_METADATA_TRAILING_LABEL capability activations onto {@code ctx} as they fire (Phase 1
     *  "capture facts" -- docs/engineering/financial-document-intelligence-principles.md).
     *  {@code ctx} is nullable -- callers with no DocumentContext in scope get the old behavior. */
    public ExtractedMetadata extract(List<String> preTableLines, DocumentContext ctx) {
        String accountHolderName = null;
        String accountNumberMasked = null;
        String branchName = null;
        String ifscCode = null;
        LocalDate periodStart = null;
        LocalDate periodEnd = null;
        java.math.BigDecimal creditLimit = null;
        LocalDate paymentDueDate = null;

        for (int i = 0; i < preTableLines.size(); i++) {
            String line = preTableLines.get(i);
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
            if (dueDate != null) { paymentDueDate = parseDate(dueDate); continue; }

            if (paymentDueDate == null && GRID_DUE_DATE_LABEL.matcher(line).matches()) {
                paymentDueDate = findGridDueDate(preTableLines, i);
                if (ctx != null && paymentDueDate != null) ctx.record("GRID_METADATA_FALLBACK");
                continue;
            }

            // GRID_METADATA_TRAILING_LABEL fallbacks (see that constant's own doc comment) -- only
            // consulted once the "label first" checks above have already had their chance on every
            // line, so a document using the ordinary "Label: Value" shape is completely unaffected.
            if (accountNumberMasked == null) {
                Matcher acctNoMatch = ACCOUNT_NUMBER_TRAILING_LABEL.matcher(line);
                if (acctNoMatch.matches()) {
                    accountNumberMasked = com.finora.imports.CsvParser.maskAccountNumber(acctNoMatch.group(1));
                    if (ctx != null) ctx.record("GRID_METADATA_TRAILING_LABEL");
                    continue;
                }
            }
            if (accountHolderName == null) {
                Matcher holderMatch = ACCOUNT_NAME_TRAILING_LABEL.matcher(line);
                if (holderMatch.find()) {
                    accountHolderName = holderMatch.group(1).trim();
                    if (ctx != null) ctx.record("GRID_METADATA_TRAILING_LABEL");
                    continue;
                }
            }
            if (ifscCode == null) {
                Matcher ifscMatch = IFSC_SHAPE.matcher(line);
                if (ifscMatch.find()) {
                    ifscCode = ifscMatch.group().toUpperCase();
                    if (ctx != null) ctx.record("GRID_METADATA_TRAILING_LABEL");
                    continue;
                }
            }
            if (periodStart == null && periodEnd == null) {
                Matcher periodMatch = STATEMENT_PERIOD_TRAILING_LABEL.matcher(line);
                if (periodMatch.matches()) {
                    LocalDate[] parsed = parsePeriod(periodMatch.group(1).trim());
                    periodStart = parsed[0];
                    periodEnd = parsed[1];
                    if (ctx != null) ctx.record("GRID_METADATA_TRAILING_LABEL");
                    continue;
                }
            }
        }

        return new ExtractedMetadata(accountHolderName, accountNumberMasked, branchName, ifscCode,
                periodStart, periodEnd, creditLimit, paymentDueDate);
    }

    /** See {@link #GRID_DUE_DATE_LABEL}'s own doc comment. Scans the few lines after a "...Due
     *  Date" label line for the first date-shaped substring and parses it -- null (not a thrown
     *  exception) if nothing date-shaped turns up within the window, same "genuinely null when
     *  the file didn't carry enough signal" discipline every other field here follows. */
    private LocalDate findGridDueDate(List<String> lines, int labelLineIndex) {
        int end = Math.min(lines.size(), labelLineIndex + 1 + GRID_VALUE_SEARCH_WINDOW);
        for (int j = labelLineIndex + 1; j < end; j++) {
            Matcher m = DATE_LIKE.matcher(lines.get(j));
            if (m.find()) {
                LocalDate parsed = parseDate(m.group());
                if (parsed != null) return parsed;
            }
        }
        return null;
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
