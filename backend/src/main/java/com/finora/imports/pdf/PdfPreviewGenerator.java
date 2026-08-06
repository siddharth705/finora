package com.finora.imports.pdf;

import com.finora.accounts.AccountDto;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.dto.ImportDto.UnparseableRow;
import com.finora.imports.CsvParser;
import com.finora.imports.pdf.StatementSummaryExtractor.PrintedSummary;
import com.finora.imports.DocumentContext;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.product.ProductAttributeExtractor;
import com.finora.imports.product.ProductAttributes;
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
    private final ProductAttributeExtractor attributeExtractor;

    private final com.finora.imports.ImportVerifier importVerifier;

    public PdfPreviewGenerator(PdfTextExtractor textExtractor, PdfTableLocator tableLocator,
                                PdfMetadataExtractor metadataExtractor, TransactionNormalizer transactionNormalizer,
                                ProductDiscovery productDiscovery, ProductAttributeExtractor attributeExtractor,
                                com.finora.imports.ImportVerifier importVerifier) {
        this.textExtractor = textExtractor;
        this.tableLocator = tableLocator;
        this.metadataExtractor = metadataExtractor;
        this.transactionNormalizer = transactionNormalizer;
        this.productDiscovery = productDiscovery;
        this.attributeExtractor = attributeExtractor;
        this.importVerifier = importVerifier;
    }

    /** Single-account convenience wrapper over {@link #generateSections} -- returns the FIRST
     *  (and, for every document with exactly one detected section, only) section in the same
     *  {@link StagingResponse} shape this method has always returned. Callers that need to
     *  detect and stage multiple accounts from one upload (see
     *  {@code ImportService.parseAndStagePdfWithSession}) call {@link #generateSections} instead. */
    public StagingResponse generate(UUID userId, String filename, byte[] fileBytes) throws IOException {
        return generate(userId, filename, fileBytes, null);
    }

    /** @param password see {@link PdfTextExtractor#extract(byte[], String)}; null when none was given. */
    public StagingResponse generate(UUID userId, String filename, byte[] fileBytes, String password) throws IOException {
        StagedAccountSection first = generateSectionsWithContext(userId, filename, fileBytes, password).sections().get(0);
        return new StagingResponse(first.rows(), first.totalParsed(), first.flaggedDuplicates(), first.detectedAccount(), first.unparseableRows());
    }

    /** Detects and stages every account section in the document. Always returns at least one
     *  element -- a document with no recognizable transaction table anywhere still yields one
     *  section with zero rows (same "well-formed empty result rather than a 500" contract the
     *  single-section path has always followed), so a bank recognizable purely from letterhead
     *  text still gets suggested even when nothing parsed as a transaction. */
    public List<StagedAccountSection> generateSections(UUID userId, String filename, byte[] fileBytes) throws IOException {
        return generateSections(userId, filename, fileBytes, null);
    }

    /** @param password see {@link PdfTextExtractor#extract(byte[], String)}; null when none was given. */
    public List<StagedAccountSection> generateSections(UUID userId, String filename, byte[] fileBytes, String password) throws IOException {
        return generateSectionsWithContext(userId, filename, fileBytes, password).sections();
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
        return generateSectionsWithContext(userId, filename, fileBytes, null);
    }

    /**
     * @param password the document open password, or null when the request carried none. Held only
     *   for the duration of this call: it is handed straight to {@link PdfTextExtractor} and is
     *   never written to the {@link DocumentContext}, the staged rows, or the import session, so
     *   it does not outlive the request. See {@link PdfTextExtractor#extract(byte[], String)} for
     *   why supplying one for an unencrypted document is safe.
     */
    public PdfGenerationResult generateSectionsWithContext(UUID userId, String filename, byte[] fileBytes, String password) throws IOException {
        DocumentContext ctx = new DocumentContext("PDF", "PdfPreviewGenerator");
        List<PositionedText> positioned = textExtractor.extract(fileBytes, password);
        PdfTableLocator.LocatedDocument doc = tableLocator.locateAll(positioned, ctx);
        // Read from the positioned runs rather than from the located table: the summary grid has
        // its own column layout, so bucketing it against the TRANSACTION table's anchors shreds it
        // -- on a real HDFC statement it arrives as one unparseable row reading "Credit Amount
        // 538.00 25,000.00 Credit Count 3 1". The geometry is intact right here; the table is
        // where it stops being intact.
        PrintedSummary printedSummary = StatementSummaryExtractor.extract(positioned, ctx);

        if (doc.sections().isEmpty()) {
            // "Never lose information" (see the engineering principles doc) applies at the
            // whole-document level too: previously this returned a well-formed but silently empty
            // response -- indistinguishable from a genuinely blank PDF. Every non-blank line of
            // extracted text is surfaced here instead, since without a recognized table there's no
            // header to key a structured row by.
            PdfTableLocator.LocatedTable empty = tableLocator.locate(positioned, ctx);
            PdfTableLocator.LocatedSection emptySection =
                    new PdfTableLocator.LocatedSection(empty.preTableLines(), List.of());
            // Goes straight to buildLedgerSection rather than through buildSections' product-vs-
            // ledger routing: with no rows and no header at all, classification can only ever
            // return UNKNOWN, and UNKNOWN's own hasTransactions()==false would otherwise divert
            // this into buildProductSections -- losing "Never lose information"'s unparseable-text
            // reporting below, which is the one thing this branch exists to preserve.
            ProductDiscovery.DiscoveredProduct unknown = productDiscovery.discover(
                    new ProductEvidenceCollector.Section(List.of(), emptySection.auxiliaryText(), null, 0));
            StagedAccountSection section = buildLedgerSection(userId, filename, emptySection, unknown, ctx, PrintedSummary.NONE);
            return new PdfGenerationResult(List.of(surfaceUnrecognizedText(section, empty.preTableLines())), ctx);
        }

        List<StagedAccountSection> result = new ArrayList<>();
        List<UnparseableRow> unparseableAcrossDocument = new ArrayList<>();
        for (int i = 0; i < doc.sections().size(); i++) {
            // A printed summary covers the whole document, so it can only be attributed to a
            // section when there is exactly one. On a composite statement the totals belong to
            // some section and we cannot tell which -- checking the wrong section's rows against
            // them would manufacture a failure out of a correct import.
            List<StagedAccountSection> staged = buildSections(userId, filename, doc.sections().get(i),
                    i, doc.sections().size(), ctx,
                    doc.sections().size() == 1 ? printedSummary : PrintedSummary.NONE);
            for (StagedAccountSection s : staged) unparseableAcrossDocument.addAll(s.unparseableRows());
            result.addAll(staged);
        }
        // One document's worth, across every section -- the DocumentContext is per-file, and a
        // combined statement's sections all failed (or didn't) as part of the same parse run.
        ctx.recordUnparseable(unparseableAcrossDocument);
        return new PdfGenerationResult(result, ctx);
    }

    /**
     * One located section becomes ONE staged section for a ledger product (a savings account, a
     * credit card) -- but for a deposit product it can become SEVERAL: a fixed-deposit section
     * lists every FD the customer holds as its own row, and each is its own product with its own
     * principal, rate and maturity date. Classifying is done ONCE here, up front, using the
     * section's raw rows and auxiliary text -- exactly the same inputs {@code buildDetectedAccountInfo}
     * always classified on -- so a ledger section's own classification is byte-for-byte what it was
     * before this method existed; only the branch taken afterward is new.
     *
     * This is also what stops a deposit's own principal/date from being fed to
     * {@link TransactionNormalizer} as if it were a transaction candidate: a real fixed-deposit row
     * ("Principal Amount", "Start Date", ...) has both a date-shaped and an amount-shaped column,
     * which is exactly what the normalizer looks for, and a section that was never a ledger to
     * begin with used to have its deposit rows silently treated as one anyway -- landing in
     * "unparseable" at best, or staged as a fabricated transaction at worst, once product routing
     * had already decided the account itself belonged in Investments.
     */
    private List<StagedAccountSection> buildSections(UUID userId, String filename,
                                                      PdfTableLocator.LocatedSection section,
                                                      int sectionIndex, int sectionCount, DocumentContext ctx,
                                                      PrintedSummary printedSummary) {
        List<String> columns = section.rows().isEmpty() ? List.of() : List.copyOf(section.rows().get(0).keySet());
        ProductDiscovery.DiscoveredProduct product = productDiscovery.discover(
                new ProductEvidenceCollector.Section(columns, section.auxiliaryText(), null,
                        section.rows().size(), sectionIndex, sectionCount));
        if (ctx != null) ctx.record("FINANCIAL_PRODUCT_CLASSIFICATION");

        // Skipping transaction parsing requires the product to be PROVEN a non-ledger, not merely
        // suspected of it. UNKNOWN's own hasTransactions() is false too (its domain needs user
        // input), so gating on that alone diverted every unclassified section into the deposit path
        // and silently dropped all of its rows -- caught by thirteen existing capability tests
        // going to zero staged rows at once. "Unknown-first" means a graceful UNKNOWN, and losing
        // the entire statement is not graceful: an unproven product keeps the ledger path, gets its
        // rows parsed exactly as before, and is held back from auto-creating anything by
        // productNeedsReview instead.
        if (product.validation().isValidated() && !product.type().hasTransactions()) {
            return buildProductSections(filename, section, product, ctx);
        }
        return List.of(buildLedgerSection(userId, filename, section, product, ctx, printedSummary));
    }

    /**
     * A fixed/recurring deposit, PPF, or any other non-ledger product: no transaction parsing at
     * all -- see {@link #buildSections}'s own doc comment for why running it here used to be
     * actively wrong -- just attribute extraction and, for a fixed deposit with more than one row,
     * one {@link StagedAccountSection} per deposit.
     */
    private List<StagedAccountSection> buildProductSections(String filename, PdfTableLocator.LocatedSection section,
                                                             ProductDiscovery.DiscoveredProduct product,
                                                             DocumentContext ctx) {
        SharedSectionFacts facts = sharedFacts(filename, section, ctx);
        List<ProductAttributes> attributes = attributeExtractor.extract(product.type(), section.rows());
        String suggestedAccountType = suggestedAccountTypeFor(product, facts.creditCardSignals());

        List<StagedAccountSection> result = new ArrayList<>();
        for (ProductAttributes attrs : attributes) {
            // No opening/closing balance, no statement period -- neither concept applies to a
            // deposit schedule the way it does to a ledger's own transaction date range.
            DetectedAccountInfo detected = facts.toDetectedAccountInfo(product, suggestedAccountType,
                    null, null, facts.metadata().statementPeriodStart(), facts.metadata().statementPeriodEnd(), attrs);
            result.add(new StagedAccountSection(detected, List.of(), 0, 0, List.of()));
        }
        return result;
    }

    private StagedAccountSection buildLedgerSection(UUID userId, String filename,
                                                    PdfTableLocator.LocatedSection section,
                                                    ProductDiscovery.DiscoveredProduct product,
                                                    DocumentContext ctx, PrintedSummary printedSummary) {
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
        DetectedAccountInfo detected = buildDetectedAccountInfo(filename, section, staged, balancePoints, product, ctx);
        // Per section rather than per file: a composite statement's sections have separate balance
        // chains, and one can verify while another does not.
        var verification = importVerifier.verify(staged,
                detected == null ? null : detected.openingBalance(),
                detected == null ? null : detected.closingBalance(),
                printedSummary);
        return new StagedAccountSection(detected, staged, staged.size(), dupCount, unparseable, verification);
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
                                                           ProductDiscovery.DiscoveredProduct product,
                                                           DocumentContext ctx) {
        LocalDate statementStart = null;
        LocalDate statementEnd = null;
        BigDecimal openingBalance = null;
        BigDecimal closingBalance = null;

        SharedSectionFacts facts = sharedFacts(filename, section, ctx);
        statementStart = facts.metadata().statementPeriodStart() != null ? facts.metadata().statementPeriodStart()
                : staged.stream().map(StagedRow::date).min(LocalDate::compareTo).orElse(null);
        statementEnd = facts.metadata().statementPeriodEnd() != null ? facts.metadata().statementPeriodEnd()
                : staged.stream().map(StagedRow::date).max(LocalDate::compareTo).orElse(null);

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

        return facts.toDetectedAccountInfo(product, suggestedAccountTypeFor(product, facts.creditCardSignals()),
                openingBalance, closingBalance, statementStart, statementEnd, ProductAttributes.empty());
    }

    /**
     * Metadata, bank identity and the credit-card text signal -- every fact about a section that
     * applies whether it turns out to be a ledger or a deposit schedule. Factored out once both
     * {@link #buildDetectedAccountInfo} (ledger) and {@link #buildProductSections} (deposit) needed
     * it, rather than the classification-and-metadata block that used to live only in the former.
     */
    private SharedSectionFacts sharedFacts(String filename, PdfTableLocator.LocatedSection section,
                                           DocumentContext ctx) {
        PdfMetadataExtractor.ExtractedMetadata metadata = metadataExtractor.extract(section.auxiliaryText(), ctx);
        BankRegistry.BankInfo bank = BankRegistry.detect(filename, new ArrayList<>(section.auxiliaryText()));
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

        return new SharedSectionFacts(metadata, bank, suggestedName, creditCardSignals);
    }

    private record SharedSectionFacts(PdfMetadataExtractor.ExtractedMetadata metadata, BankRegistry.BankInfo bank,
                                      String suggestedName, boolean creditCardSignals) {

        DetectedAccountInfo toDetectedAccountInfo(ProductDiscovery.DiscoveredProduct product,
                                                  String suggestedAccountType, BigDecimal openingBalance,
                                                  BigDecimal closingBalance, LocalDate statementStart,
                                                  LocalDate statementEnd, ProductAttributes attrs) {
            return new DetectedAccountInfo(
                    suggestedName, suggestedAccountType,
                    openingBalance, closingBalance, statementStart, statementEnd,
                    metadata.accountNumberMasked(), metadata.creditLimit(), metadata.paymentDueDate(),
                    metadata.accountHolderName(), metadata.branchName(), metadata.ifscCode(),
                    AccountDto.BankDto.from(bank),
                    product.type().name(), product.confidence(), product.needsReview(), product.report(),
                    // Hashed here and only here: this is the last point in the pipeline where the
                    // unmasked number exists, and it must not travel any further than this call.
                    //
                    // The deposit discriminator is what keeps several deposits listed under ONE
                    // section's account number distinguishable -- that number is the customer's
                    // relationship number, not any one deposit's. Null for a ledger account, whose
                    // number identifies it on its own. See ProductIdentity.forDeposit.
                    ProductIdentity.of(bank.id(), product.type(),
                            metadata.accountNumberFullForHashingOnly(), metadata.accountNumberMasked(),
                            ProductIdentity.forDeposit(attrs.principalAmount(), attrs.maturityDate(),
                                    attrs.installmentAmount()))
                            .strongKey(),
                    attrs.principalAmount(), attrs.interestRate(), attrs.maturityDate(), attrs.maturityAmount(),
                    attrs.installmentAmount(), attrs.installmentsPaid(), attrs.installmentsTotal());
        }
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
