package com.finora.imports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Answers one question per account: do its imported statements form a continuous, non-overlapping
 * timeline? Phase 1 of docs/proposals/statement-continuity-and-coverage-integrity-proposal.md --
 * a pure function over whatever {@link StatementPeriod} rows a caller already has, following the
 * same shape as this package's other small domain classes ({@link OpeningBalanceCarryForward},
 * {@link ClosingBalanceGuard}, {@link BalanceSequenceResolver}): no repository, no side effect,
 * one clear question answered.
 *
 * <p><b>Two real bank conventions for how consecutive statement periods relate</b> (that document's
 * own §0.1, corrected after a review pass found the first draft's overlap rule would have flagged
 * every PNB statement pair as an overlap): a period can end the day before the next one starts
 * ({@code exclusive-adjacent}, the common case), or a bank can reprint the boundary date as both
 * the prior period's close and the next period's open ({@code sharedDays == 1} below) -- a real,
 * confirmed PNB pattern (see {@link OpeningBalanceCarryForward}'s own class comment), not a data
 * error. Only two or more shared days are a genuine overlap.
 *
 * <p><b>Non-standard periods</b> (that document's §0.17/§0.22) never get tested for adjacency
 * against their own neighbors -- a multi-month statement has no reason to satisfy a normal
 * statement's boundary-day convention -- but a real gap on the far side of one is still detected;
 * proximity to something unusual is never a reason to stay silent about a separate hole in the
 * timeline. Their own days still count toward {@code coveredDays}, per §0.11 as narrowed by §0.12.
 *
 * <p><b>No severity classification is produced here</b> (§0.18): the raw boundary-value {@code
 * delta} on a gap is a fact; bucketing it into tiers without real account-balance distributions to
 * calibrate against is exactly the kind of invented, uncalibrated number that document's own §4
 * argues against for a health score. {@code coverageStatus} is likewise a display convenience
 * derived from the boolean flags, not a second source of truth (§0.24) -- a real consumer branches
 * on the flags.
 */
public final class StatementCoverageAnalyzer {

    private StatementCoverageAnalyzer() {}

    /** One row per {@code StatementImport} for a single account -- not yet classified. Balances
     *  are optional (null when unknown) purely to compute a gap's {@code delta}; classification and
     *  gap/overlap detection never depend on them. */
    public record StatementPeriod(UUID statementImportId, LocalDate periodStart, LocalDate periodEnd,
                                   BigDecimal openingBalance, BigDecimal closingBalance) {}

    public enum Classification { STANDARD, NON_STANDARD_PERIOD }

    public record CoverageSegment(UUID statementImportId, LocalDate periodStart, LocalDate periodEnd,
                                   Classification classification) {}

    /** {@code delta} is null when either bounding statement's balance is unknown -- never guessed. */
    public record CoverageGap(LocalDate gapStart, LocalDate gapEnd, long daysMissing, BigDecimal delta) {}

    public enum OverlapType { EXACT_DUPLICATE, PARTIAL }

    public record CoverageOverlap(UUID segmentAId, UUID segmentBId, LocalDate overlapStart, LocalDate overlapEnd,
                                   OverlapType type) {}

    public record CoverageReport(List<CoverageSegment> segments, List<CoverageGap> gaps,
                                  List<CoverageOverlap> overlaps, long coveredDays, long missingDays,
                                  Double coveragePercentage, boolean hasGaps, boolean hasOverlaps,
                                  boolean hasNonStandardPeriods, boolean hasDuplicatePeriods) {

        /** A UI convenience only (§0.24) -- never a source of information the boolean flags don't
         *  already carry. {@code COMPLETE} iff every flag is false. */
        public String coverageStatus() {
            if (hasGaps && hasOverlaps) return "HAS_GAPS_AND_OVERLAPS";
            if (hasGaps) return "HAS_GAPS";
            if (hasOverlaps) return "HAS_OVERLAPS";
            if (hasNonStandardPeriods) return "HAS_NON_STANDARD_PERIODS";
            return "COMPLETE";
        }
    }

    private static final long NON_STANDARD_FLOOR_DAYS = 90;
    private static final int COLD_START_MIN_SEGMENTS = 3;

    public static CoverageReport analyze(List<StatementPeriod> periods) {
        if (periods == null || periods.isEmpty()) {
            return new CoverageReport(List.of(), List.of(), List.of(), 0, 0, null,
                    false, false, false, false);
        }

        List<StatementPeriod> sorted = periods.stream()
                .sorted(Comparator.comparing(StatementPeriod::periodStart)
                        .thenComparing(StatementPeriod::periodEnd))
                .toList();

        List<CoverageSegment> segments = classify(sorted);

        List<CoverageGap> gaps = new ArrayList<>();
        List<CoverageOverlap> overlaps = new ArrayList<>();
        for (int i = 0; i < sorted.size() - 1; i++) {
            evaluatePair(sorted.get(i), sorted.get(i + 1),
                    segments.get(i).classification(), segments.get(i + 1).classification(),
                    gaps, overlaps);
        }

        long coveredDays = segments.stream()
                .filter(s -> s.classification() == Classification.STANDARD)
                .mapToLong(s -> durationDays(s.periodStart(), s.periodEnd()))
                .sum();
        long missingDays = gaps.stream().mapToLong(CoverageGap::daysMissing).sum();
        Double coveragePercentage = (coveredDays + missingDays) == 0 ? null
                : Math.round((coveredDays * 1000.0) / (coveredDays + missingDays)) / 10.0;

        boolean hasGaps = !gaps.isEmpty();
        boolean hasOverlaps = !overlaps.isEmpty();
        boolean hasNonStandardPeriods = segments.stream()
                .anyMatch(s -> s.classification() == Classification.NON_STANDARD_PERIOD);
        boolean hasDuplicatePeriods = overlaps.stream().anyMatch(o -> o.type() == OverlapType.EXACT_DUPLICATE);

        return new CoverageReport(segments, gaps, overlaps, coveredDays, missingDays, coveragePercentage,
                hasGaps, hasOverlaps, hasNonStandardPeriods, hasDuplicatePeriods);
    }

    /** Adaptive threshold (§0.17): with fewer than {@link #COLD_START_MIN_SEGMENTS} statements on
     *  the account, there is no basis yet to call anything "unusual," so every segment is STANDARD.
     *  Otherwise a segment is NON_STANDARD_PERIOD when its own duration exceeds both a flat
     *  {@link #NON_STANDARD_FLOOR_DAYS}-day floor AND twice the median duration of every OTHER
     *  segment on the account -- the floor stops an account whose own history already skews long
     *  from normalizing its own outliers indefinitely. */
    private static List<CoverageSegment> classify(List<StatementPeriod> sorted) {
        List<Long> durations = sorted.stream()
                .map(p -> durationDays(p.periodStart(), p.periodEnd()))
                .toList();

        List<CoverageSegment> result = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            StatementPeriod p = sorted.get(i);
            Classification classification;
            if (sorted.size() < COLD_START_MIN_SEGMENTS) {
                classification = Classification.STANDARD;
            } else {
                List<Long> others = new ArrayList<>();
                for (int j = 0; j < durations.size(); j++) {
                    if (j != i) others.add(durations.get(j));
                }
                long threshold = Math.max(NON_STANDARD_FLOOR_DAYS, 2 * median(others));
                classification = durations.get(i) > threshold
                        ? Classification.NON_STANDARD_PERIOD
                        : Classification.STANDARD;
            }
            result.add(new CoverageSegment(p.statementImportId(), p.periodStart(), p.periodEnd(), classification));
        }
        return result;
    }

    private static long median(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private static long durationDays(LocalDate start, LocalDate end) {
        return ChronoUnit.DAYS.between(start, end) + 1;
    }

    /** {@code a} is the earlier segment in sort order, {@code b} the next. */
    private static void evaluatePair(StatementPeriod a, StatementPeriod b,
                                      Classification aClass, Classification bClass,
                                      List<CoverageGap> gaps, List<CoverageOverlap> overlaps) {
        long sharedDays = a.periodEnd().isBefore(b.periodStart())
                ? 0
                : ChronoUnit.DAYS.between(b.periodStart(), a.periodEnd()) + 1;

        if (sharedDays <= 0) {
            LocalDate expectedNextStart = a.periodEnd().plusDays(1);
            // A real gap is plain date arithmetic, independent of either side's classification --
            // proximity to a non-standard segment must never suppress a genuine hole (§0.22).
            if (b.periodStart().isAfter(expectedNextStart)) {
                LocalDate gapStart = expectedNextStart;
                LocalDate gapEnd = b.periodStart().minusDays(1);
                gaps.add(new CoverageGap(gapStart, gapEnd, durationDays(gapStart, gapEnd), delta(a, b)));
            }
            return;
        }

        // sharedDays >= 1: a non-standard segment's own boundaries are never tested for overlap
        // either (§0.22) -- a broader statement legitimately enclosing narrower ones (§7) is not
        // automatically a conflict.
        if (aClass == Classification.NON_STANDARD_PERIOD || bClass == Classification.NON_STANDARD_PERIOD) {
            return;
        }

        if (sharedDays == 1) {
            return; // the one known boundary-reprint day -- continuous, not an overlap.
        }

        boolean exactDuplicate = a.periodStart().isEqual(b.periodStart()) && a.periodEnd().isEqual(b.periodEnd());
        LocalDate overlapStart = b.periodStart();
        LocalDate overlapEnd = a.periodEnd().isBefore(b.periodEnd()) ? a.periodEnd() : b.periodEnd();
        overlaps.add(new CoverageOverlap(a.statementImportId(), b.statementImportId(), overlapStart, overlapEnd,
                exactDuplicate ? OverlapType.EXACT_DUPLICATE : OverlapType.PARTIAL));
    }

    private static BigDecimal delta(StatementPeriod a, StatementPeriod b) {
        if (a.closingBalance() == null || b.openingBalance() == null) return null;
        return a.closingBalance().subtract(b.openingBalance()).abs();
    }
}
