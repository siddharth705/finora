package com.finora.imports.trust;

import com.finora.repository.HeldStatementRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The reference an operator actually quotes.
 *
 * <p>Formatting only -- uniqueness is the database sequence's job, which is the same split
 * {@code StatementAnalysisRecorder} uses for its {@code SA-} references. Nothing here needs a
 * container.
 */
class HeldStatementIdGeneratorTest {

    private HeldStatementIdGenerator generator(long seq, String instant) {
        return generator(seq, instant, ZoneOffset.UTC);
    }

    private HeldStatementIdGenerator generator(long seq, String instant, ZoneId zone) {
        HeldStatementRepository repository = mock(HeldStatementRepository.class);
        when(repository.nextHeldSequence()).thenReturn(seq);
        return new HeldStatementIdGenerator(repository, Clock.fixed(Instant.parse(instant), zone));
    }

    @Test
    void formatsAsHldYearAndSixDigitSequence() {
        assertThat(generator(1L, "2026-09-03T10:00:00Z").next()).isEqualTo("HLD-2026-000001");
    }

    @Test
    void keepsSixDigitsForLargerSequences() {
        assertThat(generator(123456L, "2026-09-03T10:00:00Z").next()).isEqualTo("HLD-2026-123456");
    }

    /**
     * Past a million holds the reference simply gets longer rather than wrapping or truncating.
     *
     * <p>Six digits is a minimum width, not a maximum. A truncating format would mint a duplicate
     * reference -- the one thing an operator-facing identifier must never do -- and
     * {@code held_id} is VARCHAR(32), so there is room.
     */
    @Test
    void growsRatherThanTruncatingPastSixDigits() {
        assertThat(generator(1_234_567L, "2026-09-03T10:00:00Z").next())
                .isEqualTo("HLD-2026-1234567");
    }

    /**
     * The year comes from the clock, so it rolls over on its own.
     *
     * <p>The sequence deliberately does NOT reset with it: the year is there to make the reference
     * readable, and uniqueness comes from the sequence alone. A per-year reset would need a second
     * source of truth and could mint {@code HLD-2027-000001} twice.
     */
    @Test
    void theYearFollowsTheClock() {
        assertThat(generator(7L, "2027-01-01T00:00:00Z").next()).isEqualTo("HLD-2027-000007");
    }

    /**
     * UTC decides the year, not the server's zone.
     *
     * <p>An instant that is already next year in UTC must not mint last year's reference because
     * the JVM happens to run in a behind-UTC zone. Asserted with a fixed clock in such a zone --
     * 2027-01-01T00:30Z is still 2026-12-31 in New York.
     */
    @Test
    void theYearIsDecidedInUtcRegardlessOfTheServerZone() {
        assertThat(generator(9L, "2027-01-01T00:30:00Z", ZoneId.of("America/New_York")).next())
                .as("the reference must not depend on where the server happens to run")
                .isEqualTo("HLD-2027-000009");
    }
}
