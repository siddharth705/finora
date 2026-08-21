package com.finora.imports;

import com.finora.accounts.AccountDto;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.product.ProductDiscovery;
import com.finora.imports.product.ProductEvidenceCollector;
import com.finora.util.BankRegistry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Detects account- and bank-level signals from a statement: which bank issued it, whether it
 * looks like a credit card vs. a savings/current account, and best-effort opening/closing
 * balance, masked account number, holder name, branch, IFSC, credit limit, and due date.
 *
 * Every field on {@link DetectedAccountInfo} is nullable and genuinely IS null when the file
 * didn't contain enough signal to derive it — nothing here is guessed just to fill the field.
 */
@Component
public class StatementValidator {

    private final ProductDiscovery productDiscovery;

    public StatementValidator(ProductDiscovery productDiscovery) {
        this.productDiscovery = productDiscovery;
    }

    /** Accumulates account-level signal across a whole file's rows. One instance per import;
     *  callers scan each row via {@link #scanRow} and pass the accumulator to
     *  {@link #buildDetectedAccountInfo} once the file has been fully read. */
    public static class AccountSignalAccumulator {
        String accountNumberMasked;
        String accountHolderName;
        String branchName;
        String ifscCode;
        BigDecimal creditLimit;
        LocalDate dueDate;
        boolean creditCardSignals;
        final List<BalanceObservation> balanceObservations = new ArrayList<>();
    }

    /** One row's running-balance observation, kept alongside its transaction date and signed
     *  amount so the day's true first/last transaction can be reconstructed from the balance
     *  chain itself (see {@link BalanceChainUtil}) rather than assumed from file position -- a
     *  real bank export's line order within a single calendar day does NOT reliably tell you
     *  which one happened first, and guessing wrong silently produces a wrong opening/closing
     *  balance (confirmed against a real PNB ONE statement: a 7-transaction same-day cluster on
     *  the statement's earliest date, where "first line for that date" was actually the day's
     *  LAST transaction, not its first). */
    private record BalanceObservation(LocalDate date, BigDecimal signedAmount,
                                       BigDecimal balance, String description) implements BalanceChainUtil.ChainLink {
        @Override public BigDecimal balanceAfter() { return balance; }
    }

    /**
     * Scans one row for account-level fields. These can show up on rows even when other columns
     * on that same row are otherwise unremarkable, so this runs on every row regardless of
     * whether it parsed as a transaction (parsedRow is null when it didn't).
     */
    public void scanRow(Map<String, String> row, StagedRow parsedRow, AccountSignalAccumulator acc) {
        if (parsedRow != null) {
            String balanceRaw = CsvParser.firstNonBlank(row, "balance", "running balance", "closing balance");
            BigDecimal balance = CsvParser.parseNumeric(balanceRaw);
            if (balance != null) {
                BigDecimal signedAmount = "INCOME".equals(parsedRow.type()) ? parsedRow.amount() : parsedRow.amount().negate();
                acc.balanceObservations.add(new BalanceObservation(parsedRow.date(), signedAmount, balance, parsedRow.description()));
            }
        }

        if (acc.accountNumberMasked == null) {
            String acctNoRaw = CsvParser.firstNonBlank(row, "account number", "account no", "card number");
            if (acctNoRaw != null && !acctNoRaw.isBlank()) acc.accountNumberMasked = CsvParser.maskAccountNumber(acctNoRaw);
        }
        if (acc.accountHolderName == null) {
            String holderRaw = CsvParser.firstNonBlank(row, "account holder", "account holder name", "customer name", "holder name");
            if (holderRaw != null && !holderRaw.isBlank()) acc.accountHolderName = holderRaw.trim();
        }
        if (acc.branchName == null) {
            String branchRaw = CsvParser.firstNonBlank(row, "branch", "branch name", "home branch");
            if (branchRaw != null && !branchRaw.isBlank()) acc.branchName = branchRaw.trim();
        }
        if (acc.ifscCode == null) {
            String ifscRaw = CsvParser.firstNonBlank(row, "ifsc", "ifsc code");
            if (ifscRaw != null && !ifscRaw.isBlank()) acc.ifscCode = ifscRaw.trim().toUpperCase(Locale.ROOT);
        }
        if (acc.creditLimit == null) {
            BigDecimal limit = CsvParser.parseNumeric(CsvParser.firstNonBlank(row, "credit limit"));
            if (limit != null) { acc.creditLimit = limit; acc.creditCardSignals = true; }
        }
        if (acc.dueDate == null) {
            String dueRaw = CsvParser.firstNonBlank(row, "due date", "payment due date");
            if (dueRaw != null) acc.dueDate = CsvParser.parseDate(dueRaw.trim());
        }
        if (CsvParser.hasHeaderMatch(row, "card number", "minimum due", "minimum amount due")) {
            acc.creditCardSignals = true;
        }
    }

