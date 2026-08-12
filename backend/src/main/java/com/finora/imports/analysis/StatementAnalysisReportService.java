package com.finora.imports.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.dto.ImportDto.FailureCountDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the evidence table. Until now nothing did.
 *
 * <p>{@code statement_analysis_sessions} has been written on every upload since V59 and read by
 * nobody — the queries on {@link StatementAnalysisSessionRepository} existed but had no callers.
 * Evidence that only a person with a database console can see is not much better than evidence
 * that was never collected, and every parser investigation so far has been driven by a throwaway
 * probe printing to a terminal instead.
 *
 * <h2>What these reports deliberately omit</h2>
 * No file name and no user id, matching {@code AdminLayoutIntelligenceController}'s boundary. A
 * statement's file name routinely carries a customer's name, and this is a platform-wide
 * engineering surface rather than a per-user one. The handle for talking about a specific
 * document is its {@code reference} ({@code SA-20260806-0145}) — quotable by support, resolvable
 * by engineering, and meaningless to anyone who does not already have access.
 *
 * <p>That does mean a layout reads as {@code FP-1-7A91D3C2} rather than "HSBC composite". Naming a
 * fingerprint is the job of the admin-curated layout registry, which is knowledge rather than
 * evidence and is a separate table that does not exist yet. Showing the fingerprint is the honest
 * intermediate state; inferring a bank name from the structure here would be a guess presented as
 * a fact.
 *
 * <h2>No thresholds, no verdicts</h2>
 * Counts only. Nothing here decides that a document is "unhealthy" or that a layout needs work —
 * the proportions are for a person to read. A number that silently became a judgement would be
 * the same mistake as a report that prints "0 ms faster" when it actually measured nothing.
 */
@Service
public class StatementAnalysisReportService {

    private static final Logger log = LoggerFactory.getLogger(StatementAnalysisReportService.class);

    /**
     * How many recent analyses the summary aggregates over.
     *
     * <p>Bounded rather than "all rows" because the histogram has to be summed in memory: it is
     * stored as JSON per row, so there is no {@code GROUP BY} that can add it up in the database.
     * A window is honest about that and stays fast as the table grows; the alternative is a report
     * that quietly gets slower every week until someone notices.
     */
    private static final int SUMMARY_WINDOW = 500;

    /** One analysis, as an admin sees it. */
    public record AnalysisView(
            String reference,
            String sourceFormat,
            /** Null when the document failed before it could be characterised. */
            String layoutFingerprint,
            String outcome,
            String failureCode,
            Integer sectionCount,
            /** Null means never measured — not the same as zero. */
            Integer rowCount,
            /** Reason -> count, dominant reason first. Empty when every row anchored. */
            Map<String, Integer> unanchoredReasons,
            int unanchoredRowCount,
            Long durationMs,
            Long byteSize,
            Instant createdAt
    ) {}

    /**
     * The engine at a glance, over the last {@link #SUMMARY_WINDOW} analyses.
     *
     * @param unanchoredReasons every reason seen in the window, summed and ordered by count. This
     *                          is the field that says where parser effort belongs: one dominant
     *                          reason across many documents is a capability, the same reason in a
     *                          single document is that document.
     */
    public record AnalysisSummary(
            int analysesInWindow,
            long totalAnalysesEver,
            long parsed,
            long failed,
            long distinctLayouts,
            long rowsExtractedInWindow,
            int unanchoredRowsInWindow,
            Map<String, Integer> unanchoredReasons
    ) {}

    /**
     * One analysis plus what is already known about its layout.
     *
     * <p>Separate from the list view on purpose: the recurrence counts cost two extra queries per
     * analysis, which is fine for the one document someone opened and would be an N+1 across a
     * page of fifty.
     *
     * @param timesLayoutSeen   including this one. Zero when the document never got far enough to
     *                          be characterised, which is not "never seen before" — it is "there
     *                          is nothing to compare it to".
     * @param timesLayoutFailed how many of those defeated the parser. Read against
     *                          {@code timesLayoutSeen}: 11 of 12 is a layout the engine cannot
     *                          read, 1 of 12 is one odd document.
     */
    public record AnalysisDetail(AnalysisView analysis, long timesLayoutSeen, long timesLayoutFailed) {}

    private final StatementAnalysisSessionRepository repository;
    private final ObjectMapper objectMapper;

