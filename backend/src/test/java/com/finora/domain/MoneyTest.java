package com.finora.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyTest {

    @Test
    void roundsToTwoDecimalPlacesUsingBankersRounding() {
        // HALF_EVEN: 2.005 rounds to 2.00 (even), 2.015 rounds to 2.02 (even) -- not a simple
        // "round half up" rule, which is exactly why this is centralized here instead of being
        // reimplemented ad hoc at each call site.
        assertThat(Money.of(new BigDecimal("2.005")).toBigDecimal()).isEqualByComparingTo("2.00");
        assertThat(Money.of(new BigDecimal("2.015")).toBigDecimal()).isEqualByComparingTo("2.02");
    }

    @Test
    void equalsIgnoresScaleDifferences() {
        assertThat(Money.of(new BigDecimal("5.0"))).isEqualTo(Money.of(new BigDecimal("5.00")));
        assertThat(Money.of(new BigDecimal("5.0")).hashCode()).isEqualTo(Money.of(new BigDecimal("5.00")).hashCode());
    }

    @Test
    void addAndSubtractComposeCorrectly() {
        Money result = Money.of(100).add(Money.of(50)).subtract(Money.of(30));
        assertThat(result.toBigDecimal()).isEqualByComparingTo("120.00");
    }

    @Test
    void comparisonsAndSignChecks() {
        Money positive = Money.of(10);
        Money negative = Money.of(-10);

        assertThat(positive.isPositive()).isTrue();
        assertThat(negative.isNegative()).isTrue();
        assertThat(Money.ZERO.isZero()).isTrue();
        assertThat(positive.isGreaterThan(negative)).isTrue();
        assertThat(negative.isLessThan(positive)).isTrue();
    }

    @Test
    void multiplyRoundsIntermediatePrecision() {
        // 33.33% of 100.00 -- multiply() rounds to scale immediately rather than carrying
        // unrounded precision into a later operation, per the class doc.
        Money result = Money.of(100).multiply(new BigDecimal("0.3333"));
        assertThat(result.toBigDecimal()).isEqualByComparingTo("33.33");
    }

    @Test
    void rejectsNullAmount() {
        assertThatIllegalArgument(() -> Money.of((BigDecimal) null));
    }

    private void assertThatIllegalArgument(Runnable r) {
        try {
            r.run();
            throw new AssertionError("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
