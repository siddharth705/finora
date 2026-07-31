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
        List<String> notifications
) {}