    public StatementAnalysisReportService(StatementAnalysisSessionRepository repository,
                                          ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** Most recent first. */
    @Transactional(readOnly = true)
    public List<AnalysisView> recent(int limit) {
        int capped = Math.max(1, Math.min(limit, SUMMARY_WINDOW));
        return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, capped))
                .stream().map(this::toView).toList();
    }

    /** One analysis by its quotable handle, or empty if that reference is unknown. */
    @Transactional(readOnly = true)
    public java.util.Optional<AnalysisView> byReference(String reference) {
        return repository.findByReference(reference).map(this::toView);
    }

    /** The same analysis, plus how often its layout has been seen and how often it failed. */
    @Transactional(readOnly = true)
    public java.util.Optional<AnalysisDetail> detailByReference(String reference) {
        return repository.findByReference(reference).map(session -> {
            String fingerprint = session.getLayoutFingerprint();
            if (fingerprint == null) return new AnalysisDetail(toView(session), 0, 0);
            return new AnalysisDetail(toView(session),
                    repository.countByLayoutFingerprint(fingerprint),
                    repository.countByLayoutFingerprintAndOutcome(fingerprint,
                            StatementAnalysisSession.Outcome.FAILED));
        });
    }

    /**
     * How many customer imports failed, by reason, since {@code since} -- Premium Import
     * Reliability v1, §4. Fits this service's own charter exactly: no file name, no user id,
     * a platform-wide count rather than a per-customer one. The support half of failure analytics
     * (one customer's own recent failures) is deliberately NOT here -- it's
     * {@code StatementAnalysisRecorder.recentCustomerFailures}, which carries a {@code userId} and
     * belongs with the class that already owns that per-user boundary.
     *
     * <p>{@code failureCode} is left untranslated -- the raw stored value ({@code
     * ErrorCode.valueOf(...).name()}, or an exception's simple class name for a codeless failure),
     * not the wire code the customer-facing DTO translates to. This is an internal
     * engineering/support view, and the more precise internal identifier is more useful here than
     * the wire code would be. A null stored value groups under the literal
     * {@code "UNKNOWN_FAILURE"} rather than silently vanishing from the count, since a failure that
     * could not even be classified is exactly the kind this report should not let disappear.
     *
     * <p>{@code since} has no default and no fallback -- an unbounded scan of a table that only
     * grows is a cost this method should never silently absorb on a caller's behalf.
     */
    @Transactional(readOnly = true)
    public List<FailureCountDto> failureCounts(Instant since) {
        return repository.failureCodeCounts(since).stream()
                .map(row -> new FailureCountDto(
                        row[0] == null ? "UNKNOWN_FAILURE" : (String) row[0],
                        (long) row[1],
                        (Instant) row[2]))
                .toList();
    }

    @Transactional(readOnly = true)
    public AnalysisSummary summary() {
        List<AnalysisView> window = recent(SUMMARY_WINDOW);

        Map<String, Integer> combined = new LinkedHashMap<>();
        long rows = 0;
        int unanchored = 0;
        for (AnalysisView view : window) {
            if (view.rowCount() != null) rows += view.rowCount();
            unanchored += view.unanchoredRowCount();
            view.unanchoredReasons().forEach((reason, count) -> combined.merge(reason, count, Integer::sum));
        }

        return new AnalysisSummary(
                window.size(),
                repository.count(),
                repository.countByOutcome(StatementAnalysisSession.Outcome.PARSED),
                repository.countByOutcome(StatementAnalysisSession.Outcome.FAILED),
                repository.countDistinctLayouts(),
                rows,
                unanchored,
                byCountDescending(combined));
    }

    /**
     * The same view, from a row the caller already has.
     *
     * <p>Public so the unified import trace can embed this shape rather than assembling a second
     * one beside it. That is the reasoning {@code AdminStatementAnalysisController} already applies
     * when it reads an analysis back instead of building the response inline: one shape, one code
     * path, no chance of two surfaces drifting into disagreeing about the same row.
     */
    public AnalysisView viewOf(StatementAnalysisSession session) {
        return toView(session);
    }

    private AnalysisView toView(StatementAnalysisSession session) {
        Map<String, Integer> reasons = readHistogram(session);
        int unanchored = 0;
        for (int count : reasons.values()) unanchored += count;
        return new AnalysisView(
                session.getReference(),
                session.getSourceFormat(),
                session.getLayoutFingerprint(),
                session.getOutcome() == null ? null : session.getOutcome().name(),
                session.getFailureCode(),
                session.getSectionCount(),
                session.getRowCount(),
                reasons,
                unanchored,
                session.getDurationMs(),
                session.getByteSize(),
                session.getCreatedAt());
    }

    /**
     * Unreadable JSON degrades one field rather than failing the report.
     *
     * <p>A row written before V60 has no histogram at all, and a row written by a future version
     * may hold a shape this one does not expect. Neither is a reason to refuse to show the
     * fingerprint, outcome and row count sitting next to it.
     */
    private Map<String, Integer> readHistogram(StatementAnalysisSession session) {
        String json = session.getUnanchoredReasonsJson();
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Integer> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            log.warn("Analysis {} has unreadable unanchored-row diagnostics; reporting the rest of "
                    + "the row without them", session.getReference(), e);
            return Map.of();
        }
    }

    /** Same ordering contract as {@link ParseDiagnostics}: dominant reason first, ties by name. */
    private static Map<String, Integer> byCountDescending(Map<String, Integer> raw) {
        if (raw.isEmpty()) return Map.of();
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(raw.entrySet());
        entries.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : entries) ordered.put(entry.getKey(), entry.getValue());
        return Collections.unmodifiableMap(ordered);
    }
}
