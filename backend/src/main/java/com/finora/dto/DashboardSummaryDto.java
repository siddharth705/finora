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
        Integer healthScore,
        String healthLabel,
        Map<String, Double> healthBreakdown,
        // D-25 PR3-A: a score computed from a handful of transactions is a harsh first impression
        // that isn't actually wrong data, just too little of it. Below MIN_TRANSACTIONS_FOR_HEALTH_SCORE
        // (DashboardService), healthScore/healthLabel are null and healthBreakdown is empty -- the
        // client shows a "Getting Started X/N transactions" progress state instead of guessing what
        // an incomplete score means. Deliberately a real transactionCount + minTransactions pair
        // rather than a bare boolean, so the client can render "7 / 10" without hardcoding the floor.
        boolean healthScoreAvailable,
        int healthScoreTransactionCount,
        int healthScoreMinTransactions,
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
