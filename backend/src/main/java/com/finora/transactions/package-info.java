/**
 * The transactions feature module: HTTP surface ({@link com.finora.transactions.TransactionController}),
 * orchestration ({@link com.finora.transactions.TransactionService}), and API contract
 * ({@link com.finora.transactions.TransactionDto}) for the ledger.
 *
 * The {@code Transaction} entity and {@code TransactionRepository} deliberately stay in
 * {@code com.finora.entity} / {@code com.finora.repository} rather than moving here too: per the
 * v56 roadmap's guiding principle ("transactions are the single source of truth"), close to
 * every other module in this codebase — accounts, budgets, goals, rules, imports, analytics,
 * reports, dashboard, reconciliation, recurring, relationships, merchants — reads or writes
 * through them directly. Moving the entity/repository now, before those modules have their own
 * migrations planned, would just be file churn across ~25 files with no module actually ready to
 * "own" them yet. Revisit this once most of those modules have migrated and it's clear whether
 * Transaction belongs here or in a shared kernel package — see
 * docs/engineering/CODING_STANDARDS.md's migration order.
 */
package com.finora.transactions;
