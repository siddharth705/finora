package com.finora.imports.product;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Everything Stage 1 observed about one section of a document.
 *
 * This is the engine's asset. It is deliberately queryable and persistable on its own, separately
 * from any conclusion drawn from it: when a classification is disputed months later, the answer to
 * "why did it decide that" is this object, not a re-parse of a file nobody kept.
 *
 * Contains no scores and no verdict. Stage 1 records; Stage 2 decides.
 */
public record SectionEvidence(List<ObservedFact> facts) {

    public SectionEvidence {
        facts = List.copyOf(facts);
    }

    public static SectionEvidence of(List<ObservedFact> facts) {
        return new SectionEvidence(facts);
    }

    public boolean has(ProductSignal signal) {
        return facts.stream().anyMatch(f -> f.signal() == signal);
    }

    /** True when every one of {@code signals} was observed -- the "combination of signals, not one
     *  keyword" test the hypotheses are built on. */
    public boolean hasAll(ProductSignal... signals) {
        for (ProductSignal s : signals) if (!has(s)) return false;
        return true;
    }

    public boolean hasAny(ProductSignal... signals) {
        for (ProductSignal s : signals) if (has(s)) return true;
        return false;
    }

    public Set<ProductSignal> signals() {
        Set<ProductSignal> present = EnumSet.noneOf(ProductSignal.class);
        for (ObservedFact f : facts) present.add(f.signal());
        return present;
    }

    public List<ObservedFact> factsFor(ProductSignal signal) {
        return facts.stream().filter(f -> f.signal() == signal).toList();
    }

    /** The strongest source a signal was seen at -- a maturity field in the column headers outranks
     *  the same words in a document-level summary, and only the strongest occurrence matters. */
    public Optional<EvidenceSource> strongestSourceFor(ProductSignal signal) {
        return facts.stream()
                .filter(f -> f.signal() == signal)
                .map(ObservedFact::source)
                .max(java.util.Comparator.naturalOrder());
    }

    /** Product namings at or above {@code minimum} strength. Callers pass
     *  {@link EvidenceSource#SECTION_TEXT} to exclude document-level namings, which is the whole
     *  reason the source is tracked. */
    public List<ObservedFact> productNamesAtLeast(EvidenceSource minimum) {
        List<ObservedFact> out = new ArrayList<>();
        for (ObservedFact f : facts) {
            if (f.signal() == ProductSignal.PRODUCT_NAME && !minimum.isStrongerThan(f.source())) {
                out.add(f);
            }
        }
        return out;
    }

    /**
     * A ledger is a COMBINATION, never one column.
     *
     * This method is the direct fix for a real misclassification: the previous engine treated the
     * presence of any single ledger-ish word as proof of a ledger, so a fixed-deposit schedule with
     * a "Deposit(Mnth)" column -- the monthly contribution amount, not money moving in -- was read
     * as a transaction account and offered to the user as a savings account.
     *
     * What actually distinguishes a ledger from a schedule of figures is a free-text narration
     * column: a ledger records EVENTS, each needing a description, while a deposit schedule records
     * amounts against dates and never needs one. Requiring a date, a description and some form of
     * amount together is what no single keyword can fake.
     */
    public boolean looksLikeALedger() {
        return has(ProductSignal.DATE_COLUMN)
                && has(ProductSignal.DESCRIPTION_COLUMN)
                && hasAny(ProductSignal.DEBIT_CREDIT_COLUMNS, ProductSignal.SINGLE_AMOUNT_COLUMN,
                          ProductSignal.RUNNING_BALANCE_COLUMN)
                && has(ProductSignal.TRANSACTION_ROWS);
    }
}
