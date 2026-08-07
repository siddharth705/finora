package com.finora.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record DashboardSummaryDto(
        BigDecimal currentBalance,
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal netWorth,
        BigDecimal monthlyIncome,
        BigDecimal monthlyExpense,
        BigDecimal netCashFlow,
        BigDecimal savingsRatePct,
        Double incomeDeltaPct,
        Double expenseDeltaPct,
        Double netDeltaPct,
        int healthScore,
        String healthLabel,
        Map<String, Double> healthBreakdown,
        Map<String, BigDecimal> spendByCategory,
        List<String> notifications,

        /*
         * Which month monthlyIncome/monthlyExpense/netCashFlow/savingsRatePct/spendByCategory and
         * the three delta percentages actually describe -- "2026-07", or null for an account with
         * no transactions at all.
         *
         * Bug 05: these figures are the newest month the user has DATA for, which is the right
         * reporting period for a product built around importing statements in arrears, but the
         * response never said so. The client had nothing to label them with and labelled them
         * "this month" / "vs last month", so a user who had not yet transacted in August read
         * July's figures as August's. Reporting on July is fine; claiming July is August is not.
         *
         * Deliberately NOT the period the budget notifications use -- those are measured against a
         * monthly allowance and follow the calendar month instead. See DashboardService.summarize
         * and ReportingPeriod for why the two differ.
         */
        String reportingMonth,
        boolean reportingMonthIsCurrent
) {}
