/**
 * The accounts feature module: HTTP surface ({@link com.finora.accounts.AccountController}),
 * orchestration ({@link com.finora.accounts.AccountService}), and API contract
 * ({@link com.finora.accounts.AccountDto}, including the nested {@code BankDto} used by the
 * import pipeline and bank-management endpoints).
 *
 * As with {@code com.finora.transactions}, the {@code Account} entity and
 * {@code AccountRepository} stay in {@code com.finora.entity} / {@code com.finora.repository} —
 * ~25 other files (imports, statement history, dashboard, reconciliation, bank management, ...)
 * read/write through them directly, and moving them now would be file churn with no clear owner
 * yet. See docs/engineering/CODING_STANDARDS.md's migration order.
 */
package com.finora.accounts;
