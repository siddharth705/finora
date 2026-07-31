/**
 * The budgets feature module: HTTP surface ({@link com.finora.budgets.BudgetController}),
 * orchestration ({@link com.finora.budgets.BudgetService}), and API contract
 * ({@link com.finora.budgets.BudgetDto}).
 *
 * {@code Budget}/{@code BudgetRepository} stay in {@code com.finora.entity}/
 * {@code com.finora.repository} — DashboardService and InsightsService also read budgets
 * directly for health-score and recommendation calculations. See
 * docs/engineering/CODING_STANDARDS.md's migration order.
 */
package com.finora.budgets;
