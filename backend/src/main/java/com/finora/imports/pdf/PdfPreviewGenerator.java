package com.finora.imports.pdf;

import com.finora.accounts.AccountDto;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.imports.CsvParser;
import com.finora.imports.TransactionNormalizer;
import com.finora.util.BankRegistry;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
 *
 * A single PDF is no longer assumed to contain exactly one account: {@link #generateSections}
 * detects every account section {@link PdfTableLocator#locateAll} finds (e.g. HSBC's "Composite
 * Statement" bundles a savings-account section and a credit-card section in one file) and stages
 * each independently. {@link #generate} remains as a single-account convenience wrapper -- for
 * the (still common) single-section document, its behavior is byte-for-byte what it always was.
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

    /** Single-account convenience wrapper over {@link #generateSections} -- returns the FIRST
     *  (and, for every document with exactly one detected section, only) section in the same
     *  {@link StagingResponse} shape this method has always returned. Callers that need to
     *  detect and stage multiple accounts from one upload (see
     *  {@code ImportService.parseAndStagePdfWithSession}) call {@link #generateSections} instead. */
    public StagingResponse generate(UUID userId, String filename, byte[] fileBytes) throws IOException {
        StagedAccountSection first = generateSections(userId, filename, fileBytes).get(0);
        return new StagingResponse(first.rows(), first.totalParsed(), first.flaggedDuplicates(), first.detectedAccount());
    }

    /** Detects and stages every account section in the document. Always returns at least one
     *  element -- a document with no recognizable transaction table anywhere still yields one
     *  section with zero rows (same "well-formed empty result rather than a 500" contract the
     *  single-section path has always followed), so a bank recognizable purely from letterhead
     *  text still gets suggested even when nothing parsed as a transaction. */
    public List<StagedAccountSection> generateSections(UUID userId, String filename, byte[] fileBytes) throws IOException {
        List<PositionedText> positioned = textExtractor.extract(fileBytes);
        PdfTableLocator.LocatedDocument doc = tableLocator.locateAll(positioned);

        if (doc.sections().isEmpty()) {
            PdfTableLocator.LocatedTable empty = tableLocator.locate(positioned);
            return List.of(buildSection(userId, filename,
                    new PdfTableLocator.LocatedSection(empty.preTableLines(), List.of())));
        }

        List<StagedAccountSection> result = new ArrayList<>();
        for (PdfTableLocator.LocatedSection section : doc.sections()) {
            result.add(buildSection(userId, filename, section));
        }
        return result;
    }

    private StagedAccountSection buildSection(UUID userId, String filename, PdfTableLocator.LocatedSection section) {
        List<StagedRow> staged = new ArrayList<>();
        // date -> balance-as-reported, purely to derive opening/closing balance below -- not
        // persisted anywhere, discarded once this method returns.
        List<BalancePoint> balancePoints = new ArrayList<>();
        for (Map<String, String> row : section.rows()) {
            StagedRow parsed = transactionNormalizer.normalize(userId, row);
            if (parsed == null) continue; // same "skip rows that don't parse as a transaction" contract CSV's PreviewGenerator follows
            staged.add(parsed);

            BigDecimal balance = CsvParser.parseNumeric(
                    CsvParser.firstNonBlank(row, "balance", "running balance", "closing balance"));
            if (balance != null) {
                BigDecimal signedAmount = "INCOME".equals(parsed.type()) ? parsed.amount() : parsed.amount().negate();
                balancePoints.add(new BalancePoint(parsed.date(), signedAmount, balance, parsed.description()));
            }
        }

        // Bug fix: some real exports (PNB ONE) list transactions newest-first -- the balance-chain
        // reconstruction below is already value-based (BalanceChainUtil.first/last match by implied
        // pre-transaction balance, never by list position) so it's unaffected by this, but the
        // staged rows themselves used to come back in raw file order, i.e. reverse-chronological,
        // which read oddly in the review table. Sorted here, once, right before returning.
        staged.sort(Comparator.comparing(StagedRow::date));

        int dupCount = (int) staged.stream().filter(StagedRow::likelyDuplicate).count();
        DetectedAccountInfo detected = buildDetectedAccountInfo(filename, section, staged, balancePoints);
        return new StagedAccountSection(detected, staged, staged.size(), dupCount);
    }

    private record BalancePoint(LocalDate date, BigDecimal signedAmount, BigDecimal balance,
                                 String description) implements com.finora.imports.BalanceChainUtil.ChainLink {
        @Override public BigDecimal balanceAfter() { return balance; }
    }

    private DetectedAccountInfo buildDetectedAccountInfo(String filename, PdfTableLocator.LocatedSection section,
                                                           List<StagedRow> staged, List<BalancePoint> balancePoints) {
        PdfMetadataExtractor.ExtractedMetadata metadata = metadataExtractor.extract(section.auxiliaryText());

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
                    && trueFirstOfDay.description().toLowerCase(Locale.ROOT).contains("opening balance");
            openingBalance = isExplicitOpeningRow
                    ? trueFirstOfDay.balance()
                    : trueFirstOfDay.balance().subtract(trueFirstOfDay.signedAmount());
            closingBalance = trueLastOfDay.balance();
        }

        List<String> bankTextHints = new ArrayList<>(section.auxiliaryText());
        BankRegistry.BankInfo bank = BankRegistry.detect(filename, bankTextHints);
        String suggestedName = bank.officialName() != null ? bank.officialName() : "Bank Statement Import";

        // A credit-card statement's own signal rarely lives in a table COLUMN the way CSV's
        // StatementValidator.scanRow can key off (e.g. "Card Number") -- Axis/HDFC-style layouts
        // carry it only in a free-text payment-summary block ("Total Payment Due", "Minimum
        // Amount Due") that sits above the transaction table, i.e. in this section's own
        // auxiliaryText, not in any row. Checking both keeps this correct for either shape.
        boolean creditCardSignals = section.rows().stream().anyMatch(row ->
                CsvParser.hasHeaderMatch(row, "card number", "minimum due", "minimum amount due"))
                || section.auxiliaryText().stream().anyMatch(this::containsCreditCardTextSignal);

        return new DetectedAccountInfo(
                suggestedName,
                creditCardSignals ? "CREDIT_CARD" : "SAVINGS",
                openingBalance, closingBalance, statementStart, statementEnd,
                metadata.accountNumberMasked(), metadata.creditLimit(), metadata.paymentDueDate(),
                metadata.accountHolderName(), metadata.branchName(), metadata.ifscCode(),
                AccountDto.BankDto.from(bank));
    }

    private boolean containsCreditCardTextSignal(String line) {
        if (line == null) return false;
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("total payment due") || lower.contains("minimum amount due")
                || lower.contains("minimum due") || lower.contains("credit limit") || lower.contains("card number");
    }
}
