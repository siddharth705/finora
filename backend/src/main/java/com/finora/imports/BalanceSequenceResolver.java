package com.finora.imports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves which order a whole statement's transactions actually happened in, day by day, so a
 * true opening and closing balance can be derived without guessing.
 *
 * <p><b>Why this exists, and why it is not just {@link BalanceChainUtil}.</b>
 * {@code BalanceChainUtil.first()}/{@code last()} only ever look at a single same-day group in
 * isolation, and when that group's internal chain-walk finds no unique answer, silently fall back
 * to {@code max}/{@code min(balanceAfter)} -- an unvalidated assumption ("last" == "the day's
 * highest balance") that is provably wrong whenever a same-day cluster contains a full reversal (a
 * credit immediately offset by a debit of the same amount, or the reverse): the round trip closes a
 * numeric loop, both candidates end up looking like they have a successor, and the fallback picks
 * the day's peak instead of its true last transaction. Confirmed against two real documents in the
 * project's corpus -- see {@code docs/architecture/system-design/same-day-reversal-closing-balance-investigation.md}
 * and {@code balance-chain-ordering-design.md}.
 *
 * <p>The fix is not a better within-group heuristic -- no such heuristic can distinguish the two
 * orderings from balance values alone once a subset nets to zero. It needs information from
 * <em>outside</em> the group: an anchor. This class owns exactly that responsibility -- walking a
 * whole statement's days in order, carrying a resolved balance forward from one day to the next --
 * which neither existing call site has today, each only ever computing its statement's boundary
 * (earliest/latest date) independently of the other. {@link BalanceChainUtil} stays exactly what it
 * already is: a stateless, single-group calculation helper, unmodified, unused by this class (which
 * needs a materially different algorithm once an anchor is available -- see {@link #resolveDay}).
 *
 * <p><b>A day's own internal resolution always wins when it is already unique.</b> The anchor is
 * consulted only to break a genuine same-day tie, never to second-guess a day that already resolves
 * on its own. This is not an optimization -- it is what keeps this class scoped to the bug it exists
 * to fix. An earlier version of this algorithm required every day's transactions to explain the
 * incoming anchor exactly, which is a strictly stronger claim than "same-day ordering is
 * determinable" -- it also demands day-to-day balance CONTINUITY across the whole statement, a
 * different, already-existing concern ({@link BalanceChainValidator}). A real regression surfaced
 * this directly: a synthetic multi-day fixture with a perfectly self-consistent (and unambiguous)
 * 3-transaction cluster on a middle day whose own starting balance simply does not connect to the
 * previous day's close -- nothing to do with same-day ordering at all -- was wrongly marked
 * {@code AMBIGUOUS} under the stricter rule. A closed loop (the actual bug) is exactly the case
 * where a day's own internal resolution is NOT unique (see {@link #resolveInternally}) -- so
 * preferring internal resolution whenever it succeeds never masks the bug this class exists to fix,
 * while no longer inventing a stronger continuity requirement nothing asked for.
 *
 * <p><b>Anchor source, deliberately narrow.</b> The only anchor used is the statement's own
 * <em>explicit</em> opening-balance declaration -- a same-day observation whose description names
 * itself as one (matching the existing, pre-established {@code isExplicitOpeningRow} convention
 * both prior call sites already used) -- or, for every day after the first, the previous day's own
 * resolved closing balance. The statement's own <em>printed closing balance</em> is deliberately
 * never used as an anchor: doing so would make ordering resolution consult the exact value
 * {@link StatementTotalsValidator} independently checks the resolved sequence against, making that
 * check circular.
 *
 * <p><b>No forced guess.</b> When a day's transactions cannot be uniquely ordered -- internally, or
 * (only when internal resolution is itself ambiguous) from the anchor -- the whole resolution is
 * {@link AmbiguityStatus#AMBIGUOUS}: no opening/closing balance is returned at all, rather than a
 * value nothing downstream could distinguish from a confirmed one. This is the same standing
 * "unknown is better than confidently wrong" discipline {@code ImportVerifier}'s own class comment
 * already states for a different reason (an invented weighting policy).
 *
 * <p><b>Print order as a last-resort, self-calibrated tiebreaker.</b> {@link #walkFromAnchor} can
 * still hit a genuine tie -- more than one remaining transaction implies the same running balance
 * (confirmed on a real BOB.pdf statement: two pairs of duplicate &plusmn;2.00 micro-transactions on
 * one day produce two candidates with identical implied predecessors). A real document's OWN
 * printed row order is additional information from outside the balance chain, exactly the kind of
 * anchor the class-level note above says this problem needs -- but it is not universally reliable:
 * a real PNB ONE statement (confirmed, and the direct reason {@link #findExplicitOpeningRow}/
 * {@link BalanceChainUtil} exist at all) prints its same-day cluster newest-first, the reverse of
 * true order. Trusting print order unconditionally would silently reintroduce this class's own bug
 * on exactly that kind of document, just via a different wrong assumption.
 *
 * <p>The fix is to never assume -- verify. {@link #isPrintOrderReliable} checks the SAME document's
 * own other days: every day that already resolves uniquely via {@link #resolveInternally} alone
 * (pure balance math, no anchor, no print-order assumption) is compared against its own printed
 * order. Only when every one of those checks agrees -- and at least one exists to check -- is print
 * order trusted as a tiebreaker for a DIFFERENT day on the SAME document that balance math alone
 * cannot resolve. Confirmed against the real corpus (2026-09-01): every checkable day across every
 * document except PNB ONE matches its own print order (220/220); PNB ONE's own days mismatch
 * uniformly (0/13) -- a clean, self-evident split with no document landing in between. A document
 * with no independently-resolvable days at all (nothing to check) is treated the same as one that
 * failed the check: refuse rather than guess.
 */
public final class BalanceSequenceResolver {

    private BalanceSequenceResolver() {}

    /** Minimal shape this resolver needs from a same-day balance observation -- {@link
     *  BalanceChainUtil.ChainLink}'s own two fields, plus the date and description every existing
     *  per-format observation record already carries. */
    public interface DatedLink extends BalanceChainUtil.ChainLink {
        LocalDate date();
        String description();
    }

    public enum AnchorSource { STATEMENT_OPENING_BALANCE, NONE }

    public enum AmbiguityStatus { UNIQUE, AMBIGUOUS }

    /**
     * @param orderedTransactions the resolved chronological sequence when {@code ambiguityStatus}
     *                            is {@code UNIQUE}; the original, unordered input otherwise
     * @param openingBalance      the balance immediately before the first transaction, or {@code
     *                            null} when ambiguous
     * @param closingBalance      the balance immediately after the last transaction, or {@code
     *                            null} when ambiguous
     * @param evidence            a short, human-readable explanation of why this ordering was
     *                            chosen, or why none could be
     */
    public record Resolution(List<? extends DatedLink> orderedTransactions, BigDecimal openingBalance,
                              BigDecimal closingBalance, AnchorSource anchorSource,
                              AmbiguityStatus ambiguityStatus, String evidence) {
    }

    public static <T extends DatedLink> Resolution resolve(List<T> observations) {
        if (observations == null || observations.isEmpty()) {
            return new Resolution(List.of(), null, null, AnchorSource.NONE, AmbiguityStatus.UNIQUE,
                    "No balance observations to resolve.");
        }

        Map<LocalDate, List<T>> byDate = new LinkedHashMap<>();
        for (T obs : observations) {
            byDate.computeIfAbsent(obs.date(), d -> new ArrayList<>()).add(obs);
        }
        List<LocalDate> days = new ArrayList<>(byDate.keySet());
        days.sort(LocalDate::compareTo);

        boolean printOrderTrusted = isPrintOrderReliable(byDate);

        List<T> ordered = new ArrayList<>();
        AnchorSource anchorSource = AnchorSource.NONE;
        BigDecimal openingBalance = null;
        BigDecimal runningAnchor = null; // the previous day's resolved close; null until day 1 resolves
        boolean usedPrintOrderTiebreak = false;

        for (int i = 0; i < days.size(); i++) {
            List<T> day = byDate.get(days.get(i));
            boolean isFirstDay = i == 0;
            T explicitOpeningRow = isFirstDay ? findExplicitOpeningRow(day) : null;

            DayResolution<T> dayResolved = resolveDay(day, explicitOpeningRow, runningAnchor, printOrderTrusted);
            if (dayResolved == null) {
                return ambiguous(observations, "Day " + days.get(i) + " has more than one "
                        + "transaction and no unique ordering could be found -- neither the day's "
                        + "own transactions, nor" + (runningAnchor != null || explicitOpeningRow != null
                                ? " the available anchor," : " any anchor (none available),")
                        + (printOrderTrusted ? " nor this document's own (independently verified reliable)"
                                + " print order," : "")
                        + " determine it uniquely.");
            }
            List<T> dayOrdered = dayResolved.ordered();
            if (dayResolved.usedPrintOrderTiebreak()) usedPrintOrderTiebreak = true;
            ordered.addAll(dayOrdered);

            if (isFirstDay) {
                anchorSource = explicitOpeningRow != null ? AnchorSource.STATEMENT_OPENING_BALANCE : AnchorSource.NONE;
                T trueFirst = dayOrdered.get(0);
                openingBalance = explicitOpeningRow != null ? explicitOpeningRow.balanceAfter()
                        : trueFirst.balanceAfter().subtract(trueFirst.signedAmount());
            }
            runningAnchor = dayOrdered.get(dayOrdered.size() - 1).balanceAfter();
        }

        String evidence = "Resolved " + days.size() + " day(s), " + observations.size()
                + " transaction(s) from anchor=" + anchorSource + "; same-day ambiguity: none."
                + (usedPrintOrderTiebreak ? " Print order used to break a genuine same-day tie on at "
                        + "least one day (verified reliable against this document's own other days)." : "");
        return new Resolution(ordered, openingBalance, runningAnchor, anchorSource, AmbiguityStatus.UNIQUE, evidence);
    }

    /** One day's resolved order, and whether reaching it needed the print-order tiebreaker -- kept
     *  separate from the {@code List<T>} itself so {@link #resolve}'s evidence string can report
     *  the tiebreak honestly without re-deriving whether it fired. */
    private record DayResolution<T extends DatedLink>(List<T> ordered, boolean usedPrintOrderTiebreak) {}

    /**
     * Resolves one day's transaction order. A day's own internal resolution (does the day's own
     * balances form a unique, self-contained chain?) is tried first and, if unique, used as-is --
     * never second-guessed against an incoming anchor (see the class-level note on why). The anchor
     * is consulted only when internal resolution is itself ambiguous: an explicit opening-balance
     * declaration for day 1, or the previous day's resolved close for every later day. Print order
     * (see the class-level "last-resort, self-calibrated tiebreaker" note) is consulted only when
     * even the anchor walk hits a genuine tie, and only when {@code printOrderTrusted}.
     */
    private static <T extends DatedLink> DayResolution<T> resolveDay(
            List<T> day, T explicitOpeningRow, BigDecimal incomingAnchor, boolean printOrderTrusted) {
        if (day.size() == 1) return new DayResolution<>(new ArrayList<>(day), false);

        if (explicitOpeningRow != null) {
            // An explicit declaration is authoritative for day 1 and is not itself a real
            // transaction (see StatementValidator/PdfPreviewGenerator's own isExplicitOpeningRow
            // history) -- always anchor from it directly rather than attempting internal
            // resolution, which has no meaningful "implied pre-balance" for a label row.
            return walkFromAnchorWithFallback(day, explicitOpeningRow.balanceAfter(), explicitOpeningRow, printOrderTrusted);
        }

        // resolveInternally is deliberately never offered the print-order tiebreaker -- it is the
        // pure, anchor-free signal isPrintOrderReliable itself calibrates trust from, and letting
        // it lean on the conclusion it is used to validate would be circular.
        List<T> internal = resolveInternally(day);
        if (internal != null) return new DayResolution<>(internal, false);

        return incomingAnchor != null
                ? walkFromAnchorWithFallback(day, incomingAnchor, null, printOrderTrusted) : null;
    }

    /** {@link #walkFromAnchor} first, exactly as before; only on a genuine tie ({@code null}) and
     *  only when {@code printOrderTrusted}, retries with the tiebreaker enabled. Two separate calls
     *  rather than one parameterized one, so the ordinary (non-ambiguous) path -- the overwhelming
     *  majority of days -- never even evaluates whether a tie exists more than once. */
    private static <T extends DatedLink> DayResolution<T> walkFromAnchorWithFallback(
            List<T> group, BigDecimal startBalance, T alreadyPlaced, boolean printOrderTrusted) {
        List<T> resolved = walkFromAnchor(group, startBalance, alreadyPlaced, false);
        if (resolved != null) return new DayResolution<>(resolved, false);
        if (!printOrderTrusted) return null;
        List<T> tieBroken = walkFromAnchor(group, startBalance, alreadyPlaced, true);
        return tieBroken == null ? null : new DayResolution<>(tieBroken, true);
    }

    /**
     * Walks a day's transactions forward from a known starting balance: at each step, exactly one
     * unplaced transaction's implied pre-balance ({@code balanceAfter - signedAmount}) must equal
     * the current running balance. Zero matches means the day's transactions don't explain the
     * anchor at all; more than one match means a genuine branch ambiguity (most commonly two
     * transactions of the same amount at the same point in the chain). Either way, {@code null} --
     * no forced guess. Naturally handles a loop of any width (not just a single reversing pair),
     * since it never special-cases "closed loop" at all -- it only ever asks "who comes next."
     *
     * @param startBalance the running balance to search from -- when {@code alreadyPlaced} is
     *                      given, this MUST be {@code alreadyPlaced.balanceAfter()} (the position
     *                      after it, not before it), since the walk searches for whoever comes
     *                      NEXT, not for {@code alreadyPlaced} itself
     * @param alreadyPlaced a transaction already known to be first (an explicit opening-balance
     *                      declaration, or the day's own uniquely-determined first transaction),
     *                      placed without being searched for; {@code null} when nothing is
     *                      pre-placed and {@code startBalance} is itself the value to search for
     * @param printOrderTiebreak when a step's candidates are otherwise indistinguishable by
     *                      balance, break the tie by picking whichever one appears earliest in
     *                      {@code group}'s own original (print) order, instead of refusing --
     *                      see the class-level "last-resort, self-calibrated tiebreaker" note for
     *                      when a caller may safely set this
     */
    private static <T extends DatedLink> List<T> walkFromAnchor(
            List<T> group, BigDecimal startBalance, T alreadyPlaced, boolean printOrderTiebreak) {
        List<T> remaining = new ArrayList<>(group);
        List<T> result = new ArrayList<>();
        BigDecimal running = startBalance;
        if (alreadyPlaced != null) {
            remaining.remove(alreadyPlaced);
            result.add(alreadyPlaced);
        }
        while (!remaining.isEmpty()) {
            List<T> matches = new ArrayList<>();
            for (T candidate : remaining) {
                BigDecimal impliedPre = candidate.balanceAfter().subtract(candidate.signedAmount());
                if (impliedPre.compareTo(running) == 0) matches.add(candidate);
            }
            T next;
            if (matches.size() == 1) {
                next = matches.get(0);
            } else if (matches.size() > 1 && printOrderTiebreak) {
                next = matches.stream().min(Comparator.comparingInt(group::indexOf)).orElseThrow();
            } else {
                return null;
            }
            result.add(next);
            remaining.remove(next);
            running = next.balanceAfter();
        }
        return result;
    }

    /** Finds a day's own unique "no predecessor" transaction from its balances alone (mirroring
     *  {@link BalanceChainUtil#first}'s primary chain-walk, minus its unvalidated fallback), then
     *  chains forward from it. {@code null} when zero or more than one candidate qualifies -- the
     *  exact shape a same-day closed loop (the bug this class fixes) always produces, since a full
     *  reversal makes every candidate look like it has a predecessor from some other candidate's
     *  perspective. Needs no anchor and does not use one, so its result never depends on it. */
    private static <T extends DatedLink> List<T> resolveInternally(List<T> group) {
        List<T> withNoPredecessor = new ArrayList<>();
        for (T candidate : group) {
            BigDecimal impliedPre = candidate.balanceAfter().subtract(candidate.signedAmount());
            boolean hasPredecessor = group.stream()
                    .anyMatch(other -> other != candidate && other.balanceAfter().compareTo(impliedPre) == 0);
            if (!hasPredecessor) withNoPredecessor.add(candidate);
        }
        if (withNoPredecessor.size() != 1) return null;
        T first = withNoPredecessor.get(0);
        return walkFromAnchor(group, first.balanceAfter(), first, false);
    }

    /**
     * Whether this document's own printed row order can safely be trusted as a last-resort
     * tiebreaker (see the class-level note). Checks every day that resolves uniquely through
     * {@link #resolveInternally} alone -- pure balance math, no anchor, no print-order assumption
     * -- against that same day's own print order. Trusted only when every checked day agrees AND
     * at least one exists to check; a single disagreement (the real PNB ONE shape) or no evidence
     * at all (nothing multi-transaction resolves on its own) both refuse rather than assume.
     */
    private static <T extends DatedLink> boolean isPrintOrderReliable(Map<LocalDate, List<T>> byDate) {
        boolean anyEvidence = false;
        for (List<T> day : byDate.values()) {
            if (day.size() <= 1) continue;
            List<T> resolved = resolveInternally(day);
            if (resolved == null) continue;
            anyEvidence = true;
            if (!resolved.equals(day)) return false;
        }
        return anyEvidence;
    }

    private static <T extends DatedLink> T findExplicitOpeningRow(List<T> day) {
        for (T obs : day) {
            if (obs.description() != null
                    && obs.description().toLowerCase(Locale.ROOT).contains("opening balance")) {
                return obs;
            }
        }
        return null;
    }

    private static <T extends DatedLink> Resolution ambiguous(List<T> observations, String reason) {
        return new Resolution(observations, null, null, AnchorSource.NONE, AmbiguityStatus.AMBIGUOUS, reason);
    }
}
