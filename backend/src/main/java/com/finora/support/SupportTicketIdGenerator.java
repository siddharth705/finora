package com.finora.support;

import com.finora.repository.SupportTicketRepository;
import org.springframework.stereotype.Component;

/**
 * Mints the reference a customer quotes back to support — {@code SUP-000001}.
 *
 * <p>A database sequence, formatted. The row still has a UUID and that stays the primary key and
 * the foreign key everywhere; this exists so nobody has to read a UUID aloud on a support call.
 *
 * <p><b>No year segment, deliberately.</b> {@code HeldStatementIdGenerator} — the sibling
 * component this one is otherwise modelled on — mints {@code HLD-2026-000001}, and the divergence
 * here is a recorded decision rather than drift. Support references are quoted by customers, who
 * have no reason to care which year a ticket was opened, and a shorter reference is easier to read
 * back over the phone.
 *
 * <p>Uniqueness comes from the sequence alone. Six digits is a minimum width rather than a
 * maximum, so past a million tickets the reference gets longer instead of wrapping.
 *
 * <p>Gaps in the sequence are expected and correct: {@code nextval} is not rollback-safe, so a
 * ticket whose transaction rolls back burns its number rather than reissuing it. A reused
 * reference would point at two different tickets, which is far worse than a gap.
 */
@Component
public class SupportTicketIdGenerator {

    static final String PREFIX = "SUP-";

    private final SupportTicketRepository repository;

    public SupportTicketIdGenerator(SupportTicketRepository repository) {
        this.repository = repository;
    }

    public String next() {
        return PREFIX + String.format("%06d", repository.nextTicketSequence());
    }
}
