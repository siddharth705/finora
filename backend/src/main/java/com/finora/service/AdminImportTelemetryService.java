package com.finora.service;

import com.finora.dto.ImportTelemetryDto;
import com.finora.imports.ImportReliabilityStatus;
import com.finora.repository.ImportJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The aggregate view over the trust telemetry V141 records.
 *
 * <p>Same "cheap live aggregate, not a reporting subsystem" shape as
 * {@link AdminLearningStatsService}: a handful of grouped counts answered from the table on
 * request, with nothing materialised and nothing scheduled.
 *
 * <p><b>Counts, never rates.</b> Every number here is a count, and the denominator is returned
 * alongside them rather than divided in. A percentage computed here would bake in one choice of
 * denominator and then hide it behind a decimal point -- and choosing that denominator wrongly is
 * the specific error this phase exists to avoid, since telemetry lands only on completed imports
 * and only on those parsed after V141.
 */
@Service
public class AdminImportTelemetryService {

    /**
     * How many deploys the parser-version breakdown returns.
     *
     * <p>A deploy SHA changes on every commit, so over a long window this degenerates into a long
     * tail of one-import buckets. A calibration question is always about recent behaviour.
     */
    static final int PARSER_VERSION_LIMIT = 20;

    private final ImportJobRepository repository;

    public AdminImportTelemetryService(ImportJobRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ImportTelemetryDto.Summary summary() {
        // Every declared status starts at zero, so one nothing has hit yet reads as a real zero
        // rather than as an absent key the caller has to interpret.
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (ImportReliabilityStatus status : ImportReliabilityStatus.values()) {
            byStatus.put(status.name(), 0L);
        }

        long completedJobs = 0;
        long predatesTelemetry = 0;
        for (Object[] row : repository.telemetryStatusCounts()) {
            String status = (String) row[0];
            long count = toLong(row[1]);
            completedJobs += count;
            if (status == null) {
                predatesTelemetry = count;
            } else {
                byStatus.put(status, count);
            }
        }

        Map<String, Long> byTextSource = new LinkedHashMap<>();
        for (Object[] row : repository.telemetryTextSourceCounts()) {
            // A recorded import can still carry no text source: the aggregation reports one only
            // when a section did. Named rather than dropped, so these counts still sum.
            String source = row[0] == null ? "UNKNOWN" : (String) row[0];
            byTextSource.merge(source, toLong(row[1]), Long::sum);
        }

        long headerUncertain = 0;
        long withFailed = 0;
        long withWarning = 0;
        List<Object[]> flags = repository.telemetryFlagCounts();
        if (!flags.isEmpty()) {
            Object[] row = flags.get(0);
            headerUncertain = toLong(row[0]);
            withFailed = toLong(row[1]);
            withWarning = toLong(row[2]);
        }

        List<ImportTelemetryDto.ParserVersionBreakdown> byParserVersion = new ArrayList<>();
        for (Object[] row : repository.telemetryParserVersionCounts(PARSER_VERSION_LIMIT)) {
            byParserVersion.add(new ImportTelemetryDto.ParserVersionBreakdown(
                    row[0] == null ? "unknown" : (String) row[0],
                    toLong(row[1]), toLong(row[2]), toLong(row[3]), toLong(row[4])));
        }

        // Two queries, one read-committed transaction: rows can be deleted between them (account
        // purge hard-deletes jobs), which would otherwise surface as a negative count in a
        // diagnostic panel. Clamped rather than locked -- an aggregate briefly off by one is fine,
        // a number that reads as a bug is not.
        long notCompleted = Math.max(0, repository.count() - completedJobs);

        return new ImportTelemetryDto.Summary(
                completedJobs,
                completedJobs - predatesTelemetry,
                predatesTelemetry,
                notCompleted,
                byStatus,
                byTextSource,
                headerUncertain,
                withFailed,
                withWarning,
                byParserVersion);
    }

    /** A native COUNT arrives as a Number whose concrete type is the driver's business, not ours
     *  -- Long today, BigInteger under older Hibernate. Read it as a Number either way. */
    private static long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
}
