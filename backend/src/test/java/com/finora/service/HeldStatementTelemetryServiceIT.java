package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.HeldStatementTelemetryDto;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The aggregate telemetry service against real Postgres -- deliberately an IT, not a unit test:
 * the whole point of this class is native SQL ({@code unnest}, {@code FILTER}, {@code
 * PERCENTILE_CONT}), and a mock repository would prove nothing about whether that SQL actually
 * means what its doc comments claim.
 *
 * <p>Seeding here follows {@code AdminHeldStatementControllerIT.seedHold}'s lighter pattern, not
 * {@code HeldStatementServiceRerunIT.seedHold}'s storage-backed one -- this class never calls
 * {@code dryRunParse} or downloads a document, so real bytes in {@code StatementStorage} buy
 * nothing here.
 */
class HeldStatementTelemetryServiceIT extends AbstractIntegrationTest {

    @Autowired private HeldStatementTelemetryService telemetryService;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private UserRepository userRepository;

    private User owner() {
        User user = new User();
        user.setEmail("telemetry-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Telemetry IT User");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HeldStatement seedOpenHold(List<String> categories) {
        User owner = owner();
        ImportJob job = new ImportJob(owner.getId(), "statement.pdf",
                "hash-" + UUID.randomUUID(), "objects/key-" + UUID.randomUUID(), "PDF");
        job.markClaimed("worker", Instant.now());
        UUID sessionId = UUID.randomUUID();
        job.holdForTrustReview(sessionId, null, Instant.now());
        importJobRepository.save(job);

        HeldStatement held = new HeldStatement("HLD-2026-8" + System.nanoTime() % 100000,
                job.getId(), owner.getId(), job.getObjectKey(),
                "Printed and parsed transaction count disagree (ROW_GROUPING)");
        held.recordSnapshot("build-1", "NEEDS_ATTENTION", "NATIVE", false, categories);
        held = heldStatementRepository.save(held);
        job.holdForTrustReview(sessionId, held.getId(), Instant.now());
        importJobRepository.save(job);
        return held;
    }

    private HeldStatement seedResolvedHold(HeldStatement.Status resolution, List<String> categories,
                                           Boolean falsePositive) {
        HeldStatement held = seedOpenHold(categories);
        if (resolution == HeldStatement.Status.IMPORTED) {
            held.markImported(owner().getId(), Instant.now(), falsePositive);
        } else if (resolution == HeldStatement.Status.REJECTED) {
            held.reject(owner().getId(), Instant.now());
        } else {
            throw new IllegalArgumentException("Not a resolution: " + resolution);
        }
        return heldStatementRepository.save(held);
    }

    @Test
    void summaryCountsHoldsResolutionsAndCategories() {
        seedResolvedHold(HeldStatement.Status.IMPORTED, List.of("COUNT_MISMATCH"), false);
        seedResolvedHold(HeldStatement.Status.IMPORTED, List.of("PERIOD_INTEGRITY"), true);
        seedResolvedHold(HeldStatement.Status.REJECTED,
                List.of("COUNT_MISMATCH", "DROPPED_TRANSACTION"), null);
        seedOpenHold(List.of("COUNT_MISMATCH"));

        HeldStatementTelemetryDto summary = telemetryService.summary();

        assertThat(summary.totalHolds()).isEqualTo(4);
        assertThat(summary.resolved()).isEqualTo(3);
        assertThat(summary.approved()).isEqualTo(2);
        assertThat(summary.rejected()).isEqualTo(1);
        assertThat(summary.falsePositives()).isEqualTo(1);
        assertThat(summary.byCategory()).containsEntry("COUNT_MISMATCH", 3L);
        assertThat(summary.byCategory()).containsEntry("PERIOD_INTEGRITY", 1L);
        assertThat(summary.byCategory()).containsEntry("DROPPED_TRANSACTION", 1L);
    }

    @Test
    void medianResolutionHoursIsNullWhenNothingHasResolvedYet() {
        seedOpenHold(List.of("COUNT_MISMATCH"));

        HeldStatementTelemetryDto summary = telemetryService.summary();

        assertThat(summary.medianResolutionHours()).isNull();
    }

    @Test
    void medianResolutionHoursIsComputedOverResolvedHoldsOnly() {
        seedResolvedHold(HeldStatement.Status.IMPORTED, List.of("COUNT_MISMATCH"), null);

        HeldStatementTelemetryDto summary = telemetryService.summary();

        assertThat(summary.medianResolutionHours()).isNotNull();
    }

    /**
     * Simulates a hold created before Task 1's migration -- hold_reason_categories is genuinely
     * NULL, not an empty array. An external review of Plan 4 specifically asked for this as its
     * own test: the "excluded, not counted as zero" claim appears in several doc comments across
     * this plan but nothing before this test actually proved unnest() on a NULL array column
     * behaves the way those comments assume, against real Postgres.
     */
    @Test
    void aHoldWithNoRecordedCategoriesIsCountedButExcludedFromByCategory() {
        seedResolvedHold(HeldStatement.Status.IMPORTED, null, false);

        HeldStatementTelemetryDto summary = telemetryService.summary();

        assertThat(summary.totalHolds()).isEqualTo(1);
        assertThat(summary.resolved()).isEqualTo(1);
        assertThat(summary.byCategory()).isEmpty();
    }
}
