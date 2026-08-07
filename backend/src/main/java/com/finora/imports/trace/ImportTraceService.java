package com.finora.imports.trace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.entity.ImportJob;
import com.finora.entity.ImportSession;
import com.finora.entity.MerchantLearningEvent;
import com.finora.entity.StatementImport;
import com.finora.imports.analysis.ImportVerificationFinding;
import com.finora.imports.analysis.ImportVerificationFindingRepository;
import com.finora.imports.analysis.StatementAnalysisReportService;
import com.finora.imports.analysis.StatementAnalysisSession;
import com.finora.imports.analysis.StatementAnalysisSessionRepository;
import com.finora.imports.jobs.ImportJobStage;
import com.finora.imports.jobs.ImportJobStageRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.StatementImportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Assembles one import's whole story from the tables that already record it.
 *
 * <p>Milestone 2 item 6 is <b>completing</b> observability, not building it. Two thirds of what a
 * support question needs was already recorded and the remaining third was the join: {@code
 * import_jobs}, {@code statement_analysis_sessions} and {@code merchant_learning_events} were keyed
 * on things that never met, so answering "what happened to this import" meant three queries and
 * knowing all three tables exist. This class is that join. It computes nothing the tables do not
 * already say.
 *
 * <h2>Two handles, one shape</h2>
 *
 * <p>An import is identified by an analysis reference ({@code SA-20260806-0145}) on the synchronous
 * path and by a job id on the asynchronous one. Both resolve to the same {@link ImportTraceDto.Trace}
 * so an operator does not have to know which path an upload took before they can look it up — which
 * is exactly the knowledge the criterion says they should not need.
 *
 * <h2>What is honestly absent</h2>
 *
 * <p>The asynchronous worker stages through {@code parseAndStageAnyFormat}, which records no
 * analysis session and creates no import session. A job-anchored trace therefore has stages and
 * verification but no {@code analysis} block, and the trace says so by leaving the field null rather
 * than by fabricating a thin row. Recording an analysis session on the worker path would need the
 * document context the worker never sees, which is a change to the staging API and not part of
 * closing these three gaps.
 *
 * <h2>The layout seam</h2>
 *
 * <p>A layout appears here as its fingerprint, carried through from the analysis view unchanged.
 * Turning {@code FP-1-7A91D3C2} into "HSBC composite" is the layout registry's job — knowledge
 * rather than evidence, a separate table, and deliberately not read from here. When it exists, it
 * resolves the fingerprint this trace already carries; nothing in this class needs to change for
 * that, and nothing in it reaches into layout intelligence today.
 */
@Service
public class ImportTraceService {

    private static final Logger log = LoggerFactory.getLogger(ImportTraceService.class);

    /**
     * How many outstanding learning events one trace lists.
     *
     * <p>Bounded because a large import can produce hundreds and the list is there to be acted on,
     * not counted — the count is already a field beside it. An unbounded list would make the
     * response size a function of the import's size, which is how a diagnostic endpoint becomes the
     * thing you cannot open during an incident.
     */
    private static final int OUTSTANDING_LEARNING_LIMIT = 50;

    private final StatementAnalysisSessionRepository analysisRepository;
    private final StatementAnalysisReportService analysisReportService;
    private final ImportVerificationFindingRepository verificationRepository;
    private final ImportJobRepository jobRepository;
    private final ImportJobStageRepository stageRepository;
    private final ImportSessionRepository importSessionRepository;
    private final MerchantLearningEventRepository learningEventRepository;
    private final StatementImportRepository statementImportRepository;
    private final ObjectMapper objectMapper;

    public ImportTraceService(StatementAnalysisSessionRepository analysisRepository,
                              StatementAnalysisReportService analysisReportService,
                              ImportVerificationFindingRepository verificationRepository,
                              ImportJobRepository jobRepository,
                              ImportJobStageRepository stageRepository,
                              ImportSessionRepository importSessionRepository,
                              MerchantLearningEventRepository learningEventRepository,
                              StatementImportRepository statementImportRepository,
                              ObjectMapper objectMapper) {
        this.analysisRepository = analysisRepository;
        this.analysisReportService = analysisReportService;
        this.verificationRepository = verificationRepository;
        this.jobRepository = jobRepository;
        this.stageRepository = stageRepository;
        this.importSessionRepository = importSessionRepository;
        this.learningEventRepository = learningEventRepository;
        this.statementImportRepository = statementImportRepository;
        this.objectMapper = objectMapper;
    }

