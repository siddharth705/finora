package com.finora.service;

import com.finora.dto.HeldStatementTelemetryDto;
import com.finora.entity.HeldStatement;
import com.finora.repository.HeldStatementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The aggregate view over held-statement trust-review outcomes. Same "cheap live aggregate, not a
 * reporting subsystem" shape as {@link AdminImportTelemetryService} -- a handful of grouped counts
 * answered from the table on request, nothing materialised, nothing scheduled.
 *
 * <p><b>Counts, never rates.</b> See {@link AdminImportTelemetryService}'s own doc for the full
 * reasoning Plan 4's Global Constraints restate.
 */
@Service
public class HeldStatementTelemetryService {

    private final HeldStatementRepository repository;

    public HeldStatementTelemetryService(HeldStatementRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public HeldStatementTelemetryDto summary() {
        long totalHolds = repository.count();

        long approved = 0;
        long rejected = 0;
        for (Object[] row : repository.telemetryStatusCounts()) {
            String status = (String) row[0];
            long count = toLong(row[1]);
            if ("IMPORTED".equals(status)) approved = count;
            if ("REJECTED".equals(status)) rejected = count;
        }

        // HeldStatement.Status.RESOLVED is this codebase's own existing EnumSet (already defined
        // in Plan 1, unchanged by this plan) -- reading it here rather than writing 'IMPORTED',
        // 'REJECTED' a second time as SQL string literals is exactly what keeps the two
        // definitions from drifting apart. See Plan 4's own Decisions table.
        String[] resolvedStatuses = HeldStatement.Status.RESOLVED.stream()
                .map(Enum::name).toArray(String[]::new);
        List<Object[]> fp = repository.telemetryFalsePositiveCounts(resolvedStatuses);
        long resolved = fp.isEmpty() ? 0 : toLong(fp.get(0)[0]);
        long falsePositives = fp.isEmpty() ? 0 : toLong(fp.get(0)[1]);

        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (Object[] row : repository.telemetryCategoryCounts()) {
            byCategory.put((String) row[0], toLong(row[1]));
        }

        Double medianHours = repository.telemetryMedianResolutionHours();

        return new HeldStatementTelemetryDto(
                totalHolds, resolved, approved, rejected, falsePositives, byCategory, medianHours);
    }

    /** A native COUNT arrives as a Number whose concrete type is the driver's business, not ours
     *  -- Long today, BigInteger under older Hibernate. Read it as a Number either way. Matches
     *  {@code AdminImportTelemetryService}'s own private helper of the same name. */
    private static long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
}
