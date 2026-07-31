package com.finora.health;

import com.finora.repository.StatementImportRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The statement import pipeline's real health signal -- see StatementImportRepository
 * .countWithSkippedRowsAfter()'s doc comment for why this reports on skip RATE rather than a
 * "failed" count that nothing in this codebase actually produces yet (a parse failure today
 * throws synchronously and never persists a row at all, so there's no FAILED status to count).
 * Absence of imports in the window is reported as UP, not DOWN or DEGRADED -- "nobody imported a
 * statement in the last 24h" is not an unhealthy pipeline, it's just quiet.
 */
@Component
public class StatementImportHealthProvider implements HealthProvider {

    // Same style of round-number threshold as ConfidenceEngine's HIGH/MEDIUM/LOW bands elsewhere
    // in this codebase -- revisit with real usage data once this pipeline has actual production
    // traffic to tune against.
    private static final double DEGRADED_SKIP_RATE = 0.25;
    private static final long WINDOW_HOURS = 24;

    private final StatementImportRepository statementImportRepository;

    public StatementImportHealthProvider(StatementImportRepository statementImportRepository) {
        this.statementImportRepository = statementImportRepository;
    }

    @Override
    public String name() {
        return "Statement Import Pipeline";
    }

    @Override
    public String category() {
        return "Financial Intelligence";
    }

    @Override
    public HealthCheckResult check() {
        Instant since = Instant.now().minus(WINDOW_HOURS, ChronoUnit.HOURS);
        long total = statementImportRepository.countByImportedAtAfter(since);
        long withSkips = statementImportRepository.countWithSkippedRowsAfter(since);

        if (total == 0) {
            return HealthCheckResult.up("No imports in the last " + WINDOW_HOURS + "h");
        }

        double skipRate = (double) withSkips / total;
        String detail = total + " import" + (total == 1 ? "" : "s") + " in the last " + WINDOW_HOURS
                + "h, " + withSkips + " with skipped rows";
        return skipRate >= DEGRADED_SKIP_RATE ? HealthCheckResult.degraded(detail) : HealthCheckResult.up(detail);
    }
}