    public DetectedAccountInfo buildDetectedAccountInfo(
            String filename, List<String[]> allRows, int headerIdx,
            List<StagedRow> staged, AccountSignalAccumulator acc) {

        // Bug fix: this used to derive the period from transaction dates alone, while the PDF path
        // (PdfPreviewGenerator.buildDetectedAccountInfo) prefers the printed period and only falls
        // back to transaction dates. A CSV carrying an explicit "Statement Period" column was
        // ignored -- and one of this suite's own fixtures shows the cost: a statement covering
        // 01-Jul-2026 to 31-Jul-2026 with transactions only on the 1st and 2nd was recorded as a
        // 2-day statement, persisted on StatementImport and shown to the user via AccountDto.
        //
        // The printed period is what the bank asserts the statement covers; transaction dates are
        // only ever a lower bound on it. Same precedence as the PDF path now, so the two formats
        // cannot disagree about the same statement.
        LocalDate[] printedPeriod = printedStatementPeriod(allRows, headerIdx);
        LocalDate statementStart = printedPeriod[0] != null ? printedPeriod[0]
                : staged.stream().map(StagedRow::date).min(LocalDate::compareTo).orElse(null);
        LocalDate statementEnd = printedPeriod[1] != null ? printedPeriod[1]
                : staged.stream().map(StagedRow::date).max(LocalDate::compareTo).orElse(null);

        BigDecimal openingBalance = null;
        BigDecimal closingBalance = null;
        if (!acc.balanceObservations.isEmpty()) {
            LocalDate minDate = acc.balanceObservations.stream().map(BalanceObservation::date).min(LocalDate::compareTo).orElseThrow();
            LocalDate maxDate = acc.balanceObservations.stream().map(BalanceObservation::date).max(LocalDate::compareTo).orElseThrow();
            List<BalanceObservation> minDateGroup = acc.balanceObservations.stream().filter(o -> o.date().equals(minDate)).toList();
            List<BalanceObservation> maxDateGroup = acc.balanceObservations.stream().filter(o -> o.date().equals(maxDate)).toList();

            BalanceObservation trueFirstOfDay = BalanceChainUtil.first(minDateGroup);
            BalanceObservation trueLastOfDay = BalanceChainUtil.last(maxDateGroup);

            // Bug fix: this used to unconditionally back out the first row's own signed amount,
            // on the assumption every statement's earliest balance observation is an ordinary
            // transaction rather than an explicit "OPENING BALANCE" label row. TransactionNormalizer
            // falls back to the balance/running-balance column as the amount for such label rows
            // (no debit/credit value to read), which made this subtract balance - (-balance),
            // silently doubling the detected opening balance whenever a CSV export prints one of
            // these rows. Mirrors the identical fix already applied on the PDF path
            // (PdfPreviewGenerator.buildDetectedAccountInfo's isExplicitOpeningRow) -- only skip the
            // signed-amount subtraction when the row actually IS that kind of explicit label row.
            boolean isExplicitOpeningRow = trueFirstOfDay.description() != null
                    && trueFirstOfDay.description().toLowerCase(Locale.ROOT).contains("opening balance");
            openingBalance = isExplicitOpeningRow
                    ? trueFirstOfDay.balance()
                    : trueFirstOfDay.balance().subtract(trueFirstOfDay.signedAmount());
            closingBalance = trueLastOfDay.balance();
        }

        List<String> bankTextHints = collectBankTextHints(allRows, headerIdx);
        BankRegistry.BankInfo bank = BankRegistry.detect(filename, bankTextHints);

        // Financial Product Discovery runs on CSV exactly as it does on PDF -- the four stages
        // consume column names and surrounding text, neither of which is format-specific. Wiring
        // only the PDF path would have made "which product is this" a question the engine could
        // answer for one input format and not the other, for no reason other than where the work
        // started.
        List<String> headerNames = headerIdx >= 0 && headerIdx < allRows.size()
                ? List.of(allRows.get(headerIdx)) : List.of();
        ProductDiscovery.DiscoveredProduct product = productDiscovery.discover(
                new ProductEvidenceCollector.Section(headerNames, bankTextHints, null, staged.size()));

        return new DetectedAccountInfo(
                suggestedAccountName(bank),
                acc.creditCardSignals ? "CREDIT_CARD" : "SAVINGS",
                openingBalance, closingBalance, statementStart, statementEnd,
                // No payment-summary panel exists in a CSV export for totalAmountDue to come from.
                acc.accountNumberMasked, acc.creditLimit, null, acc.dueDate, acc.accountHolderName,
                acc.branchName, acc.ifscCode,
                AccountDto.BankDto.from(bank),
                product.type().name(), product.confidence(), product.needsReview(), product.report(),
                // CSV's account-number detection already masks before this point (see
                // AccountSignalAccumulator), so there is no unmasked number here to hash. The
                // masked digits still make a PROBABLE match possible, which is the honest ceiling
                // for a format that never gave us the full value.
                null,
                // Deposit-attribute extraction and one-row-per-product splitting are PDF-only for
                // now (see PdfPreviewGenerator) -- no real CSV export in the current corpus
                // represents a multi-deposit FD/RD schedule the way a combined-statement PDF does,
                // and inventing that handling with no real document behind it is exactly what the
                // Test Corpus Strategy's "evidence before capability" rule warns against.
                null, null, null, null, null, null, null
        );
    }

