package com.finora.imports.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.dto.ImportDto.ImportFailureSummaryDto;
import com.finora.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Writes the evidence row for one upload attempt, in its own transaction, without ever breaking
 * the upload.
 *
 * <h2>Why {@code REQUIRES_NEW}, and why a separate bean</h2>
 * The interesting case is the failing one, and a failure is reported by throwing
 * {@link ApiException}. That is a {@code RuntimeException}, so the caller's transaction is marked
 * rollback-only and everything written inside it is discarded — including this row. The result
 * would be a table that records successes perfectly and silently loses every failure, which is the
 * exact opposite of why it exists.
 *
 * <p>This is not hypothetical. The same shape had already produced a real security bug in this
 * codebase: reuse detection wrote an account-wide revocation, threw to reject the request, and the
 * revocation was rolled back — the API said "all sessions have been signed out" while nothing had
 * been. It was fixed there with {@code noRollbackFor}, and the note left on
 * {@code AuthService.refresh} says why that is the weaker answer: it binds to a transaction
 * BOUNDARY, so every future caller has to remember to repeat it. Here the write genuinely is
 * independent of the operation reporting the failure, so it gets its own transaction and cannot be
 * undone by anyone's rollback rules.
 *
 * <p>A separate {@code @Component} because Spring proxies calls between beans, not calls a bean
 * makes to itself — {@code REQUIRES_NEW} on a private helper of {@code ImportService} would be
 * silently ignored, which is the failure mode this whole class is written to avoid.
 *
 * <h2>Recording must never break an import</h2>
 * Every method swallows its own exceptions and logs at ERROR. Losing one evidence row is a
 * measurement gap; failing a user's statement import because a telemetry insert failed is a
 * product outage. The log line is the compensating control — a burst of them means the evidence is
 * incomplete and the reports built on it should not be trusted for that window.
 */
@Component
public class StatementAnalysisRecorder {

