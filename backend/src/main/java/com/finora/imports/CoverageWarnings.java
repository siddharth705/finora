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
 * <p>Phase 4 update: the duplicate sentence used to end "...isn't supported yet" -- it now does,
 * via {@link #duplicateOfStatementId} and the "Import this one as a replacement?" action it
 * drives (§0.23), so the sentence states the fact and leaves the action to the caller instead of
 * contradicting a button sitting right next to it.
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

        // Phase 4 (§0.3/§0.23): "isn't supported yet" is gone -- it now is, via duplicateOverlaps
        // below (and duplicateOfStatementId, its flattened first-match view) and the "Import this
        // one as a replacement?" action they drive, so each sentence states the fact and leaves the
        // action to the caller instead of contradicting a button sitting right next to it.
        for (DuplicateOverlap overlap : duplicateOverlaps(report, newStatementId, importedAtById)) {
            warnings.add(overlap.warning());
        }

        return warnings;
    }

    /**
     * One exact-duplicate-period overlap involving {@code newStatementId}, paired with the
     * OTHER statement's id and the exact sentence describing it. {@link #forNewStatement} and
     * {@link #duplicateOfStatementId} are both flattened views of this same data -- every
     * sentence concatenated into one list, only the first id kept -- which is all the common case
     * (one confirm producing at most one duplicate) ever needs. A caller that has to tell one
     * overlap's sentence apart from another's needs this instead: see {@code
     * StatementImportService.confirmReimport}, which strips exactly the overlap against the
     * statement it is reimporting and must leave any OTHER duplicate this same confirm also
     * produced -- a real, unrelated one -- fully intact. Before this paired the two, a caller in
     * that position could only filter warnings by string prefix and clear duplicateOfStatementId
     * by a single id comparison: two independently-flattened values with no way to agree with each
     * other once more than one overlap existed, which is exactly how a second, unrelated
     * duplicate's warning and id went missing together (found via self-review, Phase 4 follow-up).
     */
    public record DuplicateOverlap(UUID otherStatementId, String warning) {}

    /**
     * Every exact-duplicate overlap involving {@code newStatementId}, in the same order {@link
     * #forNewStatement} emits their sentences and {@link #duplicateOfStatementId} scans them --
     * both of those are built from this now, so they can no longer independently disagree about
     * which overlaps exist.
     */
    public static List<DuplicateOverlap> duplicateOverlaps(CoverageReport report, UUID newStatementId,
                                                              Map<UUID, Instant> importedAtById) {
        List<DuplicateOverlap> overlaps = new ArrayList<>();
        for (CoverageOverlap overlap : report.overlaps()) {
            if (overlap.type() != OverlapType.EXACT_DUPLICATE) continue;
            boolean involvesNewStatement = overlap.segmentAId().equals(newStatementId)
                    || overlap.segmentBId().equals(newStatementId);
            if (!involvesNewStatement) continue;

            UUID otherId = overlap.segmentAId().equals(newStatementId) ? overlap.segmentBId() : overlap.segmentAId();
            Instant otherImportedAt = importedAtById.get(otherId);
            String importedOnClause = otherImportedAt == null ? ""
                    : ", imported on " + LocalDate.ofInstant(otherImportedAt, ZoneOffset.UTC);
            overlaps.add(new DuplicateOverlap(otherId, DUPLICATE_PERIOD_WARNING_PREFIX + importedOnClause + "."));
        }
        return overlaps;
    }

    /**
     * The ORIGINAL statement's id when {@code newStatementId}'s own confirm produced an
     * exact-duplicate-period overlap, or null when it did not. Phase 4's "Import this one as a
     * replacement?" action needs this id to know what to supersede -- kept as a separate method
     * rather than folded into {@link #forNewStatement}'s {@code List<String>} return so that
     * widely-used shape (every test in this class, and {@code ImportService}'s one call site)
     * stays unchanged.
     */
    public static UUID duplicateOfStatementId(CoverageReport report, UUID newStatementId) {
        List<DuplicateOverlap> overlaps = duplicateOverlaps(report, newStatementId, Map.of());
        return overlaps.isEmpty() ? null : overlaps.get(0).otherStatementId();
    }
}
