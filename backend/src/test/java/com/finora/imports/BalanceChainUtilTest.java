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
    void first_withNoCleanChain_fallsBackToTheEarliestImpliedStartingPoint() {
        // Two observations that don't actually link to each other at all (not a real same-day
        // chain) -- every candidate "has no predecessor," so the fallback must pick deterministically
        // rather than throw.
        Link x = new Link(new BigDecimal("-100"), new BigDecimal("900"));  // implied before: 1000
        Link y = new Link(new BigDecimal("-50"), new BigDecimal("450"));   // implied before: 500

        assertThat(BalanceChainUtil.first(List.of(x, y))).isSameAs(y); // 500 < 1000
    }
}