    /** The trace for an upload, by the handle support quotes. */
    @Transactional(readOnly = true)
    public Optional<ImportTraceDto.Trace> byAnalysisReference(String reference) {
        return analysisRepository.findByReference(reference).map(analysis -> {
            UUID sessionId = analysis.getImportSessionId();
            // The job, if this upload went through the queue. Guarded rather than passed straight
            // through: a derived query matches IS NULL, and findByImportSessionId(null) would
            // return every job that never recorded a session.
            ImportJob job = sessionId == null ? null
                    : jobRepository.findByImportSessionId(sessionId).stream().findFirst().orElse(null);
            return assemble(analysis, job, sessionId);
        });
    }

    /** The same trace, for an upload that went through the asynchronous queue. */
    @Transactional(readOnly = true)
    public Optional<ImportTraceDto.Trace> byJobId(UUID jobId) {
        return jobRepository.findById(jobId).map(job -> {
            UUID sessionId = job.getImportSessionId();
            StatementAnalysisSession analysis = sessionId == null ? null
                    : analysisRepository.findByImportSessionIdOrderByCreatedAtDesc(sessionId)
                            .stream().findFirst().orElse(null);
            return assemble(analysis, job, sessionId);
        });
    }

    /**
     * One trace from whichever of the two anchors were found.
     *
     * <p>Both are nullable and at least one is always present — the callers above each resolve their
     * own before getting here. Everything else hangs off them, so a missing block reads as "this
     * path does not record that" rather than as an error.
     */
    private ImportTraceDto.Trace assemble(StatementAnalysisSession analysis, ImportJob job, UUID sessionId) {
        List<ImportTraceDto.Finding> verification = analysis != null
                ? findings(verificationRepository
                        .findByAnalysisSessionIdOrderBySectionIndexAscRuleAsc(analysis.getId()))
                : job != null
                        ? findings(verificationRepository
                                .findByImportJobIdOrderBySectionIndexAscRuleAsc(job.getId()))
                        : List.<ImportTraceDto.Finding>of();

        List<ImportTraceDto.Stage> stages = job == null ? List.of()
                : stageRepository.findByJobIdOrderByRecordedAtAsc(job.getId()).stream()
                        .map(ImportTraceService::stage).toList();

        StatementImport statementImport = resolveStatementImport(job, sessionId);

        return new ImportTraceDto.Trace(
                analysis == null ? null : analysis.getReference(),
                job == null ? null : job.getId(),
                sessionId,
                correlationIdOf(analysis, job),
                analysis == null ? null : analysisReportService.viewOf(analysis),
                job == null ? null : job(job),
                stages,
                verification,
                learning(sessionId, statementImport),
                completion(statementImport, sessionId));
    }

    /**
     * The correlation id, preferring the analysis row's.
     *
     * <p>They are different ids and both are real. The job's is the worker <em>pass</em>, shared by
     * every job that pass claimed; the analysis row's is the request or pass that actually parsed
     * this document. The narrower one is the one an operator wants in a log search, so it wins.
     */
    private static String correlationIdOf(StatementAnalysisSession analysis, ImportJob job) {
        if (analysis != null && analysis.getCorrelationId() != null) return analysis.getCorrelationId();
        return job == null ? null : job.getCorrelationId();
    }

