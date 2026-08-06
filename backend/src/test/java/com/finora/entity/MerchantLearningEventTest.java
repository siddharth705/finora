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
        return MerchantLearningEvent.pending(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null);
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
        assertThat(event.getNextAttemptAt()).isEqualTo(firstFailure.plus(Duration.ofMinutes(2)));

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

    @Test
    void completingClearsTheLastError() {
        MerchantLearningEvent event = anEvent();
        event.recordFailure("transient", Instant.now());

        event.markCompleted(Instant.now());

        assertThat(event.getStatus()).isEqualTo(MerchantLearningEvent.Status.COMPLETED);
        assertThat(event.getLastError()).isNull();
    }
}
