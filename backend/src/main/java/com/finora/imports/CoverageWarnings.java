package com.finora.imports;

import com.finora.imports.StatementCoverageAnalyzer.CoverageGap;
import com.finora.imports.StatementCoverageAnalyzer.CoverageOverlap;
import com.finora.imports.StatementCoverageAnalyzer.CoverageReport;
import com.finora.imports.StatementCoverageAnalyzer.OverlapType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 2 of docs/proposals/statement-continuity-and-coverage-integrity-proposal.md (§11) --
 * import-time gap/duplicate-period notices. Reads a {@link StatementCoverageAnalyzer.CoverageReport}
 * already computed for the whole account (which, by the time this runs, includes the statement
 * just persisted) and picks out only the facts that actually involve that statement -- not a full
 * audit of the account's history. An old, unrelated gap elsewhere on the account must not resurface
 * as a warning on every future, unrelated import; it only gets mentioned again the moment a new
 * import happens to border it directly.
 *
 * <p>Scoped to exactly the two facts the roadmap names for this phase: a gap now bordering the new
 * statement on either side, and an exact-duplicate period involving it. A {@code PARTIAL} overlap
 * is deliberately out of scope here -- §0.23 only specifies wording for the duplicate case, and a
 * partial overlap could mean several different things (wrong account, a corrected re-upload with a
 * genuinely different range) that a one-line import-time notice can't disambiguate responsibly.
 *
 * <p>The duplicate wording is written to extend, not replace, once supersession (Phase 4) ships:
 * "isn't supported yet" states the current limitation honestly rather than implying a dead end,
 * so the eventual "Import this one as a replacement?" action reads as a continuation of the same
 * sentence a user already saw, not a new UX pattern (§0.23).
 */
public final class CoverageWarnings {

    private CoverageWarnings() {}

    /** The fixed opening of the duplicate-period sentence -- exposed so a caller with a reason to
     *  know a "duplicate" isn't really one (see {@code StatementImportService.confirmReimport},
     *  which reimport-confirms a statement without deleting the original it's correcting, so this
     *  code has no way to tell the two apart on its own) can filter it back out by prefix, without
     *  this class or ImportService's confirm/persistSection/summarise pipeline needing to know
     *  about that one caller's special case. */
    public static final String DUPLICATE_PERIOD_WARNING_PREFIX = "You already have a statement for this period";

    /**
     * @param report            the whole account's coverage report, computed AFTER the new
     *                          statement was persisted
     * @param newStatementId    the just-persisted statement's id
     * @param newPeriodStart    the just-persisted statement's printed period start
     * @param newPeriodEnd      the just-persisted statement's printed period end
     * @param importedAtById    every statement's import timestamp on the account, keyed by id --
     *                          used only to name when the OTHER side of a duplicate was imported;
     *                          a missing entry is not an error, the sentence just omits that detail
     */
    public static List<String> forNewStatement(CoverageReport report, UUID newStatementId,
                                                 LocalDate newPeriodStart, LocalDate newPeriodEnd,
                                                 Map<UUID, Instant> importedAtById) {
        List<String> warnings = new ArrayList<>();

        for (CoverageGap gap : report.gaps()) {
            boolean bordersOnEitherSide = gap.gapEnd().plusDays(1).equals(newPeriodStart)
                    || gap.gapStart().minusDays(1).equals(newPeriodEnd);
            if (bordersOnEitherSide) {
                warnings.add(String.format("Missing statement detected: %s to %s (%d day%s).",
                        gap.gapStart(), gap.gapEnd(), gap.daysMissing(), gap.daysMissing() == 1 ? "" : "s"));
            }
        }

        for (CoverageOverlap overlap : report.overlaps()) {
            if (overlap.type() != OverlapType.EXACT_DUPLICATE) continue;
            boolean involvesNewStatement = overlap.segmentAId().equals(newStatementId)
                    || overlap.segmentBId().equals(newStatementId);
            if (!involvesNewStatement) continue;

            UUID otherId = overlap.segmentAId().equals(newStatementId) ? overlap.segmentBId() : overlap.segmentAId();
            Instant otherImportedAt = importedAtById.get(otherId);
            String importedOnClause = otherImportedAt == null ? ""
                    : ", imported on " + LocalDate.ofInstant(otherImportedAt, ZoneOffset.UTC);
            warnings.add(DUPLICATE_PERIOD_WARNING_PREFIX + importedOnClause
                    + ". Replacing an existing statement isn't supported yet.");
        }

        return warnings;
    }
}