    /**
     * The statement import this upload produced, if it produced one.
     *
     * <p>Two routes, because the two paths record different things. A job names its import directly
     * (V67's unique index). A synchronous confirm records no link back to the staging session, so
     * the only honest route is through the learning events that carry both ids — which means an
     * import that taught the system nothing reports no statement import even though one exists.
     * That is a real limitation and it is stated rather than guessed around: closing it needs a
     * column on {@code statement_imports}, which the confirm path owns.
     */
    private StatementImport resolveStatementImport(ImportJob job, UUID sessionId) {
        if (job != null) {
            Optional<StatementImport> byJob = statementImportRepository.findByImportJobId(job.getId());
            if (byJob.isPresent()) return byJob.get();
        }
        if (sessionId == null) return null;
        return learningEventRepository.findBySourceImportSessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(MerchantLearningEvent::getSourceStatementImportId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .flatMap(statementImportRepository::findById)
                .orElse(null);
    }

    private static ImportTraceDto.Job job(ImportJob job) {
        Long total = job.getFinishedAt() == null || job.getCreatedAt() == null ? null
                : Math.max(0, Duration.between(job.getCreatedAt(), job.getFinishedAt()).toMillis());
        return new ImportTraceDto.Job(
                job.getStatus() == null ? null : job.getStatus().name(),
                job.getAttemptCount(),
                job.getRowsTotal(),
                job.getRowsProcessed(),
                job.getLastError(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getFinishedAt(),
                total);
    }

    private static ImportTraceDto.Stage stage(ImportJobStage stage) {
        return new ImportTraceDto.Stage(
                stage.getStage() == null ? null : stage.getStage().name(),
                stage.getAttempt(),
                stage.getOutcome() == null ? null : stage.getOutcome().name(),
                stage.getStartedAt(),
                stage.getEndedAt(),
                stage.getDurationMs());
    }

    private List<ImportTraceDto.Finding> findings(List<ImportVerificationFinding> rows) {
        return rows.stream()
                .map(row -> new ImportTraceDto.Finding(
                        row.getSectionIndex(), row.getRule(), row.getOutcome(),
                        readDetails(row), row.getCreatedAt()))
                .toList();
    }

    /**
     * Unreadable details degrade one field rather than failing the trace.
     *
     * <p>Same call {@code StatementAnalysisReportService} makes about the unanchored-reason
     * histogram: a row written by a future version may hold a shape this one does not expect, and
     * that is no reason to refuse to show the rule name and outcome sitting beside it.
     */
    private Map<String, Object> readDetails(ImportVerificationFinding row) {
        String json = row.getDetailsJson();
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            log.warn("Verification finding {} has unreadable details; reporting the rest of the "
                    + "finding without them", row.getId(), e);
            return Map.of();
        }
    }

    private ImportTraceDto.Learning learning(UUID sessionId, StatementImport statementImport) {
        List<MerchantLearningEvent> events = List.of();
        if (sessionId != null) {
            events = learningEventRepository.findBySourceImportSessionIdOrderByCreatedAtAsc(sessionId);
        }
        if (events.isEmpty() && statementImport != null) {
            // The direct-file path never had a session, so the statement import is the only key. The
            // two are not summed: an import has one of them, never both populated with different
            // sets.
            events = learningEventRepository
                    .findBySourceStatementImportIdOrderByCreatedAtAsc(statementImport.getId());
        }

        Map<String, Integer> byStatus = new LinkedHashMap<>();
        List<ImportTraceDto.LearningEvent> outstanding = new ArrayList<>();
        for (MerchantLearningEvent event : events) {
            String status = event.getStatus() == null ? "UNKNOWN" : event.getStatus().name();
            byStatus.merge(status, 1, Integer::sum);
            if (event.getStatus() != MerchantLearningEvent.Status.COMPLETED
                    && outstanding.size() < OUTSTANDING_LEARNING_LIMIT) {
                outstanding.add(new ImportTraceDto.LearningEvent(
                        event.getId(), status, event.getAttemptCount(), event.getCreatedAt()));
            }
        }
        return new ImportTraceDto.Learning(events.size(),
                java.util.Collections.unmodifiableMap(byStatus), List.copyOf(outstanding));
    }

    private ImportTraceDto.Completion completion(StatementImport statementImport, UUID sessionId) {
        Instant confirmedAt = sessionId == null ? null
                : importSessionRepository.findById(sessionId)
                        .map(ImportSession::getConfirmedAt)
                        .orElse(null);
        if (statementImport == null) {
            return new ImportTraceDto.Completion(null, null, null, null, confirmedAt);
        }
        return new ImportTraceDto.Completion(
                statementImport.getId(),
                statementImport.getTransactionsImported(),
                statementImport.getTransactionsSkipped(),
                statementImport.getImportedAt(),
                confirmedAt);
    }
}
