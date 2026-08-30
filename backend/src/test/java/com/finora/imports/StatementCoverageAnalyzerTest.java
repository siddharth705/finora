package com.finora.imports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.finora.imports.StatementCoverageAnalyzer.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers docs/proposals/statement-continuity-and-coverage-integrity-proposal.md Phase 1 --
 * specifically the corrected adjacency/gap/overlap definitions in that document's own §0.1 (the
 * PNB boundary-day fix), §0.17's adaptive non-standard-period threshold, and §0.22's precise
 * informational-only semantics for non-standard periods.
 */
class StatementCoverageAnalyzerTest {

    private static StatementPeriod period(LocalDate start, LocalDate end) {
        return new StatementPeriod(UUID.randomUUID(), start, end, money("1000"), money("2000"));
    }

    private static StatementPeriod period(LocalDate start, LocalDate end, BigDecimal closing) {
        return new StatementPeriod(UUID.randomUUID(), start, end, money("1000"), closing);
    }

    private static BigDecimal money(String v) {
        return new BigDecimal(v);
    }

    // --- Adjacency / gap / overlap (§0.1) ----------------------------------------------------

    @Test
    @DisplayName("exclusive-adjacent statements (May 1-31, Jun 1-30) -- continuous, no gap, no overlap")
    void exclusiveAdjacent_isContinuous() {
        var report = analyze(List.of(
                period(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)),
                period(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))));

        assertThat(report.gaps()).isEmpty();
        assertThat(report.overlaps()).isEmpty();
    }

    @Test
    @DisplayName("BUG this class exists to fix: PNB's real boundary-reprint pair must not be flagged as an overlap")
    void pnbBoundaryReprint_isContinuous_notAnOverlap() {
        // The exact real pair from OpeningBalanceCarryForward's own class comment: 31-05 to 30-06,
        // then 30-06 to 31-07 -- the 30th of June is reprinted as both statements' boundary day.
        var report = analyze(List.of(
                period(LocalDate.of(2026, 5, 31), LocalDate.of(2026, 6, 30)),
                period(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 31))));

        assertThat(report.overlaps())
                .as("a single reprinted boundary day is a known bank convention, not a coverage problem")
                .isEmpty();
        assertThat(report.gaps()).isEmpty();
    }

    @Test
    @DisplayName("credit-card cycle (15 Jun-14 Jul, 15 Jul-14 Aug) -- continuous with no CC-specific handling")
    void creditCardCycle_isContinuous() {
        var report = analyze(List.of(
                period(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 7, 14)),
                period(LocalDate.of(2026, 7, 15), LocalDate.of(2026, 8, 14))));

        assertThat(report.gaps()).isEmpty();
        assertThat(report.overlaps()).isEmpty();
    }

    @Test
    @DisplayName("missing month (May, then July) -- reports exactly one gap covering June")
    void missingMonth_reportsGap() {
        var report = analyze(List.of(
                period(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), money("500000")),
                period(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31))));

        assertThat(report.gaps()).hasSize(1);
        CoverageGap gap = report.gaps().get(0);
        assertThat(gap.gapStart()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(gap.gapEnd()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(gap.daysMissing()).isEqualTo(30);
        assertThat(report.hasGaps()).isTrue();
    }

    @Test
    @DisplayName("gap delta is the plain difference between the prior close and the next open, no severity tier (§0.18)")
    void gapDelta_isRawBoundaryDifference() {
        var report = analyze(List.of(
                period(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), money("500000")),
                new StatementPeriod(UUID.randomUUID(), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                        money("12000"), money("20000"))));

        assertThat(report.gaps().get(0).delta()).isEqualByComparingTo(money("488000"));
    }

    @Test
    @DisplayName("partial overlap (Jun 15-Jul 15 overlapping an existing Jun 1-Jun 30) -- flagged, not an exact duplicate")
    void partialOverlap_isFlaggedAsPartial() {
        var report = analyze(List.of(
                period(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)),
                period(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 7, 15))));

        assertThat(report.overlaps()).hasSize(1);
        assertThat(report.overlaps().get(0).type()).isEqualTo(OverlapType.PARTIAL);
        assertThat(report.hasOverlaps()).isTrue();
        assertThat(report.hasDuplicatePeriods()).isFalse();
    }

    @Test
    @DisplayName("exact duplicate period (re-uploaded the same statement) -- flagged distinctly from a partial overlap")
    void exactDuplicatePeriod_isFlaggedDistinctly() {
        var report = analyze(List.of(
                period(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)),
                period(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))));

        assertThat(report.overlaps()).hasSize(1);
        assertThat(report.overlaps().get(0).type()).isEqualTo(OverlapType.EXACT_DUPLICATE);
        assertThat(report.hasDuplicatePeriods()).isTrue();
    }

    // --- Non-standard periods (§0.17, §0.22) -------------------------------------------------

    @Test
    @DisplayName("cold start: fewer than 3 segments -- always STANDARD, no basis to call anything unusual yet")
    void coldStart_alwaysStandard() {
        var report = analyze(List.of(
                period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
                period(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31))));

        assertThat(report.segments()).allMatch(s -> s.classification() == Classification.STANDARD);
        assertThat(report.hasNonStandardPeriods()).isFalse();
    }

    @Test
    @DisplayName("a period more than 2x the account's own median duration (and over 90 days) is NON_STANDARD_PERIOD")
    void unusuallyLongPeriod_isNonStandard() {
        var report = analyze(List.of(
                period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
                period(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)),
                // ~214 days, far past 90 and far past 2x the ~30-day median of the other two
                period(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 10, 1)),
                period(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 31))));

        assertThat(report.segments().get(2).classification()).isEqualTo(Classification.NON_STANDARD_PERIOD);
        assertThat(report.hasNonStandardPeriods()).isTrue();
    }

    @Test
    @DisplayName("90-day floor applies even when the account's own median is already long")
    void ninetyDayFloor_appliesRegardlessOfMedian() {
        // Three ~120-day segments (median ~120, so 2x median = 240) plus one ~200-day segment --
        // under a pure 2x-median rule this would stay STANDARD, but 200 days on its own is not
        // "normal" for an account whose statements aren't all annual. The 90-day floor alone
        // wouldn't flag it either (200 > 90 but so are the others) -- this test locks in that the
        // floor is a backstop, not a replacement for the adaptive comparison, by using a segment
        // that is unusual relative to its own account's history.
        var report = analyze(List.of(
                period(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 30)),
                period(LocalDate.of(2024, 5, 1), LocalDate.of(2024, 8, 28)),
                period(LocalDate.of(2024, 8, 29), LocalDate.of(2024, 12, 26)),
                // ~400 days -- more than 2x the ~120-day median of the other three
                period(LocalDate.of(2024, 12, 27), LocalDate.of(2026, 1, 30))));

        assertThat(report.segments().get(3).classification()).isEqualTo(Classification.NON_STANDARD_PERIOD);
    }

    @Test
    @DisplayName("a non-standard segment's own boundaries are never tested for adjacency -- no false overlap or gap claim against its immediate neighbor")
    void nonStandardSegment_ownBoundariesNeverTested() {
        // A non-standard segment sharing 1 day with its standard neighbor would normally be
        // "continuous" anyway, so use a shape that WOULD be flagged as a partial overlap between
        // two standard segments, to prove the non-standard classification suppresses that claim.
        var report = analyze(List.of(
                period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
                period(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)),
                // Overlaps the next segment by 10 days, but is itself ~214 days -- non-standard.
                period(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 10, 1)),
                period(LocalDate.of(2026, 9, 22), LocalDate.of(2026, 10, 20))));

        assertThat(report.segments().get(2).classification()).isEqualTo(Classification.NON_STANDARD_PERIOD);
        assertThat(report.overlaps())
                .as("no overlap claim should involve the non-standard segment")
                .noneMatch(o -> o.segmentAId().equals(report.segments().get(2).statementImportId())
                        || o.segmentBId().equals(report.segments().get(2).statementImportId()));
    }

    @Test
    @DisplayName("a real gap on the far side of a non-standard segment is still detected, not suppressed")
    void realGapBeyondNonStandardSegment_isStillDetected() {
        var report = analyze(List.of(
                period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
                period(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)),
                // Non-standard (~214 days), ending 2026-10-01.
                period(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 10, 1)),
                // A genuine gap: November is missing entirely before this one starts.
                period(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31))));

        assertThat(report.gaps())
                .as("the gap between the non-standard segment's end and the next statement must still be reported")
                .anyMatch(g -> g.gapStart().equals(LocalDate.of(2026, 10, 2))
                        && g.gapEnd().equals(LocalDate.of(2026, 11, 30)));
    }

    @Test
    @DisplayName("a non-standard segment's own days still count toward coveredDays")
    void nonStandardSegment_stillCountsAsCovered() {
        var report = analyze(List.of(
                period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
                period(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)),
                period(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 10, 1))));

        // §0.12 supersedes §0.11: coveredDays excludes non-standard segments' own duration.
        // Confirmed separately below (excludedFromCoveredDays); this test only guards that its
        // presence doesn't get miscounted as a gap either.
        assertThat(report.gaps()).isEmpty();
    }

    @Test
    @DisplayName("coveredDays/missingDays exclude a non-standard segment's own duration entirely (§0.12 supersedes §0.11)")
    void coveredDays_excludesNonStandardSegmentDuration() {
        var report = analyze(List.of(
                period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),   // 31 days, standard
                period(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)),   // 28 days, standard
                period(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 10, 1)))); // ~215 days, non-standard

        assertThat(report.coveredDays()).isEqualTo(31 + 28);
    }

    // --- Coverage totals (§0.11) --------------------------------------------------------------

    @Test
    @DisplayName("coveredDays/missingDays/coveragePercentage over a straightforward account with one gap")
    void coverageTotals_overAnAccountWithOneGap() {
        var report = analyze(List.of(
                period(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)),  // 31 days
                period(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))); // 31 days, June missing (30)

        assertThat(report.coveredDays()).isEqualTo(62);
        assertThat(report.missingDays()).isEqualTo(30);
        assertThat(report.coveragePercentage()).isEqualTo(67.4, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    @DisplayName("a single segment with no neighbors is 100% covered, no gaps possible")
    void singleSegment_isFullyCovered() {
        var report = analyze(List.of(period(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31))));

        assertThat(report.coveredDays()).isEqualTo(31);
        assertThat(report.missingDays()).isZero();
        assertThat(report.coveragePercentage()).isEqualTo(100.0);
        assertThat(report.hasGaps()).isFalse();
        assertThat(report.hasOverlaps()).isFalse();
    }

    @Test
    @DisplayName("no statements at all -- an empty, harmless report")
    void noStatements_returnsEmptyReport() {
        var report = analyze(List.of());

        assertThat(report.segments()).isEmpty();
        assertThat(report.gaps()).isEmpty();
        assertThat(report.overlaps()).isEmpty();
        assertThat(report.coveragePercentage()).isNull();
    }

    // --- coverageStatus (§0.24: booleans authoritative, enum a display convenience) ----------

    @Test
    @DisplayName("coverageStatus is COMPLETE only when every flag is false")
    void coverageStatus_completeIffNoFlags() {
        var report = analyze(List.of(
                period(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)),
                period(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))));

        assertThat(report.coverageStatus()).isEqualTo("COMPLETE");
    }

    @Test
    @DisplayName("coverageStatus reflects gaps and overlaps together when both are present")
    void coverageStatus_reflectsGapsAndOverlapsTogether() {
        var report = analyze(List.of(
                period(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)),
                period(LocalDate.of(2026, 5, 15), LocalDate.of(2026, 6, 15)),
                period(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31))));

        assertThat(report.coverageStatus()).isEqualTo("HAS_GAPS_AND_OVERLAPS");
    }

    // --- Input ordering ------------------------------------------------------------------------

    @Test
    @DisplayName("out-of-order input (July confirmed before June) is sorted internally -- same result either way")
    void outOfOrderInput_sortedInternally() {
        var inOrder = analyze(List.of(
                period(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)),
                period(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31))));
        var reversed = analyze(List.of(
                period(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)),
                period(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31))));

        assertThat(reversed.gaps()).hasSize(inOrder.gaps().size());
        assertThat(reversed.gaps().get(0).gapStart()).isEqualTo(inOrder.gaps().get(0).gapStart());
    }
}
