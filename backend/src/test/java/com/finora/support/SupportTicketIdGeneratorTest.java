package com.finora.support;

import com.finora.repository.SupportTicketRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The reference a customer quotes back to support.
 *
 * <p>Formatting only -- uniqueness is the database sequence's job, same split
 * {@code HeldStatementIdGeneratorTest} uses for its sibling generator. Nothing here needs a
 * container. Unlike {@code HeldStatementIdGenerator}, there is no year segment and so no clock to
 * fix -- see this class's own doc comment for why that divergence is deliberate.
 */
class SupportTicketIdGeneratorTest {

    private SupportTicketIdGenerator generator(long seq) {
        SupportTicketRepository repository = mock(SupportTicketRepository.class);
        when(repository.nextTicketSequence()).thenReturn(seq);
        return new SupportTicketIdGenerator(repository);
    }

    @Test
    void formatsAsSupAndSixDigitSequence() {
        assertThat(generator(1L).next()).isEqualTo("SUP-000001");
    }

    @Test
    void keepsSixDigitsForLargerSequences() {
        assertThat(generator(42L).next()).isEqualTo("SUP-000042");
    }

    /**
     * Past a million tickets the reference simply gets longer rather than wrapping or truncating.
     *
     * <p>Six digits is a minimum width, not a maximum -- see the generator's own class doc. A
     * truncating format would mint a duplicate reference, which is the one thing a
     * customer-quoted identifier must never do.
     */
    @Test
    void growsRatherThanTruncatingPastSixDigits() {
        assertThat(generator(1_234_567L).next()).isEqualTo("SUP-1234567");
    }

    @Test
    void twoDistinctSequenceValues_produceTwoDistinctReferences() {
        // Uniqueness itself is the database sequence's job (nextTicketSequence's own doc comment:
        // gaps are expected and correct, a reused reference is not) -- this only pins down that
        // the generator does not accidentally collapse two different sequence values into the
        // same formatted string.
        assertThat(generator(1L).next()).isNotEqualTo(generator(2L).next());
    }
}
