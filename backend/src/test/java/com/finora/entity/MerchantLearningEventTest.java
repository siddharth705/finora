package com.finora.entity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retry schedule, in isolation.
 *
 * <p>The queue's behaviour under real transactions is covered by {@code MerchantLearningQueueIT};
 * this covers only the arithmetic, which needs no database and would be tedious to assert through
 * one.
 */
class MerchantLearningEventTest {

    private static MerchantLearningEvent anEvent() {
        return MerchantLearningEvent.pending(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null);
    }

    @Test
    void backoffDoublesEachAttempt() {
        assertThat(MerchantLearningEvent.backoffFor(1)).isEqualTo(Duration.ofMinutes(2));
        assertThat(MerchantLearningEvent.backoffFor(2)).isEqualTo(Duration.ofMinutes(4));
        assertThat(MerchantLearningEvent.backoffFor(3)).isEqualTo(Duration.ofMinutes(8));
        assertThat(MerchantLearningEvent.backoffFor(4)).isEqualTo(Duration.ofMinutes(16));
    }

    /** Guards the shift in backoffFor. Without the clamp, a large attempt count overflows the long
     *  and produces a negative or zero delay -- which would turn a backoff into a hot loop. Not
     *  reachable while MAX_ATTEMPTS is 5, which is exactly why it needs a test: the cap is a policy
     *  someone may raise. */
    @Test
    void backoffNeverGoesNegativeEvenForAbsurdAttemptCounts() {
        assertThat(MerchantLearningEvent.backoffFor(1_000)).isPositive();
        assertThat(MerchantLearningEvent.backoffFor(Integer.MAX_VALUE)).isPositive();
    }

    @Test
    void aFailureSchedulesTheNextAttemptAndRemembersWhenItFirstBroke() {
        MerchantLearningEvent event = anEvent();
        Instant firstFailure = Instant.parse("2026-08-06T10:00:00Z");

        event.recordFailure("boom", firstFailure);

        assertThat(event.getStatus()).isEqualTo(MerchantLearningEvent.Status.PENDING);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getFirstFailedAt()).isEqualTo(firstFailure);
        // ONE minute after the first failure, not two. recordFailure increments attemptCount
        // before computing the delay, so passing it straight to backoffFor doubled every wait
        // against the schedule this class documents.
        assertThat(event.getNextAttemptAt()).isEqualTo(firstFailure.plus(Duration.ofMinutes(1)));

        event.recordFailure("boom again", firstFailure.plusSeconds(600));
        // Set once, never moved -- the admin queue needs "how long has this been broken", which the
        // latest failure cannot answer.
        assertThat(event.getFirstFailedAt()).isEqualTo(firstFailure);
    }

    @Test
    void theAttemptCapIsTerminal() {
        MerchantLearningEvent event = anEvent();
        Instant now = Instant.parse("2026-08-06T10:00:00Z");

        for (int i = 0; i < MerchantLearningEvent.MAX_ATTEMPTS; i++) {
            event.recordFailure("boom", now);
        }

        assertThat(event.getStatus()).isEqualTo(MerchantLearningEvent.Status.FAILED);
        assertThat(event.getAttemptCount()).isEqualTo(MerchantLearningEvent.MAX_ATTEMPTS);
    }

    @Test
    void aLongProviderMessageIsTruncatedRatherThanStoredWhole() {
        MerchantLearningEvent event = anEvent();
        event.recordFailure("x".repeat(10_000), Instant.now());

        assertThat(event.getLastError()).hasSizeLessThanOrEqualTo(2000);
    }

    /** The documented schedule, asserted through recordFailure rather than through backoffFor --
     *  the off-by-one lived in the caller, so testing the pure function alone could not see it. */
    @Test
    void theRetryScheduleIsOneTwoFourEightSixteenMinutes() {
        MerchantLearningEvent event = anEvent();
        Instant now = Instant.parse("2026-08-07T10:00:00Z");
        int[] expectedMinutes = {1, 2, 4, 8};

        for (int minutes : expectedMinutes) {
            event.recordFailure("boom", now);
            assertThat(event.getStatus()).isEqualTo(MerchantLearningEvent.Status.PENDING);
            assertThat(event.getNextAttemptAt()).isEqualTo(now.plus(Duration.ofMinutes(minutes)));
        }

        event.recordFailure("boom", now);
        assertThat(event.getStatus()).isEqualTo(MerchantLearningEvent.Status.FAILED);
    }

    @Test
    void anAdminRetryClearsTheErrorItIsRespondingTo() {
        MerchantLearningEvent event = anEvent();
        event.recordFailure("the thing an admin just fixed", Instant.now());

        event.requeueForRetry(Instant.now());

        assertThat(event.getStatus()).isEqualTo(MerchantLearningEvent.Status.PENDING);
        assertThat(event.getLastError()).isNull();
        assertThat(event.getFirstFailedAt()).as("history survives a retry").isNotNull();
    }

    @Test
    void completingClearsTheLastError() {
        MerchantLearningEvent event = anEvent();
        event.recordFailure("transient", Instant.now());

        event.markCompleted(Instant.now());

        assertThat(event.getStatus()).isEqualTo(MerchantLearningEvent.Status.COMPLETED);
        assertThat(event.getLastError()).isNull();
    }

    /**
     * Bug 18. A worker dying mid-apply is not evidence the event itself is broken, so recovery
     * must not spend a retry attempt the way {@link #recordFailure} does -- five stranded claims
     * (five deploys, say) would otherwise exhaust the whole budget and mark an event permanently
     * FAILED without it ever once having actually run.
     */
    @Test
    void recoveringFromAbandonmentReturnsToQueueWithoutSpendingAnAttempt() {
        MerchantLearningEvent event = anEvent();
        Instant now = Instant.parse("2026-08-06T10:00:00Z");

        event.recoverFromAbandonment("Abandoned in PROCESSING for longer than 15m", now);

        assertThat(event.getStatus()).isEqualTo(MerchantLearningEvent.Status.PENDING);
        assertThat(event.getAttemptCount()).isZero();
        assertThat(event.getFirstFailedAt()).as("nothing failed -- a worker died").isNull();
        assertThat(event.getNextAttemptAt())
                .as("immediately retryable -- the stranded claim already burned real time")
                .isEqualTo(now);
        assertThat(event.getLastError()).contains("Abandoned in PROCESSING");
    }

    /** {@code recoverFromAbandonment} must not reset an attempt budget already spent on genuine
     *  failures -- it is not {@link #requeueForRetry}, which is an admin's deliberate reset. */
    @Test
    void recoveringFromAbandonmentDoesNotResetAttemptsAlreadySpentOnRealFailures() {
        MerchantLearningEvent event = anEvent();
        event.recordFailure("a genuine failure", Instant.parse("2026-08-06T09:00:00Z"));

        event.recoverFromAbandonment("Abandoned in PROCESSING", Instant.parse("2026-08-06T10:00:00Z"));

        assertThat(event.getAttemptCount())
                .as("the earlier real failure still counts -- only the abandonment itself is free")
                .isEqualTo(1);
    }
}
