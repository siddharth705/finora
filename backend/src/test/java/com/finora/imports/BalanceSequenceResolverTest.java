package com.finora.imports;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct coverage of {@link BalanceSequenceResolver}, per the design doc's own §6 regression
 * strategy ({@code docs/architecture/system-design/balance-chain-ordering-design.md}): existing
 * behavior preserved, the corrected closed-loop case, the genuinely ambiguous case, and loops wider
 * than a single reversing pair (confirmed the MAJORITY real-corpus shape, not an edge case, by the
 * design doc's own §7.1 corpus sweep).
 */
class BalanceSequenceResolverTest {

    private record Obs(LocalDate date, BigDecimal signedAmount, BigDecimal balanceAfter, String description)
            implements BalanceSequenceResolver.DatedLink {
    }

    private static Obs obs(String date, String signedAmount, String balanceAfter) {
        return new Obs(LocalDate.parse(date), new BigDecimal(signedAmount), new BigDecimal(balanceAfter), "txn");
    }

    // ================================================================
    // Baseline / existing behavior
    // ================================================================

    @Test
    void resolve_withNoObservations_isUniqueWithNoBalances() {
        var resolution = BalanceSequenceResolver.resolve(List.of());
        assertThat(resolution.ambiguityStatus()).isEqualTo(BalanceSequenceResolver.AmbiguityStatus.UNIQUE);
        assertThat(resolution.openingBalance()).isNull();
        assertThat(resolution.closingBalance()).isNull();
    }

    @Test
    void resolve_withOneObservationTotal_derivesOpeningAndClosingDirectly() {
        Obs only = obs("2026-07-01", "500.00", "10500.00");
        var resolution = BalanceSequenceResolver.resolve(List.of(only));

        assertThat(resolution.ambiguityStatus()).isEqualTo(BalanceSequenceResolver.AmbiguityStatus.UNIQUE);
        assertThat(resolution.openingBalance()).isEqualByComparingTo("10000.00");
        assertThat(resolution.closingBalance()).isEqualByComparingTo("10500.00");
    }

    @Test
    void resolve_withDistinctDatesAndNoSameDayCluster_walksEachDayInOrder() {
        Obs a = obs("2026-07-01", "-486.00", "9514.00");
        Obs b = obs("2026-07-05", "2000.00", "11514.00");
        var resolution = BalanceSequenceResolver.resolve(List.of(a, b));

        assertThat(resolution.ambiguityStatus()).isEqualTo(BalanceSequenceResolver.AmbiguityStatus.UNIQUE);
        assertThat(resolution.openingBalance()).isEqualByComparingTo("10000.00");
        assertThat(resolution.closingBalance()).isEqualByComparingTo("11514.00");
        assertThat(resolution.orderedTransactions()).isEqualTo(List.of(a, b));
    }

    /** The exact real-world PNB ONE cluster {@code BalanceChainUtilTest} covers directly (7
     *  same-day transactions, newest-first in file order) -- confirms the new day-by-day resolver
     *  reaches the identical answer for the case it must not regress. */
    @Test
    void resolve_withANewestFirstSameDayCluster_matchesBalanceChainUtilsOwnAnswer() {
        LocalDate d = LocalDate.parse("2026-06-30");
        Obs a = new Obs(d, new BigDecimal("-588.0"), new BigDecimal("35354.97"), "txn");
        Obs b = new Obs(d, new BigDecimal("-1582.0"), new BigDecimal("35942.97"), "txn");
        Obs c = new Obs(d, new BigDecimal("-440.0"), new BigDecimal("37524.97"), "txn");
        Obs dd = new Obs(d, new BigDecimal("-29.0"), new BigDecimal("37964.97"), "txn");
        Obs e = new Obs(d, new BigDecimal("-800.0"), new BigDecimal("37993.97"), "txn");
        Obs f = new Obs(d, new BigDecimal("-220.0"), new BigDecimal("38793.97"), "txn");
        Obs g = new Obs(d, new BigDecimal("7000.0"), new BigDecimal("39013.97"), "txn"); // true first

        var resolution = BalanceSequenceResolver.resolve(List.of(a, b, c, dd, e, f, g));

        assertThat(resolution.ambiguityStatus()).isEqualTo(BalanceSequenceResolver.AmbiguityStatus.UNIQUE);
        assertThat(resolution.openingBalance()).isEqualByComparingTo("32013.97");
        assertThat(resolution.closingBalance()).isEqualByComparingTo("35354.97");
        assertThat(resolution.orderedTransactions().get(0)).isSameAs(g);
    }

    // ================================================================
    // The corrected bug: a same-day full reversal on a statement boundary
    // ================================================================

    /**
     * The exact real-world shape confirmed on two documents in the corpus (ICICI savings, Union
     * Bank): a credit immediately followed by a same-day debit of the identical amount, on the
     * statement's LAST day. {@code BalanceChainUtil.last()}'s old fallback picked the credit's
     * balance (the day's peak) as "last"; the true last transaction is the debit, ending back where
     * the day started.
     */
    @Test
    void resolve_aSameDayReversalOnTheLastDay_picksTheTrueLastTransaction_notThePeakBalance() {
        Obs anchor = obs("2026-08-01", "-8.00", "93.78");            // establishes the incoming anchor
        Obs credit = obs("2026-08-02", "4602.00", "4695.78");        // the day's peak -- old code's wrong pick
        Obs debit = obs("2026-08-02", "-4602.00", "93.78");          // the TRUE last transaction

        var resolution = BalanceSequenceResolver.resolve(List.of(anchor, credit, debit));

        assertThat(resolution.ambiguityStatus()).isEqualTo(BalanceSequenceResolver.AmbiguityStatus.UNIQUE);
        // The old, buggy algorithm would have returned 4695.78 here (the credit's balance).
        assertThat(resolution.closingBalance()).isEqualByComparingTo("93.78");
        assertThat(resolution.orderedTransactions()).isEqualTo(List.of(anchor, credit, debit));
    }

    /** Mirror shape: a debit immediately followed by a same-day credit reversing it -- proves the
     *  fix is direction-agnostic, not a hardcoded "credit always comes first" assumption. */
    @Test
    void resolve_aSameDayReversal_debitThenCredit_alsoPicksTheTrueLastTransaction() {
        Obs anchor = obs("2026-08-01", "100.00", "1000.00");
        Obs debit = obs("2026-08-02", "-400.00", "600.00");   // the day's trough
        Obs credit = obs("2026-08-02", "400.00", "1000.00");  // the TRUE last transaction

        var resolution = BalanceSequenceResolver.resolve(List.of(anchor, debit, credit));

        assertThat(resolution.ambiguityStatus()).isEqualTo(BalanceSequenceResolver.AmbiguityStatus.UNIQUE);
        assertThat(resolution.closingBalance()).isEqualByComparingTo("1000.00");
        assertThat(resolution.orderedTransactions()).isEqualTo(List.of(anchor, debit, credit));
    }

    // ================================================================
    // Loops wider than a single pair -- the MAJORITY real-corpus shape (design doc §7.1, ~70%)
    // ================================================================

    @Test
    void resolve_aFourTransactionSameDayLoop_resolvesInOrder_notJustTwoWayReversals() {
        Obs anchor = obs("2026-08-01", "0.00", "1000.00");
        // +300, +200, -100, -400 -- nets to zero across all 4, each value distinct so the chain has
        // exactly one valid reading (a same-value pair, e.g. +500/-500 twice over, would make two
        // transactions genuinely indistinguishable from each other -- a different, real ambiguity,
        // not the shape this test means to isolate).
        Obs t1 = obs("2026-08-02", "300.00", "1300.00");
        Obs t2 = obs("2026-08-02", "200.00", "1500.00");
        Obs t3 = obs("2026-08-02", "-100.00", "1400.00");
        Obs t4 = obs("2026-08-02", "-400.00", "1000.00");

        // Deliberately shuffled input order -- the resolver must not depend on list order.
        var resolution = BalanceSequenceResolver.resolve(List.of(anchor, t4, t1, t3, t2));

        assertThat(resolution.ambiguityStatus()).isEqualTo(BalanceSequenceResolver.AmbiguityStatus.UNIQUE);
        assertThat(resolution.closingBalance()).isEqualByComparingTo("1000.00");
        assertThat(resolution.orderedTransactions()).isEqualTo(List.of(anchor, t1, t2, t3, t4));
    }

    @Test
    void resolve_aThreeTransactionSameDayLoop_withNoSingleReversingPair_stillResolves() {
        Obs anchor = obs("2026-08-01", "0.00", "2000.00");
        // +1000, -700, -300 -- nets to zero across all three; no two of them alone net to zero.
        Obs credit = obs("2026-08-02", "1000.00", "3000.00");
        Obs debitA = obs("2026-08-02", "-700.00", "2300.00");
        Obs debitB = obs("2026-08-02", "-300.00", "2000.00");

        var resolution = BalanceSequenceResolver.resolve(List.of(anchor, debitB, credit, debitA));

        assertThat(resolution.ambiguityStatus()).isEqualTo(BalanceSequenceResolver.AmbiguityStatus.UNIQUE);
        assertThat(resolution.closingBalance()).isEqualByComparingTo("2000.00");
        assertThat(resolution.orderedTransactions()).isEqualTo(List.of(anchor, credit, debitA, debitB));
    }

    // ================================================================
    // No forced guess
    // ================================================================

    /** The same closed-loop shape as the corrected-bug tests above, but on day 1 with no explicit
     *  opening-balance declaration -- no anchor exists anywhere to disambiguate it, so the whole
     *  resolution must be honestly AMBIGUOUS rather than guessing via a fallback. */
    @Test
    void resolve_aSameDayReversalOnDayOneWithNoAnchor_isAmbiguous_notAGuess() {
        Obs credit = obs("2026-08-02", "4602.00", "4695.78");
        Obs debit = obs("2026-08-02", "-4602.00", "93.78");

        var resolution = BalanceSequenceResolver.resolve(List.of(credit, debit));

        assertThat(resolution.ambiguityStatus()).isEqualTo(BalanceSequenceResolver.AmbiguityStatus.AMBIGUOUS);
        assertThat(resolution.openingBalance()).isNull();
        assertThat(resolution.closingBalance()).isNull();
        assertThat(resolution.evidence()).isNotBlank();
    }

    /** A same-day pair whose balances simply do not chain in EITHER order (not a reversal -- they
     *  don't net to zero) is exactly as unresolvable as a closed loop, for the same reason: no
     *  anchor exists to say which one is really first. Confirmed against a real synthetic fixture
     *  ({@code PdfFixtureBuilder.buildSingularDepositWithdrawalColumnsSample}) during this change's
     *  own verification -- see {@code ClosingBalanceCircularFinancialValidationTest}. */
    @Test
    void resolve_aSameDayPairThatDoesNotChainInEitherOrder_isAmbiguous() {
        Obs a = obs("2026-07-01", "-1000.00", "24361.97");
        Obs b = obs("2026-07-01", "10.00", "24351.97");

        var resolution = BalanceSequenceResolver.resolve(List.of(a, b));

        assertThat(resolution.ambiguityStatus()).isEqualTo(BalanceSequenceResolver.AmbiguityStatus.AMBIGUOUS);
        assertThat(resolution.openingBalance()).isNull();
        assertThat(resolution.closingBalance()).isNull();
    }

    /** A middle day's transactions that don't connect to the previous day's resolved close, but ARE
     *  themselves internally unique, must still resolve -- this is the exact regression a stricter,
     *  earlier version of this algorithm introduced (see the class-level doc comment's history) and
     *  must not reappear. */
    @Test
    void resolve_aMiddleDayThatDoesNotConnectToThePreviousClose_stillResolvesFromItsOwnChain() {
        Obs day1 = obs("2026-07-18", "1057.00", "12747.27");
        Obs day2 = obs("2026-07-19", "-1600.00", "11147.27");
        // This day's own chain is internally unique (each implies a distinct predecessor from
        // within the group), but its own starting point does not connect to day2's 11147.27 close --
        // a real, unrelated gap, not a same-day ordering question.
        Obs day3a = obs("2026-07-25", "-9.70", "10718.57");
        Obs day3b = obs("2026-07-25", "-145.00", "10573.57");
        Obs day3c = obs("2026-07-25", "-120.00", "10453.57");
        Obs day4 = obs("2026-07-26", "-377.71", "10075.86");

        var resolution = BalanceSequenceResolver.resolve(List.of(day1, day2, day3a, day3b, day3c, day4));

        assertThat(resolution.ambiguityStatus()).isEqualTo(BalanceSequenceResolver.AmbiguityStatus.UNIQUE);
        assertThat(resolution.openingBalance()).isEqualByComparingTo("11690.27");
        assertThat(resolution.closingBalance()).isEqualByComparingTo("10075.86");
    }

    // ================================================================
    // Explicit opening-balance declaration
    // ================================================================

    /** An explicit "Opening Balance" label row anchors day 1 directly, using its own stated balance
     *  rather than backing out a (meaningless, for a label row) signed-amount subtraction -- the
     *  exact "silently doubling the detected opening balance" bug both prior call sites already had
     *  to fix once (see their own history), now centralized here instead of duplicated per format. */
    @Test
    void resolve_anExplicitOpeningBalanceRow_anchorsDirectly_withoutDoublingIt() {
        Obs openingRow = new Obs(LocalDate.parse("2026-07-01"), new BigDecimal("-10000.00"),
                new BigDecimal("10000.00"), "Opening Balance");
        Obs txn = obs("2026-07-01", "-500.00", "9500.00");

        var resolution = BalanceSequenceResolver.resolve(List.of(openingRow, txn));

        assertThat(resolution.anchorSource()).isEqualTo(BalanceSequenceResolver.AnchorSource.STATEMENT_OPENING_BALANCE);
        assertThat(resolution.openingBalance()).isEqualByComparingTo("10000.00");
        assertThat(resolution.closingBalance()).isEqualByComparingTo("9500.00");
    }
}
