package com.finora.imports.analysis;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.FailureCountDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StatementAnalysisReportService#failureCounts}, Premium Import Reliability v1, §4 --
 * needs a real database, unlike the rest of this service's Mockito-covered methods
 * ({@code StatementAnalysisReportServiceTest}), because what's under test here is a JPQL
 * {@code GROUP BY} aggregate, not in-memory mapping logic.
 */
class StatementAnalysisReportServiceIT extends AbstractIntegrationTest {

    @Autowired private StatementAnalysisReportService reportService;
    @Autowired private StatementAnalysisSessionRepository repository;

    /** Builds and persists a fixture directly through the repository -- this service has no write
     *  methods of its own (it only reads), so there is no higher-level API to build fixtures
     *  through. A unique reference per call is all the entity's own constraints require. */
    private StatementAnalysisSession failedFixture(StatementAnalysisSession.Source source, String failureCode) {
        // reference is VARCHAR(24) -- "SA-" plus 20 hex chars from a UUID (dashes stripped) stays
        // comfortably under that while remaining unique per call.
        String reference = "SA-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var session = StatementAnalysisSession.failed(reference, UUID.randomUUID(),
                source, "statement.pdf", "PDF", 1L, "FP-ANALYTICS", failureCode, "irrelevant", 1L, 0,
                null);
        return repository.save(session);
    }

    /** Same as {@link #failedFixture}, but built with a backdated {@code createdAt} set BEFORE the
     *  one and only save -- {@code created_at} is {@code updatable = false}, so setting it via
     *  reflection on an already-persisted (already-INSERTed) entity and saving again is silently a
     *  no-op UPDATE that never touches this column. */
    private StatementAnalysisSession failedFixtureAt(String failureCode, Instant createdAt) {
        String reference = "SA-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var session = StatementAnalysisSession.failed(reference, UUID.randomUUID(),
                StatementAnalysisSession.Source.CUSTOMER_IMPORT, "statement.pdf", "PDF", 1L,
                "FP-ANALYTICS", failureCode, "irrelevant", 1L, 0, null);
        ReflectionTestUtils.setField(session, "createdAt", createdAt);
        return repository.save(session);
    }

    @Test
    void failureCountsGroupsMultipleFailureCodesCorrectly() {
        // Unique-per-run codes, not a real ErrorCode literal: this table is not wiped or rolled
        // back between tests, and other tests in this suite write their own fixtures against it,
        // so a shared literal would count someone else's rows into this assertion.
        Instant since = Instant.now().minusSeconds(60);
        String codeA = "IMPORT_TEST_A_" + UUID.randomUUID().toString().substring(0, 8);
        String codeB = "IMPORT_TEST_B_" + UUID.randomUUID().toString().substring(0, 8);
        failedFixture(StatementAnalysisSession.Source.CUSTOMER_IMPORT, codeA);
        failedFixture(StatementAnalysisSession.Source.CUSTOMER_IMPORT, codeA);
        failedFixture(StatementAnalysisSession.Source.CUSTOMER_IMPORT, codeB);

        var counts = reportService.failureCounts(since).stream()
                .filter(c -> Set.of(codeA, codeB).contains(c.failureCode()))
                .collect(Collectors.toMap(FailureCountDto::failureCode, FailureCountDto::count));

        assertThat(counts).containsEntry(codeA, 2L).containsEntry(codeB, 1L);
    }

    @Test
    void failureCountsExcludesAdminAnalysisSource() {
        Instant since = Instant.now().minusSeconds(60);
        String onlyThisRun = "IMPORT_TEST_" + UUID.randomUUID().toString().substring(0, 8);
        failedFixture(StatementAnalysisSession.Source.ADMIN_ANALYSIS, onlyThisRun);

        var counts = reportService.failureCounts(since);

        assertThat(counts)
                .as("an admin's own diagnostic probing must not inflate a count meant to represent customers")
                .noneMatch(c -> onlyThisRun.equals(c.failureCode()));
    }

    @Test
    void failureCountsCoalescesANullFailureCodeToUnknownFailure() {
        Instant since = Instant.now().minusSeconds(60);
        failedFixture(StatementAnalysisSession.Source.CUSTOMER_IMPORT, null);

        var counts = reportService.failureCounts(since);

        assertThat(counts)
                .as("a failure that could not even be classified must not silently vanish from the count")
                .anyMatch(c -> "UNKNOWN_FAILURE".equals(c.failureCode()) && c.count() >= 1);
    }

    @Test
    void failureCountsExcludesRowsOutsideTheRequestedWindow() {
        String onlyThisRun = "IMPORT_OLD_" + UUID.randomUUID().toString().substring(0, 8);
        failedFixtureAt(onlyThisRun, Instant.now().minus(10, ChronoUnit.DAYS));

        var counts = reportService.failureCounts(Instant.now().minusSeconds(60));

        assertThat(counts)
                .as("a row older than the requested window must not count toward it")
                .noneMatch(c -> onlyThisRun.equals(c.failureCode()));
    }

    @Test
    void failureCountsReturnsEmptyListWhenNothingIsInRange() {
        var counts = reportService.failureCounts(Instant.now().plusSeconds(3600));

        assertThat(counts).isEmpty();
    }
}
