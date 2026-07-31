package com.finora.imports.pdf;

import com.finora.accounts.AccountDto;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.imports.TransactionNormalizer;
import com.finora.util.BankRegistry;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The PDF equivalent of {@code com.finora.imports.PreviewGenerator} -- produces the exact same
 * {@link StagingResponse} CSV's own staging path returns, so everything downstream (ImportSession,
 * confirm, review) is completely unaware whether a given session came from a CSV or a PDF upload.
 * This is the ONLY class in this package anything outside com.finora.imports.pdf should ever call.
 *
 * Reuses {@link TransactionNormalizer} directly and unmodified -- it already operates on a
 * generic {@code Map<String,String>} row, with nothing CSV-specific baked in, so once
 * {@link PdfTableLocator} produces that same row shape, normalization is identical regardless of
 * source format. Does NOT reuse {@code StatementValidator} itself (its balance-observation
 * accumulator is package-private with no accessors, and widening that just for this would be a
 * cross-package change to existing, already-hardened CSV code) -- but DOES share
 * {@link com.finora.imports.BalanceChainUtil} with it for the actual opening/closing-balance
 * reconstruction, after that logic's own local copy here turned out to have the same file-order
 * bug StatementValidator's copy did, undetected for exactly as long as the two copies existed
 * independently. See that class's own doc comment for the full story.
 */
@Component
public class PdfPreviewGenerator {

    private final PdfTextExtractor textExtractor;
    private final PdfTableLocator tableLocator;
    private final PdfMetadataExtractor metadataExtractor;
    private final TransactionNormalizer transactionNormalizer;

    public PdfPreviewGenerator(PdfTextExtractor textExtractor, PdfTableLocator tableLocator,
                                PdfMetadataExtractor metadataExtractor, TransactionNormalizer transactionNormalizer) {
        this.textExtractor = textExtractor;
        this.tableLocator = tableLocator;
        this.metadataExtractor = metadataExtractor;
        this.transactionNormalizer = transactionNormalizer;
    }

    public StagingResponse generate(UUID userId, String filename, byte[] fileBytes) throws IOException {
        List<PositionedText> positioned = textExtractor.extract(fileBytes);
        PdfTableLocator.LocatedTable table = tableLocator.locate(positioned);

        List<StagedRow> staged = new ArrayList<>();
        // date -> balance-as-reported, purely to derive opening/closing balance below -- not
        // persisted anywhere, discarded once this method returns.
        List<BalancePoint> balancePoints = new ArrayList<>();
        for (Map<String, String> row : table.rows()) {
            StagedRow parsed = transactionNormalizer.normalize(userId, row);
            if (parsed == null) continue; // same "skip rows that don't parse as a transaction" contract CSV's PreviewGenerator follows
            staged.add(parsed);

            BigDecimal balance = com.finora.imports.CsvParser.parseNumeric(
                    com.finora.imports.CsvParser.firstNonBlank(row, "balance", "running balance", "closing balance"));
            if (balance != null) {
                BigDecimal signedAmount = "INCOME".equals(parsed.type()) ? parsed.amount() : parsed.amount().negate();
                balancePoints.add(new BalancePoint(parsed.date(), signedAmount, balance, parsed.description()));
            }
        }

        int dupCount = (int) staged.stream().filter(StagedRow::likelyDuplicate).count();
        DetectedAccountInfo detected = buildDetectedAccountInfo(filename, table.preTableLines(), staged, balancePoints);
        return new StagingResponse(staged, staged.size(), dupCount, detected);
    }

    private record BalancePoint(LocalDate date, BigDecimal signedAmount, BigDecimal balance,
                                 String description) implements com.finora.imports.BalanceChainUtil.ChainLink {
        @Override public BigDecimal balanceAfter() { return balance; }
    }

    private DetectedAccountInfo buildDetectedAccountInfo(String filename, List<String> preTableLines,
                                                           List<StagedRow> staged, List<BalancePoint> balancePoints) {
        PdfMetadataExtractor.ExtractedMetadata metadata = metadataExtractor.extract(preTableLines);

        LocalDate statementStart = metadata.statementPeriodStart() != null ? metadata.statementPeriodStart()
                : staged.stream().map(StagedRow::date).min(LocalDate::compareTo).orElse(null);
        LocalDate statementEnd = metadata.statementPeriodEnd() != null ? metadata.statementPeriodEnd()
                : staged.stream().map(StagedRow::date).max(LocalDate::compareTo).orElse(null);

        BigDecimal openingBalance = null;
        BigDecimal closingBalance = null;
        if (!balancePoints.isEmpty()) {
            LocalDate minDate = balancePoints.stream().map(BalancePoint::date).min(LocalDate::compareTo).orElseThrow();
            LocalDate maxDate = balancePoints.stream().map(BalancePoint::date).max(LocalDate::compareTo).orElseThrow();
            List<BalancePoint> minDateGroup = balancePoints.stream().filter(p -> p.date().equals(minDate)).toList();
            List<BalancePoint> maxDateGroup = balancePoints.stream().filter(p -> p.date().equals(maxDate)).toList();

            // Bug fix: this used to just take whichever balance point appeared first/last in
            // table.rows() for the statement's boundary dates -- exactly the same file-position
            // assumption StatementValidator's CSV path had, and just as wrong: verified against a
            // real PNB ONE PDF statement (no CSV involved) with a multi-transaction same-day
            // cluster on its earliest date, listed newest-first. Delegates to the same
            // BalanceChainUtil the CSV path now uses, specifically so this doesn't drift out of
            // sync with that fix again the way it silently did the first time.
            BalancePoint trueFirstOfDay = com.finora.imports.BalanceChainUtil.first(minDateGroup);
            BalancePoint trueLastOfDay = com.finora.imports.BalanceChainUtil.last(maxDateGroup);

            // Bug fix, compounding the one above: this unconditionally used the earliest point's
            // own reported balance as-is, on the assumption every PDF statement carries an
            // explicit "OPENING BALANCE" row (true for the golden fixture, false for a real PNB
            // ONE export, which has no such row at all -- just ordinary transactions against a
            // running balance column). Only skip the signed-amount subtraction when the row
            // actually IS that kind of explicit label row; otherwise back out its own transaction
            // amount to recover the balance that existed BEFORE it, same as CSV's StatementValidator.
            boolean isExplicitOpeningRow = trueFirstOfDay.description() != null
                    && trueFirstOfDay.description().toLowerCase(java.util.Locale.ROOT).contains("opening balance");
            openingBalance = isExplicitOpeningRow
                    ? trueFirstOfDay.balance()
                    : trueFirstOfDay.balance().subtract(trueFirstOfDay.signedAmount());
            closingBalance = trueLastOfDay.balance();
        }

        List<String> bankTextHints = new ArrayList<>(preTableLines);
        BankRegistry.BankInfo bank = BankRegistry.detect(filename, bankTextHints);
        String suggestedName = bank.officialName() != null ? bank.officialName() : "Bank Statement Import";

        return new DetectedAccountInfo(
                suggestedName,
                "SAVINGS", // Milestone 1 scope is a savings-statement layout only -- see package doc
                openingBalance, closingBalance, statementStart, statementEnd,
                metadata.accountNumberMasked(), null, null,
                metadata.accountHolderName(), metadata.branchName(), metadata.ifscCode(),
                AccountDto.BankDto.from(bank));
    }
}
