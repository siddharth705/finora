package com.finora.util.fixtures;

import java.math.BigDecimal;

/**
 * Deliberately reproduces the scale-sensitive-equals bug shape -- NOT production code, exists
 * only so {@code MoneyComparisonUsageTest} can prove its detection logic actually fires. Do not
 * "fix" this by routing it through MoneyMath; that would defeat its purpose.
 */
public class RawBigDecimalEqualsFixture {

    public boolean compare(BigDecimal a, BigDecimal b) {
        return a.equals(b); // the bug: scale-sensitive, "1500.00" != "1500"
    }
}
