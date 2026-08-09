package com.finora.imports;

import com.finora.imports.analysis.ParseDiagnostics;
import com.finora.imports.analysis.StatementAnalysisSession;
import com.finora.imports.analysis.StatementAnalysisRecorder;
import com.finora.accounts.AccountBalanceConvention;
import com.finora.accounts.AccountDto;
import com.finora.dto.ImportDto.*;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.accounts.AccountService;
import com.finora.imports.product.FinancialProductType;
import com.finora.imports.product.ProductIdentity;
import com.finora.imports.product.ProductIdentityResolver;
import com.finora.security.OwnershipGuard;
import com.finora.service.CategorizationService;
import com.finora.service.RecurringService;
import com.finora.service.ReconciliationService;
import com.finora.util.CategoryRules;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Statement Import Framework (PRD §17): accepts CSV exports from any bank/card portal,
 * auto-detects common column name variants, validates rows, flags likely duplicates,
 * best-effort-detects account-level fields (opening/closing balance, statement period, credit
 * limit, due date), and returns a staged preview — nothing is committed to the ledger until the
 * user confirms via ImportController's /import/confirm endpoint.
 *
 * One import == one account (that's how real bank/card statements work), chosen or created at
 * confirm time — see ImportDto.ConfirmRequest. Auto-creating that account when it doesn't exist
 * yet is the whole point of this flow: a brand-new user with zero accounts should be able to
 * upload a statement and land on a populated Dashboard without ever visiting the Accounts page.
 *
 * PDF ingestion (Milestone 1, digital/text-based statements only -- see com.finora.imports.pdf's
 * package doc) reuses this same staging/session/confirm pipeline entirely unmodified;
 * {@link com.finora.imports.pdf.PdfPreviewGenerator} is the only bridge point, producing the
 * exact same StagingResponse this class's own CSV path does. OCR and scanned PDFs remain out of
 * scope -- see that package's own doc comment for why, and what's deliberately deferred.
 *
 * This class is the orchestrator for the import pipeline; the mechanics live in dedicated
 * collaborators (see the v56 modularization pass, Phase 2):
 *   {@link CsvParser}            — mechanical CSV/row parsing
 *   {@link TransactionNormalizer} — row -> transaction candidate
 *   {@link StatementValidator}   — row -> account/bank-level signals
 *   {@link DuplicateDetector}    — likely-duplicate checks
 *   {@link PreviewGenerator}     — assembles the staged preview (parseAndStage)
 *   {@link ImportRuleLearningService} — confirm-time category-learning decision
 * This replaces the former monolithic CsvImportService, kept here under a name matching what it
 * actually is now: an orchestrator, not a parser.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    /** One merchant-learning confirmation a confirmed row earned, held until the statement import
     *  row exists to attribute it to. Both ids are already resolved by the row loop, so queueing
     *  costs no extra lookups. */
    private record PendingLearning(UUID merchantId, UUID categoryId) {}

    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final TransactionRepository transactionRepository;
    private final MerchantRepository merchantRepository;
    private final StatementImportRepository statementImportRepository;
    private final CategorizationService categorizationService;
    private final ReconciliationService reconciliationService;
    private final RecurringService recurringService;
    private final PreviewGenerator previewGenerator;
    private final DuplicateDetector duplicateDetector;
    private final ImportRuleLearningService ruleLearningService;
    private final ImportSessionService importSessionService;
    private final com.finora.imports.pdf.PdfPreviewGenerator pdfPreviewGenerator;
    private final ProductIdentityResolver productIdentityResolver;
    private final StatementAnalysisRecorder analysisRecorder;
    /** Keeps the verification rules' findings, which until now reached the staging response and
     *  were then discarded -- see ImportVerificationRecorder and milestone-2 item 6. */
    private final com.finora.imports.analysis.ImportVerificationRecorder verificationRecorder;
    private final com.finora.imports.storage.StatementContentService statementContentService;
    private final com.finora.service.MerchantLearningEventPublisher learningEventPublisher;
    private final LayoutRegistryService layoutRegistryService;

    public ImportService(AccountRepository accountRepository, AccountService accountService,
                          TransactionRepository transactionRepository, MerchantRepository merchantRepository,
                          StatementImportRepository statementImportRepository,
                          CategorizationService categorizationService,
                          ReconciliationService reconciliationService,
                          RecurringService recurringService,
                          PreviewGenerator previewGenerator,
                          DuplicateDetector duplicateDetector,
                          ImportRuleLearningService ruleLearningService,
                          ImportSessionService importSessionService,
                          com.finora.imports.pdf.PdfPreviewGenerator pdfPreviewGenerator,
                          ProductIdentityResolver productIdentityResolver,
                          com.finora.imports.storage.StatementContentService statementContentService,
                          StatementAnalysisRecorder analysisRecorder,
                          com.finora.imports.analysis.ImportVerificationRecorder verificationRecorder,
                          com.finora.service.MerchantLearningEventPublisher learningEventPublisher,
                          LayoutRegistryService layoutRegistryService) {
        this.layoutRegistryService = layoutRegistryService;
        this.analysisRecorder = analysisRecorder;
        this.verificationRecorder = verificationRecorder;
        this.productIdentityResolver = productIdentityResolver;
        this.statementContentService = statementContentService;
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.transactionRepository = transactionRepository;
        this.merchantRepository = merchantRepository;
        this.statementImportRepository = statementImportRepository;
        this.categorizationService = categorizationService;
        this.reconciliationService = reconciliationService;
        this.recurringService = recurringService;
        this.previewGenerator = previewGenerator;
        this.duplicateDetector = duplicateDetector;
        this.ruleLearningService = ruleLearningService;
        this.importSessionService = importSessionService;
        this.pdfPreviewGenerator = pdfPreviewGenerator;
        this.learningEventPublisher = learningEventPublisher;
    }

    /**
     * The public upload entry point (ImportController) -- unlike the byte-stream overload below,
     * this one persists what gets staged (ADR-0002), so review survives a dropped session instead
     * of only living in this HTTP response and whatever the frontend holds in memory afterward.
     */
    public StagingSessionResponse parseAndStageWithSession(UUID userId, MultipartFile file) throws IOException {
        return parseAndStageWithSession(userId, StatementUpload.safeFileName(file, "statement.csv"), file.getBytes());
    }

    /**
     * Filename + raw bytes rather than MultipartFile, so the asynchronous worker can drive this from
     * a content address without fabricating a MultipartFile just to satisfy the type — the same
     * split, for the same reason, as {@link #confirm(UUID, String, byte[], ConfirmRequest)}.
     *
     * <p>The worker needs <b>this</b> method rather than {@code parseAndStageAnyFormat} because only
     * this one persists a session. A job that staged into a response nobody held completed with a
     * row count and nothing to review.
     */
    public StagingSessionResponse parseAndStageWithSession(UUID userId, String fileName, byte[] fileContent)
            throws IOException {
        long startedAtMs = System.currentTimeMillis();
        // Captured inside the try so the catch can still record it: a document that parsed far
        // enough to be characterised and THEN failed is the most useful failure there is, because
        // the fingerprint is what makes it findable again.
        String fingerprint = null;
        // Hoisted for the same reason as the fingerprint, and assigned before the rejection check
        // below: a document rejected for extracting nothing is exactly the one whose reason
        // histogram matters, because it is the only field on that row that says why nothing
        // anchored. Computing it inside the try and losing it in the catch would discard the
        // evidence in precisely the case the table exists to capture.
        ParseDiagnostics diagnostics = ParseDiagnostics.NONE;
        try {
            var result = previewGenerator.generateWithContext(userId, fileName, new java.io.ByteArrayInputStream(fileContent));
            fingerprint = fingerprintOf(result.documentContext());
            StagingResponse staged = result.response();
            diagnostics = ParseDiagnostics.of(staged.rows().size(), result.documentContext().unanchoredReasons());
            rejectIfNothingWasExtracted(staged, result.documentContext());
            var session = importSessionService.createSession(userId, fileName, fileContent, staged.rows(), staged.detectedAccount(),
                    result.documentContext());
            String reference = analysisRecorder.recordParsed(userId, StatementAnalysisSession.Source.CUSTOMER_IMPORT, fileName,
                    "CSV", fileContent.length, fingerprint, 1, System.currentTimeMillis() - startedAtMs,
                    diagnostics, session.getId());
            // The four verification rules have already run inside the preview generator; without
            // this line their findings live only in the response the user is about to see and are
            // then gone. One section, so index 0. See ImportVerificationRecorder for why the
            // details are rebuilt from an allowlist rather than persisted as they stand.
            // singletonList, not List.of: verification is nullable on StagingResponse ("not
            // checked", distinct from a report saying NOT_APPLICABLE) and List.of would throw on it.
            verificationRecorder.recordForAnalysis(reference,
                    java.util.Collections.singletonList(staged.verification()));
            return new StagingSessionResponse(session.getId(), staged);
        } catch (ApiException e) {
            analysisRecorder.recordFailed(userId, StatementAnalysisSession.Source.CUSTOMER_IMPORT, fileName,
                    "CSV", fileContent.length, fingerprint, e.getCode() == null ? null : e.getCode().name(),
                    e.getMessage(), System.currentTimeMillis() - startedAtMs, diagnostics);
            throw e;
        }
    }

    /** Null-safe: a document can fail before it has any context to fingerprint. */
    private String fingerprintOf(com.finora.imports.DocumentContext context) {
        try {
            return context == null ? null : context.buildFingerprint();
        } catch (RuntimeException e) {
            // Fingerprinting is evidence, not control flow. A malformed document that defeats the
            // fingerprinter must still produce a recorded failure, and must still fail for its own
            // reason rather than this one.
            return null;
        }
    }

    /**
     * PDF equivalent of parseAndStageWithSession() above -- same session-creation contract,
     * different extraction path (com.finora.imports.pdf.PdfPreviewGenerator instead of
     * PreviewGenerator/CsvParser). Everything from this point on (ImportSession, confirmSession(),
     * review, confirm) is identical regardless of which staging path produced it, EXCEPT when
     * PdfPreviewGenerator detects more than one account section in the same file (e.g. HSBC's
     * "Composite Statement", which bundles a savings-account section and a credit-card section in
     * one PDF) -- that case branches to a multi-account session and response shape instead, since
     * a single ConfirmRequest/DetectedAccountInfo genuinely can't represent N accounts at once.
     */
    public PdfStagingSessionResponse parseAndStagePdfWithSession(UUID userId, MultipartFile file, String password) throws IOException {
        return parseAndStagePdfWithSession(
                userId, StatementUpload.safeFileName(file, "statement.pdf"), file.getBytes(), password);
    }

    /** Filename + raw bytes, for the asynchronous worker — see the CSV counterpart above. */
    public PdfStagingSessionResponse parseAndStagePdfWithSession(UUID userId, String fileName, byte[] fileContent,
                                                                  String password) throws IOException {
        long startedAtMs = System.currentTimeMillis();
        String fingerprint = null;
        ParseDiagnostics diagnostics = ParseDiagnostics.NONE;
        try {
            // Parsing happens BEFORE createSession, which is what makes the password retry clean: a
            // wrong or missing password throws here, so no ImportSession row exists to orphan and the
            // client simply calls this endpoint again with the same file.
            var result = pdfPreviewGenerator.generateSectionsWithContext(userId, fileName, fileContent, password);
            fingerprint = fingerprintOf(result.documentContext());
            List<StagedAccountSection> sections = onlySectionsThatAreActuallyAccounts(result.sections());
            // Summed across sections rather than per section: the histogram on the DocumentContext
            // is already whole-document, so a per-section row count would be the only figure on
            // this row with a narrower scope than the rest of it.
            diagnostics = ParseDiagnostics.of(
                    sections.stream().mapToInt(section -> section.rows().size()).sum(),
                    result.documentContext().unanchoredReasons());

            if (sections.size() <= 1) {
                // The common case (and the only case a CSV upload can ever produce): behaves exactly
                // as this method always has, just wrapped in the new response envelope.
                StagingResponse staged = sections.isEmpty()
                        ? new StagingResponse(List.of(), 0, 0, null, List.of())
                        : toStagingResponse(sections.get(0));
                rejectIfNothingWasExtracted(staged, result.documentContext());
                var session = importSessionService.createSession(userId, fileName, fileContent, staged.rows(), staged.detectedAccount(),
                        result.documentContext());
                recordPdfParsed(userId, fileName, fileContent.length, fingerprint, sections.size(), startedAtMs,
                        diagnostics, session.getId(),
                        java.util.Collections.singletonList(staged.verification()));
                return new PdfStagingSessionResponse(session.getId(), false, staged, null);
            }

            var session = importSessionService.createMultiSection(userId, fileName, fileContent, sections, result.documentContext());
            // Per section, in section order, because a composite statement's sections have separate
            // balance chains and one can verify while another does not -- collapsing them into one
            // report would lose exactly the distinction the verification framework computes.
            recordPdfParsed(userId, fileName, fileContent.length, fingerprint, sections.size(), startedAtMs,
                    diagnostics, session.getId(),
                    sections.stream().map(StagedAccountSection::verification).toList());
            return new PdfStagingSessionResponse(session.getId(), true, null, sections);
        } catch (ApiException e) {
            // The whole point of the evidence table. A password failure carries no fingerprint --
            // the document was never opened -- but IMPORT_001 and IMPORT_007 do, and those are the
            // ones that say "this layout defeated the parser", which is unanswerable today.
            analysisRecorder.recordFailed(userId, StatementAnalysisSession.Source.CUSTOMER_IMPORT, fileName,
                    "PDF", fileContent.length, fingerprint, e.getCode() == null ? null : e.getCode().name(),
                    e.getMessage(), System.currentTimeMillis() - startedAtMs, diagnostics);
            throw e;
        }
    }

    private void recordPdfParsed(UUID userId, String fileName, long byteSize, String fingerprint,
                                  int sectionCount, long startedAtMs, ParseDiagnostics diagnostics,
                                  UUID importSessionId,
                                  List<VerificationReport> verificationBySection) {
        String reference = analysisRecorder.recordParsed(userId, StatementAnalysisSession.Source.CUSTOMER_IMPORT, fileName,
                "PDF", byteSize, fingerprint, sectionCount, System.currentTimeMillis() - startedAtMs,
                diagnostics, importSessionId);
        verificationRecorder.recordForAnalysis(reference, verificationBySection);
    }

    /**
     * Stops offering a located table as a transaction ACCOUNT when it plainly isn't one, without
     * throwing its contents away.
     *
     * INTERIM. A real HDFC combined statement carries a term-deposit summary and a recurring-deposit
     * installment schedule alongside the savings account. Those are genuine financial products the
     * customer holds, and the correct end state is that they become Investments -- see the planned
     * product-classification stage, which will identify what each section IS (savings, current, FD,
     * RD, loan, overdraft, credit card, demat) and route it to the matching Finora domain before
     * anything is imported. Until that exists this method must not pretend to do it.
     *
     * What it fixes today is narrower and purely a defect: all three sections were presented as
     * ACCOUNTS, so the user was offered two empty ones to confirm. That is the same failure as the
     * repeated-account-banner bug by a different route -- asserting something is an account on
     * evidence that only shows it is a table.
     *
     * So a section with no transactions stops being offered as an account, and its rows move onto
     * the first surviving section as unparseable so the deposit details still surface for review
     * ("never lose information"). Nothing is discarded, and nothing here encodes a guess about what
     * those sections are -- that judgement belongs to product classification, not to a filter.
     * When every section is empty, the caller's zero-transaction guard reports the failure instead.
     */
    private List<StagedAccountSection> onlySectionsThatAreActuallyAccounts(List<StagedAccountSection> sections) {
        if (sections.size() <= 1) return sections;

        List<StagedAccountSection> accounts = sections.stream().filter(s -> !s.rows().isEmpty()).toList();
        if (accounts.isEmpty() || accounts.size() == sections.size()) return accounts.isEmpty() ? sections : accounts;

        List<UnparseableRow> carriedOver = new ArrayList<>(accounts.get(0).unparseableRows());
        for (StagedAccountSection dropped : sections) {
            if (dropped.rows().isEmpty()) carriedOver.addAll(dropped.unparseableRows());
        }
        StagedAccountSection first = accounts.get(0);
        List<StagedAccountSection> merged = new ArrayList<>(accounts);
        merged.set(0, new StagedAccountSection(first.detectedAccount(), first.rows(), first.totalParsed(),
                first.flaggedDuplicates(), carriedOver));
        return merged;
    }

    /**
     * An extraction that produced no transactions is a failed extraction, not an empty statement,
     * and must not be handed back as a staged session.
     *
     * This was reported against a real HDFC statement holding 100+ transactions: the upload
     * returned 200, the review screen rendered an empty table, and Confirm was live -- so the
     * pipeline's total failure to read the document was indistinguishable, to the person using it,
     * from a quiet month. Confirming it would have created a real account with no transactions in
     * it. Refusing here means every path into an import session is guaranteed to carry at least one
     * row, so "staged successfully" keeps its meaning.
     *
     * The two outcomes are reported as different error codes because they need different responses:
     * no table located at all is a layout the engine can't yet read, while a located table whose
     * every row was rejected is a parsing problem inside a layout it did recognize. Both attach the
     * text that WAS recovered ("never lose information" -- see the engineering principles doc), so
     * whoever picks up the report can see what the document actually contained without needing the
     * file itself.
     */
    /** Delegates to {@link ExtractionCheck}, which admin analysis shares so both paths reach the
     *  same verdict on the same document. */
    private void rejectIfNothingWasExtracted(StagingResponse staged, DocumentContext ctx) {
        ExtractionCheck.rejectIfNothingWasExtracted(staged, ctx);
    }

    private StagingResponse toStagingResponse(StagedAccountSection section) {
        return new StagingResponse(section.rows(), section.totalParsed(), section.flaggedDuplicates(), section.detectedAccount(), section.unparseableRows());
    }

    /**
     * Filename + raw stream rather than MultipartFile so this can be driven by something other
     * than a live HTTP upload — specifically StatementImportService.reimport(), which replays a
     * file's stored bytes (a ByteArrayInputStream) rather than a fresh multipart request. No
     * session concept here (see parseAndStageWithSession above for that) -- reimport is replaying
     * an already-durably-stored file, not a fresh upload with anything at risk of being lost.
     */
    public StagingResponse parseAndStage(UUID userId, String filename, java.io.InputStream contentStream) throws IOException {
        return previewGenerator.generate(userId, filename, contentStream);
    }

    /**
     * Bug fix: StatementImportService.reimport() replays a previously-uploaded file's stored
     * bytes -- before PDF support existed, that was always a CSV, so it always called the
     * byte-stream parseAndStage() overload above directly. Once statements could also originate
     * as PDFs (Milestone 1), that became a real regression: a PDF-sourced statement's raw bytes
     * would get fed through CsvParser's CSV reader, which would either throw or silently produce
     * garbage rows from what is actually binary PDF content.
     *
     * Routes by the explicit sourceFormat now recorded on the StatementImport row at confirm()
     * time (StatementImport.sourceFormat, V36 migration) rather than re-inferring from the
     * filename's extension -- the first version of this fix used the filename, which works but
     * is a real, if narrow, fragility (nothing stops a re-upload with a missing or mismatched
     * extension). Used by reimport() specifically; the two *upload* entry points
     * (parseAndStageWithSession / parseAndStagePdfWithSession) already know their own format
     * directly and don't need this routing at all.
     *
     * PASSWORD-PROTECTED PDFs: this path re-parses bytes stored at import time, and those bytes
     * are still encrypted -- the password used at upload is deliberately never persisted (see
     * PdfPreviewGenerator's password parameter), so it has to be supplied again by whoever asked
     * for the re-import. Passing none yields IMPORT_PDF_PASSWORD_REQUIRED, which is the signal
     * StatementHistory uses to prompt; the retry then arrives here with the password set.
     *
     * Only STAGING re-parses. ImportService.confirm() builds transactions from the rows in the
     * request and never touches the file bytes, so the password does not have to survive from the
     * re-import prompt to the confirm step -- it stops being needed the moment staging returns.
     *
     * sourceSectionIndex (V37) is the section-aware half of this same routing: a StatementImport
     * that came from section N of a multi-account PDF (e.g. HSBC's composite statement) must be
     * re-parsed against that SAME section, not section 0 -- otherwise reimport() would silently
     * replay a different account's transactions against this one. Null for every CSV import and
     * every single-account PDF import, which re-parse exactly as before.
     */
    public StagingResponse parseAndStageAnyFormat(UUID userId, String sourceFormat, String filename, byte[] content,
                                                   Integer sourceSectionIndex) throws IOException {
        return parseAndStageAnyFormat(userId, sourceFormat, filename, content, sourceSectionIndex, null);
    }

    /** @param password the document open password for a protected PDF, or null. Ignored for CSV. */
    public StagingResponse parseAndStageAnyFormat(UUID userId, String sourceFormat, String filename, byte[] content,
                                                   Integer sourceSectionIndex, String password) throws IOException {
        if ("PDF".equalsIgnoreCase(sourceFormat)) {
            if (sourceSectionIndex != null) {
                List<StagedAccountSection> sections = pdfPreviewGenerator.generateSections(userId, filename, content, password);
                if (sourceSectionIndex >= sections.size()) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "This statement's account sections no longer match what was originally imported -- re-upload the file to import it fresh.");
                }
                return toStagingResponse(sections.get(sourceSectionIndex));
            }
            return pdfPreviewGenerator.generate(userId, filename, content, password);
        }
        return parseAndStage(userId, filename, new java.io.ByteArrayInputStream(content));
    }

    /**
     * <p><b>{@code @Transactional} is not decorative here, and its absence was a real gap.</b> The
     * overloads this delegates to are all annotated -- and they are reached by SELF-invocation, so
     * Spring's proxy never sees those calls and their annotations did nothing. This entry point had
     * none of its own, which meant the whole confirm ran with no transaction at all: every
     * repository call committed independently, so a failure part way through left the statement
     * import row, some of its transactions, and a moved account balance all separately committed
     * with no way to unwind them.
     *
     * <p>Not a production path today -- the controller uses {@link #confirmSession} and
     * {@code StatementImportService.confirmReimport} crosses a bean boundary, both of which are
     * proxied and transactional. It is reached by tests, which is exactly how it stayed unnoticed,
     * and it is one call away from becoming a production path.
     */
    @Transactional
    public ConfirmResponse confirm(UUID userId, MultipartFile file, ConfirmRequest request) throws IOException {
        String fileName = StatementUpload.safeFileName(file, "statement.csv");
        return confirm(userId, fileName, file.getBytes(), request);
    }

    /**
     * Confirms every account section of a multi-account PDF staging session together (see
     * PdfPreviewGenerator.generateSections / ImportSessionService.createMultiSection) -- e.g.
     * HSBC's composite statement, once staged, surfaces a savings-account section and a
     * credit-card section for the user to review side by side, and this confirms both in one
     * request rather than requiring two separate uploads of the same file.
     *
     * Deliberately loops calling the existing, unmodified per-account confirm() overload once per
     * section rather than duplicating its transaction-import/categorization/reconciliation logic
     * here -- each section becomes its own Account (existing or new) and its own StatementImport
     * row, each storing this same multi-account PDF's full bytes (so each stays independently
     * re-importable later) -- an accepted, if N-fold-redundant, storage cost for a v1 of this.
     */
    @Transactional
    public MultiAccountConfirmResponse confirmMultiSection(UUID userId, MultiAccountConfirmRequest request) {
        if (request.sessionId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sessionId is required.");
        }
        var session = importSessionService.claimForConfirmation(userId, request.sessionId());
        var stagedSections = importSessionService.readSections(session);
        if (stagedSections.size() != request.sections().size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "The reviewed sections don't match what was staged for this import session -- try staging again.");
        }

        List<ConfirmResponse> responses = new ArrayList<>();
        for (int i = 0; i < request.sections().size(); i++) {
            SectionConfirm sectionConfirm = request.sections().get(i);
            StagedAccountSection stagedSection = stagedSections.get(i);
            if (stagedSection.rows().size() != sectionConfirm.rows().size()) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "The reviewed rows for account " + (i + 1) + " don't match what was staged for this import session -- try staging again.");
            }
            ConfirmRequest perAccountRequest = new ConfirmRequest(
                    null, // this section's ConfirmRequest doesn't carry its own sessionId -- the session is claimed once, above, for the whole multi-account request
                    sectionConfirm.rows(), sectionConfirm.existingAccountId(), sectionConfirm.newAccount(),
                    sectionConfirm.statementOpeningBalance(), sectionConfirm.statementClosingBalance());
            responses.add(confirm(userId, session.getFileName(), statementContentService.read(session), perAccountRequest, i,
                    session.getLayoutMetadataJson(), session.getLayoutFingerprint(), session.getActivatedCapabilitiesJson(),
                session.getUnparseableSummaryJson()));
        }
        return new MultiAccountConfirmResponse(responses);
    }

    /**
     * The public confirm entry point (ImportController) as of ADR-0002 -- resolves the file from
     * the persisted session instead of requiring the original file to be re-uploaded a second
     * time, and validates the confirmed row count actually matches what was staged for this
     * session rather than trusting an arbitrary client-supplied list outright (the "confirm()
     * currently trusts the client" gap ADR-0002 called out). The MultipartFile overload above
     * still exists and is still exercised directly by ImportServiceAskOnceTest -- kept as a
     * lower-level, still-supported entry point since the confirm business logic it tests lives in
     * the byte-array confirm() both paths ultimately share, not duplicated here.
     */
    @Transactional
    public ConfirmResponse confirmSession(UUID userId, ConfirmRequest request) {
        if (request.sessionId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sessionId is required.");
        }
        // Claimed atomically as the very first thing this method does -- see
        // ImportSessionService.claimForConfirmation's own doc comment. A double-click or a
        // retried request racing a first, still-in-flight confirm for the same session gets
        // rejected right here, before any row validation or transaction-import work happens, not
        // after it's already inserted a duplicate batch of transactions.
        var session = importSessionService.claimForConfirmation(userId, request.sessionId());

        var stagedRows = importSessionService.readStagedRows(session);
        if (stagedRows.size() != request.rows().size()) {
            // Not a full per-row diff (a user re-ordering or the frontend re-serializing rows in
            // a different order is fine) -- just the cheapest real check that the confirmed list
            // is plausibly "the same staged rows, reviewed," not something else entirely
            // masquerading as a confirm for this session.
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "The reviewed rows don't match what was staged for this import session -- try staging again.");
        }

        return confirm(userId, session.getFileName(), statementContentService.read(session), request, null,
                session.getLayoutMetadataJson(), session.getLayoutFingerprint(), session.getActivatedCapabilitiesJson(),
                session.getUnparseableSummaryJson());
    }

    /**
     * Filename + raw bytes rather than MultipartFile so StatementImportService.confirmReimport()
     * can drive this from a stored StatementImport's file_content column without needing a fake
     * MultipartFile implementation just to satisfy the type — same reasoning as the
     * parseAndStage(userId, filename, InputStream) split above. No ImportSession is available on
     * this path (confirmReimport() replays already-stored bytes, not a fresh staged session), so
     * the layout metadata/fingerprint/capabilities trio is left null here -- same "best-effort,
     * never recomputed after the fact" discipline as every other nullable field on this pipeline.
     */
    @Transactional
    public ConfirmResponse confirm(UUID userId, String fileName, byte[] fileContent, ConfirmRequest request) {
        return confirm(userId, fileName, fileContent, request, null, null, null, null, null);
    }

    /**
     * sourceSectionIndex-aware variant used by {@link #confirmMultiSection} -- every other caller
     * (the byte-array overload above, confirmSession()) goes through with {@code null}, which
     * this method treats identically to how confirm() has always behaved: no
     * StatementImport.sourceSectionIndex recorded, since there's only ever one section to record
     * an index into.
     */
    @Transactional
    public ConfirmResponse confirm(UUID userId, String fileName, byte[] fileContent, ConfirmRequest request, Integer sourceSectionIndex) {
        return confirm(userId, fileName, fileContent, request, sourceSectionIndex, null, null, null, null);
    }

    /**
     * Phase 1 "capture facts" (docs/engineering/financial-document-intelligence-principles.md):
     * layoutMetadataJson/layoutFingerprint/activatedCapabilitiesJson are copied verbatim from the
     * ImportSession this confirm came from (confirmSession()/confirmMultiSection() both read
     * theirs before calling down to this method) -- never recomputed here, since this method has
     * no access to the original StagedRow/DetectedAccountInfo/DocumentContext, only the reviewed
     * ConfirmedRow list. All three nullable -- a caller with no session (see the byte-array
     * overload above) simply leaves them unset.
     */
    @Transactional
    public ConfirmResponse confirm(UUID userId, String fileName, byte[] fileContent, ConfirmRequest request, Integer sourceSectionIndex,
                                    String layoutMetadataJson, String layoutFingerprint, String activatedCapabilitiesJson,
                                    String unparseableSummaryJson) {
        long startedAtMs = System.currentTimeMillis();
        List<String> accountsCreated = new ArrayList<>();
        // What was created, by PRODUCT rather than by account. The summary says "1 Savings, 1 Fixed
        // Deposit" instead of "3 accounts" -- which was both less informative and, for a combined
        // statement, wrong: two of those three were never accounts.
        Map<String, Integer> productsCreated = new LinkedHashMap<>();

        UUID accountId = resolveTargetAccount(userId, request, accountsCreated, productsCreated);

        long merchantsBefore = merchantRepository.countByUserId(userId);

        int skipped = 0;
        Map<String, Integer> categoryTally = new LinkedHashMap<>();
        List<Transaction> toInsert = new ArrayList<>();
        // Merchant-learning confirmations this import earned, queued after savedImport below so
        // each one can be attributed to the statement it came from.
        List<PendingLearning> pendingLearning = new ArrayList<>();
        LocalDate minDate = null;
        LocalDate maxDate = null;
        // Summed only over rows actually imported (skipped/unchecked rows never reach this loop's
        // "include" branch), so these match the transactions that actually landed in the ledger.
        java.math.BigDecimal totalCredits = java.math.BigDecimal.ZERO;
        java.math.BigDecimal totalDebits = java.math.BigDecimal.ZERO;
        for (ConfirmedRow row : request.rows()) {
            if (!row.include()) { skipped++; continue; }
            if ("INCOME".equals(row.type())) {
                totalCredits = totalCredits.add(row.amount());
            } else {
                totalDebits = totalDebits.add(row.amount());
            }

            Category category = categorizationService.resolveOrCreateCategory(userId, row.category());
            var decision = ruleLearningService.recordDecision(userId, row, category);
            boolean isUnresolvedGuess = decision.unresolvedGuess();

            Transaction t = new Transaction();
            t.setUserId(userId);
            t.setAccountId(accountId);
            t.setCategoryId(category.getId());
            UUID merchantId = categorizationService.resolveMerchantId(userId, row.description());
            t.setMerchantId(merchantId);
            // Collected, not applied. Applying merchant learning here is what Bug 02 was: one
            // confirmation per row, inside this transaction, where a single lost race against
            // UNIQUE(user_id, merchant_id, category_id) rolled back every transaction in a
            // statement the user had already reviewed and approved. These are queued below, once
            // the statement import row exists to attribute them to, and applied by a worker after
            // this transaction commits. merchantId is reused rather than re-resolved -- the row
            // above already paid for it.
            if (decision.worthLearning() && merchantId != null) {
                pendingLearning.add(new PendingLearning(merchantId, category.getId()));
            }
            t.setTxnDate(row.date());
            t.setDescription(row.description());
            t.setMerchant(CategoryRules.extractMerchant(row.description()));
            t.setAmount(row.amount());
            t.setTxnType(com.finora.util.EnumParsing.parse(Transaction.Type.class, row.type(), "type"));
            t.setSource(Transaction.Source.CSV_IMPORT);
            t.setReferenceNumber(row.referenceNumber());
            t.setBalanceAfter(row.balanceAfter());
            t.setNeedsCategoryReview(isUnresolvedGuess);
            // The user's answer on the duplicate review screen, recorded on the row itself so
            // reconciliation cannot later overrule it. Only meaningful when the engine actually
            // flagged the row -- a client asserting "not a duplicate" about a row nothing
            // questioned would be claiming a decision the user was never asked to make.
            if (row.likelyDuplicate() && row.confirmedNotDuplicate()) {
                t.setNotDuplicateConfirmedAt(java.time.Instant.now());
            }
            // See CategorizationService.decisionSourceFor -- categorySource/ruleId are carried
            // through from staging (StagedRow -> ConfirmedRow) unchanged by review, same as
            // category itself; a user changing the category during review doesn't currently
            // relabel categorySource as a manual override (pre-existing limitation of the
            // staging/review contract, not introduced by this change).
            t.setDecisionSource(CategorizationService.decisionSourceFor(row.categorySource()));
            t.setDecisionRuleId(row.ruleId());
            // MARK_TRANSFER/MARK_INVESTMENT/ADD_TAG rules -- see
            // CategorizationService.applySideEffectRules's doc comment. A MARK_INVESTMENT match
            // returns the new Category -- reassigning `category` keeps the tally below (and any
            // other use of `category` in this iteration) in sync with what actually got persisted.
            Category sideEffectCategory = categorizationService.applySideEffectRules(userId, t);
            if (sideEffectCategory != null) {
                category = sideEffectCategory;
                // Bug fix: t.setCategoryId(category.getId()) above (before this override was
                // known) set the transaction's persisted category to the PRE-override one --
                // reassigning the local `category` variable alone only fixed the tally below
                // (keyed on category.getName(), a separate field), not the entity that actually
                // gets saved. A MARK_INVESTMENT/etc. match would show correctly in the "categories
                // assigned" summary while the real ledger row silently kept its original, wrong
                // category — exactly the kind of mismatch a user would only notice by comparing
                // the import summary against the transaction it just created.
                t.setCategoryId(category.getId());
            }
            toInsert.add(t);

            categoryTally.merge(category.getName(), 1, Integer::sum);
            if (minDate == null || row.date().isBefore(minDate)) minDate = row.date();
            if (maxDate == null || row.date().isAfter(maxDate)) maxDate = row.date();
        }

        // Persisted BEFORE the transactions so each one can reference its id — this record, not
        // a flat list of uploaded files, is what backs the account-organized Statement History
        // (view summary/transactions, re-import, delete — see StatementImportService).
        StatementImport statementImport = new StatementImport();
        statementImport.setUserId(userId);
        statementImport.setAccountId(accountId);
        statementImport.setFileName(fileName);
        // Recorded once, here, at the one authoritative moment this row is created -- not
        // re-derived from the filename later at reimport time (see
        // StatementImportService.reimport()'s own comment for why that was a real fragility).
        statementImport.setSourceFormat(fileName != null && fileName.toLowerCase().endsWith(".pdf") ? "PDF" : "CSV");
        statementImport.setSourceSectionIndex(sourceSectionIndex);
        statementImport.setLayoutMetadataJson(layoutMetadataJson);
        statementImport.setLayoutFingerprint(layoutFingerprint);
        statementImport.setActivatedCapabilitiesJson(activatedCapabilitiesJson);
        statementImport.setUnparseableSummaryJson(unparseableSummaryJson);
        // Object storage first, then the row -- the ordering §5.1 of the migration doc requires. A
        // failure throws before anything is persisted, so a row can never point at an object that
        // was never written. fileContent is still set: Phase 2 is a dual write, not a move.
        //
        // Content-addressing is what makes this cheap for the two callers that duplicate bytes
        // today -- confirmMultiSection() calls this once per account section with the same file,
        // and confirmReimport() calls it again with an already-stored one. Both now resolve to the
        // same object instead of writing another copy.
        statementContentService.store(fileContent).ifPresent(address -> {
            statementImport.setContentHash(address.hash());
            statementImport.setObjectKey(address.key());
        });
        statementImport.setFileContent(fileContent);
        statementImport.setStatementPeriodStart(minDate);
        statementImport.setStatementPeriodEnd(maxDate);
        statementImport.setOpeningBalance(request.statementOpeningBalance());
        statementImport.setClosingBalance(request.statementClosingBalance());
        statementImport.setTransactionsImported(toInsert.size());
        statementImport.setTransactionsSkipped(skipped);
        // Measured here rather than after the save so it covers the same work the response reports
        // (below), not the save itself. Slightly under-counts the true end-to-end time by exactly
        // one insert -- consistent across every row, which is what matters for comparing layouts.
        statementImport.setImportDurationMs(System.currentTimeMillis() - startedAtMs);
        StatementImport savedImport = statementImportRepository.save(statementImport);

        // Milestone 2 item 2: the layout gets a row of its own, not just a string on this one.
        // Placed here because this is the single authoritative moment a confirmed import records
        // its fingerprint -- registering anywhere earlier would count staging runs the user
        // abandoned. The write itself happens after this transaction commits and can never fail
        // it; see LayoutRegistryService.
        layoutRegistryService.observe(
                savedImport.getLayoutFingerprint(), savedImport.getSourceFormat(), layoutMetadataJson);

        // Ordinal assigned here, from position in the list being inserted. Without it the V67
        // unique index on (statement_import_id, row_ordinal) covers nothing -- a NULL ordinal is
        // excluded by the index predicate, so every row would opt itself out of the guarantee it
        // exists to provide.
        for (int i = 0; i < toInsert.size(); i++) {
            Transaction row = toInsert.get(i);
            row.setStatementImportId(savedImport.getId());
            row.setRowOrdinal(i);
        }

        // WI1. The event rows go in HERE, inside this transaction, so an import that rolls back
        // takes its queued learning with it -- otherwise a worker would later apply confirmations
        // for transactions that do not exist, which is a worse failure than the one this replaces.
        // Only the APPLYING is deferred: MerchantLearningEventPublisher registers an afterCommit
        // hook, and the worker runs once this transaction is durable. A learning failure then
        // cannot touch these transactions, because by the time it can happen they are committed.
        // request.sessionId() is null on the direct-file confirm path and populated on the
        // session path -- passed through as-is, never substituted, so the admin queue can tell
        // "this import had no session" from "the session is unknown".
        pendingLearning.forEach(pending -> learningEventPublisher.enqueue(
                userId, pending.merchantId(), pending.categoryId(),
                savedImport.getId(), request.sessionId()));

        List<Transaction> saved = transactionRepository.saveAll(toInsert);
        int imported = saved.size();

        // Bug fix: Account.balance used to be set once at account-creation time (from the
        // detected/typed opening balance) and never touched again — meaning a brand-new account's
        // balance stayed frozen at its opening balance forever, ignoring every transaction just
        // imported, and re-importing a later statement into an EXISTING account never updated its
        // balance either. The statement's own closing balance is the authoritative "true balance
        // as of the end of this statement" figure, so this sets the account to it directly — but
        // only when this statement is the most recent one on file for the account (by period
        // end), so importing an old/forgotten statement after a newer one is already on the books
        // can't regress the balance backwards.
        //
        // Bug 02: the closing balance is a field off the REQUEST BODY, and it used to be assigned
        // to Account.balance with no check that it had anything to do with the transactions being
        // imported alongside it. Net worth, the dashboard tiles, the health score and the
        // low-balance threshold all read that column, and nothing ever recomputes it, so a value
        // that disagreed with the ledger stayed wrong permanently and silently.
        // ClosingBalanceGuard applies StatementTotalsValidator's own arithmetic to the rows
        // actually being written; an uncorroborated claim is refused and surfaced as a warning
        // rather than applied. See that class for why refusing (rather than deriving a better
        // balance) is the correct scope for a fix here.
        //
        // Bug 17 is the other half, and the two have to be decided together or they double-count.
        // The rule the account balance now follows: it moves with the transactions Finora holds.
        // A corroborated closing balance is a stronger, ABSOLUTE statement of where the account
        // ended, so it wins outright; with no such statement, the imported rows are still real
        // ledger entries and the balance must move by their net effect. Previously neither
        // happened without a closing balance -- 300 imported transactions left the balance frozen
        // at whatever the account was created with, and every derived figure with it.
        //
        // The two agree wherever both are available: for a new account, opening + net == closing
        // exactly when the guard says CORROBORATED, since that is the arithmetic it checks.
        //
        // The guard needs the ACCOUNT TYPE, and that is not a detail. Whether a debit raises or
        // lowers the closing balance is a property of the account -- on a card, balance is money
        // OWED, so a purchase increases it. The guard used to assume every account was an asset,
        // which made every arithmetically correct credit-card statement come out UNCORROBORATED
        // (off by exactly twice the net) and meant no card ever had its stated closing balance
        // applied.
        //
        // The TYPE is read here and the ENTITY is not held. Every block below re-fetches, which
        // looks like a redundant query and is not: Account extends BaseEntity, whose non-null
        // @Version makes Spring Data route save() through merge() -- and merge returns a NEW
        // managed copy, leaving the instance you passed in on its old version. Holding one
        // reference across two saves therefore fails the second with
        // ObjectOptimisticLockingFailureException, which is precisely the trap BaseEntity's own
        // class comment documents. The type cannot go stale; the row can.
        Account.Type accountType = accountRepository.findById(accountId)
                .map(Account::getAccountType).orElse(null);
        ClosingBalanceGuard.Decision balanceDecision = ClosingBalanceGuard.assess(
                accountType,
                request.statementOpeningBalance(), request.statementClosingBalance(),
                totalCredits, totalDebits, toInsert.size(), skipped);
        boolean closingBalanceIsAuthoritative = balanceDecision.mayOverwriteAccountBalance()
                && isMostRecentStatementForAccount(userId, accountId, maxDate, savedImport.getId());
        if (closingBalanceIsAuthoritative) {
            accountRepository.findById(accountId).ifPresent(account -> {
                account.setBalance(request.statementClosingBalance());
                accountRepository.save(account);
            });
        } else if (!toInsert.isEmpty()) {
            accountRepository.findById(accountId).ifPresent(account -> {
                // AccountBalanceConvention, not a local loop: the credit-card inversion (a purchase
                // INCREASES what is owed) is a property of the account type, and re-deriving it
                // here is exactly the duplication that produced this bug. StatementImportService
                // .delete reverses this with netDelta(...).negate() so an import/delete cycle
                // returns the balance to where it started.
                BigDecimal net = AccountBalanceConvention.netDelta(account.getAccountType(), toInsert);
                if (net.signum() != 0) {
                    account.setBalance(account.getBalance().add(net));
                    accountRepository.save(account);
                }
            });
            if (balanceDecision.verdict() == ClosingBalanceGuard.Verdict.UNCORROBORATED) {
                // warn, not error: this is a statement Finora read imperfectly or a review the user
                // changed, both of which are ordinary. It is logged because a sustained rise in
                // these means the parser is misreading a layout, which is worth being able to see.
                //
                // The details MAP IS NOT LOGGED, only its keys. Its values are
                // openingBalance/closingBalance/totalCredits/totalDebits/expectedClosingBalance/
                // difference -- a customer's actual account balance and the totals that reconstruct
                // it, written at WARN, which application-prod.yml emits (com.finora: INFO). That is
                // customer financial data in the application log, and from there in whatever
                // aggregator, backup and support tool the deployment feeds.
                //
                // The rate signal this line exists for survives the change intact: it is the COUNT
                // of these warnings over time that says a layout is being misread, not any one
                // statement's figures. The keys are kept because they say which branch of the guard
                // fired and what evidence it had, which is the part that distinguishes one failure
                // mode from another.
                //
                // Reproducing a specific misparse does not need the amounts here and never did: the
                // established route is scripts/trace-capture.sh against the statement itself, which
                // produces a redacted trace that can be committed as a regression fixture. See
                // docs/engineering/trace-lifecycle.md.
                log.warn("Not applying the stated closing balance to account {}: {} (evidence: {})",
                        accountId, balanceDecision.reason(), balanceDecision.details().keySet());
            }
        }

        // Bug fix (v7->v8): this path never ran reconciliation, so imported transactions never
        // got flagged as internal transfers or duplicates the way manually-entered ones do. Now
        // it does, and the summary reports exactly what reconciliation found among THIS batch.
        int duplicatesDetected = 0;
        int transfersIdentified = 0;
        if (imported > 0) {
            reconciliationService.reconcileForUser(userId);
            // Same reasoning as TransactionService's write paths -- a fresh batch of imported
            // transactions is exactly the kind of change that can newly complete (or newly break)
            // a recurring pattern, and a MARK_SUBSCRIPTION rule match needs this to take effect
            // immediately rather than waiting on the user to open the Recurring page.
            recurringService.detectForUser(userId);
            DuplicateDetector.ReconciliationTally tally = duplicateDetector.tally(saved);
            duplicatesDetected = tally.duplicatesDetected();
            transfersIdentified = tally.transfersIdentified();

            // BH-003. The balance above moved by the net effect of EVERY row this import inserted.
            // Reconciliation has just decided that some of them are duplicates of transactions the
            // ledger already held, and every reported total -- dashboard, reports, category spend
            // -- excludes them from here on. Account.balance was the one figure that did not, so
            // re-importing a statement (or uploading the same file twice) moved the balance a
            // second time and left it permanently overstated, with the rows that caused it hidden
            // from the ledger view. Nothing recomputes that column, so the disagreement was
            // permanent and silent -- the exact failure ClosingBalanceGuard's own comment describes
            // this pipeline as existing to prevent.
            //
            // The rule the balance follows is unchanged: it moves with the transactions Finora
            // COUNTS. A duplicate is not counted, so its contribution comes back off.
            //
            // Only on the netDelta branch. When the closing balance was authoritative the column
            // holds an absolute figure the statement stated, not an accumulated one -- re-importing
            // writes the same number again, which is already idempotent, and subtracting from it
            // would corrupt a balance that was correct.
            if (!closingBalanceIsAuthoritative && !tally.duplicates().isEmpty()) {
                // Re-fetched, like every other block that writes this row -- see the note above
                // the guard on why holding one Account reference across two saves does not work.
                accountRepository.findById(accountId).ifPresent(account -> {
                    BigDecimal reversal = AccountBalanceConvention
                            .netDelta(account.getAccountType(), tally.duplicates()).negate();
                    if (reversal.signum() != 0) {
                        account.setBalance(account.getBalance().add(reversal));
                        accountRepository.save(account);
                    }
                });
            }
        }

        long merchantsAfter = merchantRepository.countByUserId(userId);
        int newMerchantsLearned = (int) Math.max(0, merchantsAfter - merchantsBefore);

        List<String> warnings = new ArrayList<>();
        if (skipped > 0) {
            warnings.add(skipped + " row(s) were left unchecked during review and were not imported.");
        }
        // Silence here was the actual harm in Bug 02: the balance was written whether or not it
        // agreed with the transactions, so there was never anything to notice. The wording says
        // what actually happened now that Bug 17 is fixed too -- the balance was not left frozen,
        // it moved by what these transactions do; it just was not set to the figure printed on the
        // statement, and the user should know which of the two they are looking at.
        if (!closingBalanceIsAuthoritative
                && balanceDecision.verdict() == ClosingBalanceGuard.Verdict.UNCORROBORATED) {
            warnings.add("This account's balance was updated from the imported transactions rather "
                    + "than from the statement's stated closing balance: " + balanceDecision.reason());
        }

        // Re-fetched (not the pre-import in-memory copy) so the summary reflects the balance
        // update above, if it applied. Falls back to AccountDto.from(a) (no statement/transaction
        // counts) rather than the full listForUser() aggregation -- the summary screen only ever
        // needs this one account's identity/balance, not its statement/transaction history.
        AccountDto accountSnapshot = accountRepository.findById(accountId)
                .map(AccountDto::from)
                .orElse(null);

        return new ConfirmResponse(imported, skipped, duplicatesDetected, transfersIdentified,
                newMerchantsLearned, accountsCreated, productsCreated, categoryTally, warnings,
                accountSnapshot, totalCredits, totalDebits,
                request.statementOpeningBalance(), request.statementClosingBalance(),
                minDate, maxDate,
                System.currentTimeMillis() - startedAtMs,
                "CSV");
    }

    /**
     * Whether this statement is the newest one on file for the account, so its stated closing
     * balance may be treated as where the account actually ended.
     *
     * <p>BH-024. This used to load EVERY statement import the user has and filter in memory, once
     * per confirm and once per section for a composite statement -- a full read of the largest
     * table in the schema to produce a boolean. It also dereferenced {@code si.getAccountId()}
     * without a null check, so a row with no account would have thrown mid-import. One aggregate
     * query answers it, and the database handles the nulls.
     */
    private boolean isMostRecentStatementForAccount(UUID userId, UUID accountId, LocalDate thisStatementEnd, UUID thisStatementId) {
        if (thisStatementEnd == null) return true; // nothing to compare against — apply rather than never updating
        return statementImportRepository
                .findLatestPeriodEndForAccount(userId, accountId, thisStatementId)
                .map(latestOther -> !latestOther.isAfter(thisStatementEnd))
                .orElse(true); // this is the account's only statement, or no other one states a period
    }

    /** What the review screen says this product is, falling back to the coarse account type when a
     *  client hasn't been updated to echo the classification back. Never guesses a finer product
     *  than it was told: an unstated FD stays SAVINGS here rather than being invented. */
    private FinancialProductType productTypeOf(NewAccountRequest na) {
        if (na.detectedProduct() != null && !na.detectedProduct().isBlank()) {
            try {
                return FinancialProductType.valueOf(na.detectedProduct());
            } catch (IllegalArgumentException e) {
                // An unknown value from a client is not worth failing an import over.
            }
        }
        // Nothing detected, so the form's own account type is all there is. Mapped only where an
        // account type names exactly ONE product; INVESTMENT does not (it covers FD, RD, PPF, mutual
        // funds and more), so it yields UNKNOWN, whose null accountType() lets the user's choice
        // through untouched in resolveTargetAccount.
        //
        // Bug fix: this used to fall through to SAVINGS for anything that wasn't CREDIT_CARD or
        // WALLET, and SAVINGS's own routing then OVERRODE the form -- so a user who explicitly
        // picked Investment on the review screen got a Savings account. Inventing a product the
        // user's own choice contradicts is the one thing this fallback must not do.
        return switch (na.accountType() == null ? "" : na.accountType()) {
            case "CREDIT_CARD" -> FinancialProductType.CREDIT_CARD;
            case "WALLET" -> FinancialProductType.WALLET;
            case "SAVINGS" -> FinancialProductType.SAVINGS;
            default -> FinancialProductType.UNKNOWN;
        };
    }

    private UUID resolveTargetAccount(UUID userId, ConfirmRequest request, List<String> accountsCreated,
                                       Map<String, Integer> productsCreated) {
        if (request.existingAccountId() != null) {
            return OwnershipGuard.requireOwned(accountRepository.findById(request.existingAccountId()),
                    Account::getUserId, userId, "Account").getId();
        }
        if (request.newAccount() != null) {
            NewAccountRequest na = request.newAccount();
            if (na.name() == null || na.name().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "The new account needs a name.");
            }

            // Before creating anything: is this a product the user already holds?
            //
            // Classification finds the same fixed deposit in every monthly statement, so without
            // this check each re-import creates another one and double-counts it in net worth --
            // and unpicking that afterwards is a data migration plus a merge UI, not a bug fix.
            // Only an EXACT identity match (same institution AND same product number) redirects
            // silently. A merely PROBABLE one deliberately falls through and creates the account
            // the user asked for: quietly importing into the wrong deposit corrupts two products
            // at once, which is worse than a duplicate the user can see and merge.
            ProductIdentity discovered = ProductIdentity.stored(
                    na.bankId(), productTypeOf(na), na.productIdentityHash(), na.accountNumberMasked());
            ProductIdentityResolver.ProductMatch match = productIdentityResolver.resolve(userId, discovered);
            if (match.mayImportWithoutAsking()) {
                return match.account().getId();
            }

            // Route the product to where it actually belongs. FinancialProductType carries its own
            // routing, so a term deposit becomes an INVESTMENT with investmentKind "FD" -- landing
            // in the Investments module alongside mutual funds and PPF -- rather than an empty
            // savings account, which is what every deposit section used to become.
            //
            // The user's own choice still wins when they made one: accountType comes from the
            // review screen, and an UNKNOWN product has nothing to route by, so it falls back to
            // whatever they picked. That fallback IS the correction loop -- they name it once.
            FinancialProductType product = productTypeOf(na);
            String accountType = product.accountType() != null
                    ? product.accountType().name() : na.accountType();
            AccountDto created = accountService.create(userId, new AccountDto.CreateRequest(
                    na.name(), accountType, na.openingBalance(), na.creditLimit(), na.dueDate(),
                    product.investmentKind(),
                    na.accountHolderName(), na.accountNumberMasked(), na.bankId(),
                    na.branchName(), na.ifscCode(),
                    na.principalAmount(), na.interestRate(), na.maturityDate(), na.maturityAmount(),
                    na.installmentAmount(), na.installmentsPaid(), na.installmentsTotal()), userId);
            accountsCreated.add(created.name());
            productsCreated.merge(product.name(), 1, Integer::sum);

            // Stamp the identity so the NEXT import of this product recognises it. Done here rather
            // than through AccountDto.CreateRequest so the public account API stays unchanged --
            // these two columns exist for the importer, not for anyone creating an account by hand.
            accountRepository.findById(created.id()).ifPresent(account -> {
                account.setProductType(discovered.type().name());
                account.setProductIdentityHash(discovered.strongKey());
                accountRepository.save(account);
            });
            return created.id();
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "Choose an existing account or provide details for a new one.");
    }
}
