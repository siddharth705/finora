package com.finora.imports.pdf;

import com.finora.imports.DuplicateIndex;
import com.finora.imports.MerchantIndex;
import com.finora.accounts.AccountDto;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.dto.ImportDto.UnparseableRow;
import com.finora.entity.CategoryRule;
import com.finora.imports.CsvParser;
import com.finora.imports.pdf.StatementSummaryExtractor.PrintedSummary;
import com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence;
import com.finora.imports.DocumentContext;
import com.finora.imports.ExplicitZeroActivityDetector;
import com.finora.imports.RowKind;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.product.FinancialProductType;
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
import java.util.regex.Pattern;

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

    private final com.finora.imports.pdf.acquisition.DocumentTextAcquirer textAcquirer;
    private final PdfTableLocator tableLocator;
    private final PdfMetadataExtractor metadataExtractor;
    private final TransactionNormalizer transactionNormalizer;
    private final ProductDiscovery productDiscovery;
    private final ProductAttributeExtractor attributeExtractor;

    private final com.finora.imports.ImportVerifier importVerifier;
    private final com.finora.service.RuleEngineService ruleEngineService;

    /**
     * The wired constructor. Takes an ACQUIRER rather than an extractor, so that how the text was
     * obtained is a decision made once, outside this class, and everything here stays the same
     * whether the characters were read or recognised.
     *
     * <p>{@code @Autowired} because there are now two constructors and Spring will not guess. Its
     * failure when it cannot is indirect -- "No default constructor found", reported against this
     * class rather than against the ambiguity -- so the annotation is load-bearing rather than
     * decorative.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public PdfPreviewGenerator(com.finora.imports.pdf.acquisition.DocumentTextAcquirer textAcquirer,
                                PdfTableLocator tableLocator,
                                PdfMetadataExtractor metadataExtractor, TransactionNormalizer transactionNormalizer,
                                ProductDiscovery productDiscovery, ProductAttributeExtractor attributeExtractor,
                                com.finora.imports.ImportVerifier importVerifier,
                                com.finora.service.RuleEngineService ruleEngineService) {
        this.textAcquirer = textAcquirer;
        this.tableLocator = tableLocator;
        this.metadataExtractor = metadataExtractor;
        this.transactionNormalizer = transactionNormalizer;
        this.productDiscovery = productDiscovery;
        this.attributeExtractor = attributeExtractor;
        this.importVerifier = importVerifier;
        this.ruleEngineService = ruleEngineService;
    }

    /**
     * Native extraction only, for callers that hold an extractor rather than an acquirer.
     *
     * <p>Every existing test builds this class from a {@link PdfTextExtractor}, and rewriting all of
     * them to construct an acquirer would have changed a great deal of test code in the same commit
     * that changed routing -- which is exactly the diff in which a real regression hides. This
     * overload keeps those call sites reading as they did and gives them the behaviour they have
     * always had: read the text layer, and do nothing else.
     */
    public PdfPreviewGenerator(PdfTextExtractor textExtractor, PdfTableLocator tableLocator,
                                PdfMetadataExtractor metadataExtractor, TransactionNormalizer transactionNormalizer,
                                ProductDiscovery productDiscovery, ProductAttributeExtractor attributeExtractor,
                                com.finora.imports.ImportVerifier importVerifier,
                                com.finora.service.RuleEngineService ruleEngineService) {
        this(new com.finora.imports.pdf.acquisition.NativePdfAcquirer(textExtractor), tableLocator,
                metadataExtractor, transactionNormalizer, productDiscovery, attributeExtractor,
                importVerifier, ruleEngineService);
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
        // first.verification() is passed explicitly because the five-argument StagingResponse
        // overload defaults it to null, and this wrapper used to take that default -- discarding
        // the report buildLedgerSection had just computed for this very section. Same failure as
        // the one surfaceUnrecognizedText documents below, at the other end of the same method.
        // Reachable in production via ImportService.parseAndStageAnyFormat, i.e. the "Re-import
        // Statement" action on a single-account PDF. See
        // docs/architecture/system-design/pdfpreviewgenerator-verification-loss-investigation.md.
        return new StagingResponse(first.rows(), first.totalParsed(), first.flaggedDuplicates(),
                first.detectedAccount(), first.unparseableRows(), first.verification());
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
     *  facts" -- docs/engineering/financial-document-intelligence-principles.md).
     *
     *  <p>{@code creditCardSummary} (roadmap item 6 follow-up, PR #451): the SAME document-level
     *  reading already computed below and handed to every section's {@code buildLedgerSection}
     *  (see the same "effectively always one account" reasoning there) -- exposed here too so
     *  {@code ImportService} can carry the full balance breakdown into the session, not just the
     *  {@code totalAmountDue} field that already reaches {@code DetectedAccountInfo}. {@code
     *  CreditCardSummaryEvidence.NONE} (never null) when no summary panel was found. */
    public record PdfGenerationResult(List<StagedAccountSection> sections, DocumentContext documentContext,
                                       CreditCardSummaryEvidence creditCardSummary) {}

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
        // Acquisition, not extraction: this may be the PDF's own text layer or characters
        // recognised from its pixels, and nothing below this line is allowed to care which. See
        // RoutingTextAcquirer for which one runs and why.
        com.finora.imports.pdf.acquisition.AcquiredDocument acquired = textAcquirer.acquire(fileBytes, password);
        List<PositionedText> positioned = acquired.runs();
        // A count, never the text. Lets ExtractionCheck tell "the pages carry no text" from
        // "we read plenty and could not make a table of it" -- see DocumentContext.
        //
        // CodeQL (java/useless-null-check), 2026-09-04: this used to be guarded by `if (ctx !=
        // null)`, which was always true -- ctx comes straight from `new DocumentContext(...)`
        // above, with no reassignment in between. The guard's own presence is what misled a
        // second CodeQL query (java/dereferenced-value-may-be-null) into flagging ctx.textSource()
        // much further down this method as inconsistent with it. Removed rather than kept: every
        // other ctx use in this method (locateAll, extract, ...) already assumes it's non-null.
        ctx.recordExtractedRuns(positioned.size());
        // Provenance, not a judgement -- see DocumentContext.recordTextSource's own doc comment.
        // Recorded here because this is the one place the AcquiredDocument itself (not just its
        // runs) is ever in scope.
        ctx.recordTextSource(acquired.source());
        PdfTableLocator.LocatedDocument doc = tableLocator.locateAll(positioned, ctx);
        doc = new PdfTableLocator.LocatedDocument(
                mergeOrphanedInvestmentFragments(doc.sections(), ctx), doc.physicalRowFormationEvidence());
        // Read from the positioned runs rather than from the located table: the summary grid has
        // its own column layout, so bucketing it against the TRANSACTION table's anchors shreds it
        // -- on a real HDFC statement it arrives as one unparseable row reading "Credit Amount
        // 538.00 25,000.00 Credit Count 3 1". The geometry is intact right here; the table is
        // where it stops being intact.
        PrintedSummary printedSummary = StatementSummaryExtractor.extract(positioned, ctx);
        // Read the same way and for the same reason as printedSummary above: a credit-card billing
        // panel has its own column layout distinct from the transaction table's. Unlike
        // printedSummary, this is never re-attributed per section below -- a real credit-card
        // statement is effectively always one account, and CreditCardStatementTotalsValidator never
        // reads a section's transaction rows anyway, so handing every section the same document-
        // level reading is correct, not a simplification that loses anything.
        CreditCardSummaryEvidence printedCreditCardSummary = CreditCardSummaryExtractor.extract(positioned, ctx);
        // Read the same way, for a NARROWER reason than printedCreditCardSummary above: a statement
        // that states its transaction date range inside the table's own repeated header row (rather
        // than any pre-table "Statement Period" field) never reaches PdfMetadataExtractor's
        // auxiliaryText at all -- see TransactionTableDateRangeExtractor's own doc comment. Unlike a
        // credit-card billing panel, this extractor is not restricted to credit-card documents, and
        // "a real credit-card statement is effectively always one account" does not transfer to it --
        // PdfTableLocator's own SECTION_MARKER/composite-deposit-schedule evidence proves genuine
        // multi-account documents with independently-dated sections exist in this corpus. Trusted
        // document-wide only when the document IS effectively one section (matches printedSummary's
        // own "which section does this belong to" caution below, just resolved eagerly instead of via
        // attributePrintedSummary, since a first/only match is unambiguous when there is only one
        // section to receive it) -- withheld otherwise rather than risking the first section's own
        // printed range being copied onto every other section in a composite statement.
        TransactionTableDateRangeExtractor.PrintedDateRange printedDateRange =
                doc.sections().size() <= 1
                        ? TransactionTableDateRangeExtractor.extract(positioned, ctx)
                        : TransactionTableDateRangeExtractor.PrintedDateRange.NONE;
        // Third tier, same single-section caution as printedDateRange above: a real Kotak Mahindra
        // Bank SAVINGS statement prints its period as a bare, title-adjacent date range that reaches
        // neither PdfMetadataExtractor's auxiliaryText nor TransactionTableDateRangeExtractor's
        // table-header reading (see StatementTitleDateRangeExtractor's own doc comment). Tried only
        // when the table-header reading above found nothing, and folded into the same
        // printedDateRange value rather than threaded through every downstream signature as a third
        // parameter -- every consumer below already treats printedDateRange as "the best positional
        // reading available," and this is exactly that, just from a third source.
        if (printedDateRange.start() == null && doc.sections().size() <= 1) {
            StatementTitleDateRangeExtractor.PrintedDateRange titleDateRange =
                    StatementTitleDateRangeExtractor.extract(positioned, ctx);
            if (titleDateRange.start() != null) {
                printedDateRange = new TransactionTableDateRangeExtractor.PrintedDateRange(
                        titleDateRange.start(), titleDateRange.end());
            }
        }
        // Fourth tier, same single-section caution and the same folding-into-printedDateRange as the
        // third: a real Axis credit-card statement states its period inside the payment-summary grid
        // whose label row reaches none of the three sources above. Measured, not assumed -- that
        // section's auxiliaryText holds 127 lines and not one contains the word "period", so no
        // line-based pattern could recover it, while the positioned geometry is unambiguous. See
        // StatementPeriodGridExtractor's own doc comment.
        if (printedDateRange.start() == null && doc.sections().size() <= 1) {
            StatementPeriodGridExtractor.PrintedDateRange gridPeriod =
                    StatementPeriodGridExtractor.extract(positioned, ctx);
            if (gridPeriod.start() != null) {
                printedDateRange = new TransactionTableDateRangeExtractor.PrintedDateRange(
                        gridPeriod.start(), gridPeriod.end());
            }
        }

        // A credit-card statement's own "Payment Summary" grid (Axis, SBI evidence): read
        // document-wide, same reasoning as printedCreditCardSummary above ("a real credit-card
        // statement is effectively always one account") -- not gated on section count, since this
        // is a whole-account fact, not a per-section transaction range the way printedDateRange is.
        // See PaymentDueDateGridExtractor's own doc comment for why PdfMetadataExtractor's
        // line-based reading can never recover this on these two real documents at all.
        LocalDate gridPaymentDueDate = PaymentDueDateGridExtractor.extract(positioned, ctx);
        // Same reasoning, same document-wide/ungated-on-section-count scope, for the credit limit
        // itself: the same real Axis/SBI "Payment Summary" grid scrambles "Credit Limit" away from
        // its own value too -- see CreditLimitGridExtractor's own doc comment.
        BigDecimal gridCreditLimit = CreditLimitGridExtractor.extract(positioned, ctx);
        // Same reasoning, same document-wide/ungated-on-section-count scope, for the account/card
        // number itself: a real Axis credit-card statement's own "Credit Card Number" field is
        // scrambled the same way its Payment Due Date is -- see AccountNumberGridExtractor's own
        // doc comment.
        String gridAccountNumberMasked = AccountNumberGridExtractor.extract(positioned, ctx);
        // A different real document (ICICI) prints its own account number with no label at all,
        // positioned directly under the transaction table's own "Date" column header -- neither
        // scrambled nor label-anchored, so AccountNumberGridExtractor alone can't recover it. See
        // AccountNumberTransactionHeaderExtractor's own doc comment.
        if (gridAccountNumberMasked == null) {
            gridAccountNumberMasked = AccountNumberTransactionHeaderExtractor.extract(positioned, ctx);
        }

        if (doc.sections().isEmpty()) {
            // "Never lose information" (see the engineering principles doc) applies at the
            // whole-document level too: previously this returned a well-formed but silently empty
            // response -- indistinguishable from a genuinely blank PDF. Every non-blank line of
            // extracted text is surfaced here instead, since without a recognized table there's no
            // header to key a structured row by.
            PdfTableLocator.LocatedTable empty = tableLocator.locate(positioned, ctx);
            PdfTableLocator.LocatedSection emptySection =
                    new PdfTableLocator.LocatedSection(empty.preTableLines(), List.of(),
                            PdfTableLocator.ExtractionEvidence.NONE);
            // Goes straight to buildLedgerSection rather than through buildSections' product-vs-
            // ledger routing: with no rows and no header at all, classification can only ever
            // return UNKNOWN, and UNKNOWN's own hasTransactions()==false would otherwise divert
            // this into buildProductSections -- losing "Never lose information"'s unparseable-text
            // reporting below, which is the one thing this branch exists to preserve.
            ProductDiscovery.DiscoveredProduct unknown = productDiscovery.discover(
                    new ProductEvidenceCollector.Section(List.of(), emptySection.auxiliaryText(), null, 0));
            // The summary IS this section's: no table was recognised, so the document is one
            // section and there is no other candidate it could describe. Withholding it here left
            // the contradiction -- printed activity, nothing staged -- with nothing to state it.
            StagedAccountSection section = buildLedgerSection(userId, filename, emptySection, unknown, ctx,
                    printedSummary, printedCreditCardSummary, printedDateRange, gridPaymentDueDate,
                    gridCreditLimit, gridAccountNumberMasked, 1);
            return new PdfGenerationResult(List.of(surfaceUnrecognizedText(section, empty.preTableLines())), ctx,
                    printedCreditCardSummary);
        }

        List<StagedAccountSection> result = new ArrayList<>();
        List<UnparseableRow> unparseableAcrossDocument = new ArrayList<>();
        for (int i = 0; i < doc.sections().size(); i++) {
            // Built with no summary regardless. Which section a document-level summary belongs to
            // is not answerable here -- see attributePrintedSummary below, which decides it once
            // every section exists.
            List<StagedAccountSection> staged = buildSections(userId, filename, doc.sections().get(i),
                    i, doc.sections().size(), ctx, PrintedSummary.NONE, printedCreditCardSummary,
                    printedDateRange, gridPaymentDueDate, gridCreditLimit, gridAccountNumberMasked);
            for (StagedAccountSection s : staged) unparseableAcrossDocument.addAll(s.unparseableRows());
            result.addAll(staged);
        }
        result = attributePrintedSummary(result, printedSummary);
        result = inheritAccountNumberAcrossSections(result);
        // One document's worth, across every section -- the DocumentContext is per-file, and a
        // combined statement's sections all failed (or didn't) as part of the same parse run.
        ctx.recordUnparseable(unparseableAcrossDocument);
        return new PdfGenerationResult(result, ctx, printedCreditCardSummary);
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
                                                      PrintedSummary printedSummary,
                                                      CreditCardSummaryEvidence printedCreditCardSummary,
                                                      TransactionTableDateRangeExtractor.PrintedDateRange printedDateRange,
                                                      LocalDate gridPaymentDueDate, BigDecimal gridCreditLimit,
                                                      String gridAccountNumberMasked) {
        List<String> columns = section.rows().isEmpty() ? List.of() : List.copyOf(section.rows().get(0).keySet());
        ProductDiscovery.DiscoveredProduct product = productDiscovery.discover(
                new ProductEvidenceCollector.Section(columns, section.auxiliaryText(), null,
                        section.rows().size(), sectionIndex, sectionCount));
        // CodeQL (java/dereferenced-value-may-be-null #73), 2026-09-04, round 2: this and the two
        // other `ctx != null` checks in this file (originally at buildLedgerSection/
        // attributePrintedSummary) were never actually reachable with a null ctx -- traced the
        // full call graph: buildSections is only ever called from generateSectionsWithContext
        // passing its own local ctx (from `new DocumentContext(...)`, never reassigned), and every
        // method below it in the chain (buildProductSections, buildLedgerSection,
        // buildDetectedAccountInfo, sharedFacts) only ever receives ctx from a caller one level up
        // in this same chain -- never a literal null anywhere. These guards were what made CodeQL
        // flag ctx.textSource() in buildLedgerSection as inconsistent with "somewhere this is
        // checked for null," even after the one *directly* provable case (generateSectionsWithContext
        // itself) was fixed first.
        ctx.record("FINANCIAL_PRODUCT_CLASSIFICATION");

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
        return List.of(buildLedgerSection(userId, filename, section, product, ctx, printedSummary,
                printedCreditCardSummary, printedDateRange, gridPaymentDueDate, gridCreditLimit,
                gridAccountNumberMasked, sectionCount));
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
            // No payment-summary panel applies to a deposit schedule -- totalAmountDue is a
            // credit-card-ledger-only concept.
            DetectedAccountInfo detected = facts.toDetectedAccountInfo(product, suggestedAccountType,
                    null, null, facts.metadata().statementPeriodStart(), facts.metadata().statementPeriodEnd(), attrs,
                    null, facts.metadata().paymentDueDate(), facts.metadata().creditLimit(), null);
            result.add(new StagedAccountSection(detected, List.of(), 0, 0, List.of()));
        }
        return result;
    }

    private StagedAccountSection buildLedgerSection(UUID userId, String filename,
                                                    PdfTableLocator.LocatedSection section,
                                                    ProductDiscovery.DiscoveredProduct product,
                                                    DocumentContext ctx, PrintedSummary printedSummary,
                                                    CreditCardSummaryEvidence printedCreditCardSummary,
                                                    TransactionTableDateRangeExtractor.PrintedDateRange printedDateRange,
                                                    LocalDate gridPaymentDueDate, BigDecimal gridCreditLimit,
                                                    String gridAccountNumberMasked, int sectionCount) {
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
        // Hoisted for the same reason as PreviewGenerator's CSV loop -- see its comment. A
        // multi-account PDF calls this once per section, so the rule set is loaded once per
        // section rather than once per row.
        List<CategoryRule> rules = ruleEngineService.ruleSet(userId);
        // Built once per statement for the same reason the rule set is: a user's existing
        // transactions cannot change partway through parsing one file.
        DuplicateIndex duplicateIndex = transactionNormalizer.duplicateIndexFor(userId);
        // Same reasoning again, for merchant resolution (Transaction Intelligence Phase A) -- see
        // PreviewGenerator's identical hoist and MerchantIndex's own doc comment.
        MerchantIndex merchantIndex = transactionNormalizer.merchantIndexFor(userId);
        List<Map<String, String>> sectionRows = section.rows();
        // Checked once, up front, against the RAW located rows -- before the loop below decides
        // what any of them mean transactionally. A row can state the statement's own zero-activity
        // claim and still fail every classification below (see ExplicitZeroActivityDetector's own
        // doc comment for why it does, on the real evidencing document); this must not depend on
        // that loop's outcome, since the loop staging zero rows is exactly the situation this
        // exists to explain.
        //
        // Bug fix: same single-section caution as printedDateRange above, and for the identical
        // reason -- this fact is derived from ONE section's own rows, but DocumentContext is
        // shared and sticky across the WHOLE document (see its own doc comment), and
        // ExtractionCheck reads the flag as a whole-document verdict. Left ungated, a composite
        // statement where one section genuinely declares zero activity and a DIFFERENT section
        // has an unrelated, genuine extraction failure would have BOTH staged rows sum to zero and
        // the shared flag already true -- masking the real failure behind "nothing to import from
        // this file." The real evidencing document (HSBC) locates exactly one section, so this
        // costs it nothing; it only withholds the fact when a second section exists to be
        // misattributed to.
        // ctx is never null here -- see the call-graph trace on the FINANCIAL_PRODUCT_CLASSIFICATION
        // guard above, in buildSections.
        if (sectionCount <= 1
                && ExplicitZeroActivityDetector.anyRowDeclaresZeroTransactionCount(sectionRows)) {
            ctx.recordExplicitZeroActivityDeclared();
        }
        for (int i = 0; i < sectionRows.size(); i++) {
            Map<String, String> row = sectionRows.get(i);
            // 1-based, within this section -- same convention as PreviewGenerator's CSV path.
            int rowPosition = i + 1;
            StagedRow parsed = transactionNormalizer.normalize(userId, row, ctx, rules, duplicateIndex, merchantIndex);
            if (parsed == null) {
                unparseable.add(new UnparseableRow(row, transactionNormalizer.explainFailure(row)));
                continue;
            }
            parsed = parsed.withRowPosition(rowPosition);
            // RowKind.BALANCE_MARKER (see that enum's doc comment): a row whose only recognizable
            // amount came from a Balance-style column, not a real debit/credit/amount column --
            // structurally a statement's own OPENING BALANCE/CLOSING BALANCE label, not a
            // transaction. It must never become an importable ledger candidate (never added to
            // `staged`, so it never reaches ImportVerifier, the review screen, or a confirm
            // request), but it is NOT discarded: `parsed` is still read below, same as any other
            // row, to derive the statement's opening/closing balance -- that derivation has always
            // worked from the row's own date/amount, never from whether the row made it into
            // `staged`.
            if (parsed.kind() == RowKind.TRANSACTION) {
                staged.add(parsed);
            } else if (transactionNormalizer.hasUnrecognizedNonBlankColumn(row)) {
                // Not a CONFIDENT balance-marker classification: this row also has a non-blank
                // value under a column name TransactionNormalizer doesn't recognize at all, so
                // the BALANCE_MARKER verdict rests on a hint-list gap, not on the row genuinely
                // lacking transactional data (see hasUnrecognizedNonBlankColumn's own doc
                // comment). Excluding it from `staged` unconditionally would silently vanish a
                // row that may well be a real transaction -- worse than the pre-existing
                // wrong-amount bug this row shape used to hit, because at least that left the row
                // visible for the user to notice. Surface it via the existing unparseable
                // diagnostic instead; it is still used for balance-point derivation below exactly
                // like any other row.
                unparseable.add(new UnparseableRow(row,
                        "Row has a value in an unrecognized column and no recognized transactional "
                                + "amount column, so it could not be confidently classified as a transaction "
                                + "or excluded as a balance marker"));
            } else if (transactionNormalizer.hasUnparseableRecognizedAmount(row)) {
                // Third fix pass -- see TransactionNormalizer.hasUnparseableRecognizedAmount's own
                // doc comment and PreviewGenerator's identical guard. This row's column NAMES are
                // all recognized, but a real transactional column (Debit/Credit/Amount/etc.) holds
                // a non-blank value CsvParser.parseNumeric couldn't parse (e.g. "1500/-", or a
                // bank Dr/Cr format variant not yet covered) -- it never resolves, so the row
                // classified BALANCE_MARKER purely because its real amount column came up empty,
                // not because it genuinely lacks transactional data. Excluding it unconditionally
                // would silently vanish it with zero trace. Route it to the unparseable diagnostic
                // instead, same as the unrecognized-column case above.
                unparseable.add(new UnparseableRow(row,
                        "Row has a value in a recognized transactional amount column that could not be "
                                + "parsed as a number, so it could not be confidently classified as a "
                                + "transaction or excluded as a balance marker"));
            }

            BigDecimal balance = CsvParser.parseNumeric(
                    CsvParser.firstNonBlank(row, "balance", "running balance", "closing balance"));
            if (balance != null) {
                BigDecimal signedAmount = "INCOME".equals(parsed.type()) ? parsed.amount() : parsed.amount().negate();
                balancePoints.add(new BalancePoint(parsed.date(), signedAmount, balance, parsed.description()));
            }
        }

        // The document's own row sequence, captured BEFORE the display sort below.
        //
        // Bug fix: the sort was applied first and the sorted list was then handed to
        // importVerifier.verify(), which forwards it to BalanceChainValidator -- whose contract
        // says in as many words that "the chain is only meaningful along the document's own
        // sequence, which for same-day transactions is not date order ... rows arrive here
        // already sequenced." That validator walks the list positionally, carrying `previous`
        // forward, so on a newest-first export (PNB ONE, named in the sort comment below) the
        // sort reversed the entire sequence and every link computed against the wrong
        // predecessor. Essentially every pair was flagged, the discrepancy ratio cleared
        // FAILED_THRESHOLD, and a perfectly parsed statement was reported to the user as failing
        // its own verification -- the check manufacturing the misread it exists to catch. The CSV
        // path does not sort, so the two staging paths disagreed about the same document, which
        // is the drift BalanceChainValidator.report() was centralised to prevent.
        //
        // The sort's own justification was right about BalanceChainUtil, which is value-based and
        // genuinely order-independent, and wrong about BalanceChainValidator, which is a
        // different class.
        List<StagedRow> documentOrder = List.copyOf(staged);

        // Some real exports (PNB ONE) list transactions newest-first, which reads oddly in the
        // review table. Purely presentational, and now provably so: everything order-sensitive
        // reads documentOrder above.
        staged.sort(Comparator.comparing(StagedRow::date));

        int dupCount = (int) staged.stream().filter(StagedRow::likelyDuplicate).count();
        DetectedAccountInfo detected = buildDetectedAccountInfo(filename, section, balancePoints, product, ctx,
                printedCreditCardSummary, printedDateRange, gridPaymentDueDate, gridCreditLimit,
                gridAccountNumberMasked);
        // Per section rather than per file: a composite statement's sections have separate balance
        // chains, and one can verify while another does not.
        var verification = importVerifier.verify(documentOrder,
                detected == null ? null : detected.openingBalance(),
                detected == null ? null : detected.closingBalance(),
                printedSummary, section.rows(), unparseable, section.evidence().droppedTransactionCandidates(),
                printedCreditCardSummary,
                section.evidence().headerReconstructionFindings(), ctx.textSource());
        return new StagedAccountSection(detected, staged, staged.size(), dupCount, unparseable, verification);
    }

    /**
     * Gives a document-level printed summary to the one section it can only be about.
     *
     * <p>A printed summary describes the whole document, so attributing it to a section is a guess
     * unless the document leaves exactly one candidate. Checking the wrong section's rows against
     * another section's totals would manufacture a failure out of a correct import, which is worse
     * than not checking at all.
     *
     * <p>The rule is therefore exactly one condition, and deliberately not a heuristic near it:
     * <b>attribute only when precisely one section ended up with transactions.</b> Not the section
     * with the most rows, not the largest, not the first -- those are guesses wearing a rule's
     * clothing, and on a genuine two-account statement each one would pick a section whose totals
     * the summary does not describe.
     *
     * <p>Measured on the real HDFC combined statement this exists for: four sections, of which one
     * carries 75 transactions and three (a fixed-deposit schedule and two recurring-deposit
     * tables) carry none. The statement prints "Debit Count 66 / Credit Count 9" and totals of
     * 39,601.91 and 98,197.00, every one of which matches that section exactly -- and the strongest
     * evidence available that the parse is correct was being discarded, on the document family
     * SummaryTotalsValidator was built for.
     *
     * <p>Why this runs after building rather than during it: "has transactions" means STAGED rows,
     * which is not known until a section has been parsed. Deciding it from located rows instead
     * would have counted all four of that statement's sections -- its deposit tables locate 9, 2
     * and 7 rows and stage none of them -- and declined to attribute, which is the behaviour this
     * change exists to correct.
     */
    private List<StagedAccountSection> attributePrintedSummary(List<StagedAccountSection> sections,
                                                                PrintedSummary printedSummary) {
        if (printedSummary == null || printedSummary.isEmpty()) return sections;

        List<StagedAccountSection> transactional = sections.stream()
                .filter(s -> s.rows() != null && !s.rows().isEmpty())
                .toList();
        // Zero candidates and two-or-more candidates are both "cannot tell", and both keep the
        // existing behaviour. Zero is not merely uninteresting: a statement that printed totals
        // while nothing parsed is real evidence of a failed read, and surfacing THAT is a separate
        // question about when a finding should exist at all -- not this one, which only decides
        // which section receives a finding that already exists.
        if (transactional.size() != 1) return sections;

        StagedAccountSection target = transactional.get(0);
        List<StagedAccountSection> revised = new ArrayList<>(sections.size());
        for (StagedAccountSection s : sections) {
            revised.add(s != target ? s : new StagedAccountSection(s.detectedAccount(), s.rows(),
                    s.totalParsed(), s.flaggedDuplicates(), s.unparseableRows(),
                    // Zero, and it is never read: the located-row count is evidence for the
                    // contradiction branch, which requires a section with NO staged rows, while
                    // attribution by definition targets the one section that has them. Passing the
                    // unparseable count here would have been a different number wearing this one's
                    // name.
                    importVerifier.reviseSummaryTotals(s.verification(), s.rows(), printedSummary, 0)));
        }
        return revised;
    }

    /**
     * INVESTMENT_FRAGMENT_REMERGED. A real deposit product routinely prints its own account/holding
     * across TWO structurally different tables under one logical account -- a summary block (rate,
     * principal, maturity) and a separate schedule (installments, per-instrument rows) -- with no
     * SECTION_MARKER-style identity banner tying them together, so {@link PdfTableLocator} (which
     * has no notion of product type at all -- see {@code LocatedSection}'s own doc comment) stages
     * them as two independent sections. The summary table alone usually carries enough evidence to
     * classify confidently; the schedule alone often does not, because the two tables' own expected
     * signals ({@code MATURITY_FIELD}, {@code INTEREST_RATE_FIELD} on one; {@code
     * INSTALLMENT_FIELD} on the other) never repeat on both, capping its own standalone confidence
     * below the classifier's own 0.6 confidence threshold even with a genuine hypothesis.
     *
     * <p>Confirmed on a real HDFC composite statement: a Recurring Deposit's own "RD ACCOUNT
     * SUMMARY" table (2 rows, 95% confident RECURRING_DEPOSIT on its own) is immediately followed
     * by its own "LATEST INSTALLMENT DETAILS" table (7 rows, scores only 50% alone -- 2 of RD's 4
     * expected signals recorded NEGATIVE, since neither MATURITY_FIELD nor INTEREST_RATE_FIELD
     * repeats on an installment schedule -- falls to UNKNOWN, and UNKNOWN.hasTransactions()==false
     * means those 7 real rows never stage as transactions either, even though they were correctly
     * extracted). Merging the two sections' raw content BEFORE classification runs (rather than
     * trying to combine two already-built {@code StagedAccountSection} DTOs afterward, each with
     * its own derived attribute fields) lets the ordinary classification pipeline see every signal
     * together in one pass, the same evidence {@code FinancialProductClassifierTest}'s own {@code
     * anInstallmentScheduleIsARecurringDepositAndRoutesToInvestments} proves is sufficient when
     * both tables' signals are present together.
     *
     * <h2>Why this is narrowly scoped, not a general "merge adjacent sections" pass</h2>
     *
     * Deliberately requires the FIRST section to already be a confidently-classified, VALIDATED
     * INVESTMENT-domain product on its own -- never merges two sections that are merely adjacent,
     * and never merges INTO a section whose own classification is itself uncertain. A trial
     * classification of the SECOND section, run the identical way {@link #buildSections} will run
     * it for real, must also come back exactly {@link FinancialProductType#UNKNOWN} -- a section
     * that already classifies as something else (a genuinely separate, distinct account) is never
     * absorbed. Running the real, same-signature classification twice for a merge candidate (once
     * here to decide, once again inside {@code buildSections} on whatever the pre-pass leaves
     * behind) is redundant work, not redundant risk: both calls are pure functions of the same
     * evidence, so the second call simply confirms what the first already found.
     */
    private List<PdfTableLocator.LocatedSection> mergeOrphanedInvestmentFragments(
            List<PdfTableLocator.LocatedSection> sections, DocumentContext ctx) {
        List<PdfTableLocator.LocatedSection> merged = new ArrayList<>(sections);
        for (int i = 0; i < merged.size() - 1; ) {
            if (canMergeAsInvestmentFragment(merged, i)) {
                PdfTableLocator.LocatedSection first = merged.get(i);
                PdfTableLocator.LocatedSection second = merged.get(i + 1);
                List<Map<String, String>> mergedRows = new ArrayList<>(first.rows());
                mergedRows.addAll(second.rows());
                List<String> mergedAuxiliary = new ArrayList<>(first.auxiliaryText());
                mergedAuxiliary.addAll(second.auxiliaryText());
                List<PdfTableLocator.DroppedCandidateRow> mergedDropped =
                        new ArrayList<>(first.evidence().droppedTransactionCandidates());
                mergedDropped.addAll(second.evidence().droppedTransactionCandidates());
                List<PdfTableLocator.HeaderReconstructionFinding> mergedFindings =
                        new ArrayList<>(first.evidence().headerReconstructionFindings());
                mergedFindings.addAll(second.evidence().headerReconstructionFindings());
                merged.set(i, new PdfTableLocator.LocatedSection(mergedAuxiliary, mergedRows,
                        new PdfTableLocator.ExtractionEvidence(mergedDropped, mergedFindings)));
                merged.remove(i + 1);
                if (ctx != null) ctx.record("INVESTMENT_FRAGMENT_REMERGED");
                // Re-check the same index: the section that just absorbed a fragment might itself
                // still be confidently classified and immediately followed by ANOTHER orphaned
                // fragment (a document with more than two tables under one deposit account).
            } else {
                i++;
            }
        }
        return merged;
    }

    /** True when {@code sections.get(index)} is a confidently-classified, validated INVESTMENT-
     *  domain product and {@code sections.get(index + 1)}, classified the same way {@link
     *  #buildSections} classifies it for real, comes back UNKNOWN -- see {@link
     *  #mergeOrphanedInvestmentFragments}'s own doc comment for the full rationale. */
    private boolean canMergeAsInvestmentFragment(List<PdfTableLocator.LocatedSection> sections, int index) {
        int sectionCount = sections.size();
        ProductDiscovery.DiscoveredProduct first = classifySectionAlone(sections.get(index), index, sectionCount);
        if (!first.validation().isValidated() || !first.classification().isConfident()) return false;
        if (first.type().domain() != FinancialProductType.Domain.INVESTMENT) return false;

        ProductDiscovery.DiscoveredProduct second =
                classifySectionAlone(sections.get(index + 1), index + 1, sectionCount);
        return second.type() == FinancialProductType.UNKNOWN;
    }

    private ProductDiscovery.DiscoveredProduct classifySectionAlone(PdfTableLocator.LocatedSection section,
                                                                      int sectionIndex, int sectionCount) {
        List<String> columns = section.rows().isEmpty() ? List.of() : List.copyOf(section.rows().get(0).keySet());
        return productDiscovery.discover(new ProductEvidenceCollector.Section(columns, section.auxiliaryText(),
                null, section.rows().size(), sectionIndex, sectionCount));
    }

    /**
     * A credit-card statement's account/card number belongs to the whole relationship, not any one
     * section -- the same "effectively always one account" assumption {@code gridPaymentDueDate}/
     * {@code printedCreditCardSummary} already make elsewhere in this class, applied here as a
     * POST-PROCESSING fallback rather than a document-wide extraction: the number itself is already
     * correctly found by {@link PdfMetadataExtractor} on the section that owns it, and what's
     * missing is only propagating it to a SIBLING section that never found its own.
     *
     * <p>Confirmed on two real documents (SBI, IndusInd): both have a genuine, fully-formed
     * CREDIT_CARD section that DOES find its own {@code accountNumberMasked}, plus a second,
     * malformed {@code UNKNOWN}-product fragment (a rewards/purchase-detail sub-table {@link
     * PdfTableLocator} mis-splits into its own section) that never does.
     *
     * <p>Scoped narrowly to avoid mis-attributing a genuinely DIFFERENT account's number onto a
     * sibling: only ever copies FROM a {@code CREDIT_CARD} section (the "one relationship"
     * assumption applies to a credit card specifically, not to a generic multi-account composite
     * statement) INTO a sibling whose own {@code detectedProduct} is {@code UNKNOWN} OR that same
     * {@code CREDIT_CARD} -- never into a section the pipeline has positively identified as some
     * OTHER, distinct product. A real composite statement (Shivani_HDFC) has a genuine {@code
     * RECURRING_DEPOSIT} section with its own certificate number, which must never be overwritten
     * by a sibling account's number; gating on {@code UNKNOWN}/{@code CREDIT_CARD} specifically
     * (not "any section missing a number") is what keeps that case untouched.
     *
     * <p>The {@code CREDIT_CARD} half of that gate was added once WRAPPED_HEADER_INTERIOR_TIER_COLUMNS
     * started recognizing IndusInd's own malformed rewards/purchase-detail fragment's Description/
     * Merchant Category columns well enough for {@code ProductDiscovery} to positively identify it
     * as {@code CREDIT_CARD} too, rather than leaving it {@code UNKNOWN} -- a genuine improvement
     * that, left ungated, would have silently stopped this section from ever getting its account
     * number back: it is still a fragment of the SAME card, just no longer an unidentified one.
     */
    private List<StagedAccountSection> inheritAccountNumberAcrossSections(List<StagedAccountSection> sections) {
        if (sections.size() <= 1) return sections;
        String sourceAccountNumber = null;
        for (StagedAccountSection s : sections) {
            DetectedAccountInfo acc = s.detectedAccount();
            if (acc != null && "CREDIT_CARD".equals(acc.detectedProduct()) && acc.accountNumberMasked() != null) {
                sourceAccountNumber = acc.accountNumberMasked();
                break;
            }
        }
        if (sourceAccountNumber == null) return sections;

        List<StagedAccountSection> revised = new ArrayList<>(sections.size());
        for (StagedAccountSection s : sections) {
            DetectedAccountInfo acc = s.detectedAccount();
            if (acc != null && acc.accountNumberMasked() == null
                    && ("UNKNOWN".equals(acc.detectedProduct()) || "CREDIT_CARD".equals(acc.detectedProduct()))) {
                DetectedAccountInfo updated = new DetectedAccountInfo(
                        acc.suggestedName(), acc.suggestedAccountType(), acc.openingBalance(), acc.closingBalance(),
                        acc.statementPeriodStart(), acc.statementPeriodEnd(), sourceAccountNumber, acc.creditLimit(),
                        acc.totalAmountDue(), acc.paymentDueDate(), acc.accountHolderName(), acc.branchName(),
                        acc.ifscCode(), acc.bank(), acc.detectedProduct(), acc.productConfidence(),
                        acc.productNeedsReview(), acc.productEvidence(), acc.productIdentityHash(),
                        acc.principalAmount(), acc.interestRate(), acc.maturityDate(), acc.maturityAmount(),
                        acc.installmentAmount(), acc.installmentsPaid(), acc.installmentsTotal());
                revised.add(new StagedAccountSection(updated, s.rows(), s.totalParsed(), s.flaggedDuplicates(),
                        s.unparseableRows(), s.verification()));
            } else {
                revised.add(s);
            }
        }
        return revised;
    }

    private StagedAccountSection surfaceUnrecognizedText(StagedAccountSection section, List<String> extractedLines) {
        List<UnparseableRow> unparseable = new ArrayList<>();
        for (String line : extractedLines) {
            if (line == null || line.isBlank()) continue;
            unparseable.add(new UnparseableRow(Map.of("text", line),
                    "No transaction table was recognized anywhere in this document"));
        }
        // The five-argument constructor defaults verification to null, and this path used it --
        // silently discarding the report buildLedgerSection had just computed. That threw the
        // evidence away on exactly the documents where it matters most: the ones where nothing
        // parsed, which is where a statement's own printed totals are the only thing left that can
        // say the read failed. A real SBI statement printing 5 debits and 1 credit reached the user
        // with no verification report at all.
        return new StagedAccountSection(section.detectedAccount(), section.rows(), section.totalParsed(),
                section.flaggedDuplicates(), unparseable, section.verification());
    }

    private record BalancePoint(LocalDate date, BigDecimal signedAmount, BigDecimal balance,
                                 String description) implements com.finora.imports.BalanceSequenceResolver.DatedLink {
        @Override public BigDecimal balanceAfter() { return balance; }
    }

    private DetectedAccountInfo buildDetectedAccountInfo(String filename, PdfTableLocator.LocatedSection section,
                                                           List<BalancePoint> balancePoints,
                                                           ProductDiscovery.DiscoveredProduct product,
                                                           DocumentContext ctx,
                                                           CreditCardSummaryEvidence printedCreditCardSummary,
                                                           TransactionTableDateRangeExtractor.PrintedDateRange printedDateRange,
                                                           LocalDate gridPaymentDueDate, BigDecimal gridCreditLimit,
                                                           String gridAccountNumberMasked) {
        LocalDate statementStart = null;
        LocalDate statementEnd = null;
        BigDecimal openingBalance = null;
        BigDecimal closingBalance = null;

        SharedSectionFacts facts = sharedFacts(filename, section, ctx);
        // Bug fix: this used to fall back to the confirmed rows' own min/max transaction date
        // whenever nothing was printed -- which is only ever a LOWER bound on the statement's true
        // period whenever a cycle has no activity near its own printed boundary dates. Confirmed
        // wrong against a real Kotak Mahindra Bank credit-card statement: its printed period is
        // 16-Feb-2026 to 15-Mar-2026, but its own earliest/latest transactions fall on 15-Feb and
        // 14-Mar, so the transaction-range fallback silently reported a narrower, incorrect period
        // even though the statement states its real one. Two genuine sources are tried, in order --
        // PdfMetadataExtractor's own "Statement Period" label (a pre-table field), then
        // TransactionTableDateRangeExtractor's table-header reading (see its own doc comment for why
        // a field stated inside the table's own header row never reaches the first source at all) --
        // and if neither ever printed a period, this stays null rather than guessing one from the
        // rows. ImportService.confirmMultiSection's own analogous fallback was removed for the same
        // reason; see its own comment.
        statementStart = facts.metadata().statementPeriodStart() != null
                ? facts.metadata().statementPeriodStart() : printedDateRange.start();
        statementEnd = facts.metadata().statementPeriodEnd() != null
                ? facts.metadata().statementPeriodEnd() : printedDateRange.end();

        // Phase 2G: was independent BalanceChainUtil.first(minDateGroup)/last(maxDateGroup) calls,
        // each only ever looking at its own boundary date in isolation -- exactly the same
        // file-position-blind, but still boundary-only, gap StatementValidator's CSV path had.
        // Silently wrong whenever a boundary day's transactions include a same-day reversal (a
        // credit immediately offset by a debit of the same amount): the two candidates close a
        // numeric loop no within-group heuristic can resolve, and the old fallback picked the day's
        // peak balance as "last" instead of its true last transaction (confirmed on two real
        // documents; see
        // docs/architecture/system-design/same-day-reversal-closing-balance-investigation.md).
        // BalanceSequenceResolver walks every day in order instead of just the two boundaries, using
        // the statement's own explicit opening-balance declaration (when present) as its anchor --
        // same shared resolver the CSV path now uses too, specifically so this cannot drift out of
        // sync the way the two independent implementations silently did before BalanceChainUtil was
        // first extracted. Returns no opening/closing balance at all -- rather than a guess -- when
        // a day's ordering genuinely cannot be determined.
        if (!balancePoints.isEmpty()) {
            com.finora.imports.BalanceSequenceResolver.Resolution resolution =
                    com.finora.imports.BalanceSequenceResolver.resolve(balancePoints);
            openingBalance = resolution.openingBalance();
            closingBalance = resolution.closingBalance();
        }

        // Same precedence as statementStart/statementEnd above: PdfMetadataExtractor's own
        // line-based field wins when present, and the positioned-text grid reading is only tried
        // once that comes up empty -- see PaymentDueDateGridExtractor's own doc comment for why
        // its two real evidencing documents (Axis, SBI) can never be read the line-based way.
        LocalDate paymentDueDate = facts.metadata().paymentDueDate() != null
                ? facts.metadata().paymentDueDate() : gridPaymentDueDate;

        // INVERTED precedence from paymentDueDate above -- the positioned-text grid wins here, and
        // PdfMetadataExtractor's own line-based reading (GRID_CREDIT_LIMIT_LABEL's findGridValue
        // fallback) is only used when the grid reading found nothing. Evidenced necessary, not just
        // theoretically safer: on the real Indusland.pdf, the line-based fallback's own "first
        // amount-shaped match after the label" rule finds the WRONG number -- an unrelated "Payments
        // & Other Credits" figure that a stray word ("Credit") from the same corrupted line join
        // happens to sit directly before, one line above the credit limit's own true value. The grid
        // reading, by matching x-overlap against the label's own column instead of taking the first
        // number it meets, gets the real value. Confirmed safe to invert: checked all 8 real
        // credit-card documents directly -- CreditLimitGridExtractor.extract fires on exactly Axis,
        // SBI and Indusland (where metadata's line-based reading is respectively absent, absent, and
        // wrong) and returns null on AU/ICICI/Kotak/HDFC/HSBC (none of which carry a bare, standalone
        // "Credit Limit" text run this extractor's exact-match label requires -- AU's is "Total
        // Credit Limit:", ICICI's is "Credit Limit (Including cash)", neither an exact match), so
        // inverting the precedence cannot change their already-correct result.
        BigDecimal creditLimit = gridCreditLimit != null
                ? gridCreditLimit : facts.metadata().creditLimit();

        return facts.toDetectedAccountInfo(product, suggestedAccountTypeFor(product, facts.creditCardSignals()),
                openingBalance, closingBalance, statementStart, statementEnd, ProductAttributes.empty(),
                printedCreditCardSummary == null ? null : printedCreditCardSummary.totalAmountDue(),
                paymentDueDate, creditLimit, gridAccountNumberMasked);
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
        //
        // Bug fix: a single free-text match is not enough evidence. Verified against 17 real
        // statements: three savings/current accounts (Bank of Baroda, HSBC, SBI) each false
        // -positived a credit-card classification off exactly ONE isolated hit -- a generic
        // anti-phishing warning every bank prints regardless of account type ("please do not
        // share your ... card number, PIN, OTP ..."), and, for HSBC, a multi-account SUMMARY
        // TABLE's shared "Credit Limit" column header repeated once per account category
        // (Deposits and Investments, Borrowings), not evidence that THIS section is a credit
        // card. Every genuine credit-card statement tested (HDFC, ICICI, Axis), by contrast, hits
        // several DISTINCT phrases, not just one, because the payment-summary block and MITC
        // legal text repeat the concept from several angles. Requiring >= 2 distinct phrases
        // (not occurrence count -- HSBC's repeated column header would still pass a naive count)
        // reproduces every true positive tested and eliminates every false positive found.
        //
        // "card number" is dropped from this free-text scan entirely: every single occurrence
        // observed across all 17 documents, false-positive or genuine credit-card statement
        // alike, was "how to block your card" security instructions, never a labelled field --
        // it never once contributed real signal, only noise.
        //
        // Two follow-ups, both driven by real documents the first pass had not yet been measured
        // against (see CREDIT_CARD_TEXT_SIGNALS and CREDIT_LIMIT_LABELLED_FIELD for the detail):
        // the phrase list now carries every spelling the observed issuers actually print, since
        // substring matching does not collapse "Minimum Due" into "Minimum Amount Due" and whole
        // issuers were being missed outright; and the credit limit is now required as a LABELLED
        // FIELD with an amount rather than as a bare phrase, which is what separates AU's genuine
        // "Total Credit Limit: 1,00,000.00" from HSBC's repeated summary column header and an
        // overdraft's terms. The >= 2 threshold itself is unchanged.
        boolean creditCardSignals = section.rows().stream().anyMatch(row ->
                CsvParser.hasHeaderMatch(row, "card number", "minimum due", "minimum amount due"))
                || countDistinctCreditCardSignals(section.auxiliaryText()) >= MIN_CREDIT_CARD_TEXT_SIGNALS;
        // ctx is never null here either -- same call-graph trace as the other two guards removed
        // in this file (buildSections/buildLedgerSection).
        if (creditCardSignals) ctx.record("CREDIT_CARD_SUMMARY_SIGNAL");

        return new SharedSectionFacts(metadata, bank, suggestedName, creditCardSignals);
    }

    private record SharedSectionFacts(PdfMetadataExtractor.ExtractedMetadata metadata, BankRegistry.BankInfo bank,
                                      String suggestedName, boolean creditCardSignals) {

        DetectedAccountInfo toDetectedAccountInfo(ProductDiscovery.DiscoveredProduct product,
                                                  String suggestedAccountType, BigDecimal openingBalance,
                                                  BigDecimal closingBalance, LocalDate statementStart,
                                                  LocalDate statementEnd, ProductAttributes attrs,
                                                  BigDecimal totalAmountDue, LocalDate paymentDueDate,
                                                  BigDecimal creditLimit, String gridAccountNumberMasked) {
            // Same precedence as paymentDueDate: PdfMetadataExtractor's own line-based field wins
            // when present, and the positioned-text grid reading (AccountNumberGridExtractor /
            // AccountNumberTransactionHeaderExtractor) is only tried once that comes up empty -- see
            // those classes' own doc comments for why a real Axis/ICICI document's own account
            // number can never be read the line-based way.
            //
            // NOT gated on this section's own detectedProduct -- CARD_NUMBER_LABEL_SRC matches
            // "Account Number" as well as "Card No"/"Credit Card Number" (see PdfMetadataExtractor),
            // so both grid extractors are real, evidenced signals on SAVINGS statements too (a real
            // HSBC savings document only recovers its account number via AccountNumberGridExtractor's
            // PRINTED_ACCOUNT_NUMBER_GRID path) -- gating on "CREDIT_CARD" specifically silently
            // broke that document, and ICICI CC.pdf's own detectedProduct is UNKNOWN (low-confidence
            // classification) even though its section genuinely needs this exact fallback. Document-
            // wide, ungated application carries the same theoretical cross-section leak risk
            // gridPaymentDueDate already accepts (see that field's own precedent from PR #708); no
            // real corpus document currently exercises a composite statement with two genuinely
            // different, positively-identified accounts where this would misfire.
            String accountNumberMasked = metadata.accountNumberMasked() != null
                    ? metadata.accountNumberMasked() : gridAccountNumberMasked;
            return new DetectedAccountInfo(
                    suggestedName, suggestedAccountType,
                    openingBalance, closingBalance, statementStart, statementEnd,
                    accountNumberMasked, creditLimit, totalAmountDue,
                    paymentDueDate,
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
                            metadata.accountNumberFullForHashingOnly(), accountNumberMasked,
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

    /** At least this many DISTINCT signals -- phrases from {@link #CREDIT_CARD_TEXT_SIGNALS}, plus
     *  the labelled credit-limit field, which counts as one more -- must appear somewhere in the
     *  section's free text before it counts as a credit-card signal. See the bug-fix comment at
     *  this constant's call site for the real statements that motivated it. */
    private static final int MIN_CREDIT_CARD_TEXT_SIGNALS = 2;

    /**
     * Payment-summary labels, matched as free-text substrings.
     *
     * <p>Every entry is wording taken from a real statement rather than a guess, and the list
     * covers all three spellings the observed issuers use for the same two concepts, because no
     * two of them agree:
     *
     * <ul>
     *   <li>Axis prints "Total Payment Due" and "Minimum Payment Due".</li>
     *   <li>HDFC prints "Total Amount Due" and "Minimum Due".</li>
     *   <li>AU prints "Total amount due" and "Minimum amount due".</li>
     * </ul>
     *
     * <p>Substring matching does NOT collapse these into each other, which is the trap this list
     * was previously falling into: "minimum due" is not a substring of "minimum amount due" or of
     * "minimum payment due", and "total amount due" is not a substring of "total payment due". A
     * list holding only some of the spellings therefore misses whole issuers outright -- an AU or
     * Axis card statement reached at most ONE listed phrase and was classified SAVINGS. Each
     * spelling has to be listed explicitly; none of them is redundant.
     *
     * <p>Note that no single label can inflate the count on its own: a line reading "Minimum
     * Payment Due" matches exactly one entry, not two, precisely because of the same
     * no-substring-overlap property.
     */
    private static final List<String> CREDIT_CARD_TEXT_SIGNALS = List.of(
            "total payment due", "total amount due",
            "minimum amount due", "minimum payment due", "minimum due");

    /**
     * The credit limit, required as a LABELLED FIELD -- "Credit Limit: 1,00,000.00", optionally
     * prefixed with "Total"/"Available" -- rather than as a bare phrase anywhere in the text.
     *
     * <p>A bare "credit limit" substring was the weakest entry on the free-text list and the one
     * doing the most damage at both ends. On HSBC's combined statement it is a SUMMARY TABLE's
     * shared column header, printed once per account category with "Not Applicable" underneath it,
     * and says nothing about the section it appears above; on a current account it turns up in an
     * overdraft facility's terms ("your sanctioned credit limit is 2,00,000.00") describing a
     * different product entirely. Meanwhile on AU's genuine card statement the real thing is
     * printed as an actual labelled field with an actual amount ("Total Credit Limit:
     * Rs.1,00,000.00") -- so requiring the FIELD SHAPE keeps every true positive observed while
     * discarding both false-positive shapes, which no amount of tuning the threshold could
     * separate.
     *
     * <p>This is the same idea as {@link CsvParser#hasHeaderMatch}'s exact-column-name check on the
     * row path: structure, not the presence of a word. The currency symbol is optional because the
     * text layer may or may not carry one (and a non-Latin glyph may not survive extraction), and
     * the separator is optional because statements print both "Credit Limit: 1,00,000" and
     * "Credit Limit 1,00,000". What is NOT optional is adjacency of "credit" and "limit" and an
     * amount immediately after the label -- that is what makes it a field rather than a mention.
     */
    private static final Pattern CREDIT_LIMIT_LABELLED_FIELD = Pattern.compile(
            "(?:total\\s+|available\\s+)?credit\\s+limit\\s*[:\\-]?\\s*(?:₹|rs\\.?|inr)?\\s*[\\d,]*\\d(?:\\.\\d{1,2})?",
            Pattern.CASE_INSENSITIVE);

    /**
     * How many distinct credit-card signals this section's free text carries.
     *
     * <p>The labelled credit-limit field is folded in here as ONE more distinct signal rather than
     * being OR'd in as an independent shortcut, deliberately: it is corroborating evidence of the
     * same kind as a payment-summary label, not a stronger class of evidence, and the whole point
     * of {@link #MIN_CREDIT_CARD_TEXT_SIGNALS} is that no single free-text hit may classify a
     * document on its own. An OR would have handed exactly that power back to one hit.
     *
     * <p>Package-private so a test can assert the MARGIN a real document clears the threshold by,
     * not merely that it clears it -- a true positive scraping through on exactly two signals and
     * one comfortably over are very different states of this rule.
     */
    long countDistinctCreditCardSignals(List<String> auxiliaryText) {
        long phraseSignals = CREDIT_CARD_TEXT_SIGNALS.stream()
                .filter(signal -> auxiliaryText.stream().anyMatch(line -> line != null
                        && line.toLowerCase(Locale.ROOT).contains(signal)))
                .count();
        boolean creditLimitField = auxiliaryText.stream()
                .anyMatch(line -> line != null && CREDIT_LIMIT_LABELLED_FIELD.matcher(line).find());
        return phraseSignals + (creditLimitField ? 1 : 0);
    }
}
