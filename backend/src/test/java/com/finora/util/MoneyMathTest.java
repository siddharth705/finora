package com.finora.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two properties {@link MoneyMath} exists to guarantee: numeric equality ignoring scale, and
 * every comparison failing closed on null. See the class doc for the actual RuleEngineService bug
 * ("1500.00" vs "1500") this generalizes the fix for.
 */
class MoneyMathTest {

    @Test
    void equalsValue_treatsDifferentScalesOfTheSameNumberAsEqual() {
        // The exact bug: a DB-scaled amount and a plainly-typed rule value.
        assertThat(MoneyMath.equalsValue(new BigDecimal("1500.00"), new BigDecimal("1500"))).isTrue();
        assertThat(MoneyMath.equalsValue(new BigDecimal("0.50"), new BigDecimal("0.5"))).isTrue();
    }

    @Test
    void equalsValue_isFalseForGenuinelyDifferentAmounts() {
        assertThat(MoneyMath.equalsValue(new BigDecimal("1500.01"), new BigDecimal("1500"))).isFalse();
    }

    @Test
    void equalsValue_failsClosed_onEitherOrBothOperandsBeingNull() {
        assertThat(MoneyMath.equalsValue(null, new BigDecimal("100"))).isFalse();
        assertThat(MoneyMath.equalsValue(new BigDecimal("100"), null)).isFalse();
        assertThat(MoneyMath.equalsValue(null, null)).isFalse();
    }

    @Test
    void isGreaterThan_andIsLessThan_agreeWithNaturalOrdering() {
        assertThat(MoneyMath.isGreaterThan(new BigDecimal("200"), new BigDecimal("100"))).isTrue();
        assertThat(MoneyMath.isLessThan(new BigDecimal("50"), new BigDecimal("100"))).isTrue();
        assertThat(MoneyMath.isGreaterThan(new BigDecimal("100"), new BigDecimal("100"))).isFalse();
        assertThat(MoneyMath.isLessThan(new BigDecimal("100"), new BigDecimal("100"))).isFalse();
    }

    @Test
    void isGreaterThan_andIsLessThan_bothFailClosed_onANullOperand() {
        // The specific footgun this API is designed to make impossible: a single compareTo()
        // wrapper that maps null to 0 fails GT/LT closed but EQUALS open. Verifying both
        // directions independently return false confirms there is no shared "0 means match" path.
        assertThat(MoneyMath.isGreaterThan(null, new BigDecimal("100"))).isFalse();
        assertThat(MoneyMath.isGreaterThan(new BigDecimal("100"), null)).isFalse();
        assertThat(MoneyMath.isLessThan(null, new BigDecimal("100"))).isFalse();
        assertThat(MoneyMath.isLessThan(new BigDecimal("100"), null)).isFalse();
    }

    @Test
    void isBetweenInclusive_includesTheBoundariesThemselves() {
        BigDecimal low = new BigDecimal("1000");
        BigDecimal high = new BigDecimal("5000");

        assertThat(MoneyMath.isBetweenInclusive(new BigDecimal("1000"), low, high)).isTrue();
        assertThat(MoneyMath.isBetweenInclusive(new BigDecimal("5000"), low, high)).isTrue();
        assertThat(MoneyMath.isBetweenInclusive(new BigDecimal("2500"), low, high)).isTrue();
        assertThat(MoneyMath.isBetweenInclusive(new BigDecimal("999.99"), low, high)).isFalse();
        assertThat(MoneyMath.isBetweenInclusive(new BigDecimal("5000.01"), low, high)).isFalse();
    }

    @Test
    void isBetweenInclusive_failsClosed_whenTheAmountIsNull() {
        assertThat(MoneyMath.isBetweenInclusive(null, new BigDecimal("1000"), new BigDecimal("5000")))
                .isFalse();
    }
}
