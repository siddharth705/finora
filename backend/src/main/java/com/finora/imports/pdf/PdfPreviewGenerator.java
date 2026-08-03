package com.finora.imports.pdf;

import com.finora.accounts.AccountDto;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.dto.ImportDto.UnparseableRow;
import com.finora.imports.CsvParser;
import com.finora.imports.DocumentContext;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.product.ProductDiscovery;
import com.finora.imports.product.ProductIdentity;
import com.finora.imports.product.ProductEvidenceCollector;
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
    private final ProductDiscovery productDiscovery;

    public PdfPreviewGenerator(PdfTextExtractor textExtractor, PdfTableLocator tableLocator,
                                PdfMetadataExtractor metadataExtractor, TransactionNormalizer transactionNormalizer,
                                ProductDiscovery productDiscovery) {
        this.textExtractor = textExtractor;
        this.tableLocator = tableLocator;
        this.metadataExtractor = metadataExtractor;
        this.transactionNormalizer = transactionNormalizer;
        this.productDiscovery = productDiscovery;
    }

    /** Single-account convenience wrapper over {@link #generateSections} -- returns the FIRST
     *  (and, for every document with exactly one detected section, only) section in the same
     *  {@link StagingResponse} shape this method has always returned. Callers that need to
     *  detect and stage multiple accounts from one upload (see
     *  {@code ImportService.parseAndStagePdfWithSession}) call {@link #generateSections} instead. */
    public StagingResponse generate(UUID userId, String filename, byte[] fileBytes) throws IOException {
        StagedAccountSection first = generateSectionsWithContext(userId, filename, fileBytes).sections().get(0);
        return new StagingResponse(first.rows(), first.totalParsed(), first.flaggedDuplicates(), first.detectedAccount(), first.unparseableRows());
    }

    /** Detects and stages every account section in the document. Always returns at least one
     *  element -- a document with no recognizable transaction table anywhere still yields one
     *  section with zero rows (same "well-formed empty result rather than a 500" contract the
     *  single-section path has always followed), so a bank recognizable purely from letterhead
     *  text still gets suggested even when nothing parsed as a transaction. */
    public List<StagedAccountSection> generateSections(UUID userId, String filename, byte[] fileBytes) throws IOException {
        return generateSectionsWithContext(userId, filename, fileBytes).sections();
    }

    /** One {@link DocumentContext}'s worth of recorded structural facts and capability
     *  activations for the WHOLE document -- every section of a multi-account PDF (e.g. HSBC's
     *  composite statement) shares one, since they came from the same file (Phase 1 "capture
     *  facts" -- docs/engineering/financial-document-intelligence-principles.md). */
    public record PdfGenerationResult(List<StagedAccountSection> sections, DocumentContext documentContext) {}

    /** Same as {@link #generateSections}, but also returns the {@link DocumentContext} built
     *  while parsing -- the entry point {@code ImportService} uses when it needs to persist that
     *  context (a fresh upload); {@link #generateSections}/{@link #generate} stay the plain,
     *  context-discarding wrappers every existing caller (including every capability's own
     *  regression test) already depends on. */
    public PdfGenerationResult generateSectionsWithContext(UUID userId, String filename, byte[] fileBytes) throws IOException {
        DocumentContext ctx = new DocumentContext("PDF", "PdfPreviewGenerator");
        List<PositionedText> positioned = textExtractor.extract(fileBytes);
        PdfTableLocator.LocatedDocument doc = tableLocator.locateAll(positioned, ctx);

        if (doc.sections().isEmpty()) {
            // "Never lose information" (see the engineering principles doc) applies at the
            // whole-document level too: previously this returned a well-formed but silently empty
            // response -- indistinguishable from a genuinely blank PDF. Every non-blank line of
            // extracted text is surfaced here instead, since without a recognized table there's no
            // header to key a structured row by.
            PdfTableLocator.LocatedTable empty = tableLocator.locate(positioned, ctx);
            StagedAccountSection section = buildSection(userId, filename,
                    new PdfTableLocator.LocatedSection(empty.preTableLines(), List.of()), ctx);
            return new PdfGenerationResult(List.of(surfaceUnrecognizedText(section, empty.preTableLines())), ctx);
        }

        List<StagedAccountSection> result = new ArrayList<>();
        for (PdfTableLocator.LocatedSection section : doc.sections()) {
            result.add(buildSection(userId, filename, section, ctx));
        }
        return new PdfGenerationResult(result, ctx);
    }

    private StagedAccountSection buildSection(UUID userId, String filename, PdfTableLocator.LocatedSection section,
                                               DocumentContext ctx) {
        List<StagedRow> staged = new ArrayList<>();
        // "Never lose information" (see the engineering principles doc) -- a row that fails to
        // normalize is reported with WHY, not just silently absent from the row count. Real cost
        // of this on the PDF path specifically: PdfTableLocator treats everything after a header
        // as a candidate row (see its own doc comment), so this can include genuine boilerplate
        // (disclaimer paragraphs, page footers that survived PAGE_FOOTER filtering under a
        // different phrasing, etc.), not just transactions the engine failed to understand. Kept
        // unfiltered here anyway rather than guessing at a second heuristic for "is this row even
        // worth reporting" -- the frontend review screen is where that judgment call belongs, not
        // this layer inventing a second, less-principled filter on top of the real one.
        List<UnparseableRow> unparseable = new ArrayList<>();
        // date -> balance-as-reported, purely to derive opening/closing balance below -- not
        // persisted anywhere, discarded once this method returns.
        List<BalancePoint> balancePoints = new ArrayList<>();
        for (Map<String, String> row : section.rows()) {
            StagedRow parsed = transactionNormalizer.normalize(userId, row, ctx);
            if (parsed == null) {
                unparseable.add(new UnparseableRow(row, transactionNormalizer.explainFailure(row)));
                continue;
            }
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
        DetectedAccountInfo detected = buildDetectedAccountInfo(filename, section, staged, balancePoints, ctx);
        return new StagedAccountSection(detected, staged, staged.size(), dupCount, unparseable);
    }

    private StagedAccountSection surfaceUnrecognizedText(StagedAccountSection section, List<String> extractedLines) {
        List<UnparseableRow> unparseable = new ArrayList<>();
        for (String line : extractedLines) {
            if (line == null || line.isBlank()) continue;
            unparseable.add(new UnparseableRow(Map.of("text", line),
                    "No transaction table was recognized anywhere in this document"));
        }
        return new StagedAccountSection(section.detectedAccount(), section.rows(), section.totalParsed(),
                section.flaggedDuplicates(), unparseable);
    }

    private record BalancePoint(LocalDate date, BigDecimal signedAmount, BigDecimal balance,
                                 String description) implements com.finora.imports.BalanceChainUtil.ChainLink {
        @Override public BigDecimal balanceAfter() { return balance; }
    }

    private DetectedAccountInfo buildDetectedAccountInfo(String filename, PdfTableLocator.LocatedSection section,
                                                           List<StagedRow> staged, List<BalancePoint> balancePoints,
                                                           DocumentContext ctx) {
        PdfMetadataExtractor.ExtractedMetadata metadata = metadataExtractor.extract(section.auxiliaryText(), ctx);

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
        if (ctx != null && creditCardSignals) ctx.record("CREDIT_CARD_SUMMARY_SIGNAL");

        // Financial Product Discovery: Evidence Collection -> Classification -> Validation. Runs on
        // this section's own columns and its own auxiliary text, so a combined statement's opening
        // summary can't name a product for a table it has nothing to do with.
        ProductDiscovery.DiscoveredProduct product = productDiscovery.discover(
                new ProductEvidenceCollector.Section(
                        section.rows().isEmpty() ? List.of() : List.copyOf(section.rows().get(0).keySet()),
                        section.auxiliaryText(), null, section.rows().size()));
        if (ctx != null) ctx.record("FINANCIAL_PRODUCT_CLASSIFICATION");

        return new DetectedAccountInfo(
                suggestedName,
                suggestedAccountTypeFor(product, creditCardSignals),
                openingBalance, closingBalance, statementStart, statementEnd,
                metadata.accountNumberMasked(), metadata.creditLimit(), metadata.paymentDueDate(),
                metadata.accountHolderName(), metadata.branchName(), metadata.ifscCode(),
                AccountDto.BankDto.from(bank),
                product.type().name(), product.confidence(), product.needsReview(), product.report(),
                // Hashed here and only here: this is the last point in the pipeline where the
                // unmasked number exists, and it must not travel any further than this call.
                ProductIdentity.of(bank.id(), product.type(),
                        metadata.accountNumberFullForHashingOnly(), metadata.accountNumberMasked())
                        .strongKey());
    }

    /**
     * What to prefill the review form's account-type field with.
     *
     * Deliberately conservative, and deliberately NOT the same question as {@code detectedProduct}.
     * The existing credit-card text signal is a proven capability with its own regression coverage,
     * so it still wins where it fires; product discovery only gets to choose the type when it has
     * actually validated a product, which is what lets a term deposit prefill INVESTMENT instead of
     * being offered as a savings account. Everything else keeps the long-standing SAVINGS default
     * -- an UNKNOWN product still has to put something in the form, and {@code productNeedsReview}
     * is what tells the review screen not to trust it.
     */
    private String suggestedAccountTypeFor(ProductDiscovery.DiscoveredProduct product, boolean creditCardSignals) {
        if (creditCardSignals) return "CREDIT_CARD";
        if (product.mayCreateAutomatically() && product.type().accountType() != null) {
            return product.type().accountType().name();
        }
        return "SAVINGS";
    }

    private boolean containsCreditCardTextSignal(String line) {
        if (line == null) return false;
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("total payment due") || lower.contains("minimum amount due")
                || lower.contains("minimum due") || lower.contains("credit limit") || lower.contains("card number");
    }
}