    private static final Logger log = LoggerFactory.getLogger(StatementAnalysisRecorder.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StatementAnalysisSessionRepository repository;
    private final ObjectMapper objectMapper;

    public StatementAnalysisRecorder(StatementAnalysisSessionRepository repository,
                                     ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** @return the reference of the recorded session, or null if recording failed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String recordParsed(UUID userId, StatementAnalysisSession.Source source, String fileName,
                                String sourceFormat, long byteSize, String layoutFingerprint,
                                int sectionCount, long durationMs, ParseDiagnostics diagnostics) {
        return recordParsed(userId, source, fileName, sourceFormat, byteSize, layoutFingerprint,
                sectionCount, durationMs, diagnostics, null);
    }

    /**
     * The same, naming the staging session this upload produced.
     *
     * <p>That id is what turns three tables that merely coexist into a trace: {@code
     * merchant_learning_events} has carried {@code source_import_session_id} since V63, so recording
     * it here is the whole of what "which merchants did this import teach" needed. Callers with no
     * session — the admin analysis path, which deliberately creates none — use the overload above.
     *
     * @return the reference of the recorded session, or null if recording failed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String recordParsed(UUID userId, StatementAnalysisSession.Source source, String fileName,
                                String sourceFormat, long byteSize, String layoutFingerprint,
                                int sectionCount, long durationMs, ParseDiagnostics diagnostics,
                                UUID importSessionId) {
        try {
            var session = StatementAnalysisSession.parsed(nextReference(), userId, source, fileName,
                    sourceFormat, byteSize, layoutFingerprint, sectionCount, durationMs,
                    diagnostics.rowCount(), writeHistogram(diagnostics, fileName),
                    importSessionId, currentCorrelationId());
            return repository.save(session).getReference();
        } catch (RuntimeException e) {
            log.error("Could not record a parsed analysis session for {} -- layout evidence for "
                    + "this upload is lost", fileName, e);
            return null;
        }
    }

    /** @return the reference of the recorded session, or null if recording failed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String recordFailed(UUID userId, StatementAnalysisSession.Source source, String fileName,
                                String sourceFormat, long byteSize, String layoutFingerprint,
                                String failureCode, String failureDetail, long durationMs,
                                ParseDiagnostics diagnostics) {
        try {
            var session = StatementAnalysisSession.failed(nextReference(), userId, source, fileName,
                    sourceFormat, byteSize, layoutFingerprint, failureCode, truncate(failureDetail),
                    durationMs, diagnostics.rowCount(), writeHistogram(diagnostics, fileName),
                    currentCorrelationId());
            return repository.save(session).getReference();
        } catch (RuntimeException e) {
            log.error("Could not record a FAILED analysis session for {} ({}) -- the layout that "
                    + "defeated the parser is now unrecorded", fileName, failureCode, e);
            return null;
        }
    }

    /**
     * A user's own recent failed imports -- Premium Import Reliability v1, §2.1's durable failure
     * record. Lives here rather than in a new service because this class is already the one place
     * that owns {@code StatementAnalysisSessionRepository} outside the admin-only
     * {@link StatementAnalysisReportService}, and CODING_STANDARDS.md keeps repository access
     * behind a service rather than letting a controller reach into it directly.
     *
     * <p>Filtered to {@code CUSTOMER_IMPORT} + {@code FAILED} and mapped straight to the
     * customer-facing DTO here, not in the controller: {@code failureDetail} can carry a fragment
     * of the document that defeated the parser (see {@link #truncate}), so keeping the
     * entity-to-DTO mapping in the one place that also writes the row is what guarantees that
     * field can never leak into a customer response by a future caller forgetting to re-apply the
     * same narrow projection.
     *
     * <p>Bug fix, caught by a post-commit review rather than by any test at the time: {@code
     * failureCode} on the entity is {@link ImportService#recordParseFailure}'s {@code
     * ApiException.getCode().name()} -- the Java enum IDENTIFIER ({@code "IMPORT_NO_HEADER_DETECTED"},
     * {@code "IMPORT_CORRUPT_PDF"}) -- not the wire code ({@code "IMPORT_001"}, {@code "IMPORT_011"})
     * the frontend's failure-UX contract (importFailureMessages.ts) is keyed by. Handing the raw
     * enum name to a customer response meant every single row in the failures list fell through to
     * that contract's generic fallback, silently, since the lookup never threw -- it just never
     * matched. {@link #wireCodeOf} translates at this one boundary, so the customer-facing DTO
     * carries what a customer-facing consumer actually expects, while the entity/database keep
     * recording the enum name unchanged (every existing admin histogram and analytics query is
     * already built on that value and must not move out from under them).
     */
    public List<ImportFailureSummaryDto> recentCustomerFailures(UUID userId, int limit) {
        return repository.findByUserIdAndSourceAndOutcomeOrderByCreatedAtDesc(userId,
                        StatementAnalysisSession.Source.CUSTOMER_IMPORT, StatementAnalysisSession.Outcome.FAILED,
                        PageRequest.of(0, limit))
                .stream()
                .map(s -> new ImportFailureSummaryDto(s.getReference(), s.getFileName(), wireCodeOf(s.getFailureCode()), s.getCreatedAt()))
                .toList();
    }

    /**
     * The wire code ({@code "IMPORT_001"}) for a stored {@code failureCode} that is really an
     * {@link com.finora.exception.ErrorCode} enum name ({@code "IMPORT_NO_HEADER_DETECTED"}) --
     * see {@link #recentCustomerFailures}'s doc comment for why this translation exists at all.
     * Delegates to {@link com.finora.exception.ErrorCode#wireCodeOrNull}, extracted there once a
     * second table ({@code ImportJob.failureCode}, Premium Import Reliability v1, §3.1) needed the
     * identical translation.
     */
    private static String wireCodeOf(String storedFailureCode) {
        return com.finora.exception.ErrorCode.wireCodeOrNull(storedFailureCode);
    }

    /**
     * The correlation id in flight, read rather than passed in.
     *
     * <p>Taking it from MDC is what stops it drifting from the id the logs actually used: a
     * parameter would be one more thing a call site can forget or fill in from the wrong variable,
     * and an evidence row pointing at a correlation id that appears in no log line is worse than one
     * pointing at nothing. The prefix ({@code request-}, {@code worker-}, {@code scheduler-}) comes
     * along with it, so the row also records where the upload came from.
     *
     * <p>Bounded to the column width here rather than trusted: the key is writable by any filter.
     */
    private String currentCorrelationId() {
        String id = org.slf4j.MDC.get(com.finora.config.CorrelationIdFilter.MDC_KEY);
        if (id == null || id.isBlank()) return null;
        return id.length() <= 64 ? id : id.substring(0, 64);
    }

    /**
     * {@code SA-20260806-0145}. The date makes it readable at a glance; the sequence makes it
     * unique without a per-day counter that two concurrent uploads could race on.
     *
     * <p>Bug fix: this used {@code seq % 10_000}, which truncated an unbounded database sequence
     * to four digits, so the 10,001st reference in a calendar day repeated the 1st. The column is
     * {@code VARCHAR(24) NOT NULL UNIQUE} (V59), so the insert was then rejected, the surrounding
     * {@code catch (RuntimeException)} logged "the layout that defeated the parser is now
     * unrecorded" and returned null -- the table quietly stopped accepting the evidence V59
     * exists to collect ("Every upload leaves a record, whether or not it ever becomes an
     * import"). The modulo was never a column-width constraint: VARCHAR(24) leaves ample room,
     * and the doc comment's own claim that "the sequence makes it unique" is only true once the
     * sequence is allowed to be the value.
     *
     * <p>{@code %04d} is kept as a minimum width, so everyday references still read
     * {@code SA-20260806-0145} and only genuinely large sequences grow past four digits.
     */
    private String nextReference() {
        long seq = repository.nextReferenceNumber();
        return "SA-" + LocalDate.now(ZoneOffset.UTC).format(DAY) + "-" + String.format("%04d", seq);
    }

    /**
     * The histogram as JSON, or null if it is empty or could not be written.
     *
     * <p>Serialisation gets its own try/catch rather than riding on the caller's: the enclosing
     * catch would abandon the whole row over a diagnostics problem, throwing away the fingerprint,
     * the outcome and the failure code — every field that already worked — to save a field that
     * did not. Losing the histogram degrades a measurement; losing the row loses the observation.
     *
     * <p>Empty serialises to null, not {@code "{}"}. "Every row anchored" is the healthy case and
     * it should read as absence, not as a document with an empty finding.
     */
    private String writeHistogram(ParseDiagnostics diagnostics, String fileName) {
        if (diagnostics == null || diagnostics.unanchoredReasons().isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(diagnostics.unanchoredReasons());
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Could not serialise unanchored-row diagnostics for {} -- the rest of the "
                    + "analysis row is still being written without them", fileName, e);
            return null;
        }
    }

    /**
     * A parser failure message can carry a chunk of the document it choked on, and this table is
     * explicitly not a place statement content goes.
     */
    private String truncate(String detail) {
        if (detail == null) return null;
        return detail.length() <= 500 ? detail : detail.substring(0, 500) + "…";
    }
}
