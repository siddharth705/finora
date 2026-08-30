package com.finora.imports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.finora.imports.StatementCoverageAnalyzer.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 of docs/proposals/statement-continuity-and-coverage-integrity-proposal.md (§11) --
 * import-time gap/duplicate-period notices. Pure logic over an already-computed {@link
 * StatementCoverageAnalyzer.CoverageReport}: which gap/overlap facts are worth telling the user
 * about *right now*, scoped to only the ones that directly involve the statement just persisted --
 * not a full audit of the account's entire history, which would re-nag about an old, unrelated
 * gap on every future unrelated import.
 */
class CoverageWarningsTest {

    private static final UUID NEW_ID = UUID.randomUUID();
    private static final UUID OTHER_ID = UUID.randomUUID();

    @Test
    @DisplayName("a gap immediately before the new statement produces a warning naming its dates and length")
    void gapImmediatelyBeforeNewStatement_isWarned() {
        CoverageGap gap = new CoverageGap(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), 30, null);
        CoverageReport report = new CoverageReport(List.of(), List.of(gap), List.of(),
                0, 30, null, true, false, false, false);

        List<String> warnings = CoverageWarnings.forNewStatement(report, NEW_ID,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), Map.of());

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("2026-06-01").contains("2026-06-30").contains("30 days");
    }

    @Test
    @DisplayName("a gap immediately after the new statement is also warned -- both sides matter")
    void gapImmediatelyAfterNewStatement_isWarned() {
        CoverageGap gap = new CoverageGap(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 31, null);
        CoverageReport report = new CoverageReport(List.of(), List.of(gap), List.of(),
                0, 31, null, true, false, false, false);

        List<String> warnings = CoverageWarnings.forNewStatement(report, NEW_ID,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), Map.of());

        assertThat(warnings).hasSize(1);
    }

    @Test
    @DisplayName("a single day's gap is worded in the singular")
    void oneDayGap_singularWording() {
        CoverageGap gap = new CoverageGap(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 30), 1, null);
        CoverageReport report = new CoverageReport(List.of(), List.of(gap), List.of(),
                0, 1, null, true, false, false, false);

        List<String> warnings = CoverageWarnings.forNewStatement(report, NEW_ID,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), Map.of());

        assertThat(warnings.get(0)).contains("1 day").doesNotContain("1 days");
    }

    @Test
    @DisplayName("a gap that does not touch the new statement is not surfaced -- old, unrelated history stays quiet")
    void unrelatedGapElsewhereOnTheAccount_isNotSurfaced() {
        // A gap between two OTHER statements entirely, nowhere near the one just imported.
        CoverageGap unrelatedGap = new CoverageGap(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), 31, null);
        CoverageReport report = new CoverageReport(List.of(), List.of(unrelatedGap), List.of(),
                0, 31, null, true, false, false, false);

        List<String> warnings = CoverageWarnings.forNewStatement(report, NEW_ID,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), Map.of());

        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("filling a gap produces no warning -- an empty gap list means nothing to say")
    void noGapsAtAll_noWarnings() {
        CoverageReport report = new CoverageReport(List.of(), List.of(), List.of(),
                92, 0, 100.0, false, false, false, false);

        List<String> warnings = CoverageWarnings.forNewStatement(report, NEW_ID,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), Map.of());

        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("an exact-duplicate overlap involving the new statement is warned, wording per §0.23, forward-compatible with a future replace action")
    void exactDuplicateInvolvingNewStatement_isWarned() {
        CoverageOverlap dup = new CoverageOverlap(NEW_ID, OTHER_ID,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), OverlapType.EXACT_DUPLICATE);
        CoverageReport report = new CoverageReport(List.of(), List.of(), List.of(dup),
                30, 0, 100.0, false, true, false, true);

        List<String> warnings = CoverageWarnings.forNewStatement(report, NEW_ID,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                Map.of(OTHER_ID, Instant.parse("2026-06-15T10:00:00Z")));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .contains("You already have a statement for this period")
                .contains("2026-06-15");
    }

    @Test
    @DisplayName("the duplicate warning still reads sensibly when the other statement's import date is unknown")
    void exactDuplicate_withoutImportedAtInTheMap_stillWarns() {
        CoverageOverlap dup = new CoverageOverlap(NEW_ID, OTHER_ID,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), OverlapType.EXACT_DUPLICATE);
        CoverageReport report = new CoverageReport(List.of(), List.of(), List.of(dup),
                30, 0, 100.0, false, true, false, true);

        List<String> warnings = CoverageWarnings.forNewStatement(report, NEW_ID,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), Map.of());

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("You already have a statement for this period");
    }

    @Test
    @DisplayName("an exact-duplicate overlap NOT involving the new statement is not surfaced")
    void exactDuplicateElsewhereOnTheAccount_isNotSurfaced() {
        UUID unrelatedA = UUID.randomUUID();
        UUID unrelatedB = UUID.randomUUID();
        CoverageOverlap dup = new CoverageOverlap(unrelatedA, unrelatedB,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), OverlapType.EXACT_DUPLICATE);
        CoverageReport report = new CoverageReport(List.of(), List.of(), List.of(dup),
                31, 0, 100.0, false, true, false, true);

        List<String> warnings = CoverageWarnings.forNewStatement(report, NEW_ID,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), Map.of());

        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("a PARTIAL overlap involving the new statement is out of scope for Phase 2 -- not warned")
    void partialOverlapInvolvingNewStatement_isNotSurfaced() {
        // §0.23 only specifies wording for the exact-duplicate case; a partial overlap could mean
        // several different things (wrong account, corrected re-upload with a different range) and
        // Phase 2's scope is limited to the two facts the roadmap actually names.
        CoverageOverlap partial = new CoverageOverlap(NEW_ID, OTHER_ID,
                LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 30), OverlapType.PARTIAL);
        CoverageReport report = new CoverageReport(List.of(), List.of(), List.of(partial),
                30, 0, 100.0, false, true, false, false);

        List<String> warnings = CoverageWarnings.forNewStatement(report, NEW_ID,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), Map.of());

        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("both a bordering gap and a duplicate can be warned together for the same import")
    void gapAndDuplicateTogether_bothWarned() {
        CoverageGap gap = new CoverageGap(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), 31, null);
        CoverageOverlap dup = new CoverageOverlap(NEW_ID, OTHER_ID,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), OverlapType.EXACT_DUPLICATE);
        CoverageReport report = new CoverageReport(List.of(), List.of(gap), List.of(dup),
                30, 31, null, true, true, false, true);

        List<String> warnings = CoverageWarnings.forNewStatement(report, NEW_ID,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), Map.of());

        assertThat(warnings).hasSize(2);
    }

    // --- duplicateOfStatementId: Phase 4's "Import this one as a replacement?" (§0.3/§0.23) needs
    // the ORIGINAL statement's id, not just prose, to know what to supersede. A separate method
    // rather than folding this into forNewStatement's return -- that shape is exercised by every
    // test above and by ImportService's one call site; this keeps both unchanged. ---

    @Test
    @DisplayName("duplicateOfStatementId returns the OTHER statement's id when the new one is an exact duplicate")
    void duplicateOfStatementId_returnsTheOtherStatementsId() {
        CoverageOverlap dup = new CoverageOverlap(NEW_ID, OTHER_ID,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), OverlapType.EXACT_DUPLICATE);
        CoverageReport report = new CoverageReport(List.of(), List.of(), List.of(dup),
                30, 0, 100.0, false, true, false, true);

        assertThat(CoverageWarnings.duplicateOfStatementId(report, NEW_ID)).isEqualTo(OTHER_ID);
    }

    @Test
    @DisplayName("duplicateOfStatementId finds the new statement on either side of the overlap")
    void duplicateOfStatementId_findsTheNewStatementOnEitherSide() {
        CoverageOverlap dup = new CoverageOverlap(OTHER_ID, NEW_ID,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), OverlapType.EXACT_DUPLICATE);
        CoverageReport report = new CoverageReport(List.of(), List.of(), List.of(dup),
                30, 0, 100.0, false, true, false, true);

        assertThat(CoverageWarnings.duplicateOfStatementId(report, NEW_ID)).isEqualTo(OTHER_ID);
    }

    @Test
    @DisplayName("duplicateOfStatementId is null when there is no exact-duplicate overlap involving the new statement")
    void duplicateOfStatementId_null_whenNoDuplicateInvolvesTheNewStatement() {
        CoverageReport report = new CoverageReport(List.of(), List.of(), List.of(), 30, 0, 100.0, false, false, false, false);

        assertThat(CoverageWarnings.duplicateOfStatementId(report, NEW_ID)).isNull();
    }

    @Test
    @DisplayName("duplicateOfStatementId ignores a PARTIAL overlap -- only EXACT_DUPLICATE qualifies")
    void duplicateOfStatementId_null_forAPartialOverlap() {
        CoverageOverlap partial = new CoverageOverlap(NEW_ID, OTHER_ID,
                LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 30), OverlapType.PARTIAL);
        CoverageReport report = new CoverageReport(List.of(), List.of(), List.of(partial),
                30, 0, 100.0, false, true, false, false);

        assertThat(CoverageWarnings.duplicateOfStatementId(report, NEW_ID)).isNull();
    }

    @Test
    @DisplayName("duplicateOfStatementId ignores a duplicate elsewhere on the account that doesn't involve the new statement")
    void duplicateOfStatementId_null_forADuplicateNotInvolvingTheNewStatement() {
        UUID unrelatedA = UUID.randomUUID();
        UUID unrelatedB = UUID.randomUUID();
        CoverageOverlap dup = new CoverageOverlap(unrelatedA, unrelatedB,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), OverlapType.EXACT_DUPLICATE);
        CoverageReport report = new CoverageReport(List.of(), List.of(), List.of(dup),
                31, 0, 100.0, false, true, false, true);

        assertThat(CoverageWarnings.duplicateOfStatementId(report, NEW_ID)).isNull();
    }
}