    /** Real bank exports commonly carry the bank's name in the letterhead/metadata rows that sit
     *  above the transaction table (the same rows the header-detection skips over) -- e.g. "PNB
     *  ONE Statement" or "State Bank of India" printed as a plain cell before the header row.
     *  Scanning those cells alongside the filename gives BankRegistry.detect a second, more
     *  reliable signal than the filename alone (a user can rename a file to anything; the bank's
     *  own letterhead text in the file itself is a stronger signal that they didn't write). */
    private List<String> collectBankTextHints(List<String[]> allRows, int headerIdx) {
        int limit = headerIdx >= 0 ? Math.min(headerIdx + 1, allRows.size()) : Math.min(allRows.size(), 30);
        List<String> hints = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            for (String cell : allRows.get(i)) {
                if (cell != null && !cell.isBlank()) hints.add(cell);
            }
        }
        return hints;
    }

    /** Header spellings for a column carrying the statement's own printed period. Matched through
     *  CsvParser.normalizeHeaderCell, so casing and a trailing parenthetical are already handled. */
    private static final List<String> STATEMENT_PERIOD_HINTS =
            List.of("statement period", "statement date range", "period", "statement duration");

    /**
     * The period the statement itself declares, as {@code [start, end]} with either element null
     * when it can't be read. Mirrors the PDF path's precedence -- see the caller's comment.
     *
     * <p>Reads the first non-blank value below the header, not every row: the period is a property
     * of the statement, so it is identical on every row that carries it, and a later row
     * disagreeing would mean something is wrong that guessing cannot fix.
     *
     * <p>Splits on "to" or a dash surrounded by whitespace -- the two spellings seen in real
     * statements ("01-Jul-2026 to 31-Jul-2026", "24/06/2026 - 22/07/2026"). Parsing goes through
     * {@link CsvParser#parseDate} so this understands exactly the formats the rest of the CSV
     * pipeline does, rather than growing a second, quietly different date vocabulary.
     */
    private LocalDate[] printedStatementPeriod(List<String[]> allRows, int headerIdx) {
        LocalDate[] none = new LocalDate[]{null, null};
        if (allRows == null || headerIdx < 0 || headerIdx >= allRows.size()) return none;

        String[] header = allRows.get(headerIdx);
        int periodColumn = -1;
        for (int c = 0; c < header.length; c++) {
            if (header[c] != null && STATEMENT_PERIOD_HINTS.contains(CsvParser.normalizeHeaderCell(header[c]))) {
                periodColumn = c;
                break;
            }
        }
        if (periodColumn < 0) return none;

        for (int r = headerIdx + 1; r < allRows.size(); r++) {
            String[] row = allRows.get(r);
            if (row == null || periodColumn >= row.length) continue;
            String value = row[periodColumn];
            if (value == null || value.isBlank()) continue;

            String[] parts = value.trim().split("(?i)\\s+to\\s+|\\s+-\\s+|\\s+–\\s+");
            if (parts.length < 2) return none;
            LocalDate start = CsvParser.parseDate(parts[0].trim());
            LocalDate end = CsvParser.parseDate(parts[1].trim());
            // Both or neither: half a period is worse than none, because the missing half would
            // silently fall back to a transaction date and produce a range that never appeared on
            // the statement at all.
            if (start == null || end == null) return none;
            return new LocalDate[]{start, end};
        }
        return none;
    }

    private String suggestedAccountName(BankRegistry.BankInfo bank) {
        // Never the raw filename -- that was the reported bug ("Pnbone Stmt Xx4802
        // 23072026.csv" showing up as the account name verbatim). A recognized bank's official
        // name is used instead, or this clean, honest fallback when nothing was recognized.
        return bank.officialName() != null ? bank.officialName() : "Bank Statement Import";
    }
}
