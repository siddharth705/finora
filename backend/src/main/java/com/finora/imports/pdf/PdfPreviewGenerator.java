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
 * source format. Does NOT reuse {@code StatementValidator} -- its balance-observation
 * accumulator is package-private with no accessors, and widening that just for this would be a
 * cross-package change to existing, already-hardened CSV code that this milestone's own
 * guardrails say to avoid. Opening/closing balance is instead derived locally, by the same
 * earliest/latest-by-date logic, small enough that duplicating it here is more honest than
 * reaching into another package's internals to avoid ten lines of arithmetic.
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
            if (balance != null) balancePoints.add(new BalancePoint(parsed.date(), balance));
        }

        int dupCount = (int) staged.stream().filter(StagedRow::likelyDuplicate).count();
        DetectedAccountInfo detected = buildDetectedAccountInfo(filename, table.preTableLines(), staged, balancePoints);
        return new StagingResponse(staged, staged.size(), dupCount, detected);
    }

    private record BalancePoint(LocalDate date, BigDecimal balance) {}

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
            BalancePoint earliest = balancePoints.get(0);
            BalancePoint latest = balancePoints.get(0);
            for (BalancePoint p : balancePoints) {
                if (p.date().isBefore(earliest.date())) earliest = p;
                if (!p.date().isBefore(latest.date())) latest = p;
            }
            // Opening balance = the earliest row's own reported balance -- unlike CSV's
            // StatementValidator (which subtracts that row's signed transaction amount to get
            // the balance BEFORE it), a PDF statement conventionally includes an explicit
            // "OPENING BALANCE" row with no transaction amount of its own -- see the golden
            // fixture -- so the earliest reported balance already IS the opening balance.
            openingBalance = earliest.balance();
            closingBalance = latest.balance();
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
