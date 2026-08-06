package com.finora.imports;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.analysis.ParseDiagnostics;
import com.finora.imports.analysis.StatementAnalysisSession;
import com.finora.imports.analysis.StatementAnalysisRecorder;
import com.finora.imports.pdf.PdfPreviewGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Runs the real import engine on a document and keeps nothing but the evidence.
 *
 * <p>The tool the team has been missing. Every parser investigation so far has started with a
 * statement sitting in someone's Downloads folder and a throwaway probe reading it by absolute
 * path — which means the measurement exists on one laptop, cannot be repeated by anyone else, and
 * disappears when the file does. This puts the same document through the same pipeline a customer
 * upload uses and leaves a permanent, quotable analysis behind.
 *
 * <h2>Why the whole thing runs inside a transaction that is rolled back on purpose</h2>
 * The staging path is not read-only, and that is not obvious from the outside. Parsing a row calls
 * {@code TransactionNormalizer.normalize}, which calls {@code CategorizationService.suggest},
 * which calls {@code MerchantNormalizationEngine.resolve} — and {@code resolve} CREATES a merchant
 * and an alias row when it does not recognise a description. Analysing a 569-row statement would
 * therefore write hundreds of merchant rows attributed to whichever admin ran it, from a tool
 * whose entire premise is "import nothing".
 *
 * <p>Three ways to stop that were available. Threading a "dry run" flag down through normalize,
 * suggest and resolve touches the hot path of every real import to serve a diagnostic. Skipping
 * normalization changes what the engine actually does, so the analysis would stop describing what
 * a customer would get — which destroys the reason for running it. Rolling back does neither: the
 * code executed is byte-for-byte the code a customer's upload runs, and nothing it wrote survives.
 *
 * <p>The evidence row survives because {@link StatementAnalysisRecorder} writes in
 * {@code REQUIRES_NEW}. That was built for the failure case — a parse failure throws, the caller's
 * transaction rolls back, and the evidence had to outlive it — and this reuses exactly that
 * property, with the same integration test already proving it works.
 *
 * <h2>What is deliberately not kept</h2>
 * The bytes. No {@code StatementStorage} call, no {@code ImportSession}, no staged rows in the
 * response. Storing analysed documents is a real feature with a real retention story attached
 * (the corpus), and quietly acquiring a pile of customer statements as a side effect of a
 * diagnostic tool is precisely how that decision gets made by accident instead of on purpose.
 */
