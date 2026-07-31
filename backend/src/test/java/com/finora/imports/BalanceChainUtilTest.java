package com.finora.imports;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct coverage of the core algorithm both {@code StatementValidator} (CSV) and
 * {@code PdfPreviewGenerator} (PDF) delegate to for reconstructing a same-day cluster's true
 * first/last transaction from the balance chain itself. See this class's own doc comment for why
 * it exists at all: the same file-position assumption was independently wrong in both of those
 * classes' own prior local copies of this logic.
 */
class BalanceChainUtilTest {

    private record Link(BigDecimal signedAmount, BigDecimal balanceAfter) implements BalanceChainUtil.ChainLink {}

    @Test
    void first_withASingleObservation_returnsItDirectly() {
        Link only = new Link(new BigDecimal("100"), new BigDecimal("500"));
        assertThat(BalanceChainUtil.first(List.of(only))).isSameAs(only);
    }

    @Test
    void last_withASingleObservation_returnsItDirectly() {
        Link only = new Link(new BigDecimal("100"), new BigDecimal("500"));
        assertThat(BalanceChainUtil.last(List.of(only))).isSameAs(only);
    }

    /**
     * The exact real-world scenario this fix was built for: 7 transactions on one calendar day,
     * from an actual PNB ONE statement, listed newest-transaction-of-the-day first. The true
     * first transaction of the day (a 7000 credit) is listed LAST; every other entry chains
     * cleanly off the one before it.
     */
    @Test
    void first_findsTheTrueFirstTransaction_inANewestFirstSameDayCluster() {
        Link a = new Link(new BigDecimal("-588.0"), new BigDecimal("35354.97"));
        Link b = new Link(new BigDecimal("-1582.0"), new BigDecimal("35942.97"));
        Link c = new Link(new BigDecimal("-440.0"), new BigDecimal("37524.97"));
        Link d = new Link(new BigDecimal("-29.0"), new BigDecimal("37964.97"));
        Link e = new Link(new BigDecimal("-800.0"), new BigDecimal("37993.97"));
        Link f = new Link(new BigDecimal("-220.0"), new BigDecimal("38793.97"));
        Link g = new Link(new BigDecimal("7000.0"), new BigDecimal("39013.97")); // true first, listed last

        List<Link> newestFirst = List.of(a, b, c, d, e, f, g);

        Link trueFirst = BalanceChainUtil.first(newestFirst);
        assertThat(trueFirst).isSameAs(g);
        // Recovering the pre-transaction balance from it is what StatementValidator/
        // PdfPreviewGenerator actually use this for.
        assertThat(trueFirst.balanceAfter().subtract(trueFirst.signedAmount())).isEqualByComparingTo("32013.97");
    }

    /** Same cluster, opposite question: the chronologically LAST transaction of the day is `a`
     *  (the lowest resulting balance), even though it's listed FIRST in the file. */
    @Test
    void last_findsTheTrueLastTransaction_inTheSameNewestFirstCluster() {
        Link a = new Link(new BigDecimal("-588.0"), new BigDecimal("35354.97"));
        Link b = new Link(new BigDecimal("-1582.0"), new BigDecimal("35942.97"));
        Link g = new Link(new BigDecimal("7000.0"), new BigDecimal("39013.97"));

        assertThat(BalanceChainUtil.last(List.of(a, b, g))).isSameAs(a);
    }

    /** Mirror scenario, oldest-of-the-day-first ordering this time -- proves the algorithm reads
     *  the chain itself rather than having just hardcoded the opposite file-position assumption. */
    @Test
    void firstAndLast_workIdenticallyRegardlessOfFileOrder_oldestFirstCluster() {
        Link first = new Link(new BigDecimal("500.0"), new BigDecimal("1500.00"));   // 1000 -> 1500
        Link second = new Link(new BigDecimal("-200.0"), new BigDecimal("1300.00")); // 1500 -> 1300

        List<Link> oldestFirst = List.of(first, second);
        assertThat(BalanceChainUtil.first(oldestFirst)).isSameAs(first);
        assertThat(BalanceChainUtil.last(oldestFirst)).isSameAs(second);

        List<Link> reversed = List.of(second, first);
        assertThat(BalanceChainUtil.first(reversed)).isSameAs(first);
        assertThat(BalanceChainUtil.last(reversed)).isSameAs(second);
    }

    @Test
    void first_whenEveryCandidateAppearsToHaveAPredecessor_fallsBackToTheEarliestImpliedStartingPoint() {
        // A genuine 3-way cycle -- not a real same-day chain, but constructed so every candidate's
        // implied pre-transaction balance matches some OTHER candidate's balanceAfter, meaning the
        // main loop's "who has no predecessor" search never finds one and falls through to the
        // fallback. (Two candidates that simply don't relate to each other, tried first, does NOT
        // exercise this branch -- whichever of the two has no predecessor gets returned
        // immediately by the main loop, correctly, without ever reaching the fallback.)
        Link a = new Link(new BigDecimal("-100"), new BigDecimal("900"));   // implied before: 1000
        Link b = new Link(new BigDecimal("50"), new BigDecimal("1000"));   // implied before: 950
        Link c = new Link(new BigDecimal("50"), new BigDecimal("950"));    // implied before: 900
        // a's implied-before (1000) matches b's balanceAfter -> a has a predecessor (b).
        // b's implied-before (950) matches c's balanceAfter -> b has a predecessor (c).
        // c's implied-before (900) matches a's balanceAfter -> c has a predecessor (a).
        // Every candidate has a predecessor -- the fallback must fire, picking the smallest
        // implied-before (900, c's).

        assertThat(BalanceChainUtil.first(List.of(a, b, c))).isSameAs(c);
    }
}
