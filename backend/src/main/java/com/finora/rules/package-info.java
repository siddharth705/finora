/**
 * The rules feature module: HTTP surface ({@link com.finora.rules.RuleController}), the
 * user/admin-facing CRUD orchestration ({@link com.finora.rules.RuleService}), and API contract
 * ({@link com.finora.rules.RuleDto}) for category_rules management.
 *
 * {@link com.finora.service.RuleEngineService} — the evaluation engine that actually matches
 * rules against transactions — deliberately stays in {@code com.finora.service} rather than
 * moving here: it's consumed directly by CategorizationService, RecurringService, and the
 * imports module as a cross-cutting concern, not just by this module's own CRUD surface. Likewise
 * {@code CategoryRule}/{@code CategoryRuleRepository} stay in {@code entity}/{@code repository}.
 * See docs/engineering/CODING_STANDARDS.md's migration order.
 */
package com.finora.rules;
