package com.finora.imports;

import com.finora.imports.analysis.ParseDiagnostics;
import com.finora.imports.analysis.StatementAnalysisSession;
import com.finora.imports.analysis.StatementAnalysisRecorder;
import com.finora.accounts.AccountBalanceConvention;
import com.finora.accounts.AccountDto;
import com.finora.dto.ImportDto.*;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.ImportSession;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.storage.ContentAddress;
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
import java.util.Objects;
import java.util.Optional;
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
    /**
     * C-9 shadow mode. Observes the closing-balance evidence at confirm time and records it;
     * nothing in this class reads what it produced, and it returns void so nothing can. Nullable
     * purely so the unit tests that construct this service by hand can leave it out -- Spring
     * always injects the real one. See {@link #observeClosingBalanceEvidence}.
     */
    private final com.finora.imports.evidence.ClosingBalanceEvidenceShadowObserver evidenceShadowObserver;

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
                          LayoutRegistryService layoutRegistryService,
                          com.finora.imports.evidence.ClosingBalanceEvidenceShadowObserver evidenceShadowObserver) {
        this.evidenceShadowObserver = evidenceShadowObserver;
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
        // Duplicate-upload protection (distributed-resilience-patterns-audit-2026-08-14.md §3;
        // V79__import_session_stage_idempotency.sql). Checked BEFORE parsing, not after: a
        // double-clicked upload or a retried request that arrives after the first has already
        // finished staging would otherwise pay for a full second parse just to discover a
        // duplicate row it can't create. See ImportSessionService.findLiveSessionByContentHash's
        // own doc comment for what this does and does not guarantee.
        Optional<ImportSession> alreadyStaged = importSessionService.findLiveSessionByContentHash(
                userId, ContentAddress.hashOf(fileContent));
        if (alreadyStaged.isPresent()) {
            ImportSession session = alreadyStaged.get();
            return new StagingSessionResponse(session.getId(), rebuildStagingResponse(session));
        }

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
        } catch (RuntimeException e) {
            // BH-028. This caught ApiException only, so a document that made the PARSER FALL OVER
            // -- an index out of bounds in column bucketing, a PDFBox failure, anything unchecked
            // -- produced a 500, a stack trace in the log, and NO row in the evidence table. That
            // table exists to answer "which layouts defeat the parser", and it was systematically
            // missing the layouts that defeat it hardest: the anticipated rejections were all
            // recorded and the unanticipated crashes were all invisible, including on every retry.
            //
            // Recording is safe from here: recordFailed is REQUIRES_NEW, so it commits on its own
            // and cannot roll anything back, and cannot itself be rolled back.
            recordParseFailure(userId, fileName, "CSV", fileContent.length, fingerprint, e,
                    startedAtMs, diagnostics);
            throw e;
        }
    }

    /**
     * Records a failed parse, whatever kind of failure it was.
     *
     * <p>BH-028. An {@code ApiException} carries an {@link ErrorCode} naming a rejection the
     * pipeline anticipated ({@code IMPORT_001}, {@code IMPORT_007}, a missing PDF password).
     * Anything else is the pipeline breaking rather than refusing, and there is no code for that --
     * so the exception's own class name is used, which is the most useful thing available and
     * keeps "index out of bounds" distinguishable from "PDFBox could not open this" in the failure
     * histogram.
     *
     * <p><b>Recording must never replace the failure being recorded.</b> If the recorder itself
     * throws -- the analysis table is unreachable, say -- the caller has to receive the ORIGINAL
     * exception, because that is the one that explains what happened to their document. A
     * bookkeeping failure masking a parse failure would turn a diagnosable problem into a
     * mysterious one, which is the opposite of what this table is for.
     */
    private void recordParseFailure(UUID userId, String fileName, String sourceFormat, long byteSize,
                                     String fingerprint, RuntimeException failure, long startedAtMs,
                                     ParseDiagnostics diagnostics) {
        String code = ErrorCode.failureCodeOf(failure);
        try {
            analysisRecorder.recordFailed(userId, StatementAnalysisSession.Source.CUSTOMER_IMPORT, fileName,
                    sourceFormat, byteSize, fingerprint, code, failure.getMessage(),
                    System.currentTimeMillis() - startedAtMs, diagnostics);
        } catch (RuntimeException recordingFailed) {
            log.error("Could not record the failed analysis for {} -- the parse failure itself is "
                    + "being rethrown and is the one that matters.", fileName, recordingFailed);
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
        // Same duplicate-upload protection as the CSV path above -- see that method's own comment
        // and ImportSessionService.findLiveSessionByContentHash. Checked ahead of opening the
        // document at all, so a duplicate re-upload of a password-protected PDF needs no password:
        // nothing is being parsed, only an already-staged result handed back.
        Optional<ImportSession> alreadyStaged = importSessionService.findLiveSessionByContentHash(
                userId, ContentAddress.hashOf(fileContent));
        if (alreadyStaged.isPresent()) {
            return rebuildPdfStagingSessionResponse(alreadyStaged.get());
        }

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

            // P-002 Fix 1. Hoisted out of the single-section branch below, where it used to live and
            // where it only ever saw documents that located one table. A document that staged NO
            // transaction in ANY section is the same failed extraction whether the locator cut it
            // into one section or eight, but only the one-section shape was ever refused: the
            // multi-section branch had no zero-extraction guard at all, and
            // StagedAccountSectionFilter deliberately returns every section unfiltered when none of
            // them has rows (it defers the verdict to "the caller's zero-transaction guard", which
            // on this path was not being called). The result on real documents was a review screen
            // offering eight zero-transaction accounts to confirm -- e.g. the committed
            // kotak-credit-card-ledger-validation trace, whose eight located sections are prose
            // fragments -- where the identical content in one section is cleanly rejected.
            //
            // The check itself is unchanged and shared, so the multi-section rejection is the same
            // error code and the same message the single-section path has always produced. It only
            // fires when the WHOLE document is empty; a multi-section document with transactions
            // anywhere still proceeds, and its individual empty sections are still dropped by the
            // filter above rather than newly rejected here.
            ExtractionCheck.rejectIfNothingWasExtracted(sections, result.documentContext());

            if (sections.size() <= 1) {
                // The common case (and the only case a CSV upload can ever produce): behaves exactly
                // as this method always has, just wrapped in the new response envelope.
                StagingResponse staged = sections.isEmpty()
                        ? new StagingResponse(List.of(), 0, 0, null, List.of())
                        : toStagingResponse(sections.get(0));
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
        } catch (RuntimeException e) {
            // The whole point of the evidence table. A password failure carries no fingerprint --
            // the document was never opened -- but IMPORT_001 and IMPORT_007 do, and those are the
            // ones that say "this layout defeated the parser".
            //
            // BH-028: widened from ApiException. A PDF is far more likely than a CSV to crash the
            // parser outright rather than be cleanly rejected, so this is the path where the gap
            // mattered most -- the documents that most needed a fingerprint recorded were exactly
            // the ones that recorded nothing.
            recordParseFailure(userId, fileName, "PDF", fileContent.length, fingerprint, e,
                    startedAtMs, diagnostics);
            throw e;
        }
    }

    /** Rebuilds the response an already-staged session would have produced, for the duplicate-
     *  upload short-circuit in both stage methods above -- same fields {@code ImportController
     *  .getSession()} reads back for an ordinary resume, just assembled here instead since this
     *  path never reaches the controller's own GET. */
    private StagingResponse rebuildStagingResponse(ImportSession session) {
        List<StagedRow> rows = importSessionService.readStagedRows(session);
        int dupCount = (int) rows.stream().filter(StagedRow::likelyDuplicate).count();
        return new StagingResponse(rows, rows.size(), dupCount, importSessionService.readDetectedAccount(session), List.of());
    }

    /** PDF equivalent of {@link #rebuildStagingResponse} -- branches on the found session's own
     *  kind rather than assuming single-account, since a duplicate PDF upload can match either
     *  shape depending on what the original upload staged. */
    private PdfStagingSessionResponse rebuildPdfStagingSessionResponse(ImportSession session) {
        if (ImportSession.KIND_MULTI_ACCOUNT.equals(session.getSessionKind())) {
            return new PdfStagingSessionResponse(session.getId(), true, null, importSessionService.readSections(session));
        }
        return new PdfStagingSessionResponse(session.getId(), false, rebuildStagingResponse(session), null);
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
     * Stops offering a located table as a transaction ACCOUNT when it plainly isn't one -- see
     * {@link StagedAccountSectionFilter#onlySectionsThatAreActuallyAccounts} for the rule itself and
     * for why it lives there rather than here.
     *
     * <p>Short version: the list this returns is what {@code createMultiSection} persists as
     * {@code sectionsJson}, and every section index the system later speaks in -- the
     * {@code confirmMultiSection} loop index, the implicit index 0 of a single-account
     * {@code confirmSession} -- is an index into THIS list, not into the generator's raw output.
     * Anything else that needs to resolve one of those indices back to a section must apply the
     * same filter first, which is only possible if there is one filter to apply.
     */
    private List<StagedAccountSection> onlySectionsThatAreActuallyAccounts(List<StagedAccountSection> sections) {
        return StagedAccountSectionFilter.onlySectionsThatAreActuallyAccounts(sections);
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

    /**
     * <p><b>{@code section.verification()} is the whole reason this method is not a five-argument
     * call.</b> It used to be one, and the five-argument {@link StagingResponse} overload defaults
     * verification to null -- so the four rules ran on every single-account PDF, produced findings,
     * and had them discarded one line later. This is the conversion the LIVE upload endpoint uses
     * ({@code POST /import/pdf/stage} -> the {@code sections.size() <= 1} branch above, the common
     * case), and the section-indexed re-import uses it too. The user saw nothing: the review screen
     * renders no panel at all for an absent report, so a statement with a broken balance chain and
     * one that verified cleanly produced a byte-identical screen. Nothing was persisted either --
     * {@code recordPdfParsed} forwards this same field, and the recorder skips a null report, so
     * {@code import_verification_findings} held zero rows for the most common PDF shape there is.
     * See docs/architecture/system-design/pdfpreviewgenerator-verification-loss-investigation.md.
     */
    private StagingResponse toStagingResponse(StagedAccountSection section) {
        return new StagingResponse(section.rows(), section.totalParsed(), section.flaggedDuplicates(),
                section.detectedAccount(), section.unparseableRows(), section.verification());
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
        // C-9 shadow mode, before the claim -- see observeClosingBalanceEvidence for why the order
        // is forced. One observation per section, with the same section index the persist loop
        // below uses. Records; decides nothing.
        // Null-guarded rather than assumed: a null sections list currently fails AFTER the claim,
        // and nothing here may change which failure a caller gets or when the session is claimed.
        if (request.sections() != null) {
            for (int i = 0; i < request.sections().size(); i++) {
                SectionConfirm observed = request.sections().get(i);
                observeClosingBalanceEvidence(userId, request.sessionId(), i,
                        observed == null ? null : observed.statementClosingBalance());
            }
        }

        var session = importSessionService.claimForConfirmation(userId, request.sessionId());
        var stagedSections = importSessionService.readSections(session);
        if (stagedSections.size() != request.sections().size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "The reviewed sections don't match what was staged for this import session -- try staging again.");
        }

        // BH-041: persist every section FIRST, reconcile once, then summarise. See reconcileAcross
        // for why that is both cheaper and slightly more correct than the per-section loop this
        // replaces. Row-count validation stays in this first pass, so a mismatched section still
        // rejects the whole request before anything is written -- the transaction is the same one.
        List<PersistedSection> persisted = new ArrayList<>();
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
                    sectionConfirm.statementOpeningBalance(), sectionConfirm.statementClosingBalance(),
                    null, // a multi-section PDF was already unlocked once to be staged; no password to carry here
                    sectionConfirm.statementPeriodStart(), sectionConfirm.statementPeriodEnd());
            persisted.add(persistSection(userId, session.getFileName(), statementContentService.read(session), perAccountRequest, i,
                    session.getLayoutMetadataJson(), session.getLayoutFingerprint(), session.getActivatedCapabilitiesJson(),
                // A multi-section import is CSV/PDF only -- a Gmail receipt is never
                // multi-account -- so source is always null on this path, not session.getSource().
                session.getUnparseableSummaryJson(), null));
        }

        reconcileAcross(userId, persisted);

        List<ConfirmResponse> responses = new ArrayList<>();
        for (PersistedSection section : persisted) {
            responses.add(summarise(userId, section));
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
        //
        // C-9 shadow mode runs one line above the claim, deliberately -- see
        // observeClosingBalanceEvidence. Single-account session, so a null section index, exactly
        // as the confirm() call below passes.
        observeClosingBalanceEvidence(userId, request.sessionId(), null, request.statementClosingBalance());

        var session = importSessionService.claimForConfirmation(userId, request.sessionId());

        var stagedRows = importSessionService.readStagedRows(session);
        // BH-023. The count check this replaces was the whole of the integrity story, and its own
        // comment admitted as much: "the cheapest real check that the confirmed list is plausibly
        // the same staged rows". Plausibly was not enough -- same count, entirely different rows
        // was accepted, and the ledger recorded transactions the stored document does not contain.
        ConfirmedRowIntegrity.requireSameRows(stagedRows, request.rows());
        return confirm(userId, session.getFileName(), statementContentService.read(session), request, null,
                session.getLayoutMetadataJson(), session.getLayoutFingerprint(), session.getActivatedCapabilitiesJson(),
                session.getUnparseableSummaryJson(), session.getSource());
    }

    /**
     * C-9 shadow mode: computes the closing-balance evidence assessment for this confirm and
     * records it. <b>Observation only.</b> It returns void, it is called for its side effect on a
     * telemetry table and for nothing else, and no branch in this class reads anything it produced
     * -- {@code ClosingBalanceGuard} in {@code persistSection} remains the sole gate on whether a
     * closing balance is written, unchanged.
     *
     * <p><b>Called before {@code claimForConfirmation}, and it has to be.</b> The re-derivation
     * checks ownership through {@code ImportSessionService.getOwnedSession}, which rejects a
     * session whose status is already {@code CONFIRMED}. Claiming first sets exactly that status,
     * so an observation placed after the claim would fail with "already been confirmed" every
     * single time and the shadow corpus would be uniformly empty. Before the claim, the confirm has
     * written nothing at all, which is also the safest possible moment for it.
     *
     * <p>Consequence, stated rather than hidden: an observation is recorded for a confirm that
     * subsequently fails its row-integrity check or loses the claim race. The observation describes
     * the staged document, not the outcome of the import, and its {@code createdAt} plus its
     * analysis session are enough to join it back to one -- so this is left as it is rather than
     * deferred to after the persist, where a rolled-back confirm would silently lose the
     * measurement instead.
     *
     * <p>The observer never throws (see its class doc: suspended transaction, {@link Throwable}
     * caught at three separate levels). The null check is for the hand-constructed instances in
     * unit tests; the guard below is belt-and-braces, so that even a future observer that broke its
     * own contract could not fail an import from here.
     */
    private void observeClosingBalanceEvidence(UUID userId, UUID sessionId, Integer sourceSectionIndex,
                                                java.math.BigDecimal closingBalanceClaim) {
        if (evidenceShadowObserver == null) return;
        try {
            evidenceShadowObserver.observe(userId, sessionId, sourceSectionIndex, closingBalanceClaim);
        } catch (Throwable t) {
            log.warn("Shadow evidence observation failed for session {} -- the import continues "
                    + "exactly as it would have", sessionId, t);
        }
    }

    /**
     * Filename + raw bytes rather than MultipartFile so StatementImportService.confirmReimport()
     * can drive this from a stored StatementImport's file_content column without needing a fake
     * MultipartFile implementation just to satisfy the type — same reasoning as the
     * parseAndStage(userId, filename, InputStream) split above. No ImportSession is available on
     * this path (confirmReimport() replays already-stored bytes, not a fresh staged session), so
     * the layout metadata/fingerprint/capabilities trio -- and source (C5-B) -- are left null here,
     * same "best-effort, never recomputed after the fact" discipline as every other nullable field
     * on this pipeline. Re-importing a Gmail-derived StatementImport this way would fall back to
     * CSV_IMPORT provenance; that gap is accepted rather than solved here, on the same reasoning as
     * the PDF-mislabelled-as-CSV_IMPORT one Transaction.Source's own comment already accepts.
     */
    @Transactional
    public ConfirmResponse confirm(UUID userId, String fileName, byte[] fileContent, ConfirmRequest request) {
        return confirm(userId, fileName, fileContent, request, null, null, null, null, null, null);
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
        return confirm(userId, fileName, fileContent, request, sourceSectionIndex, null, null, null, null, null);
    }

    /**
     * Phase 1 "capture facts" (docs/engineering/financial-document-intelligence-principles.md):
     * layoutMetadataJson/layoutFingerprint/activatedCapabilitiesJson are copied verbatim from the
     * ImportSession this confirm came from (confirmSession()/confirmMultiSection() both read
     * theirs before calling down to this method) -- never recomputed here, since this method has
     * no access to the original StagedRow/DetectedAccountInfo/DocumentContext, only the reviewed
     * ConfirmedRow list. All three nullable -- a caller with no session (see the byte-array
     * overload above) simply leaves them unset.
     *
     * <p>{@code source} (C5-B): {@link com.finora.entity.ImportSession#SOURCE_GMAIL} or null, copied
     * verbatim from the session the same way the metadata trio is -- never recomputed here, and
     * multi-section confirms always pass null (a Gmail receipt is never multi-section).
     */
    @Transactional
    public ConfirmResponse confirm(UUID userId, String fileName, byte[] fileContent, ConfirmRequest request, Integer sourceSectionIndex,
                                    String layoutMetadataJson, String layoutFingerprint, String activatedCapabilitiesJson,
                                    String unparseableSummaryJson, String source) {
        PersistedSection section = persistSection(userId, fileName, fileContent, request, sourceSectionIndex,
                layoutMetadataJson, layoutFingerprint, activatedCapabilitiesJson, unparseableSummaryJson, source);
        reconcileAcross(userId, List.of(section));
        return summarise(userId, section);
    }

    /**
     * BH-041. One reconciliation pass for a whole import, however many sections it had.
     *
     * <p>This used to run once per section, at user-wide scope, from inside each section's own
     * confirm. Three sections therefore reconciled the user's entire transaction history three
     * times: measured at +309 prepared statements, +136 query executions and +132 ms against a
     * 200-row history, all of it repeating work the previous section had just done.
     *
     * <p>Two things had to be true before the passes could be collapsed into one, and both were
     * checked rather than assumed:
     *
     * <ul>
     *   <li><b>The per-section counts survive.</b> Each section writes its own
     *       {@code StatementImport}, and {@link DuplicateDetector#tally} is a post-hoc read of
     *       persisted flags scoped by that id — it does not care how many passes ran or when. So
     *       {@code perAccount} keeps exactly the meaning it had, and no API changed.</li>
     *   <li><b>Reconciling later is not reconciling less.</b> Every pass was already user-wide, so
     *       the last one always saw every section. Running once after all of them are persisted
     *       reaches the same final state.</li>
     * </ul>
     *
     * <p>It also fixes an asymmetry nobody had reported: section 1 was summarised before section 2
     * existed, so a transfer between two sections of the same statement was counted in section 2's
     * {@code transfersIdentified} and not in section 1's. Both sides see it now.
     *
     * <p>The window spans every section's dates together, so a transfer whose legs sit in different
     * sections is inside one candidate set.
     */
    private void reconcileAcross(UUID userId, List<PersistedSection> sections) {
        if (sections.stream().noneMatch(s -> s.imported() > 0)) return;
        LocalDate earliest = sections.stream().map(PersistedSection::minDate)
                .filter(Objects::nonNull).min(LocalDate::compareTo).orElse(null);
        LocalDate latest = sections.stream().map(PersistedSection::maxDate)
                .filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null);
        reconciliationService.reconcileForImport(userId, earliest, latest);
        // Same reasoning as TransactionService's write paths -- a fresh batch of imported
        // transactions is exactly the kind of change that can newly complete (or newly break) a
        // recurring pattern, and a MARK_SUBSCRIPTION rule match needs this to take effect
        // immediately rather than waiting on the user to open the Recurring page. Hoisted here with
        // reconciliation for the same reason: it was per-section, it is user-wide, and the 3-vs-1
        // measurement counted its repeats too.
        recurringService.detectForUser(userId);
    }

    /**
     * Everything one section does up to the point reconciliation has to run: resolve the account,
     * build and insert the rows, write the StatementImport, move the balance.
     *
     * <p>Split out of {@code confirm()} for BH-041 so a multi-section import can persist every
     * section before any reconciliation happens. The counterpart is {@link #summarise}, and the two
     * are not independently reorderable — see that method on why the BH-003 balance reversal has to
     * travel with the tally.
     */
    private PersistedSection persistSection(UUID userId, String fileName, byte[] fileContent, ConfirmRequest request,
                                    Integer sourceSectionIndex,
                                    String layoutMetadataJson, String layoutFingerprint, String activatedCapabilitiesJson,
                                    String unparseableSummaryJson, String source) {
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
        // Loaded once for the whole confirmed statement, not once per row. The per-row overload
        // (CategorizationService.applySideEffectRules(UUID, Transaction)) re-queried category_rules
        // twice for every confirmed row -- same pattern, same fix, as suggestReadOnly's rule-set
        // hoist on the staging side (see that method's own doc comment); a user's rules cannot
        // change partway through confirming one import, so hoisting is equivalent by construction.
        List<com.finora.entity.CategoryRule> confirmRules = categorizationService.ruleSetFor(userId);
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

            // Bug 04: "Other" for a null/blank category cell, not a thrown validation error --
            // this mirrors CategoryRules' own fallback for "nothing matched" (see its own doc
            // comment), so an unparseable category cell degrades to the same bucket a
            // low-confidence guess would, rather than failing the whole import.
            // resolveOrCreateCategory itself throws on null/blank (see its own doc comment) for
            // every OTHER caller, where a blank name is a genuinely malformed request rather than
            // a parser artifact to paper over -- the degradation belongs here, at the one call
            // site the bug report identifies as reachable with unbounded, possibly-blank raw
            // parser output.
            String rowCategory = (row.category() == null || row.category().isBlank()) ? "Other" : row.category();
            Category category = categorizationService.resolveOrCreateCategory(userId, rowCategory);
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
            // GMAIL_IMPORT only when the session actually said so (C5-B); everything else keeps
            // the exact pre-existing behaviour, INCLUDING the known PDF-mislabelled-as-CSV_IMPORT
            // gap -- see Transaction.Source's own comment. Not fixing that here.
            t.setSource(com.finora.entity.ImportSession.SOURCE_GMAIL.equals(source)
                    ? Transaction.Source.GMAIL_IMPORT : Transaction.Source.CSV_IMPORT);
            t.setReferenceNumber(row.referenceNumber());
            t.setBalanceAfter(row.balanceAfter());
            t.setNeedsCategoryReview(categorizationService.needsCategoryReview(userId, isUnresolvedGuess, row.categoryConfidence()));
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
            t.setDecisionConfidence(row.categoryConfidence());
            // MARK_TRANSFER/MARK_INVESTMENT/ADD_TAG rules -- see
            // CategorizationService.applySideEffectRules's doc comment. A MARK_INVESTMENT match
            // returns the new Category -- reassigning `category` keeps the tally below (and any
            // other use of `category` in this iteration) in sync with what actually got persisted.
            Category sideEffectCategory = categorizationService.applySideEffectRules(userId, t, confirmRules);
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
        // was never written.
        //
        // This is also the FIRST time this file's bytes reach object storage at all -- staging
        // (ImportSessionService.storeContent) deliberately keeps them in file_content, temporary
        // database storage, until now. See that method's own doc comment for why: a session a user
        // never confirms should never have cost an R2 write.
        //
        // BH-025/BH-046: fileContent is set ONLY when store() came back empty (no provider
        // configured -- the row stays legacy, read from fileContent exactly as before this fix).
        // When storage IS configured, the object is the only copy; fileContent is left null
        // instead of duplicated into BYTEA. This used to be an unconditional dual write,
        // justified as temporary until a Phase 3 backfill + Phase 4 column drop. BH-046 found
        // neither phase survived -- Phase 3 was deleted for having nothing to migrate, and Phase 4
        // never got a trigger -- which turned "temporary" into "permanent", and BH-025 found the
        // cost that permanence carries: confirmMultiSection() calls this once per account section
        // with the same file, so a 3-section 9 MB statement was writing 27 MB of BYTEA on top of
        // the already-deduplicated content-addressed object. See
        // docs/engineering/statement-storage-migration.md §5.0 for the full history.
        //
        // Content-addressing is what makes the object-storage side of this cheap for the two
        // callers that duplicate bytes today -- confirmMultiSection() calls this once per account
        // section with the same file, and confirmReimport() calls it again with an
        // already-stored one. Both resolve to the same object instead of writing another copy.
        //
        // Storage review: statementContentService.store now compresses (GZIP) before the object
        // ever reaches R2 -- see that class's own "Compression" doc section for why content_hash
        // still identifies the ORIGINAL bytes regardless. originalSize/storedSize/compressionType
        // are recorded purely as storage-savings metrics; nothing on the read path branches on the
        // size fields, only on compressionType (StatementContentService.read).
        var storedContent = statementContentService.store(fileContent,
                com.finora.imports.StatementUpload.Format.valueOf(statementImport.getSourceFormat()).contentType());
        if (storedContent.isPresent()) {
            var stored = storedContent.get();
            statementImport.setContentHash(stored.address().hash());
            statementImport.setObjectKey(stored.address().key());
            statementImport.setOriginalSize(stored.originalSize());
            statementImport.setStoredSize(stored.storedSize());
            statementImport.setCompressionType(stored.compressionType());
            statementImport.setOriginalMimeType(stored.mimeType());
            statementImport.setEncryptionKeyId(stored.encryptionKeyId());
        } else {
            statementImport.setFileContent(fileContent);
        }
        // Bug fix: this used to be minDate/maxDate unconditionally -- the confirmed rows' own date
        // range, which is only ever a lower bound on the statement's true period whenever a cycle
        // has no activity near its own printed boundary dates. PdfPreviewGenerator/StatementValidator
        // already compute and surface the printed period at staging time (see their own
        // buildDetectedAccountInfo), and ConfirmRequest now echoes it back -- same precedence as
        // those two methods: prefer the printed period, fall back to the transaction range only when
        // nothing was printed (or an older client didn't send it).
        statementImport.setStatementPeriodStart(
                request.statementPeriodStart() != null ? request.statementPeriodStart() : minDate);
        statementImport.setStatementPeriodEnd(
                request.statementPeriodEnd() != null ? request.statementPeriodEnd() : maxDate);
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

        // Counted HERE rather than after reconciliation, which is where it used to sit. Nothing
        // between the two points creates merchants -- they are created while the rows above are
        // built -- so the number is identical, and taking it before the shared pass is what keeps
        // it attributable to THIS section instead of picking up merchants a later section learned.
        long merchantsAfter = merchantRepository.countByUserId(userId);
        int newMerchantsLearned = (int) Math.max(0, merchantsAfter - merchantsBefore);

        return new PersistedSection(accountId, savedImport, saved, imported, skipped,
                closingBalanceIsAuthoritative, balanceDecision, accountsCreated, productsCreated,
                categoryTally, newMerchantsLearned, totalCredits, totalDebits,
                request.statementOpeningBalance(), request.statementClosingBalance(),
                minDate, maxDate, startedAtMs);
    }

    /**
     * One section's share of an import, carried from {@link #persistSection} across the shared
     * reconciliation pass to {@link #summarise}.
     *
     * <p>Exists because BH-041 put a step between persisting a section and reporting on it. Every
     * field is a value the old single-pass method held as a local; nothing is recomputed on the far
     * side, so the summary describes the section it came from rather than the import as a whole.
     */
    private record PersistedSection(
            UUID accountId,
            StatementImport savedImport,
            List<Transaction> saved,
            int imported,
            int skipped,
            boolean closingBalanceIsAuthoritative,
            ClosingBalanceGuard.Decision balanceDecision,
            List<String> accountsCreated,
            Map<String, Integer> productsCreated,
            Map<String, Integer> categoryTally,
            int newMerchantsLearned,
            BigDecimal totalCredits,
            BigDecimal totalDebits,
            BigDecimal statementOpeningBalance,
            BigDecimal statementClosingBalance,
            LocalDate minDate,
            LocalDate maxDate,
            long startedAtMs) {}

    /**
     * What one section reports once reconciliation has run: what it found among this section's own
     * rows, the balance correction that follows from it, and the response.
     *
     * <p><b>The tally and the BH-003 reversal are one step, not two.</b> Reconciliation has just
     * decided which of this section's rows are duplicates; the balance was moved by all of them.
     * Reading the flags without reversing their contribution is the BH-003 bug exactly — a card
     * balance that went 4000.00 to 3000.00 on a second import of the same file, with the rows that
     * caused it hidden from the ledger view. Anything that reorders this method must keep the two
     * together.
     */
    private ConfirmResponse summarise(UUID userId, PersistedSection section) {
        UUID accountId = section.accountId();
        boolean closingBalanceIsAuthoritative = section.closingBalanceIsAuthoritative();

        // Bug fix (v7->v8): this path never ran reconciliation, so imported transactions never
        // got flagged as internal transfers or duplicates the way manually-entered ones do. Now
        // it does, and the summary reports exactly what reconciliation found among THIS batch --
        // scoped by this section's own statement_import_id, which is what lets one shared pass
        // still produce per-section numbers.
        int duplicatesDetected = 0;
        int transfersIdentified = 0;
        if (section.imported() > 0) {
            DuplicateDetector.ReconciliationTally tally = duplicateDetector.tally(section.saved());
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

        ClosingBalanceGuard.Decision balanceDecision = section.balanceDecision();
        List<String> warnings = new ArrayList<>();
        if (section.skipped() > 0) {
            warnings.add(section.skipped() + " row(s) were left unchecked during review and were not imported.");
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

        return new ConfirmResponse(section.imported(), section.skipped(), duplicatesDetected, transfersIdentified,
                section.newMerchantsLearned(), section.accountsCreated(), section.productsCreated(),
                section.categoryTally(), warnings,
                accountSnapshot, section.totalCredits(), section.totalDebits(),
                section.statementOpeningBalance(), section.statementClosingBalance(),
                // The already-resolved value on the just-saved row, not section.minDate()/maxDate()
                // -- this is the immediate post-confirm summary the "Statement period: ..." line
                // (Import.tsx) reads, and it must agree with what Statement History shows later, or
                // a printed period narrower/wider than the confirmed rows' own range would appear
                // correct here and wrong there (or vice versa) depending purely on which screen the
                // user happened to look at. See persistSection's own comment for the precedence.
                section.savedImport().getStatementPeriodStart(), section.savedImport().getStatementPeriodEnd(),
                System.currentTimeMillis() - section.startedAtMs(),
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
