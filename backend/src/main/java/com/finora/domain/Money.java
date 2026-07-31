package com.finora.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;

/**
 * First component of the domain layer called for in the v56 roadmap (Phase 2, "Introduce a
 * Domain Layer"): a small, immutable wrapper around BigDecimal that fixes the rounding and scale
 * rules every money-handling class in this codebase (TransactionService, CsvImportService/
 * ImportService, BudgetService, AnalyticsService, ...) currently re-implements ad hoc with raw
 * BigDecimal arithmetic.
 *
 * Deliberately introduced additively rather than as a big-bang replacement of every BigDecimal
 * amount field: entities and DTOs keep BigDecimal (JPA/Jackson both work with it natively without
 * a custom converter), and Money is meant to be used inside calculation-heavy domain logic — e.g.
 * a future BudgetEngine/ForecastEngine — where consistent rounding actually matters and getting
 * it wrong is a real bug (see the "add tolerance" reasoning below). Retrofitting every existing
 * BigDecimal field to Money is out of scope for this pass; adopt it where a new calculation is
 * being written, not by touching working code that doesn't need it.
 *
 * Finora is single-currency (INR) today, so this deliberately does NOT carry a currency code —
 * adding one now would be speculative complexity ahead of the "Multi-currency support" item in
 * the roadmap's Future Product Vision, which is when a currency field would actually do
 * something (reject/convert cross-currency arithmetic). When that lands, Money is the one place
 * that needs to grow a currency field, rather than every call site that does money arithmetic.
 */
public final class Money implements Comparable<Money> {

    // Two decimal places, banker's rounding (HALF_EVEN) — matches how every bank statement in
    // this codebase's test fixtures (see the SBI/PNB dummy CSVs) already reports amounts, and
    // avoids the systematic upward bias plain HALF_UP introduces over many summed transactions.
    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(SCALE, ROUNDING));

    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        this.amount = amount;
    }

    public static Money of(BigDecimal amount) {
        if (amount == null) throw new IllegalArgumentException("Money.of(null) — use Money.ZERO for an absent amount");
        return new Money(amount.setScale(SCALE, ROUNDING));
    }

    public static Money of(double amount) {
        return of(BigDecimal.valueOf(amount));
    }

    public BigDecimal toBigDecimal() {
        return amount;
    }

    public Money add(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money subtract(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    public Money negate() {
        return new Money(amount.negate());
    }

    public Money abs() {
        return new Money(amount.abs());
    }

    /** Rounds to SCALE after multiplying — used for percentage-of-amount calculations (e.g. a
     *  budget's "spent" fraction) rather than raw BigDecimal.multiply, which would otherwise
     *  carry unrounded precision forward into the next operation. */
    public Money multiply(BigDecimal factor) {
        return new Money(amount.multiply(factor).setScale(SCALE, ROUNDING));
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isGreaterThan(Money other) {
        return amount.compareTo(other.amount) > 0;
    }

    public boolean isLessThan(Money other) {
        return amount.compareTo(other.amount) < 0;
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money other)) return false;
        // compareTo, not BigDecimal.equals -- 5.00 and 5.0 are the same money even if they
        // arrived at different unscaled representations upstream (both are normalized to SCALE
        // by of(), but this stays correct even if that invariant is ever loosened).
        return amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        // Consistent with the compareTo-based equals above: strip scale before hashing so two
        // Money instances that compare equal always hash equal too.
        return amount.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }

    public static Comparator<Money> naturalOrder() {
        return Comparator.naturalOrder();
    }
}
