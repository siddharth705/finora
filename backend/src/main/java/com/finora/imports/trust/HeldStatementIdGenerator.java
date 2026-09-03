package com.finora.imports.trust;

import com.finora.repository.HeldStatementRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Mints the reference operators actually quote -- {@code HLD-2026-000001}.
 *
 * <p>Year plus a database sequence, so it is readable, sortable and collision-free. Operators
 * never see a raw UUID; the row still has one, and that stays the foreign key.
 *
 * <p>Uniqueness comes from the sequence alone, never from the year. The year is there to make the
 * reference readable and is deliberately NOT a counter reset -- resetting per year would need a
 * second source of truth and could mint {@code HLD-2027-000001} twice. Six digits is a minimum
 * width rather than a maximum, so past a million holds the reference gets longer instead of
 * wrapping.
 */
@Component
public class HeldStatementIdGenerator {

    private final HeldStatementRepository repository;
    private final Clock clock;

    /**
     * {@code @Autowired} is required, not decorative: this class has two constructors, and Spring
     * only auto-selects when there is exactly one. Without it the container falls back to looking
     * for a no-arg constructor, finds none, and fails at startup -- which is how this was caught.
     */
    @Autowired
    public HeldStatementIdGenerator(HeldStatementRepository repository) {
        this(repository, Clock.systemUTC());
    }

    /** Package-private: only this package's tests need to fix "now", so the year in a minted
     *  reference is deterministic rather than dependent on the day the test happens to run. */
    HeldStatementIdGenerator(HeldStatementRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public String next() {
        long sequence = repository.nextHeldSequence();
        // UTC, not the JVM's zone: an instant that is already next year in UTC must mint next
        // year's reference wherever the server happens to run.
        int year = LocalDate.now(clock.withZone(ZoneOffset.UTC)).getYear();
        return "HLD-" + year + "-" + String.format("%06d", sequence);
    }
}