@Service
public class AdminAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AdminAnalysisService.class);

    private final PreviewGenerator previewGenerator;
    private final PdfPreviewGenerator pdfPreviewGenerator;
    private final StatementAnalysisRecorder analysisRecorder;

    public AdminAnalysisService(PreviewGenerator previewGenerator,
                                PdfPreviewGenerator pdfPreviewGenerator,
                                StatementAnalysisRecorder analysisRecorder) {
        this.previewGenerator = previewGenerator;
        this.pdfPreviewGenerator = pdfPreviewGenerator;
        this.analysisRecorder = analysisRecorder;
    }

    /**
     * Parses {@code file} and returns the reference of the analysis it recorded.
     *
     * <p>A parse FAILURE is a successful analysis here, and the return type says so: this returns
     * a reference either way rather than throwing. Studying documents the engine cannot read is
     * the main reason the tool exists, so surfacing a failure as an HTTP error would hand the
     * admin an error toast and no link to the evidence — the one case where the evidence matters
     * most. The recorded outcome carries the failure code.
     *
     * @return the analysis reference, never null
     * @throws ApiException only when the analysis genuinely could not be recorded
     */
    @Transactional
    public String analyze(UUID adminUserId, MultipartFile file, String password) throws IOException {
        // Set BEFORE any parsing, not after: an early return or an unexpected throw must not be
        // able to leave writes committed. Marking it up front means the rollback is the default
        // outcome of this method rather than something the happy path remembers to do.
        //
        // This marks the transaction LOCAL rollback-only, and this method is the outermost
        // boundary (the controller has none), so Spring rolls back silently at commit rather than
        // raising UnexpectedRollbackException at the caller.
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();

        byte[] content = file.getBytes();
        String fileName = file.getOriginalFilename() == null ? "statement" : file.getOriginalFilename();
        String format = looksLikePdf(fileName) ? "PDF" : "CSV";
        long startedAtMs = System.currentTimeMillis();

        String fingerprint = null;
        ParseDiagnostics diagnostics = ParseDiagnostics.NONE;
        try {
            if ("PDF".equals(format)) {
                var result = pdfPreviewGenerator.generateSectionsWithContext(adminUserId, fileName, content, password);
                fingerprint = result.documentContext().buildFingerprint();
                List<StagedAccountSection> sections = result.sections();
                diagnostics = ParseDiagnostics.of(
                        sections.stream().mapToInt(section -> section.rows().size()).sum(),
                        result.documentContext().unanchoredReasons());
                // Mirrors ImportService exactly, including only applying to the single-section
                // case: more than one detected section means the engine plainly found something.
                if (sections.size() <= 1) {
                    ExtractionCheck.rejectIfNothingWasExtracted(sections.isEmpty()
                            ? new StagingResponse(List.of(), 0, 0, null, List.of())
                            : new StagingResponse(sections.get(0).rows(), sections.get(0).totalParsed(),
                                    sections.get(0).flaggedDuplicates(), sections.get(0).detectedAccount(),
                                    sections.get(0).unparseableRows()),
                            result.documentContext());
                }
                return required(analysisRecorder.recordParsed(adminUserId,
                        StatementAnalysisSession.Source.ADMIN_ANALYSIS, fileName, format, content.length,
                        fingerprint, sections.size(), elapsed(startedAtMs), diagnostics), fileName);
            }

            var result = previewGenerator.generateWithContext(adminUserId, fileName,
                    new ByteArrayInputStream(content));
            fingerprint = result.documentContext().buildFingerprint();
            diagnostics = ParseDiagnostics.of(result.response().rows().size(),
                    result.documentContext().unanchoredReasons());
            ExtractionCheck.rejectIfNothingWasExtracted(result.response(), result.documentContext());
            return required(analysisRecorder.recordParsed(adminUserId,
                    StatementAnalysisSession.Source.ADMIN_ANALYSIS, fileName, format, content.length,
                    fingerprint, 1, elapsed(startedAtMs), diagnostics), fileName);

        } catch (ApiException e) {
            // Recorded and returned, not rethrown -- see this method's doc comment.
            return required(analysisRecorder.recordFailed(adminUserId,
                    StatementAnalysisSession.Source.ADMIN_ANALYSIS, fileName, format, content.length,
                    fingerprint, e.getCode() == null ? null : e.getCode().name(), e.getMessage(),
                    elapsed(startedAtMs), diagnostics), fileName);

        } catch (RuntimeException e) {
            // An engine crash is the most interesting finding of all and the easiest to lose:
            // without this, a NullPointerException deep in the parser would propagate as a 500
            // with nothing recorded, and the document that caused it would be untraceable.
            log.error("Admin analysis of {} crashed the engine", fileName, e);
            return required(analysisRecorder.recordFailed(adminUserId,
                    StatementAnalysisSession.Source.ADMIN_ANALYSIS, fileName, format, content.length,
                    fingerprint, "ENGINE_CRASH", e.getClass().getSimpleName() + ": " + e.getMessage(),
                    elapsed(startedAtMs), diagnostics), fileName);
        }
    }

    private static long elapsed(long startedAtMs) {
        return System.currentTimeMillis() - startedAtMs;
    }

    /**
     * The recorder returns null when it could not write, because losing one telemetry row must
     * never break a customer's import. Here the row IS the deliverable — there is nothing else to
     * return — so the same null has to become an error rather than a reference to nothing.
     */
    private static String required(String reference, String fileName) {
        if (reference != null) return reference;
        log.error("Admin analysis of {} ran but could not be recorded", fileName);
        throw new ApiException(ErrorCode.INTERNAL_ERROR,
                "The document was analysed but the analysis could not be saved.");
    }

    private static boolean looksLikePdf(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }
}
