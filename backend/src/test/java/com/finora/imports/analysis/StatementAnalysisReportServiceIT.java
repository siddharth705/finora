package com.finora.imports.analysis;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.FailureCountDto;
import com.finora.repository.RegisteredLayoutRepository;
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
 * {@link StatementAnalysisReportService#failureCounts}, Premium Import Reliability v1, §4.9 --
 * needs a real database, unlike the rest of this service's Mockito-covered methods
 * ({@code StatementAnalysisReportServiceTest}), because what's under test here is a JPQL
 * {@code GROUP BY} aggregate, not in-memory mapping logic.
 */
class StatementAnalysisReportServiceIT extends AbstractIntegrationTest {

    @Autowired private StatementAnalysisReportService reportService;
    @Autowired private StatementAnalysisSessionRepository repository;
    @Autowired private RegisteredLayoutRepository registeredLayoutRepository;

    /** Builds and persists a fixture directly through the repository -- this service has no write
     *  methods of its own (it only reads), so there is no higher-level API to build fixtures
     *  through. A unique reference per call is all the entity's own constraints require. */
    private StatementAnalysisSession failedFixture(StatementAnalysisSession.Source source, String failureCode) {
        return failedFixture(source, failureCode, "FP-ANALYTICS");
    }

    /** Same as the two-arg overload, but with an explicit fingerprint -- needed for the
     *  best-effort-bank tests, which have to control which layout registry row (if any) a
     *  fingerprint resolves to. */
    private StatementAnalysisSession failedFixture(StatementAnalysisSession.Source source, String failureCode,
                                                     String fingerprint) {
        // reference is VARCHAR(24) -- "SA-" plus 20 hex chars from a UUID (dashes stripped) stays
        // comfortably under that while remaining unique per call.
        String reference = "SA-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var session = StatementAnalysisSession.failed(reference, UUID.randomUUID(),
                source, "statement.pdf", "PDF", 1L, fingerprint, failureCode, "irrelevant", 1L, 0,
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

    @Test
    void failureCountsResolvesBestEffortBankFromTheLayoutRegistry() {
        Instant since = Instant.now().minusSeconds(60);
        String code = "IMPORT_TEST_BANK_" + UUID.randomUUID().toString().substring(0, 8);
        String fingerprint = "FP-BANK-" + UUID.randomUUID().toString().substring(0, 8);
        registeredLayoutRepository.observe(fingerprint, "PDF", "test-parser", Instant.now());
        // findByFingerprint returns a detached entity outside any surrounding transaction --
        // rename() alone mutates it in memory only, save() is what actually persists the curation.
        var layout = registeredLayoutRepository.findByFingerprint(fingerprint).orElseThrow();
        layout.rename("Test Bank Statement");
        registeredLayoutRepository.save(layout);
        failedFixture(StatementAnalysisSession.Source.CUSTOMER_IMPORT, code, fingerprint);

        var counts = reportService.failureCounts(since);

        assertThat(counts)
                .filteredOn(c -> code.equals(c.failureCode()))
                .singleElement()
                .extracting(FailureCountDto::bank)
                .isEqualTo("Test Bank Statement");
    }

    @Test
    void failureCountsBreaksATiedFingerprintCountDeterministically() {
        // Two distinct fingerprints, each failing the SAME code exactly once -- a genuine tie on
        // COUNT(s), the exact case the repository query's secondary ORDER BY (layoutFingerprint
        // ASC) exists to make repeatable. A shared random suffix keeps both fingerprints unique
        // per test run while keeping "TA" < "TB" alphabetically fixed, so the expected winner
        // (fpA/"Bank A") is deterministic regardless of what the suffix happens to be.
        Instant since = Instant.now().minusSeconds(60);
        String code = "IMPORT_TEST_TIE_" + UUID.randomUUID().toString().substring(0, 8);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String fpA = "FP-TA-" + suffix;
        String fpB = "FP-TB-" + suffix;
        registeredLayoutRepository.observe(fpA, "PDF", "test-parser", Instant.now());
        registeredLayoutRepository.observe(fpB, "PDF", "test-parser", Instant.now());
        var layoutA = registeredLayoutRepository.findByFingerprint(fpA).orElseThrow();
        layoutA.rename("Bank A");
        registeredLayoutRepository.save(layoutA);
        var layoutB = registeredLayoutRepository.findByFingerprint(fpB).orElseThrow();
        layoutB.rename("Bank B");
        registeredLayoutRepository.save(layoutB);
        failedFixture(StatementAnalysisSession.Source.CUSTOMER_IMPORT, code, fpA);
        failedFixture(StatementAnalysisSession.Source.CUSTOMER_IMPORT, code, fpB);

        // Called twice, deliberately -- the bug this guards against was the SAME call returning a
        // DIFFERENT answer against unchanged data, which a single assertion can't catch.
        var firstCall = reportService.failureCounts(since);
        var secondCall = reportService.failureCounts(since);

        assertThat(firstCall)
                .filteredOn(c -> code.equals(c.failureCode()))
                .singleElement()
                .extracting(FailureCountDto::bank)
                .isEqualTo("Bank A");
        assertThat(secondCall)
                .filteredOn(c -> code.equals(c.failureCode()))
                .singleElement()
                .extracting(FailureCountDto::bank)
                .isEqualTo("Bank A");
    }

    @Test
    void failureCountsLeavesBankNullWhenTheDominantFingerprintHasNeverBeenNamed() {
        // FP-ANALYTICS (the default fixture fingerprint) is never registered by this test class --
        // matching the far more common real-world case, a layout the engine has seen fail but
        // never confirmed once, so it has no layout_registry row (named or otherwise) at all.
        Instant since = Instant.now().minusSeconds(60);
        String code = "IMPORT_TEST_UNNAMED_" + UUID.randomUUID().toString().substring(0, 8);
        failedFixture(StatementAnalysisSession.Source.CUSTOMER_IMPORT, code);

        var counts = reportService.failureCounts(since);

        assertThat(counts)
                .filteredOn(c -> code.equals(c.failureCode()))
                .singleElement()
                .extracting(FailureCountDto::bank)
                .isNull();
    }
}
