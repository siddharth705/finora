package com.finora.imports.trace;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.ImportJob;
import com.finora.entity.ImportSession;
import com.finora.entity.Merchant;
import com.finora.entity.MerchantLearningEvent;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.imports.StatementUpload;
import com.finora.imports.analysis.ImportVerificationRecorder;
import com.finora.imports.analysis.ParseDiagnostics;
import com.finora.imports.analysis.StatementAnalysisRecorder;
import com.finora.imports.analysis.StatementAnalysisSession;
import com.finora.imports.jobs.ImportJobStore;
import com.finora.imports.jobs.ImportStageRecorder;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 2's sixth success criterion, asserted directly: <i>an administrator can trace one import
 * from upload through parsing, verification, learning and completion in a single view, without a log
 * or an engineer.</i>
 *
 * <p>Each test below is one clause of that sentence, and the last one is the "single view" clause —
 * the one that fails if the data is all present but still needs three lookups to gather. That is the
 * state this work item started from: {@code import_jobs}, {@code statement_analysis_sessions} and
 * {@code merchant_learning_events} all recorded their part and were keyed on things that never met.
 *
 * <p>Real Postgres because the whole subject is a join. Mocked repositories would return whatever
 * this test told them to and would agree with a service that joined on nothing.
 */
@TestPropertySource(properties = {
        "app.import.queue.enabled=false",
        "app.learning.queue.enabled=false"})
class ImportTraceIT extends AbstractIntegrationTest {

    @Autowired private ImportTraceService traceService;
    @Autowired private StatementAnalysisRecorder analysisRecorder;
    @Autowired private ImportVerificationRecorder verificationRecorder;
    @Autowired private ImportStageRecorder stageRecorder;
    @Autowired private ImportJobStore jobStore;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ImportSessionRepository importSessionRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private CategoryRepository categoryRepository;

    // ------------------------------------------------------------------ fixtures

