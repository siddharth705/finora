package com.finora.imports.trace;

import com.finora.imports.analysis.StatementAnalysisReportService.AnalysisView;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One import, end to end, in one shape.
 *
 * <p>Milestone 2's sixth success criterion: <i>an administrator can trace one import from upload
 * through parsing, verification, learning and completion in a single view, without a log or an
 * engineer.</i> Everything here already existed somewhere; what did not exist was a query that could
 * put it together, because {@code import_jobs}, {@code statement_analysis_sessions} and
 * {@code merchant_learning_events} were keyed on things that never met.
 *
 * <h2>Assembled, not aggregated</h2>
 *
 * <p>There is no health field, no score and no overall verdict. Each block reports what its own
 * table recorded and the reader draws the conclusion — the same position {@code VerificationReport}
 * takes about combining rules and {@code StatementAnalysisReportService} takes about counts. A
 * summary judgement here would need a weighting policy nothing can calibrate, and a number that
 * silently became a verdict is the failure mode both of those were written to avoid.
 *
 * <h2>What it deliberately omits</h2>
 *
 * <p>No file name and no user id, matching {@code AdminStatementAnalysisController}'s boundary: a
 * statement's file name routinely carries a customer's name, and this is a platform engineering
 * surface rather than a per-user one. The handles are the analysis {@code reference}
 * ({@code SA-20260806-0145}) and the job id.
 *
 * <p>Learning events are reported as counts plus the outstanding ones, with no merchant or category
 * ids and no error text. Those belong to the Merchant Review Center and its own
 * {@code LEARNING_QUEUE_MANAGE} permission; this view says how many an import produced and how many
 * have not landed, which is the question a trace is being read to answer.
 */
public final class ImportTraceDto {

    private ImportTraceDto() {
        // Namespace for the nested records, per CODING_STANDARDS' DTO convention.
    }

    /**
     * @param analysis   the upload's evidence row, reusing the shape the analyses endpoint already
     *                   serves. Null for an asynchronous job, which stages without recording one —
     *                   see {@link ImportTraceService} for why that is stated rather than papered
     *                   over.
     * @param job        the queue row. Null for the synchronous path, which has no job.
     * @param stages     per-stage timing, in the order the stages were recorded. Empty for the
     *                   synchronous path, whose stages are not separately timed.
     * @param verification every rule that ran, with what it found. Empty means no verification was
     *                   recorded, which is not the same as every rule passing.
     */
    public record Trace(
            String analysisReference,
            UUID importJobId,
            UUID importSessionId,
            String correlationId,
            AnalysisView analysis,
            Job job,
            List<Stage> stages,
            List<Finding> verification,
            Learning learning,
            Completion completion
    ) {}

    /**
     * The queue row, minus everything that identifies a person.
     *
     * @param totalDurationMs queued to finished — what the person who uploaded actually waited,
     *                        which is a different number from any single stage's duration and from
     *                        the analysis session's parse time
     */
    public record Job(
            String status,
            int attemptCount,
            Integer rowsTotal,
            int rowsProcessed,
            String lastError,
            Instant queuedAt,
            Instant startedAt,
            Instant finishedAt,
            Long totalDurationMs
    ) {}

    /**
     * One stage of one attempt.
     *
     * @param outcome RUNNING, COMPLETED, FAILED or SKIPPED. A stage still RUNNING on a job that has
     *                finished is a worker that died inside it; a SKIPPED stage is one that did not
     *                run at all, which is the observation that can prove optimising it unnecessary.
     */
    public record Stage(
            String stage,
            int attempt,
            String outcome,
            Instant startedAt,
            Instant endedAt,
            Long durationMs
    ) {}

    /**
     * One verification rule's outcome.
     *
     * @param details structural facts only — counts, booleans and bounded enum constants. Balances,
     *                totals and raw cell values are absent by construction; see
     *                {@code ImportVerificationRecorder}.
     */
    public record Finding(
            int sectionIndex,
            String rule,
            String outcome,
            Map<String, Object> details,
            Instant recordedAt
    ) {}

    /**
     * What the import taught the system.
     *
     * @param events      how many learning events it produced. Zero is a legitimate answer: an
     *                    import of merchants Finora already knew teaches it nothing.
     * @param byStatus    PENDING / PROCESSING / COMPLETED / FAILED, counted
     * @param outstanding the ones that have not completed, bounded. These are the only ones anyone
     *                    acts on, and a large import's completed events would otherwise be most of
     *                    the response.
     */
    public record Learning(
            int events,
            Map<String, Integer> byStatus,
            List<LearningEvent> outstanding
    ) {}

    /** No merchant id, no category id, no error text — see the class comment. */
    public record LearningEvent(
            UUID id,
            String status,
            int attemptCount,
            Instant createdAt
    ) {}

    /**
     * Whether it landed, and what landed.
     *
     * @param statementImportId null when nothing was confirmed. Staging successfully and importing
     *                          are different events, and a job reaching COMPLETED means only the
     *                          first — confirming is still the user's decision.
     */
    public record Completion(
            UUID statementImportId,
            Integer transactionsImported,
            Integer transactionsSkipped,
            Instant importedAt,
            Instant sessionConfirmedAt
    ) {}
}
