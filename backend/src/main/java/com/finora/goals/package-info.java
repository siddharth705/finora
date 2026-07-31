/**
 * The goals feature module — fully self-contained, unlike transactions/accounts/budgets. Nothing
 * else in the codebase reads {@link com.finora.goals.Goal} or
 * {@link com.finora.goals.GoalContribution} directly, so this module's entity and repositories
 * moved here too rather than staying in the shared {@code entity}/{@code repository} packages —
 * see docs/engineering/CODING_STANDARDS.md's migration order and the "Entities ... stay in
 * entity/repository until their owning module is migrated" note: once a module's dependents are
 * verified to be zero, there's no reason to leave its entity behind.
 */
package com.finora.goals;
