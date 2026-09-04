package com.finora.entity;

import com.finora.entity.SupportTicket.Status;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ticket status machine, exhaustively — all sixteen from/to pairs, not a sample.
 *
 * <p>These are product decisions with concrete operational consequences, recorded in the plan as
 * D4, D6 and D7. They are the kind of rule a later change can "tidy up" without realising it is
 * reversing a decision, so every cell is pinned here rather than left to the service to imply.
 */
class SupportTicketStatusTest {

    /** The agreed matrix. Anything not listed for a status is a 409. */
    private static final Map<Status, Set<Status>> ALLOWED = Map.of(
            Status.OPEN,        EnumSet.of(Status.IN_PROGRESS, Status.RESOLVED, Status.CLOSED),
            Status.IN_PROGRESS, EnumSet.of(Status.RESOLVED, Status.CLOSED),
            Status.RESOLVED,    EnumSet.of(Status.CLOSED),
            Status.CLOSED,      EnumSet.noneOf(Status.class));

    @Test
    void everyFromToPairMatchesTheAgreedMatrix() {
        for (Status from : Status.values()) {
            for (Status to : Status.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(ALLOWED.get(from).contains(to));
            }
        }
    }

    /**
     * D6, stated on its own because it is the one most likely to be "fixed" by someone who thinks
     * a reopen is obviously missing. It is not missing: a resolved ticket is never reopened, and a
     * customer whose issue turns out not to be fixed raises a new request. The user-facing ticket
     * detail has to say so, because there is no reply thread in v1 either.
     */
    @Test
    void aResolvedTicketIsNeverReopened() {
        assertThat(Status.RESOLVED.canTransitionTo(Status.OPEN)).isFalse();
        assertThat(Status.RESOLVED.canTransitionTo(Status.IN_PROGRESS)).isFalse();
        assertThat(Status.RESOLVED.canTransitionTo(Status.CLOSED)).isTrue();
    }

    /**
     * D7. Without this, admins mark spam IN_PROGRESS and immediately CLOSED purely to satisfy the
     * validator, which puts fictitious work in the reporting the audit trail is supposed to carry.
     */
    @Test
    void spamClosesInOneStep() {
        assertThat(Status.OPEN.canTransitionTo(Status.CLOSED)).isTrue();
    }

    @Test
    void closedIsTerminalAndNothingLeavesIt() {
        for (Status to : Status.values()) {
            assertThat(Status.CLOSED.canTransitionTo(to)).as("CLOSED -> %s", to).isFalse();
        }
    }

    /** Re-applying the current status is not a transition, so it cannot smuggle past the guard. */
    @Test
    void identityIsNotATransition() {
        for (Status s : Status.values()) {
            assertThat(s.canTransitionTo(s)).as("%s -> itself", s).isFalse();
        }
    }

    @Test
    void nullTargetIsRejectedRatherThanThrowing() {
        for (Status s : Status.values()) {
            assertThat(s.canTransitionTo(null)).as("%s -> null", s).isFalse();
        }
    }
}
