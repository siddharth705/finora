package com.finora.imports.jobs;

import com.finora.dto.ImportDto;
import com.finora.imports.ImportReliabilityStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The aggregation that turns one report per account section into one row's worth of facts.
 *
 * <p>Pure, so it needs no job, no Spring context and no database -- which is the practical reason
 * this lives outside {@code ImportJob} as well as the architectural one.
 */
class VerificationTelemetryTest {

    private static ImportDto.VerificationReport report(String outcome, boolean uncertain,
            String textSource, ImportReliabilityStatus status) {
        return new ImportDto.VerificationReport(
                List.of(new ImportDto.VerificationFinding("RULE", outcome, Map.of())),
                uncertain, textSource, status);
    }

    /**
     * A composite statement's sections are reconciled by worst case, not by average: one clean
     * section does not excuse another that needs attention, because the job is a single row and the
     * decision is about the document.
     */
    @Test
    void sectionsAreAggregatedByWorstCase() {
        VerificationTelemetry t = VerificationTelemetry.from(List.of(
                report("VERIFIED", false, "NATIVE", ImportReliabilityStatus.CLEAN),
                report("FAILED", true, "OCR", ImportReliabilityStatus.NEEDS_ATTENTION)));

        assertThat(t.reliabilityStatus()).isEqualTo(ImportReliabilityStatus.NEEDS_ATTENTION);
        assertThat(t.headerReconstructionUncertain()).isTrue();
        assertThat(t.findingsCount()).isEqualTo(2);
        assertThat(t.failedCount()).isEqualTo(1);
        assertThat(t.warningCount()).isZero();
    }

    /** REVIEW_RECOMMENDED must not be promoted past a section that is genuinely clean, nor
     *  demoted by one -- the ordering is the whole mechanism. */
    @Test
    void aMiddleSeverityBeatsCleanAndLosesToAttention() {
        assertThat(VerificationTelemetry.from(List.of(
                report("VERIFIED", false, "NATIVE", ImportReliabilityStatus.CLEAN),
                report("WARNING", false, "NATIVE", ImportReliabilityStatus.REVIEW_RECOMMENDED)))
                .reliabilityStatus()).isEqualTo(ImportReliabilityStatus.REVIEW_RECOMMENDED);

        assertThat(VerificationTelemetry.from(List.of(
                report("WARNING", false, "NATIVE", ImportReliabilityStatus.REVIEW_RECOMMENDED),
                report("FAILED", false, "NATIVE", ImportReliabilityStatus.NEEDS_ATTENTION)))
                .reliabilityStatus()).isEqualTo(ImportReliabilityStatus.NEEDS_ATTENTION);
    }

    @Test
    void warningsAndFailuresAreCountedSeparately() {
        VerificationTelemetry t = VerificationTelemetry.from(List.of(
                report("WARNING", false, "NATIVE", ImportReliabilityStatus.REVIEW_RECOMMENDED),
                report("WARNING", false, "NATIVE", ImportReliabilityStatus.REVIEW_RECOMMENDED),
                report("FAILED", false, "NATIVE", ImportReliabilityStatus.NEEDS_ATTENTION)));

        assertThat(t.findingsCount()).isEqualTo(3);
        assertThat(t.warningCount()).isEqualTo(2);
        assertThat(t.failedCount()).isEqualTo(1);
    }

    /**
     * "Nothing was observed" has to stay distinguishable from "observed and found nothing", or
     * every import that predates telemetry silently claims to have been verified clean.
     */
    @Test
    void noReportsIsEmpty_notClean() {
        assertThat(VerificationTelemetry.from(List.of()).isEmpty()).isTrue();
        assertThat(VerificationTelemetry.from(null).isEmpty()).isTrue();
        assertThat(VerificationTelemetry.from(List.of()).reliabilityStatus()).isNull();
    }

    /** A verified section is observed, so it is not empty even though it found nothing wrong. */
    @Test
    void aCleanReportIsObserved_soNotEmpty() {
        VerificationTelemetry t = VerificationTelemetry.from(List.of(
                report("VERIFIED", false, "NATIVE", ImportReliabilityStatus.CLEAN)));

        assertThat(t.isEmpty()).isFalse();
        assertThat(t.reliabilityStatus()).isEqualTo(ImportReliabilityStatus.CLEAN);
        assertThat(t.failedCount()).isZero();
    }
}
