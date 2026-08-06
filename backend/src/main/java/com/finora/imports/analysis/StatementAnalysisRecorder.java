package com.finora.imports.analysis;

import com.finora.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

    public StatementAnalysisRecorder(StatementAnalysisSessionRepository repository) {
        this.repository = repository;
    }

    /** @return the reference of the recorded session, or null if recording failed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String recordParsed(UUID userId, StatementAnalysisSession.Source source, String fileName,
                                String sourceFormat, long byteSize, String layoutFingerprint,
                                int sectionCount, long durationMs) {
        try {
            var session = StatementAnalysisSession.parsed(nextReference(), userId, source, fileName,
                    sourceFormat, byteSize, layoutFingerprint, sectionCount, durationMs);
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
                                String failureCode, String failureDetail, long durationMs) {
        try {
            var session = StatementAnalysisSession.failed(nextReference(), userId, source, fileName,
                    sourceFormat, byteSize, layoutFingerprint, failureCode, truncate(failureDetail),
                    durationMs);
            return repository.save(session).getReference();
        } catch (RuntimeException e) {
            log.error("Could not record a FAILED analysis session for {} ({}) -- the layout that "
                    + "defeated the parser is now unrecorded", fileName, failureCode, e);
            return null;
        }
    }

    /**
     * {@code SA-20260806-0145}. The date makes it readable at a glance; the sequence makes it
     * unique without a per-day counter that two concurrent uploads could race on.
     */
    private String nextReference() {
        long seq = repository.nextReferenceNumber();
        return "SA-" + LocalDate.now(ZoneOffset.UTC).format(DAY) + "-" + String.format("%04d", seq % 10_000);
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
