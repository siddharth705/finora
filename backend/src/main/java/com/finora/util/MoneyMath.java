package com.finora.util;

import java.math.BigDecimal;

/**
 * The one place in the app that compares two {@link BigDecimal} money values, so no call site has
 * to remember two separate footguns:
 *
 * <ul>
 *   <li><b>{@code BigDecimal.equals()} is scale-sensitive.</b> {@code new BigDecimal("1500.00")}
 *       does not equal {@code new BigDecimal("1500")} under {@code .equals()} even though they
 *       represent the same amount -- only {@code .compareTo() == 0} treats them as equal. This is
 *       exactly the bug that let {@code RuleEngineService}'s AMOUNT+EQUALS rules silently never
 *       match anything: a DB-scaled amount like {@code "1500.00"} was compared against a plainly
 *       typed rule value like {@code "1500"} with string/{@code equals()} semantics, so the
 *       comparison was false 100% of the time with no error anywhere to reveal it.</li>
 *   <li><b>A single {@code compareTo()}-returning helper is a trap once null enters the
 *       picture.</b> The natural way to make comparison null-safe is to have a malformed/missing
 *       operand return {@code 0} so neither {@code > 0} nor {@code < 0} matches -- which correctly
 *       fails GT/LT closed, but silently fails EQUALS <i>open</i>, since {@code 0 == 0} looks like
 *       a real match. {@code RuleEngineService} originally had to carry a hand-written comment
 *       warning the next reader not to reuse {@code compareAmount()} for equality for exactly this
 *       reason. Splitting into named, single-purpose predicates below makes that misuse a
 *       compile-time non-issue instead of a comment someone has to read and remember.</li>
 * </ul>
 *
 * <p>Every method here fails closed: a {@code null} operand never satisfies equals/greater-
 * than/less-than, in either direction. There is no argument to any method here that results in
 * "true by accident."
 */
public final class MoneyMath {

    private MoneyMath() {}

    /** Numeric equality, not representation equality -- {@code "1500.00"} and {@code "1500"} are
     *  equal. {@code false} if either operand is null. */
    public static boolean equalsValue(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }

    /** {@code false} if either operand is null -- never treats a missing amount as satisfying a
     *  greater-than comparison. */
    public static boolean isGreaterThan(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) > 0;
    }

    /** {@code false} if either operand is null. */
    public static boolean isLessThan(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) < 0;
    }

    /** {@code false} if either operand is null. */
    public static boolean isGreaterThanOrEqualTo(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) >= 0;
    }

    /** {@code false} if either operand is null. */
    public static boolean isLessThanOrEqualTo(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) <= 0;
    }

    /** Inclusive range check: {@code low <= amount <= high}. {@code false} if any of the three
     *  operands is null. */
    public static boolean isBetweenInclusive(BigDecimal amount, BigDecimal low, BigDecimal high) {
        return isGreaterThanOrEqualTo(amount, low) && isLessThanOrEqualTo(amount, high);
    }
}