    private User user() {
        User user = new User();
        user.setEmail("import-trace-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Import Trace IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private Account account(User owner) {
        Account account = new Account();
        account.setUserId(owner.getId());
        account.setName("Trace IT Savings");
        account.setAccountType(Account.Type.SAVINGS);
        return accountRepository.save(account);
    }

    private ImportSession stagingSession(User owner) {
        ImportSession session = new ImportSession();
        session.setUserId(owner.getId());
        session.setFileName("statement.pdf");
        session.setFileContent("synthetic".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        session.setStagedRowsJson("[]");
        session.setDetectedAccountJson("{}");
        session.setExpiresAt(Instant.now().plusSeconds(3600));
        return importSessionRepository.save(session);
    }

    private StatementImport statementImport(User owner, Account account, UUID importJobId) {
        StatementImport statementImport = new StatementImport();
        statementImport.setUserId(owner.getId());
        statementImport.setAccountId(account.getId());
        statementImport.setFileName("statement.pdf");
        statementImport.setFileContent("synthetic".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        statementImport.setTransactionsImported(124);
        statementImport.setTransactionsSkipped(2);
        if (importJobId != null) statementImport.setImportJobId(importJobId);
        return statementImportRepository.save(statementImport);
    }

    private MerchantLearningEvent learningEvent(User owner, UUID statementImportId, UUID sessionId) {
        Merchant merchant = new Merchant();
        merchant.setUserId(owner.getId());
        merchant.setCanonicalName("Trace IT Merchant " + UUID.randomUUID());
        Merchant savedMerchant = merchantRepository.save(merchant);

        Category category = new Category();
        category.setUserId(owner.getId());
        category.setName("Trace IT Category " + UUID.randomUUID());
        Category savedCategory = categoryRepository.save(category);

        return learningEventRepository.save(MerchantLearningEvent.pending(
                owner.getId(), savedMerchant.getId(), savedCategory.getId(),
                statementImportId, sessionId));
    }

    private static ImportDto.VerificationReport verificationReport() {
        return new ImportDto.VerificationReport(List.of(
                new ImportDto.VerificationFinding("BALANCE_CHAIN", "VERIFIED",
                        Map.of("rowsChecked", 124, "rowsWithBalance", 124)),
                new ImportDto.VerificationFinding("SUMMARY_TOTALS", "NOT_APPLICABLE",
                        Map.of("reason", "No printed totals were available for this section, so there "
                                + "was nothing to compare against."))));
    }

    /**
     * A complete synchronous import: staged, verified, confirmed, and it taught the system three
     * merchants, one of which has not landed.
     */
    private String syntheticImport(User owner) {
        Account account = account(owner);
        ImportSession session = stagingSession(owner);
        StatementImport statementImport = statementImport(owner, account, null);

        String reference = analysisRecorder.recordParsed(owner.getId(),
                StatementAnalysisSession.Source.CUSTOMER_IMPORT, "statement.pdf", "PDF", 40960L,
                "FP-TEST-1A9E", 1, 812L,
                ParseDiagnostics.of(124, Map.of("NO_DATE_IN_ANCHOR_COLUMN", 3)), session.getId());
        verificationRecorder.recordForAnalysis(reference, List.of(verificationReport()));

        MerchantLearningEvent first = learningEvent(owner, statementImport.getId(), session.getId());
        first.markCompleted(Instant.now());
        MerchantLearningEvent second = learningEvent(owner, statementImport.getId(), session.getId());
        second.markCompleted(Instant.now());
        MerchantLearningEvent stuck = learningEvent(owner, statementImport.getId(), session.getId());
        for (int i = 0; i < MerchantLearningEvent.MAX_ATTEMPTS; i++) {
            stuck.recordFailure("UNIQUE(user_id, merchant_id, category_id)", Instant.now());
        }
        learningEventRepository.saveAll(List.of(first, second, stuck));

        session.setStatus(ImportSession.STATUS_CONFIRMED);
        session.setConfirmedAt(Instant.now());
        importSessionRepository.save(session);
        return reference;
    }

    // ------------------------------------------------------------------ the criterion

    @Test
    void oneCallAnswersUploadParsingVerificationLearningAndCompletionTogether() {
        // The "single view" clause. Every field asserted here already existed in some table before
        // this work item; what did not exist was any query that could return them together, so a
        // support question meant three lookups and knowing all three tables existed.
        User owner = user();
        String reference = syntheticImport(owner);

        ImportTraceDto.Trace trace = traceService.byAnalysisReference(reference).orElseThrow();

        // Upload and parsing.
        assertThat(trace.analysisReference()).isEqualTo(reference);
        assertThat(trace.analysis().layoutFingerprint()).isEqualTo("FP-TEST-1A9E");
        assertThat(trace.analysis().rowCount()).isEqualTo(124);
        assertThat(trace.analysis().durationMs()).isEqualTo(812L);
        assertThat(trace.analysis().unanchoredReasons()).containsEntry("NO_DATE_IN_ANCHOR_COLUMN", 3);

        // Verification -- previously discarded with the staging response.
        assertThat(trace.verification()).extracting(ImportTraceDto.Finding::rule)
                .containsExactly("BALANCE_CHAIN", "SUMMARY_TOTALS");
        assertThat(trace.verification()).extracting(ImportTraceDto.Finding::outcome)
                .containsExactly("VERIFIED", "NOT_APPLICABLE");

        // Learning.
        assertThat(trace.learning().events()).isEqualTo(3);
        assertThat(trace.learning().byStatus())
                .containsEntry("COMPLETED", 2).containsEntry("FAILED", 1);
        assertThat(trace.learning().outstanding())
                .as("the ones an operator would act on, and only those")
                .singleElement()
                .satisfies(event -> assertThat(event.status()).isEqualTo("FAILED"));

        // Completion.
        assertThat(trace.completion().transactionsImported()).isEqualTo(124);
        assertThat(trace.completion().transactionsSkipped()).isEqualTo(2);
        assertThat(trace.completion().sessionConfirmedAt()).isNotNull();
    }

    @Test
    void theStagingSessionIsWhatTiesLearningBackToTheUpload() {
        // The join V72 adds. merchant_learning_events has carried source_import_session_id since
        // V63 and the analysis row had no way to name it, so "which merchants did this import
        // teach" was a question about timing rather than a join. Removing the id from the analysis
        // row is what this test would catch.
        User owner = user();
        String reference = syntheticImport(owner);

        ImportTraceDto.Trace trace = traceService.byAnalysisReference(reference).orElseThrow();

        assertThat(trace.importSessionId()).isNotNull();
        assertThat(trace.learning().events())
                .as("reached only through that id -- without it this is zero")
                .isEqualTo(3);
    }

    // ------------------------------------------------------------------ per-stage timing

    @Test
    void aJobTraceShowsHowLongEachStageTookAndWhichStagesNeverRan() {
        // The gap this closes: statement_analysis_sessions.duration_ms is the total, so "which
        // stage was slow" had no answer at all. SKIPPED is the half that can prove an optimisation
        // unnecessary -- DEDUPING is not slow on this path, it does not run.
        User owner = user();
        Account account = account(owner);
        ImportJob job = jobStore.enqueue(owner.getId(), "statement.csv",
                "hash-" + UUID.randomUUID(), "objects/x", StatementUpload.Format.CSV);

        stageRecorder.entered(job.getId(), 1, ImportJob.Status.PARSING);
        stageRecorder.completed(job.getId(), 1, ImportJob.Status.PARSING);
        stageRecorder.entered(job.getId(), 1, ImportJob.Status.ANALYZING);
        stageRecorder.completed(job.getId(), 1, ImportJob.Status.ANALYZING);
        stageRecorder.skipped(job.getId(), 1,
                List.of(ImportJob.Status.DEDUPING, ImportJob.Status.IMPORTING, ImportJob.Status.LEARNING));
        verificationRecorder.recordForJob(job.getId(), List.of(verificationReport()));
        statementImport(owner, account, job.getId());

        ImportTraceDto.Trace trace = traceService.byJobId(job.getId()).orElseThrow();

        assertThat(trace.stages()).extracting(ImportTraceDto.Stage::stage)
                .containsExactly("PARSING", "ANALYZING", "DEDUPING", "IMPORTING", "LEARNING");
        assertThat(trace.stages()).filteredOn(stage -> "COMPLETED".equals(stage.outcome()))
                .allSatisfy(stage -> assertThat(stage.durationMs()).isNotNull());
        assertThat(trace.stages()).filteredOn(stage -> "SKIPPED".equals(stage.outcome()))
                .as("a stage that never ran carries no timing, so it cannot be averaged into one")
                .allSatisfy(stage -> assertThat(stage.durationMs()).isNull())
                .hasSize(3);

        assertThat(trace.job().status()).isEqualTo(ImportJob.Status.QUEUED.name());
        assertThat(trace.verification()).hasSize(2);
        assertThat(trace.completion().statementImportId()).isNotNull();
    }

    @Test
    void aStageStillRunningOnAFinishedJobIsVisibleAsSuch() {
        // A worker that died mid-stage. The row's whole reason for being written on entry is that
        // this case leaves evidence naming the stage, rather than leaving nothing.
        User owner = user();
        ImportJob job = jobStore.enqueue(owner.getId(), "statement.csv",
                "hash-" + UUID.randomUUID(), "objects/x", StatementUpload.Format.CSV);
        stageRecorder.entered(job.getId(), 1, ImportJob.Status.ANALYZING);

        ImportTraceDto.Trace trace = traceService.byJobId(job.getId()).orElseThrow();

        assertThat(trace.stages()).singleElement().satisfies(stage -> {
            assertThat(stage.stage()).isEqualTo("ANALYZING");
            assertThat(stage.outcome()).isEqualTo("RUNNING");
            assertThat(stage.endedAt()).isNull();
        });
    }

    // ------------------------------------------------------------------ honest absences

    @Test
    void anImportThatTaughtNothingReportsZeroRatherThanNothing() {
        // Zero learning events is a legitimate outcome -- an import of merchants Finora already
        // knows teaches it nothing -- and must not read the same as "learning was not looked up".
        User owner = user();
        ImportSession session = stagingSession(owner);
        String reference = analysisRecorder.recordParsed(owner.getId(),
                StatementAnalysisSession.Source.CUSTOMER_IMPORT, "statement.csv", "CSV", 2048L,
                "FP-TEST-0B12", 1, 40L, ParseDiagnostics.of(3, Map.of()), session.getId());

        ImportTraceDto.Trace trace = traceService.byAnalysisReference(reference).orElseThrow();

        assertThat(trace.learning().events()).isZero();
        assertThat(trace.learning().outstanding()).isEmpty();
        assertThat(trace.completion().statementImportId())
                .as("staged but never confirmed -- staging successfully and importing are "
                    + "different events")
                .isNull();
    }

    @Test
    void anAdminAnalysisHasNoSessionAndStillTraces() {
        // Layout Studio runs the engine without importing anything, so there is no staging session,
        // no learning and no completion. The trace has to render that as absence rather than fail.
        User owner = user();
        String reference = analysisRecorder.recordParsed(owner.getId(),
                StatementAnalysisSession.Source.ADMIN_ANALYSIS, "statement.pdf", "PDF", 8192L,
                "FP-TEST-77C4", 2, 300L, ParseDiagnostics.of(0, Map.of()));

        ImportTraceDto.Trace trace = traceService.byAnalysisReference(reference).orElseThrow();

        assertThat(trace.importSessionId()).isNull();
        assertThat(trace.job()).isNull();
        assertThat(trace.stages()).isEmpty();
        assertThat(trace.learning().events()).isZero();
        assertThat(trace.completion().statementImportId()).isNull();
        assertThat(trace.analysis().layoutFingerprint()).isEqualTo("FP-TEST-77C4");
    }

    @Test
    void anUnknownHandleIsEmptyRatherThanAnEmptyTrace() {
        // A trace full of nulls for a reference that never existed would read as "this import went
        // very badly" rather than "no such import".
        assertThat(traceService.byAnalysisReference("SA-99999999-9999")).isEmpty();
        assertThat(traceService.byJobId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void aFailedUploadTracesToItsFailureRatherThanToNothing() {
        // The documents most worth tracing are the ones that did not work. V59 exists to record
        // them; this asserts the trace surfaces what it recorded.
        User owner = user();
        String reference = analysisRecorder.recordFailed(owner.getId(),
                StatementAnalysisSession.Source.CUSTOMER_IMPORT, "statement.pdf", "PDF", 8192L,
                "FP-TEST-DEAD", "IMPORT_001", "No transaction table found", 95L,
                ParseDiagnostics.of(0, Map.of("NO_DATE_IN_ANCHOR_COLUMN", 97)));

        ImportTraceDto.Trace trace = traceService.byAnalysisReference(reference).orElseThrow();

        assertThat(trace.analysis().outcome()).isEqualTo("FAILED");
        assertThat(trace.analysis().failureCode()).isEqualTo("IMPORT_001");
        assertThat(trace.analysis().unanchoredReasons())
                .as("on a rejected document the histogram is the only field that says why")
                .containsEntry("NO_DATE_IN_ANCHOR_COLUMN", 97);
        assertThat(trace.verification())
                .as("nothing staged, so no rule ran -- empty, not a fabricated pass")
                .isEmpty();
    }
}
