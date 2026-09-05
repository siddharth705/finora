package com.finora.imports.trust;

import com.finora.dto.ImportDto;
import com.finora.imports.RowAccountingValidator;
import com.finora.imports.SummaryTotalsValidator;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether an extraction is trustworthy enough to reach a user's ledger unreviewed.
 *
 * <p>Three conditions, and deliberately only three. Each is a signal the pipeline already
 * computes, chosen because it is evidence that a specific transaction is <em>wrong or missing</em>
 * rather than evidence that extraction was merely difficult:
 *
 * <ol>
 *   <li><b>Printed vs parsed count mismatch.</b> The document grades its own extraction -- the
 *       bank printed how many debits and credits it believes are there. Amounts-only mismatches
 *       are excluded: a wrong amount is a different defect from a missing row.</li>
 *   <li><b>A confirmed dropped transaction.</b> Only {@code PRE_HEADER_ACTIVITY_CANDIDATE}, the
 *       one dropped-row reason verified against real documents to mean a genuinely lost
 *       transaction rather than a merely unexplained row.</li>
 *   <li><b>Statement period integrity.</b> A period that ends before it starts, sits in the
 *       future, or spans more than {@value #MAX_PERIOD_DAYS} days did not come out of the
 *       document correctly.</li>
 * </ol>
 *
 * <h2>What is deliberately excluded</h2>
 * OCR provenance, column ambiguity, header-reconstruction uncertainty, balance-chain
 * discrepancies, duplicates and missing account metadata are all observed and persisted, and none
 * of them hold an import. Nor does the aggregate {@code ImportReliabilityStatus}: this reads the
 * specific findings so the verdict and the gate can be tuned independently. A <b>missing</b>
 * period never holds either -- corpus data showed that would quarantine most good imports. Each
 * becomes a candidate only once telemetry shows its real distribution.
 *
 * <p>Pure, static and side-effect-free by design. It runs on the worker's success path, where an
 * exception would turn a merely-unverified import into a failed one, so every input is treated as
 * possibly null: a CSV import has no verification reports at all, and a section can carry no
 * period.
 */
public final class TrustPredicate {

    /** Beyond this, a "statement period" is not a statement period. Generous on purpose -- annual
     *  and 13-month statements are real, and this is looking for nonsense, not for unusual. */
    public static final long MAX_PERIOD_DAYS = 400;

    /**
     * The {@code suspectedCause} values that mean the COUNTS disagree, as opposed to the amounts.
     *
     * <p>An allow-list rather than "everything except AMOUNTS", deliberately. A cause added to
     * {@link SummaryTotalsValidator} later must not begin quarantining live imports the moment it
     * ships, before anyone has seen how often it fires -- that is the same observe-then-gate rule
     * every excluded signal above follows, applied to a signal that does not exist yet.
     *
     * <p>{@code PRINTED_ACTIVITY_WITH_ZERO_STAGED} is the extreme member and the easiest to miss:
     * the validator emits it with outcome WARNING rather than FAILED (its own comment explains
     * why -- the data did not fail validation, it never arrived), so anything gating on FAILED
     * would skip the case that validator itself calls the strongest evidence a read failed. It is
     * reachable on an otherwise-successful import because
     * {@code ExtractionCheck.rejectIfNothingWasExtracted} flattens all sections into one
     * whole-document view and throws only when the entire document staged nothing: a composite
     * statement whose second section staged zero rows while printing activity imports today, one
     * account short.
     */
    private static final Set<String> COUNT_MISMATCH_CAUSES = Set.of(
            "DIRECTION",
            "ROW_GROUPING",
            "MISSING_OR_EXTRA_ROWS",
            SummaryTotalsValidator.PRINTED_ACTIVITY_WITH_ZERO_STAGED);

    /** Key inside {@code ROW_ACCOUNTING}'s {@code droppedTransactionCandidateReasons} histogram.
     *  Mirrors {@code ImportReliabilityStatusDeriver}'s own constant; the literal is produced by
     *  {@code PdfTableLocator}. */
    private static final String PRE_HEADER_ACTIVITY_CANDIDATE = "PRE_HEADER_ACTIVITY_CANDIDATE";

    private static final String DROPPED_REASONS = "droppedTransactionCandidateReasons";
    private static final String SUSPECTED_CAUSE = "suspectedCause";

    private TrustPredicate() {}

    /** The machine-readable tag behind each of {@code evaluate}'s reason sentences -- see Plan 4's
     *  Decisions table for why {@code held_statements.hold_reason_categories} exists rather than
     *  parsing {@code trigger_summary}'s prose back apart. */
    public enum Category { COUNT_MISMATCH, DROPPED_TRANSACTION, PERIOD_INTEGRITY }

    /**
     * @param reports one report per account section, or null for an import that verified nothing
     * @param periods one {@code {start, end}} pair per account section; the array, or either
     *                element, may be null -- which is never on its own a reason to hold
     * @param today   the clock, injected so the future-period rule is testable
     */
    public static HoldDecision evaluate(List<ImportDto.VerificationReport> reports,
                                        List<LocalDate[]> periods,
                                        LocalDate today) {
        // Insertion-ordered and de-duplicating: several sections of one statement commonly fail
        // the same way, and telling an operator "the counts disagree" three times reads as three
        // separate problems.
        Set<String> reasons = new LinkedHashSet<>();
        Set<Category> categories = new LinkedHashSet<>();

        if (reports != null) {
            for (ImportDto.VerificationReport report : reports) {
                if (report == null || report.findings() == null) continue;
                for (ImportDto.VerificationFinding finding : report.findings()) {
                    if (finding == null) continue;
                    countMismatch(finding).ifPresent(r -> {
                        reasons.add(r);
                        categories.add(Category.COUNT_MISMATCH);
                    });
                    droppedTransaction(finding).ifPresent(r -> {
                        reasons.add(r);
                        categories.add(Category.DROPPED_TRANSACTION);
                    });
                }
            }
        }
        if (periods != null) {
            for (LocalDate[] period : periods) {
                periodIntegrity(period, today).ifPresent(r -> {
                    reasons.add(r);
                    categories.add(Category.PERIOD_INTEGRITY);
                });
            }
        }

        return reasons.isEmpty() ? HoldDecision.RELEASE
                : new HoldDecision(true, List.copyOf(reasons), List.copyOf(categories));
    }

    /** Keyed on the cause rather than the outcome -- see {@link #COUNT_MISMATCH_CAUSES} for why
     *  the outcome is the wrong thing to filter on here. */
    private static Optional<String> countMismatch(ImportDto.VerificationFinding finding) {
        if (!SummaryTotalsValidator.RULE.equals(finding.rule()) || finding.details() == null) {
            return Optional.empty();
        }
        Object cause = finding.details().get(SUSPECTED_CAUSE);
        if (!(cause instanceof String named) || !COUNT_MISMATCH_CAUSES.contains(named)) {
            return Optional.empty();
        }
        return Optional.of("Printed and parsed transaction count disagree (" + named + ")");
    }

    private static Optional<String> droppedTransaction(ImportDto.VerificationFinding finding) {
        if (!RowAccountingValidator.RULE.equals(finding.rule()) || finding.details() == null) {
            return Optional.empty();
        }
        Object reasons = finding.details().get(DROPPED_REASONS);
        if (reasons instanceof Map<?, ?> map && map.containsKey(PRE_HEADER_ACTIVITY_CANDIDATE)) {
            return Optional.of("A transaction was likely dropped before the header row");
        }
        return Optional.empty();
    }

    private static Optional<String> periodIntegrity(LocalDate[] period, LocalDate today) {
        if (period == null || period.length != 2 || period[0] == null || period[1] == null) {
            // A missing period is not a defect this gates on -- see the class doc.
            return Optional.empty();
        }
        LocalDate start = period[0];
        LocalDate end = period[1];

        if (end.isBefore(start)) {
            return Optional.of("Statement period ends before it starts");
        }
        if (today != null && (start.isAfter(today) || end.isAfter(today))) {
            return Optional.of("Statement period is in the future");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_PERIOD_DAYS) {
            return Optional.of("Statement period spans more than " + MAX_PERIOD_DAYS + " days");
        }
        return Optional.empty();
    }
}
