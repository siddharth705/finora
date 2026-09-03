package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportTelemetryDto;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.exception.ErrorCode;
import com.finora.imports.ImportReliabilityStatus;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The telemetry readout, against a real Postgres.
 *
 * <p>An integration test rather than a mocked-repository unit test on purpose: every query behind
 * this service is native SQL using {@code FILTER (WHERE ...)} and a GROUP BY that has to return a
 * NULL bucket. A mock would happily return whatever fixture rows it was handed and prove nothing
 * about whether that SQL parses, runs, or groups the way the service assumes.
 *
 * <p>Absolute counts are safe here because {@link AbstractIntegrationTest} deletes every
 * {@code import_jobs} row before each test and the suite runs {@code @Isolated}.
 */
class AdminImportTelemetryIT extends AbstractIntegrationTest {

    @Autowired
    private AdminImportTelemetryService telemetryService;

    @Autowired
    private ImportJobRepository repository;

    @Autowired
    private UserRepository userRepository;

    /** import_jobs.user_id carries a real foreign key, so a job needs a real owner. One per test
     *  is enough -- nothing here is scoped per user, and the readout is platform-wide by design. */
    private UUID userId;

    @BeforeEach
    void createOwner() {
        User user = new User();
        user.setEmail("telemetry-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Telemetry Readout Test User");
        userId = userRepository.save(user).getId();
    }

    /**
     * The distinction the whole phase rests on: an import that predates telemetry must never be
     * counted as one that was verified and found clean.
     */
    @Test
    void anImportWithoutTelemetryCountsAsPredating_notAsClean() {
        repository.save(completedJob(null));

        ImportTelemetryDto.Summary summary = telemetryService.summary();

        assertThat(summary.completedJobs()).isEqualTo(1);
        assertThat(summary.predatesTelemetry()).isEqualTo(1);
        assertThat(summary.withTelemetry()).isZero();
        assertThat(summary.byReliabilityStatus())
                .as("a row with no telemetry contributes to no status bucket at all")
                .containsEntry("CLEAN", 0L)
                .containsEntry("REVIEW_RECOMMENDED", 0L)
                .containsEntry("NEEDS_ATTENTION", 0L);
    }

    /**
     * Failed jobs never carry telemetry -- the worker records on the success path only -- so
     * including them in the denominator would permanently understate every rate derived from it.
     * Production has three such rows today, which is what makes this worth pinning.
     */
    @Test
    void failedJobsAreExcludedFromTheDenominatorAndReportedSeparately() {
        ImportJob failed = new ImportJob(userId, "s.pdf", "hash-failed", "k1", "PDF");
        failed.markClaimed("worker", Instant.now());
        ImportJob.FailureOutcome outcome = failed.recordFailure(
                "unreadable", "IMPORT_PARSE_FAILED", ErrorCode.RetryPolicy.FAIL_FAST, Instant.now());
        assertThat(outcome)
                .as("the fixture has to actually reach FAILED, or this test proves nothing")
                .isEqualTo(ImportJob.FailureOutcome.DEAD_LETTERED);
        repository.save(failed);
        repository.save(completedJob(ImportReliabilityStatus.CLEAN));

        ImportTelemetryDto.Summary summary = telemetryService.summary();

        assertThat(summary.completedJobs()).isEqualTo(1);
        assertThat(summary.withTelemetry()).isEqualTo(1);
        assertThat(summary.notCompleted())
                .as("excluded from the rate, but visible so the exclusion is not silent")
                .isEqualTo(1);
    }

    /** The grouped counts, the FILTER counts and the text-source split, in one populated case. */
    @Test
    void aggregatesStatusesFlagsAndTextSource() {
        repository.save(telemetryJob("h1", ImportReliabilityStatus.CLEAN, "NATIVE", false, 0, 0));
        repository.save(telemetryJob("h2", ImportReliabilityStatus.NEEDS_ATTENTION, "OCR", true, 1, 0));
        repository.save(telemetryJob("h3", ImportReliabilityStatus.REVIEW_RECOMMENDED, "OCR", false, 0, 2));

        ImportTelemetryDto.Summary summary = telemetryService.summary();

        assertThat(summary.withTelemetry()).isEqualTo(3);
        assertThat(summary.byReliabilityStatus())
                .containsEntry("CLEAN", 1L)
                .containsEntry("REVIEW_RECOMMENDED", 1L)
                .containsEntry("NEEDS_ATTENTION", 1L);
        assertThat(summary.byTextSource()).containsEntry("NATIVE", 1L).containsEntry("OCR", 2L);
        assertThat(summary.headerReconstructionUncertain()).isEqualTo(1);
        assertThat(summary.withFailedFindings()).isEqualTo(1);
        assertThat(summary.withWarningFindings()).isEqualTo(1);
    }

    /**
     * A rate pooled across a parser change is two populations averaged together, so the breakdown
     * has to actually separate them.
     */
    @Test
    void splitsTheDistributionByParserVersion() {
        repository.save(telemetryJob("h1", ImportReliabilityStatus.CLEAN, "NATIVE", false, 0, 0, "sha-old"));
        repository.save(telemetryJob("h2", ImportReliabilityStatus.NEEDS_ATTENTION, "NATIVE", false, 1, 0, "sha-new"));
        repository.save(telemetryJob("h3", ImportReliabilityStatus.CLEAN, "NATIVE", false, 0, 0, "sha-new"));

        ImportTelemetryDto.Summary summary = telemetryService.summary();

        assertThat(summary.byParserVersion()).hasSize(2);
        ImportTelemetryDto.ParserVersionBreakdown newest = summary.byParserVersion().stream()
                .filter(b -> "sha-new".equals(b.parserVersion())).findFirst().orElseThrow();
        assertThat(newest.jobs()).isEqualTo(2);
        assertThat(newest.clean()).isEqualTo(1);
        assertThat(newest.needsAttention()).isEqualTo(1);
    }

    /** No imports at all must read as zeros, not as an exception or an absent bucket. */
    @Test
    void anEmptyTableReportsZeros() {
        ImportTelemetryDto.Summary summary = telemetryService.summary();

        assertThat(summary.completedJobs()).isZero();
        assertThat(summary.withTelemetry()).isZero();
        assertThat(summary.byReliabilityStatus()).containsEntry("CLEAN", 0L);
        assertThat(summary.byParserVersion()).isEmpty();
    }

    // ------------------------------------------------------------------ fixtures

    private ImportJob completedJob(ImportReliabilityStatus status) {
        return status == null
                ? complete(new ImportJob(userId, "s.pdf", "hash-plain", "k", "PDF"))
                : telemetryJob("hash-" + status.name(), status, "NATIVE", false, 0, 0);
    }

    private ImportJob telemetryJob(String contentHash, ImportReliabilityStatus status,
                                   String textSource, boolean headerUncertain,
                                   int failedCount, int warningCount) {
        return telemetryJob(contentHash, status, textSource, headerUncertain, failedCount,
                warningCount, "sha-test");
    }

    private ImportJob telemetryJob(String contentHash, ImportReliabilityStatus status,
                                   String textSource, boolean headerUncertain,
                                   int failedCount, int warningCount, String parserVersion) {
        ImportJob job = complete(new ImportJob(
                userId, "s.pdf", contentHash, "k-" + contentHash, "PDF"));
        job.recordVerificationTelemetry(status, textSource, headerUncertain,
                failedCount + warningCount, failedCount, warningCount, parserVersion);
        return job;
    }

    private ImportJob complete(ImportJob job) {
        job.markClaimed("worker", Instant.now());
        job.complete(UUID.randomUUID(), Instant.now());
        return job;
    }
}
