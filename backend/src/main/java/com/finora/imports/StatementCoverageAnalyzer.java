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
 * <p><b>Non-standard periods</b> (that document's §0.17/§0.22) are still counted for gap/coverage
 * purposes -- their raw date range fills whatever calendar days they cover, so a real gap between
 * two OTHER segments on either side of one is never falsely reported and their own days count
 * toward {@code coveredDays}, per §0.22 (superseding an earlier draft of §0.12 that said the
 * opposite -- a real self-contradiction across two review rounds of the proposal, found and
 * resolved here rather than silently picked one way). What classification changes is narrower:
 * a non-standard segment is never tested for a boundary-day OVERLAP claim, since a broader
 * statement legitimately enclosing narrower ones (§7) is not automatically a conflict.
 *
 * <p><b>Bug found via self-review, fixed here.</b> An earlier version of this class walked only
 * ADJACENT pairs in sort order to find gaps and overlaps, and summed each segment's own duration
 * for {@code coveredDays}. Both are wrong the moment one statement's period nests around or
 * substantially overlaps others without being adjacent-by-index to all of them: a broader
 * statement can already cover the space between two narrower, non-touching ones, which the
 * adjacent-pairs walk had no way to see -- it reported a phantom gap there, and separately
 * double-counted the nested statements' days in the naive sum. The fix: {@code coveredDays} is the
 * size of the UNION of every segment's date range (merging overlapping/touching/nested ranges into
 * disjoint covered blocks, walked in one pass since the segments are sorted), and overlap detection
 * compares every pair of STANDARD segments, not just adjacent ones.
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

        MergeResult merged = mergeIntoCoveredBlocksAndGaps(sorted);
        List<CoverageOverlap> overlaps = findOverlaps(sorted, segments);

        long coveredDays = merged.coveredDays();
        long missingDays = merged.gaps().stream().mapToLong(CoverageGap::daysMissing).sum();
        Double coveragePercentage = (coveredDays + missingDays) == 0 ? null
                : Math.round((coveredDays * 1000.0) / (coveredDays + missingDays)) / 10.0;

        boolean hasGaps = !merged.gaps().isEmpty();
        boolean hasOverlaps = !overlaps.isEmpty();
        boolean hasNonStandardPeriods = segments.stream()
                .anyMatch(s -> s.classification() == Classification.NON_STANDARD_PERIOD);
        boolean hasDuplicatePeriods = overlaps.stream().anyMatch(o -> o.type() == OverlapType.EXACT_DUPLICATE);

        return new CoverageReport(segments, merged.gaps(), overlaps, coveredDays, missingDays, coveragePercentage,
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

    private record MergeResult(long coveredDays, List<CoverageGap> gaps) {}

    /** Merges every segment's date range -- regardless of classification, since even a
     *  non-standard segment's presence fills whatever days it spans (§0.22) -- into disjoint
     *  covered blocks in one pass over the sorted list. {@code coveredDays} is the sum of the
     *  merged blocks' lengths (the UNION of every segment's range), never a per-segment sum that
     *  would double-count an overlapping or nested segment's days. A gap is only ever the space
     *  between two blocks that genuinely could not be merged -- so a broader segment spanning the
     *  distance between two narrower, non-touching ones is credited for covering it, rather than
     *  the narrower pair being compared to each other in isolation and reporting a phantom hole. */
    private static MergeResult mergeIntoCoveredBlocksAndGaps(List<StatementPeriod> sorted) {
        List<CoverageGap> gaps = new ArrayList<>();
        long coveredDays = 0;

        LocalDate blockStart = sorted.get(0).periodStart();
        LocalDate blockEnd = sorted.get(0).periodEnd();
        StatementPeriod blockEndOwner = sorted.get(0);

        for (int i = 1; i < sorted.size(); i++) {
            StatementPeriod s = sorted.get(i);
            LocalDate expectedNextStart = blockEnd.plusDays(1);
            if (s.periodStart().isAfter(expectedNextStart)) {
                // A real gap: close out the current block, record it, start a new one.
                coveredDays += durationDays(blockStart, blockEnd);
                LocalDate gapStart = expectedNextStart;
                LocalDate gapEnd = s.periodStart().minusDays(1);
                gaps.add(new CoverageGap(gapStart, gapEnd, durationDays(gapStart, gapEnd), delta(blockEndOwner, s)));
                blockStart = s.periodStart();
                blockEnd = s.periodEnd();
                blockEndOwner = s;
            } else if (s.periodEnd().isAfter(blockEnd)) {
                // Touching, overlapping, or the single PNB boundary-reprint day -- extend the
                // block; classification never enters this decision, only raw calendar coverage.
                blockEnd = s.periodEnd();
                blockEndOwner = s;
            }
            // else: s is fully nested inside the current block -- already covered.
        }
        coveredDays += durationDays(blockStart, blockEnd);

        return new MergeResult(coveredDays, gaps);
    }

    /** Every pair of STANDARD segments with two or more shared days -- not just adjacent pairs in
     *  sort order, since a segment enclosing two others that don't touch each other must be
     *  flagged against both, not just the one next to it by index. A non-standard segment is never
     *  tested for an overlap claim (§0.22/§7): a broader statement legitimately enclosing narrower
     *  ones is not automatically a conflict. */
    private static List<CoverageOverlap> findOverlaps(List<StatementPeriod> sorted, List<CoverageSegment> segments) {
        List<CoverageOverlap> overlaps = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            if (segments.get(i).classification() != Classification.STANDARD) continue;
            for (int j = i + 1; j < sorted.size(); j++) {
                if (segments.get(j).classification() != Classification.STANDARD) continue;
                StatementPeriod a = sorted.get(i);
                StatementPeriod b = sorted.get(j);
                long sharedDays = sharedDays(a, b);
                if (sharedDays < 2) continue; // 0 = no overlap, 1 = the known PNB boundary day
                boolean exactDuplicate = a.periodStart().isEqual(b.periodStart())
                        && a.periodEnd().isEqual(b.periodEnd());
                LocalDate overlapStart = b.periodStart();
                LocalDate overlapEnd = a.periodEnd().isBefore(b.periodEnd()) ? a.periodEnd() : b.periodEnd();
                overlaps.add(new CoverageOverlap(a.statementImportId(), b.statementImportId(),
                        overlapStart, overlapEnd, exactDuplicate ? OverlapType.EXACT_DUPLICATE : OverlapType.PARTIAL));
            }
        }
        return overlaps;
    }

    /** {@code a} must start no later than {@code b} (guaranteed by the sorted-list iteration order
     *  every caller uses). §0.1's own definition, unchanged. */
    private static long sharedDays(StatementPeriod a, StatementPeriod b) {
        return a.periodEnd().isBefore(b.periodStart()) ? 0 : ChronoUnit.DAYS.between(b.periodStart(), a.periodEnd()) + 1;
    }

    private static BigDecimal delta(StatementPeriod a, StatementPeriod b) {
        if (a.closingBalance() == null || b.openingBalance() == null) return null;
        return a.closingBalance().subtract(b.openingBalance()).abs();
    }
}
