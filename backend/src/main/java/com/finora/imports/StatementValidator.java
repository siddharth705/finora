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
                                       BigDecimal balance) implements BalanceChainUtil.ChainLink {
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
                acc.balanceObservations.add(new BalanceObservation(parsedRow.date(), signedAmount, balance));
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

        LocalDate statementStart = staged.stream().map(StagedRow::date).min(LocalDate::compareTo).orElse(null);
        LocalDate statementEnd = staged.stream().map(StagedRow::date).max(LocalDate::compareTo).orElse(null);

        BigDecimal openingBalance = null;
        BigDecimal closingBalance = null;
        if (!acc.balanceObservations.isEmpty()) {
            LocalDate minDate = acc.balanceObservations.stream().map(BalanceObservation::date).min(LocalDate::compareTo).orElseThrow();
            LocalDate maxDate = acc.balanceObservations.stream().map(BalanceObservation::date).max(LocalDate::compareTo).orElseThrow();
            List<BalanceObservation> minDateGroup = acc.balanceObservations.stream().filter(o -> o.date().equals(minDate)).toList();
            List<BalanceObservation> maxDateGroup = acc.balanceObservations.stream().filter(o -> o.date().equals(maxDate)).toList();

            BalanceObservation trueFirstOfDay = BalanceChainUtil.first(minDateGroup);
            BalanceObservation trueLastOfDay = BalanceChainUtil.last(maxDateGroup);
            openingBalance = trueFirstOfDay.balance().subtract(trueFirstOfDay.signedAmount());
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
                acc.accountNumberMasked, acc.creditLimit, acc.dueDate, acc.accountHolderName,
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

    private String suggestedAccountName(BankRegistry.BankInfo bank) {
        // Never the raw filename -- that was the reported bug ("Pnbone Stmt Xx4802
        // 23072026.csv" showing up as the account name verbatim). A recognized bank's official
        // name is used instead, or this clean, honest fallback when nothing was recognized.
        return bank.officialName() != null ? bank.officialName() : "Bank Statement Import";
    }
}
