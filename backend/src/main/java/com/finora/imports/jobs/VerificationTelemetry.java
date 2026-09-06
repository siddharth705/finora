package com.finora.imports.jobs;

import com.finora.dto.ImportDto;
import com.finora.imports.ImportReliabilityStatus;

import java.util.List;

/**
 * What verification observed about one import, flattened to the shape a job row can hold.
 *
 * <p>Exists because the aggregation cannot live on {@link com.finora.entity.ImportJob}: entities
 * must not reach into {@code com.finora.dto}, and {@code LayerDependencyDirectionTest} enforces
 * that -- "an entity is the bottom of the dependency graph. Once it can reach a service or a
 * repository, 'load this row' and 'run this business rule' stop being separable." Walking a list of
 * DTOs is exactly the business rule that rule keeps out. So the traversal happens here and the
 * entity receives finished values.
 *
 * <p>That split has a second benefit worth stating: this is a pure function over reports, so the
 * aggregation is testable without constructing a job at all.
 */
public record VerificationTelemetry(
        ImportReliabilityStatus reliabilityStatus,
        String textSource,
        boolean headerReconstructionUncertain,
        int findingsCount,
        int failedCount,
        int warningCount) {

    /** What a path that produced no verification reports records: nothing observed, as distinct
     *  from observed-and-clean. */
    public static final VerificationTelemetry NONE =
            new VerificationTelemetry(null, null, false, 0, 0, 0);

    /**
     * Collapses one report per account section into one row's worth of facts.
     *
     * <p><b>Worst case, not average.</b> A composite statement produces a report per section and the
     * job is a single row; a document with one clean section and one needing attention needs
     * attention. Averaging would let a clean section dilute a real problem in the one beside it.
     */
    public static VerificationTelemetry from(List<ImportDto.VerificationReport> reports) {
        if (reports == null || reports.isEmpty()) {
            return NONE;
        }

        int findings = 0;
        int failed = 0;
        int warning = 0;
        boolean uncertain = false;
        String source = null;
        ImportReliabilityStatus worst = null;

        for (ImportDto.VerificationReport report : reports) {
            if (report == null) continue;
            if (report.findings() != null) {
                for (ImportDto.VerificationFinding f : report.findings()) {
                    findings++;
                    if ("FAILED".equals(f.outcome())) failed++;
                    else if ("WARNING".equals(f.outcome())) warning++;
                }
            }
            uncertain |= report.headerReconstructionUncertain();
            if (source == null) source = report.textSource();
            // Ordinal comparison is safe here and only here: ImportReliabilityStatus is declared
            // least-to-most severe, which is the order this needs. Stated rather than assumed,
            // because a value inserted out of order would silently redefine "worst".
            if (report.reliabilityStatus() != null
                    && (worst == null || report.reliabilityStatus().ordinal() > worst.ordinal())) {
                worst = report.reliabilityStatus();
            }
        }
        return new VerificationTelemetry(worst, source, uncertain, findings, failed, warning);
    }

    /** True when nothing was observed, so the caller can leave the row's evidence columns null
     *  rather than writing zeros that would read as "verified, found nothing". */
    public boolean isEmpty() {
        return findingsCount == 0 && reliabilityStatus == null && textSource == null;
    }
}
