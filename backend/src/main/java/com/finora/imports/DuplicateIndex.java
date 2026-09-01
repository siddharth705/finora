package com.finora.imports;

import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import com.finora.util.DuplicateMatching;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Answers "have I seen this transaction before?" for one staging pass, without asking the database
 * once per row.
 *
 * <p>{@code DuplicateDetector.findMatch} issued one query per row -- 1.00 statements/row, measured,
 * and recommendation 2 of the import pipeline profile. This loads a day at a time instead: a
 * statement covers at most ~31 distinct dates whatever its row count, so a 5,000-row statement
 * costs ~31 queries rather than 5,000.
 *
 * <h2>Lazy per-date rather than one range query</h2>
 *
 * <p>The obvious shape is a single query bounded by the statement's date range. It is not reachable
 * without restructuring: rows are parsed one at a time inside {@code TransactionNormalizer}, so the
 * range is not known until parsing has already finished. Loading each date on first sight converges
 * on the same query count without a two-pass rewrite, and degrades gracefully -- a statement
 * spanning two years costs one query per date actually present, not one per date in the span.
 *
 * <h2>Scale, and the bug this class exists not to introduce</h2>
 *
 * <p>The query it replaces compares {@code t.amount = :amount} in SQL, where Postgres NUMERIC
 * equality is by VALUE: {@code 100.00} and {@code 100.0} are the same number. Java's
 * {@link BigDecimal#equals} is by value AND scale, so those are different keys in a {@link HashMap}.
 *
 * <p>A naive in-memory index would therefore silently stop matching duplicates whenever a stored
 * transaction and a parsed row happened to carry different scales -- which is exactly what happens
 * when one came from a CSV reading {@code 100.0} and the other from a PDF reading {@code 100.00}.
 * Nothing would fail; users would simply start seeing duplicates they used to be warned about.
 *
 * <p>{@link #normaliseAmount} strips trailing zeros so the key is the value, matching the database.
 * {@code DuplicateIndexTest} asserts it directly, because this is the one thing about this class
 * that cannot be caught by reading it.
 *
 * <h2>Not thread-safe, and scoped to one pass</h2>
 *
 * <p>One index belongs to one staging pass on one thread. It is a cache of what the database held
 * when each date was first touched, which is correct for the duration of a parse and would be
 * quietly wrong if held longer.
 */
public final class DuplicateIndex {

    private final TransactionRepository transactionRepository;
    private final UUID userId;
    private final List<UUID> liveAccountIds;

    /** date -> (amount|description) -> matching transactions, loaded on first sight of the date. */
    private final Map<LocalDate, Map<String, List<Transaction>>> byDate = new HashMap<>();

    /**
     * @param liveAccountIds the user's live (non-soft-deleted) account ids, computed once by
     *                       {@code DuplicateDetector.indexFor} rather than re-derived per date --
     *                       this class is already a cache scoped to the duration of one parse, so
     *                       computing it once is both correct and consistent with that design.
     *                       Excludes a soft-deleted account's transactions, which the plain
     *                       user-scoped query would otherwise keep matching against forever.
     */
    DuplicateIndex(TransactionRepository transactionRepository, UUID userId, List<UUID> liveAccountIds) {
        this.transactionRepository = transactionRepository;
        this.userId = userId;
        this.liveAccountIds = liveAccountIds;
    }

    /**
     * Every existing transaction matching this row on date, amount and description -- the same
     * three-way equality the replaced query used, in the same order of precedence.
     *
     * <p>Returns the repository's own list so callers keep the "how many" signal that
     * {@code DuplicateDetector} reports: more than one match usually means the user genuinely
     * transacts this amount on this date repeatedly, which is precisely when skipping is wrong.
     */
    public List<Transaction> matches(LocalDate date, BigDecimal amount, String description) {
        if (date == null || amount == null || description == null) return List.of();
        return byDate
                .computeIfAbsent(date, this::loadDate)
                .getOrDefault(key(amount, description), List.of());
    }

    private Map<String, List<Transaction>> loadDate(LocalDate date) {
        if (liveAccountIds.isEmpty()) return new HashMap<>();
        Map<String, List<Transaction>> index = new HashMap<>();
        // Both bounds the same date: findByUserIdAndTxnDateBetweenAndAccountIdIn is inclusive, so
        // this is one day. Scoped to liveAccountIds so a soft-deleted account's transactions don't
        // keep matching against forever (see that method's own doc comment).
        for (Transaction existing : transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(
                userId, date, date, liveAccountIds)) {
            if (existing.getAmount() == null || existing.getDescription() == null) continue;
            index.computeIfAbsent(key(existing.getAmount(), existing.getDescription()),
                    k -> new java.util.ArrayList<>()).add(existing);
        }
        return index;
    }

    /** Value-not-scale, matching Postgres NUMERIC equality -- see the class comment. */
    static String normaliseAmount(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    /** A newline separator because a description can contain anything else, and a description
     *  ending in the separator followed by an empty one must not collide with its neighbour.
     *
     *  <p>A2 (two-pass mobile audit, 2026-09-01). Case- and space-folded via {@link
     *  DuplicateMatching#normalizeDescription}, matching {@code
     *  TransactionRepository.findPotentialDuplicatesByUserAndAccountIdIn} and {@code
     *  ReconciliationService.duplicateKey} -- all three implement the same "is this the same
     *  transaction" question and must agree, per this class's own equivalence contract
     *  ({@code DuplicateIndexIT.assertBothPathsAgree}). Two extractions of the same underlying
     *  bank line (a CSV export vs. a re-scraped PDF) can differ by nothing more than case or a
     *  stray leading/trailing space; raw equality treated those as two different transactions.
     *  Note the shared helper rather than an inlined {@code trim().toLowerCase()}: Java's {@code
     *  trim()} and SQL's {@code TRIM()} disagree about tabs and newlines, which would break the
     *  very equivalence this is meant to strengthen -- see {@link DuplicateMatching}. */
    private static String key(BigDecimal amount, String description) {
        return normaliseAmount(amount) + "\n" + DuplicateMatching.normalizeDescription(description);
    }
}
